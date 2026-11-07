package io.temporal.demo.docrouting.activities;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.demo.docrouting.systems.SystemsServer;
import io.temporal.demo.docrouting.workflow.DocumentRequest;
import io.temporal.demo.docrouting.workflow.Route;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestActivityEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentActivitiesTest {
  static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dir;
  SystemsServer systems;
  Javalin web;
  TestActivityEnvironment env;
  DocumentActivities activities;
  final LinkedBlockingQueue<JsonNode> callbacks = new LinkedBlockingQueue<>();

  static final DocumentRequest BU1_INVOICE = new DocumentRequest("d1", "BU1", "invoice", 3000, "CC-10", "Dana");
  static final DocumentRequest BAD_CC = new DocumentRequest("d2", "BU2", "purchase-order", 800, "CC-99", "Dana");

  @BeforeEach
  void setUp() throws Exception {
    Files.copy(Path.of("config/rules.default.json"), dir.resolve("rules.json"));
    Files.copy(Path.of("config/reference-data.default.json"), dir.resolve("reference-data.json"));
    systems = new SystemsServer(new SystemsServer.Settings(
        Duration.ofMillis(50), dir.resolve("reference-data.json"))).start(0);
    web = Javalin.create(c -> c.showJavalinBanner = false)
        .post("/callbacks/{system}", ctx -> callbacks.add(JSON.readTree(ctx.body())))
        .start(0);
    env = TestActivityEnvironment.newInstance();
    env.registerActivitiesImplementations(new DocumentActivitiesImpl(
        "http://localhost:" + systems.port(), "http://localhost:" + web.port(),
        new RulesLoader(dir.resolve("rules.json")), Duration.ZERO));
    activities = env.newActivityStub(DocumentActivities.class);
  }

  @AfterEach
  void tearDown() {
    env.close();
    systems.stop();
    web.stop();
  }

  JsonNode effects() throws Exception {
    var res = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + systems.port() + "/effects")).build(),
        HttpResponse.BodyHandlers.ofString());
    return JSON.readTree(res.body());
  }

  @Test
  void classifyAndRoute() {
    String type = activities.classify(BU1_INVOICE);
    assertEquals("invoice", type);
    var decision = activities.evaluateRules(BU1_INVOICE, type, false);
    assertEquals(Route.B, decision.path());
    assertEquals(1, decision.rulesVersion());
  }

  @Test
  void unknownBusinessUnitIsNonRetryable() {
    var doc = new DocumentRequest("d3", "BU9", "invoice", 1, "CC-10", "Dana");
    var e = assertThrows(ActivityFailure.class, () -> activities.evaluateRules(doc, "invoice", false));
    var cause = (ApplicationFailure) e.getCause();
    assertTrue(cause.isNonRetryable());
  }

  @Test
  void erpPostingCallsBackToTheWebApp() throws Exception {
    String postingId = activities.postInvoiceToErp(BU1_INVOICE);
    assertTrue(postingId.startsWith("ERP-"));
    JsonNode ack = callbacks.poll(5, TimeUnit.SECONDS);
    assertNotNull(ack);
    assertEquals(postingId, ack.get("postingId").asText());
  }

  @Test
  void unknownCostCenterFailsWithoutRetry() {
    var e = assertThrows(ActivityFailure.class, () -> activities.checkCostCenter(BAD_CC));
    var cause = (ApplicationFailure) e.getCause();
    assertEquals(HttpCalls.REJECTED, cause.getType());
    assertTrue(cause.isNonRetryable());
    assertTrue(cause.getOriginalMessage().contains("CC-99"), cause.getOriginalMessage());
  }

  @Test
  void sideEffectsAreSentWithStableIdempotencyKeys() throws Exception {
    activities.reserveBudget(BU1_INVOICE);
    activities.reserveBudget(BU1_INVOICE); // same workflow + activity type => same key
    activities.releaseBudget(BU1_INVOICE);
    activities.notifyRequester(BU1_INVOICE, "done");
    JsonNode effects = effects();
    assertEquals(3, effects.size(), effects.toString());
    assertEquals("Reserve budget", effects.get(0).get("action").asText());
  }
}
