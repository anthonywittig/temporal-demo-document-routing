package io.temporal.demo.docrouting.workflow;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards determinism: recorded v1 histories, one per path, must replay against the current
 * Workflow code configured as v1. A change that would break in-flight v1 requests fails here.
 *
 * <p>To re-record the fixtures (only when v1 behavior is meant to change):
 * {@code mvn test -Dtest=HistoryReplayTest -Drecord=true}
 */
class HistoryReplayTest {
  static final String QUEUE = "replay-queue";
  static final Path FIXTURES = Path.of("src/test/resources/histories");
  static final WorkflowSettings V1 = new WorkflowSettings(Duration.ofSeconds(60), Duration.ofMinutes(2), false);

  @ParameterizedTest
  @ValueSource(strings = {"v1-path-a.json", "v1-path-b.json", "v1-path-c-rejected.json"})
  void v1HistoriesReplayAgainstCurrentCode(String fixture) throws Exception {
    String json;
    try (InputStream in = getClass().getResourceAsStream("/histories/" + fixture)) {
      assertNotNull(in, "missing fixture " + fixture + "; record with -Drecord=true");
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      Worker worker = env.newWorker(QUEUE);
      worker.registerWorkflowImplementationFactory(
          DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(V1));
      WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(json), worker);
    }
  }

  /**
   * v2 adds an archive step, so it can't replay a v1 history. That's why every request is pinned
   * to the version it started on: v1 requests drain on v1 workers instead of moving to v2 code.
   */
  @Test
  void v2CodeCannotReplayV1History() throws Exception {
    String json;
    try (InputStream in = getClass().getResourceAsStream("/histories/v1-path-b.json")) {
      json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    WorkflowSettings v2 = new WorkflowSettings(V1.slaDuration(), V1.callbackTimeout(), true);
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      Worker worker = env.newWorker(QUEUE);
      worker.registerWorkflowImplementationFactory(
          DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(v2));
      assertThrows(Exception.class,
          () -> WorkflowReplayer.replayWorkflowExecution(WorkflowExecutionHistory.fromJson(json), worker));
    }
  }

  @Test
  @EnabledIfSystemProperty(named = "record", matches = "true")
  void recordFixtures() throws Exception {
    Files.createDirectories(FIXTURES);
    record("v1-path-a.json", Route.A, false, (wf, client) -> {
      awaitStep(wf, Steps.HUMAN_TASK);
      wf.review(new ReviewDecision("rec-a-r1", ReviewAction.REJECT, "Sam", "recorded"));
    });
    record("v1-path-b.json", Route.B, false, (wf, client) -> {
      awaitStep(wf, Steps.AWAIT_ERP_CONFIRMATION);
      wf.erpPostingConfirmed(new ErpConfirmation("A-rec", "now"));
    });
    record("v1-path-c-rejected.json", Route.C, true, (wf, client) -> {});
  }

  interface Drive {
    void run(DocumentRequestWorkflow wf, WorkflowClient client) throws Exception;
  }

  private void record(String file, Route route, boolean supplierRejects, Drive drive) throws Exception {
    // A fixed identity, so the committed histories don't carry the recording machine's host name.
    var options = TestEnvironmentOptions.newBuilder()
        .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setIdentity("replay-fixture-recorder").build())
        .build();
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance(options)) {
      for (var sa : new String[] {"Step", "Path", "BusinessUnit"}) {
        env.registerSearchAttribute(sa, IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
      }
      env.registerSearchAttribute("SlaBreached", IndexedValueType.INDEXED_VALUE_TYPE_BOOL);
      env.registerSearchAttribute("RulesVersion", IndexedValueType.INDEXED_VALUE_TYPE_INT);
      FakeActivities fake = new FakeActivities().route(route, 1);
      fake.supplierRejects = supplierRejects;
      Worker worker = env.newWorker(QUEUE);
      worker.registerWorkflowImplementationFactory(
          DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(V1));
      worker.registerActivitiesImplementations(fake);
      env.start();

      String id = "rec-" + route.name().toLowerCase();
      WorkflowClient client = env.getWorkflowClient();
      DocumentRequestWorkflow wf = client.newWorkflowStub(DocumentRequestWorkflow.class,
          WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(DocumentRequestWorkflow.workflowId(id)).build());
      WorkflowClient.start(wf::process, new DocumentRequest(id, "Equipment", "purchase-order", 800, "CC-10", "Dana"));
      drive.run(wf, client);
      WorkflowStub.fromTyped(wf).getResult(RequestOutcome.class);

      String json = client.fetchHistory(DocumentRequestWorkflow.workflowId(id)).toJson(true);
      Files.writeString(FIXTURES.resolve(file), json);
    }
  }

  private static void awaitStep(DocumentRequestWorkflow wf, String expected) throws InterruptedException, IOException {
    Instant giveUp = Instant.now().plusSeconds(10);
    while (!expected.equals(WorkflowStub.fromTyped(wf).describe().getTypedSearchAttributes().get(SearchAttrs.STEP))) {
      if (Instant.now().isAfter(giveUp)) fail("never reached " + expected);
      Thread.sleep(50);
    }
  }
}
