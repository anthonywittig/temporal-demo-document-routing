package io.temporal.demo.docrouting.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowStub;
import io.temporal.demo.docrouting.Config;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflow;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflowImpl;
import io.temporal.demo.docrouting.workflow.FakeActivities;
import io.temporal.demo.docrouting.workflow.RequestOutcome;
import io.temporal.demo.docrouting.workflow.Route;
import io.temporal.demo.docrouting.workflow.WorkflowSettings;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {
  static final ObjectMapper JSON = new ObjectMapper();
  final HttpClient http = HttpClient.newHttpClient();

  @TempDir Path dir;
  TestWorkflowEnvironment env;
  WebServer web;
  final FakeActivities fake = new FakeActivities().route(Route.B, 1);

  @BeforeEach
  void setUp() throws Exception {
    Files.copy(Path.of("config/rules.default.json"), dir.resolve("rules.json"));
    Files.copy(Path.of("config/reference-data.default.json"), dir.resolve("reference-data.json"));
    env = TestWorkflowEnvironment.newInstance();
    for (var sa : new String[] {"Step", "Path", "BusinessUnit"}) {
      env.registerSearchAttribute(sa, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    }
    env.registerSearchAttribute("SlaBreached", IndexedValueType.INDEXED_VALUE_TYPE_BOOL);
    env.registerSearchAttribute("RulesVersion", IndexedValueType.INDEXED_VALUE_TYPE_INT);

    Config config = Config.from(Map.of("DATA_DIR", dir.toString(), "SYSTEMS_URL", "http://localhost:1"));
    Worker worker = env.newWorker(config.taskQueue());
    worker.registerWorkflowImplementationFactory(DocumentRequestWorkflow.class,
        () -> new DocumentRequestWorkflowImpl(new WorkflowSettings(Duration.ofSeconds(60), Duration.ofMinutes(2), false)));
    worker.registerActivitiesImplementations(fake);
    env.start();

    web = new WebServer(config, env.getWorkflowClient(), new RulesLoader(config.rulesFile())).start(0);
  }

  @AfterEach
  void tearDown() {
    web.stop();
    env.close();
  }

  HttpResponse<String> call(String method, String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + web.port() + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void duplicateSubmissionStartsNothingNew() throws Exception {
    var first = call("POST", "/api/submit", "{\"scenario\":\"nutrition-invoice-3000\"}");
    assertEquals(200, first.statusCode(), first.body());
    JsonNode started = JSON.readTree(first.body()).get("results").get(0);
    String workflowId = started.get("workflowId").asText();
    String runId = env.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe().getExecution().getRunId();

    var dup = call("POST", "/api/submit", "{\"scenario\":\"duplicate\"}");
    assertEquals(409, dup.statusCode(), dup.body());
    JsonNode refused = JSON.readTree(dup.body()).get("results").get(0);
    assertTrue(refused.get("duplicate").asBoolean());
    assertEquals(workflowId, refused.get("workflowId").asText());

    String runIdAfter = env.getWorkflowClient().newUntypedWorkflowStub(workflowId).describe().getExecution().getRunId();
    assertEquals(runId, runIdAfter, "no new execution may start for a duplicate");
  }

  @Test
  void erpCallbackBecomesASignalAndCompletesTheRequest() throws Exception {
    var res = call("POST", "/api/submit", "{\"scenario\":\"nutrition-invoice-3000\"}");
    String workflowId = JSON.readTree(res.body()).get("results").get(0).get("workflowId").asText();

    var cb = call("POST", "/callbacks/erp",
        "{\"workflowId\":\"" + workflowId + "\",\"postingId\":\"A-1\",\"confirmedAt\":\"now\"}");
    assertEquals(200, cb.statusCode(), cb.body());

    RequestOutcome outcome = WorkflowStub.fromTyped(
        env.getWorkflowClient().newWorkflowStub(DocumentRequestWorkflow.class, workflowId)).getResult(RequestOutcome.class);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertTrue(fake.calls().contains("NotifyRequester"));
  }

  @Test
  void callbackForFinishedRequestIsAcknowledgedSoTheSystemStopsRetrying() throws Exception {
    var cb = call("POST", "/callbacks/erp", "{\"workflowId\":\"doc-nope\",\"postingId\":\"A-1\"}");
    assertEquals(200, cb.statusCode());
    assertEquals("IGNORED", JSON.readTree(cb.body()).get("status").asText());
  }

  @Test
  void batchScenarioStartsThreeRequests() throws Exception {
    var res = call("POST", "/api/submit", "{\"scenario\":\"cc99-batch\"}");
    assertEquals(200, res.statusCode());
    assertEquals(3, JSON.readTree(res.body()).get("results").size());
  }

  JsonNode rulesFile() throws Exception {
    return JSON.readTree(Files.readString(dir.resolve("rules.json")));
  }

  @Test
  void savingAThresholdWritesTheNextRulesVersion() throws Exception {
    var res = call("PUT", "/api/rules", "{\"thresholds\":{\"Devices\":5000}}");
    assertEquals(200, res.statusCode(), res.body());
    JsonNode view = JSON.readTree(res.body());
    assertEquals(2, view.get("version").asInt());
    assertEquals(5000, view.get("thresholds").get("Devices").asLong());
    assertEquals(5000, view.get("thresholds").get("Nutrition").asLong(), "untouched unit keeps its threshold");

    JsonNode file = rulesFile();
    assertEquals(2, file.get("version").asInt());
    assertEquals(5000, file.get("businessUnits").get("Devices").get("humanReviewAbove").asLong());
    assertEquals("B", file.get("businessUnits").get("Devices").get("default").asText(), "other fields are kept");
    assertEquals(2, file.get("businessUnits").get("Devices").get("externalChecksFor").size());

  }

  @Test
  void invalidThresholdIsRefusedAndNothingIsWritten() throws Exception {
    String before = Files.readString(dir.resolve("rules.json"));
    var negative = call("PUT", "/api/rules", "{\"thresholds\":{\"Nutrition\":-5}}");
    assertEquals(400, negative.statusCode());
    assertTrue(negative.body().contains("must not be negative"), negative.body());

    var text = call("PUT", "/api/rules", "{\"thresholds\":{\"Nutrition\":\"lots\"}}");
    assertEquals(400, text.statusCode());
    assertTrue(text.body().contains("whole number"), text.body());

    var unknown = call("PUT", "/api/rules", "{\"thresholds\":{\"BU9\":10}}");
    assertEquals(400, unknown.statusCode());

    assertEquals(before, Files.readString(dir.resolve("rules.json")));
  }

  @Test
  void resetRestoresDefaultRulesAndReferenceData() throws Exception {
    call("PUT", "/api/rules", "{\"thresholds\":{\"Devices\":5000}}");
    call("PUT", "/api/rules", "{\"thresholds\":{\"Nutrition\":2000}}");
    Files.writeString(dir.resolve("reference-data.json"), "{\"costCenters\":[\"CC-10\",\"CC-99\"]}");
    assertEquals(3, rulesFile().get("version").asInt());

    var res = call("POST", "/api/reset-data", "");
    assertEquals(200, res.statusCode(), res.body());
    assertEquals(1, JSON.readTree(res.body()).get("rules").get("version").asInt());
    assertEquals(1, rulesFile().get("version").asInt());
    assertFalse(Files.readString(dir.resolve("reference-data.json")).contains("CC-99"));
  }

  @Test
  void controlProxyReportsMissingSystemsProcess() throws Exception {
    var res = call("PUT", "/api/control", "{\"erpDown\":true,\"supplierRejects\":false}");
    assertEquals(503, res.statusCode());
    assertTrue(res.body().contains("make systems"));
  }
}
