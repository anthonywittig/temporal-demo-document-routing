package io.temporal.demo.docrouting.workflow;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DocumentRequestWorkflowTest {
  static final String QUEUE = "test-queue";
  static final Duration SLA = Duration.ofSeconds(60);
  static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(2);

  TestWorkflowEnvironment env;
  WorkflowClient client;
  final FakeActivities fake = new FakeActivities();

  void start(boolean archive) {
    env = TestWorkflowEnvironment.newInstance();
    env.registerSearchAttribute("Step", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("Path", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("BusinessUnit", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("SlaBreached", IndexedValueType.INDEXED_VALUE_TYPE_BOOL);
    env.registerSearchAttribute("RulesVersion", IndexedValueType.INDEXED_VALUE_TYPE_INT);
    Worker worker = env.newWorker(QUEUE);
    WorkflowSettings settings = new WorkflowSettings(SLA, CALLBACK_TIMEOUT, archive);
    worker.registerWorkflowImplementationFactory(
        DocumentRequestWorkflow.class, () -> new DocumentRequestWorkflowImpl(settings));
    worker.registerActivitiesImplementations(fake);
    env.start();
    client = env.getWorkflowClient();
  }

  @AfterEach
  void tearDown() {
    if (env != null) env.close();
  }

  static DocumentRequest doc(String id, String bu, String type, long cost) {
    return new DocumentRequest(id, bu, type, cost, "CC-10", "Dana");
  }

  DocumentRequestWorkflow begin(DocumentRequest doc) {
    DocumentRequestWorkflow wf = client.newWorkflowStub(DocumentRequestWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(QUEUE)
            .setWorkflowId(DocumentRequestWorkflow.workflowId(doc.documentId()))
            .build());
    WorkflowClient.start(wf::process, doc);
    return wf;
  }

  RequestOutcome result(DocumentRequestWorkflow wf) {
    return WorkflowStub.fromTyped(wf).getResult(RequestOutcome.class);
  }

  WorkflowExecutionDescription describe(DocumentRequestWorkflow wf) {
    return WorkflowStub.fromTyped(wf).describe();
  }

  String step(DocumentRequestWorkflow wf) {
    return describe(wf).getTypedSearchAttributes().get(SearchAttrs.STEP);
  }

  /** Lets the workflow run (with time skipping paused) until it reaches the given step. */
  void awaitStep(DocumentRequestWorkflow wf, String expected) throws InterruptedException {
    Instant giveUp = Instant.now().plusSeconds(10);
    while (!expected.equals(step(wf))) {
      if (Instant.now().isAfter(giveUp)) fail("never reached step " + expected + ", last was " + step(wf));
      Thread.sleep(50);
    }
  }

  // ---- routing and search attributes ---------------------------------------------------------

  @Test
  void decisionAndProgressAreSearchable() throws Exception {
    start(false);
    fake.route(Route.A, 7);
    var wf = begin(doc("sa1", "Devices", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    var attrs = describe(wf).getTypedSearchAttributes();
    assertEquals("A", attrs.get(SearchAttrs.PATH));
    assertEquals("Devices", attrs.get(SearchAttrs.BUSINESS_UNIT));
    assertEquals(7L, attrs.get(SearchAttrs.RULES_VERSION));
    assertEquals(false, attrs.get(SearchAttrs.SLA_BREACHED));
    assertEquals("sa1-r1", describe(wf).getMemo("taskId", String.class));
    assertEquals(List.of("Classify", "EvaluateRules"), fake.calls());
  }

  // ---- path B ----------------------------------------------------------------------------------

  @Test
  void pathBWaitsForErpConfirmationThenNotifies() throws Exception {
    start(false);
    fake.route(Route.B, 1);
    var wf = begin(doc("b1", "Nutrition", "invoice", 3000));
    awaitStep(wf, Steps.AWAIT_ERP_CONFIRMATION);
    assertFalse(fake.calls().contains("NotifyRequester"), "must not notify before the acknowledgement");
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertEquals(Route.B, outcome.path());
    assertEquals(List.of("Classify", "EvaluateRules", "PostInvoiceToErp", "NotifyRequester"), fake.calls());
    assertEquals(Steps.COMPLETED, step(wf));
  }

  @Test
  void acknowledgementArrivingEarlyIsKept() {
    start(false);
    fake.route(Route.B, 1);
    var wf = begin(doc("b2", "Nutrition", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    wf.erpPostingConfirmed(new ErpConfirmation("A-dup", "later")); // duplicate is ignored
    assertEquals(RequestOutcome.COMPLETED, result(wf).status());
    assertEquals(1, fake.calls().stream().filter("NotifyRequester"::equals).count());
  }

  @Test
  void missingErpConfirmationFailsTheStep() {
    start(false);
    fake.route(Route.B, 1);
    var wf = begin(doc("b3", "Nutrition", "invoice", 3000));
    var e = assertThrows(WorkflowFailedException.class, () -> result(wf));
    assertEquals("CallbackTimeout", ((ApplicationFailure) e.getCause()).getType());
    assertEquals(Steps.AWAIT_ERP_CONFIRMATION, step(wf));
  }

  // ---- path A ----------------------------------------------------------------------------------

  @Test
  void reviewerRejectsTheRequest() throws Exception {
    start(true); // even on v2, a rejection is not archived
    fake.route(Route.A, 1);
    var wf = begin(doc("a1", "Devices", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    ReviewResult r = wf.review(new ReviewDecision("a1-r1", ReviewAction.REJECT, "Sam", "not in budget"));
    assertEquals("a1-r1", r.taskId());
    assertEquals("Rejected by Sam", r.message());
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.REJECTED, outcome.status());
    assertEquals(Route.A, outcome.path());
    assertTrue(outcome.detail().contains("Sam"));
    assertEquals(List.of("Classify", "EvaluateRules", "NotifyRequester"), fake.calls());
    assertEquals(Steps.REJECTED, step(wf));
  }

  @Test
  void approveReEvaluatesUnderCurrentRulesWithoutTheCostCheck() throws Exception {
    start(false);
    fake.route(Route.A, 1).route(Route.B, 2); // the rules change while the task is open
    var wf = begin(doc("a2", "Devices", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    ReviewResult r = wf.review(new ReviewDecision("a2-r1", ReviewAction.APPROVE, "Sam", "worth it"));
    assertEquals("Approved by Sam", r.message());
    awaitStep(wf, Steps.AWAIT_ERP_CONFIRMATION);
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertEquals(Route.B, outcome.path());
    assertEquals(2, outcome.rulesVersion());
    assertEquals(List.of(false, true), fake.costApprovedFlags, "the second evaluation must skip the cost check");
  }

  @Test
  void approvalCanStillEndInPathC() throws Exception {
    start(false);
    fake.route(Route.A, 1).route(Route.C, 1);
    var wf = begin(doc("a5", "Devices", "purchase-order", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    wf.review(new ReviewDecision("a5-r1", ReviewAction.APPROVE, "Sam", ""));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.COMPLETED, outcome.status());
    assertEquals(Route.C, outcome.path());
    assertEquals(List.of("Classify", "EvaluateRules", "EvaluateRules", "CheckCostCenter",
        "ReserveBudget", "SendPurchaseOrder", "NotifyRequester"), fake.calls());
  }

  @Test
  void staleOrWrongTaskIsRejectedWithoutTouchingHistory() throws Exception {
    start(false);
    fake.route(Route.A, 1).route(Route.A, 1);
    var wf = begin(doc("a3", "Devices", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);

    var wrong = assertThrows(WorkflowUpdateException.class,
        () -> wf.review(new ReviewDecision("nope", ReviewAction.REJECT, "Sam", "")));
    assertTrue(wrong.getCause().getMessage().contains("no longer open"), wrong.getCause().getMessage());

    wf.review(new ReviewDecision("a3-r1", ReviewAction.APPROVE, "Sam", ""));
    awaitStep(wf, Steps.HUMAN_TASK); // the scripted rules route it to a person again
    // A second click on the first task, after it was approved, is stale.
    var stale = assertThrows(WorkflowUpdateException.class,
        () -> wf.review(new ReviewDecision("a3-r1", ReviewAction.REJECT, "Sam", "")));
    assertTrue(stale.getCause().getMessage().contains("a3-r2"), stale.getCause().getMessage());

    long updatesInHistory = client.fetchHistory(DocumentRequestWorkflow.workflowId("a3")).getEvents().stream()
        .filter(ev -> ev.hasWorkflowExecutionUpdateAcceptedEventAttributes()).count();
    assertEquals(1, updatesInHistory, "rejected updates must not be written to history");
  }

  @Test
  void slaBreachMarksTheTaskButKeepsItOpen() throws Exception {
    start(false);
    fake.route(Route.A, 1);
    var wf = begin(doc("a4", "Devices", "invoice", 3000));
    awaitStep(wf, Steps.HUMAN_TASK);
    env.sleep(SLA.plusSeconds(5));
    assertEquals(true, describe(wf).getTypedSearchAttributes().get(SearchAttrs.SLA_BREACHED));
    assertEquals(Steps.HUMAN_TASK, step(wf));
    wf.review(new ReviewDecision("a4-r1", ReviewAction.REJECT, "Sam", "late, and no"));
    assertEquals(RequestOutcome.REJECTED, result(wf).status());
  }

  // ---- path C ----------------------------------------------------------------------------------

  @Test
  void pathCRunsChecksTasksAndFinalAction() throws Exception {
    start(false);
    fake.route(Route.C, 1);
    var wf = begin(doc("c1", "Devices", "purchase-order", 800));
    assertEquals(RequestOutcome.COMPLETED, result(wf).status());
    assertEquals(List.of("Classify", "EvaluateRules", "CheckCostCenter",
        "ReserveBudget", "SendPurchaseOrder", "NotifyRequester"), fake.calls());
  }

  @Test
  void supplierRejectionReleasesTheBudget() throws Exception {
    start(false);
    fake.route(Route.C, 1);
    fake.supplierRejects = true;
    var wf = begin(doc("c2", "Devices", "purchase-order", 800));
    RequestOutcome outcome = result(wf);
    assertEquals(RequestOutcome.REJECTED, outcome.status());
    List<String> tail = fake.calls().subList(fake.calls().size() - 3, fake.calls().size());
    assertEquals(List.of("ReserveBudget", "SendPurchaseOrder", "ReleaseBudget"), tail);
    assertEquals(Steps.REJECTED, step(wf));
  }

  @Test
  void unknownCostCenterFailsTheRequestAtCheckCostCenter() {
    start(false);
    fake.route(Route.C, 1);
    fake.costCenterUnknown = true;
    var wf = begin(doc("c3", "Devices", "purchase-order", 800));
    var e = assertThrows(WorkflowFailedException.class, () -> result(wf));
    assertTrue(e.getCause().getCause().getMessage().contains("unknown cost center"), e.getCause().getCause().getMessage());
    assertEquals(Steps.CHECK_COST_CENTER, step(wf));
    assertEquals(1, fake.calls().stream().filter("CheckCostCenter"::equals).count(), "422 must not be retried");
  }

  // ---- versions --------------------------------------------------------------------------------

  @Test
  void v1HasNoArchiveStep() {
    start(false);
    fake.route(Route.B, 1);
    var wf = begin(doc("v1", "Nutrition", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    result(wf);
    assertFalse(fake.calls().contains("ArchiveDocument"));
  }

  @Test
  void v2ArchivesEveryCompletedRequest() {
    start(true);
    fake.route(Route.B, 1);
    var wf = begin(doc("v2", "Nutrition", "invoice", 3000));
    wf.erpPostingConfirmed(new ErpConfirmation("A-test", "now"));
    result(wf);
    assertEquals("ArchiveDocument", fake.calls().get(fake.calls().size() - 1));
    assertEquals(Steps.COMPLETED, step(wf));
  }
}
