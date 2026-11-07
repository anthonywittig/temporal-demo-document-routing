package io.temporal.demo.docrouting.workflow;

import static io.temporal.demo.docrouting.workflow.SearchAttrs.*;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.demo.docrouting.activities.DocumentActivities;
import io.temporal.demo.docrouting.activities.HttpCalls;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Saga;
import io.temporal.workflow.TimerOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;

public class DocumentRequestWorkflowImpl implements DocumentRequestWorkflow {
  private static final Logger log = Workflow.getLogger(DocumentRequestWorkflowImpl.class);

  /**
   * Every external call: give up on one attempt after 5s, retry transient failures with
   * exponential backoff (1s, 2s, 4s ... capped at 10s) for about three minutes, and never retry a
   * failure the activity marked non-retryable (a 4xx such as 422).
   */
  static final ActivityOptions ACTIVITY_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .setRetryOptions(
              RetryOptions.newBuilder()
                  .setInitialInterval(Duration.ofSeconds(1))
                  .setBackoffCoefficient(2)
                  .setMaximumInterval(Duration.ofSeconds(10))
                  .setMaximumAttempts(20)
                  .build())
          .build();

  private final WorkflowSettings settings;

  // Human review state
  private String openTaskId;
  private ReviewDecision decision;

  // The ERP's asynchronous confirmation, set by a Signal from the callback
  private ErpConfirmation erpConfirmation;

  public DocumentRequestWorkflowImpl(WorkflowSettings settings) {
    this.settings = settings;
  }

  @Override
  @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
  public RequestOutcome process(DocumentRequest doc) {
    Workflow.upsertTypedSearchAttributes(BUSINESS_UNIT.valueSet(doc.businessUnit()));

    step(Steps.CLASSIFY);
    String docType = activity("Classify").classify(doc);
    // Set once a reviewer approves the cost; later evaluations skip the cost threshold.
    boolean costApproved = false;

    // At most two rounds: once the cost is approved the rules can't route back to HumanReview,
    // because only the cost threshold leads there (RulesLoader.pathFor never picks it). The round
    // number keeps each review task's ID unique, so a stale decision can't approve a later task.
    for (int round = 1; ; round++) {
      step(Steps.EVALUATE_RULES);
      // Routing runs in an Activity: the rules are read from outside, and the decision (with
      // the rules version that made it) is recorded in history. In-flight requests keep it.
      RoutingDecision route = activity("Evaluate Rules").evaluateRules(doc, docType, costApproved);
      Workflow.upsertTypedSearchAttributes(
          PATH.valueSet(route.path().value()), RULES_VERSION.valueSet((long) route.rulesVersion()));
      log.info("{} routed to path {} by rules v{}: {}", doc.documentId(), route.path(), route.rulesVersion(), route.reason());

      switch (route.path()) {
        case HUMAN_REVIEW -> {
          ReviewDecision review = humanTask(doc, route, round);
          if (review.action() == ReviewAction.APPROVE) {
            // Back through the rules in force now, without the cost check that sent it here.
            costApproved = true;
            continue;
          }
          return rejectedByReviewer(doc, route, review);
        }
        case INVOICE -> {
          invoice(doc);
          return finish(doc, route, "Posted to the ERP, requester notified");
        }
        case PURCHASE_ORDER -> {
          return purchaseOrder(doc, route);
        }
      }
    }
  }

  // ---- HumanReview: a person approves or rejects the cost -----------------------------------

  private ReviewDecision humanTask(DocumentRequest doc, RoutingDecision route, int round) {
    openTaskId = doc.documentId() + "-r" + round;
    decision = null;
    Instant deadline = Instant.ofEpochMilli(Workflow.currentTimeMillis()).plus(settings.slaDuration());

    // The inbox reads these from visibility, so it works even when no worker is running.
    Map<String, Object> memo = new LinkedHashMap<>();
    memo.put("taskId", openTaskId);
    memo.put("slaDeadline", deadline.toString());
    memo.put("routingReason", route.reason());
    Workflow.upsertMemo(memo);
    Workflow.upsertTypedSearchAttributes(STEP.valueSet(Steps.HUMAN_TASK), SLA_BREACHED.valueSet(false));

    // The SLA marks the task as breached but doesn't cancel it: a late decision still counts.
    CancellationScope sla = Workflow.newCancellationScope(() ->
        Workflow.newTimer(settings.slaDuration(), named("Review SLA")).handle((ignored, canceled) -> {
          if (canceled == null) {
            log.info("{} task {} breached its SLA", doc.documentId(), openTaskId);
            Workflow.upsertTypedSearchAttributes(SLA_BREACHED.valueSet(true));
          }
          return null;
        }));
    sla.run();

    Workflow.await(() -> decision != null);
    sla.cancel();
    return decision;
  }

  @Override
  public void validateReview(ReviewDecision d) {
    if (openTaskId == null || d == null || !openTaskId.equals(d.taskId())) {
      throw new IllegalArgumentException(
          "Task " + (d == null ? "?" : d.taskId()) + " is no longer open"
              + (openTaskId == null ? "" : "; the open task is " + openTaskId));
    }
    if (d.action() == null) throw new IllegalArgumentException("Choose APPROVE or REJECT");
    if (d.reviewer() == null || d.reviewer().isBlank()) throw new IllegalArgumentException("Reviewer is required");
  }

  @Override
  public ReviewResult review(ReviewDecision d) {
    String taskId = openTaskId;
    decision = d;
    openTaskId = null;
    return new ReviewResult(taskId, d.action(),
        d.action() == ReviewAction.APPROVE ? "Approved by " + d.reviewer() : "Rejected by " + d.reviewer());
  }

  /** The reviewer turned the request down: tell the requester and end, with nothing else to undo. */
  private RequestOutcome rejectedByReviewer(DocumentRequest doc, RoutingDecision route, ReviewDecision review) {
    activity("Notify Requester").notifyRequester(doc, "rejected by " + review.reviewer());
    step(Steps.REJECTED);
    return new RequestOutcome(RequestOutcome.REJECTED, route.path(), route.rulesVersion(),
        "Rejected by " + review.reviewer());
  }

  // ---- Invoice: ERP posting, asynchronous confirmation, notification ------------------------

  private void invoice(DocumentRequest doc) {
    step(Steps.POST_TO_ERP);
    activity("Post Invoice to ERP").postInvoiceToErp(doc);

    step(Steps.AWAIT_ERP_CONFIRMATION);
    if (!awaitWithTimer(settings.callbackTimeout(), "Wait for ERP Confirmation", () -> erpConfirmation != null)) {
      throw ApplicationFailure.newNonRetryableFailure(
          "The ERP did not confirm the posting within " + settings.callbackTimeout(), "CallbackTimeout");
    }

    step(Steps.NOTIFY);
    activity("Notify Requester").notifyRequester(doc, "posted to the ERP, reference " + erpConfirmation.postingId());
  }

  @Override
  public void erpPostingConfirmed(ErpConfirmation ack) {
    if (erpConfirmation == null) erpConfirmation = ack; // a repeated acknowledgement changes nothing
  }

  // ---- PurchaseOrder: cost-center check, budget reservation, purchase order, compensation -------

  private RequestOutcome purchaseOrder(DocumentRequest doc, RoutingDecision route) {
    step(Steps.CHECK_COST_CENTER);
    // A 422 here (unknown cost center) is non-retryable: the Workflow fails at this step and
    // waits for an operator to fix the data and reset it.
    activity("Check Cost Center").checkCostCenter(doc);

    // Each side-effecting step registers its undo as soon as it has succeeded. There is one
    // today; more steps would register theirs the same way and be undone in reverse order.
    Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
    step(Steps.RESERVE_BUDGET);
    activity("Reserve Budget").reserveBudget(doc);
    saga.addCompensation(activity("Release Budget")::releaseBudget, doc);

    step(Steps.SEND_PURCHASE_ORDER);
    try {
      activity("Send Purchase Order").sendPurchaseOrder(doc);
    } catch (ActivityFailure e) {
      step(Steps.COMPENSATING);
      saga.compensate(); // releases the budget reservation
      if (isRejection(e)) {
        step(Steps.REJECTED);
        return new RequestOutcome(RequestOutcome.REJECTED, route.path(), route.rulesVersion(),
            "The supplier portal rejected the purchase order; budget reservation released");
      }
      throw e;
    }
    activity("Notify Requester").notifyRequester(doc, "purchase order sent to the supplier portal");
    return finish(doc, route, "Cost center checked, purchase order sent to the supplier portal");
  }

  // ---- shared -------------------------------------------------------------------------------

  private RequestOutcome finish(DocumentRequest doc, RoutingDecision route, String detail) {
    if (settings.archiveDocuments()) {
      step(Steps.ARCHIVE);
      activity("Archive Document").archiveDocument(doc);
    }
    step(Steps.COMPLETED);
    return new RequestOutcome(RequestOutcome.COMPLETED, route.path(), route.rulesVersion(), detail);
  }

  /**
   * Like {@code Workflow.await(timeout, condition)}, but the timer carries a summary, so the
   * Temporal UI timeline shows "Wait for ERP Confirmation" instead of a timer ID. Returns false if
   * the timer fired first.
   */
  private static boolean awaitWithTimer(Duration timeout, String summary, Supplier<Boolean> condition) {
    @SuppressWarnings("unchecked")
    Promise<Void>[] timer = new Promise[1];
    CancellationScope scope = Workflow.newCancellationScope(() -> timer[0] = Workflow.newTimer(timeout, named(summary)));
    scope.run();
    Workflow.await(() -> condition.get() || timer[0].isCompleted());
    if (!condition.get()) return false;
    scope.cancel(); // the condition won: cancel the timer, as Workflow.await(timeout, ...) does
    return true;
  }

  /**
   * An Activity stub whose calls carry a summary, so the Temporal UI timeline labels the bar with
   * a readable phrase ("Evaluate Rules") instead of the Activity type ("EvaluateRules"). The type
   * itself stays the same, so in-flight requests and recorded histories still replay.
   */
  private static DocumentActivities activity(String summary) {
    return Workflow.newActivityStub(
        DocumentActivities.class, ActivityOptions.newBuilder(ACTIVITY_OPTIONS).setSummary(summary).build());
  }

  private static TimerOptions named(String summary) {
    return TimerOptions.newBuilder().setSummary(summary).build();
  }

  private static void step(String step) {
    Workflow.upsertTypedSearchAttributes(STEP.valueSet(step));
  }

  private static boolean isRejection(ActivityFailure e) {
    return e.getCause() instanceof ApplicationFailure af && HttpCalls.REJECTED.equals(af.getType());
  }
}
