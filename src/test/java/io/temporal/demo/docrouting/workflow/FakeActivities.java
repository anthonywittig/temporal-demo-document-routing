package io.temporal.demo.docrouting.workflow;

import io.temporal.activity.Activity;
import io.temporal.demo.docrouting.activities.DocumentActivities;
import io.temporal.demo.docrouting.activities.HttpCalls;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scriptable activities for workflow tests. Records every call by activity type, in order. */
public class FakeActivities implements DocumentActivities {
  public final List<String> calls = new CopyOnWriteArrayList<>();
  final Deque<RoutingDecision> decisions = new ArrayDeque<>();
  /** The costApproved flag passed to each evaluateRules call, in order. */
  public final List<Boolean> costApprovedFlags = new CopyOnWriteArrayList<>();
  volatile boolean costCenterUnknown;
  volatile boolean supplierRejects;

  public FakeActivities route(Route path, int version) {
    decisions.add(new RoutingDecision(path, "test routes to " + path, version));
    return this;
  }

  public List<String> calls() {
    return Collections.unmodifiableList(calls);
  }

  private void record() {
    calls.add(Activity.getExecutionContext().getInfo().getActivityType());
  }

  @Override
  public String classify(DocumentRequest doc) {
    record();
    return doc.docType();
  }

  @Override
  public synchronized RoutingDecision evaluateRules(EvaluateRulesInput input) {
    record();
    costApprovedFlags.add(input.costApproved());
    // The last scripted decision repeats once the script runs out.
    return decisions.size() > 1 ? decisions.poll() : decisions.peek();
  }

  @Override
  public String postInvoiceToErp(DocumentRequest doc) {
    record();
    return "A-test";
  }

  @Override
  public void notifyRequester(NotifyRequesterInput input) {
    record();
  }

  @Override
  public void reserveBudget(DocumentRequest doc) {
    record();
    if (costCenterUnknown) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Budget: unknown cost center " + doc.costCenter(), HttpCalls.REJECTED);
    }
  }

  @Override
  public void releaseBudget(DocumentRequest doc) {
    record();
  }

  @Override
  public void sendPurchaseOrder(DocumentRequest doc) {
    record();
    if (supplierRejects) {
      throw ApplicationFailure.newNonRetryableFailure("Supplier portal rejected the purchase order", HttpCalls.REJECTED);
    }
  }

  @Override
  public void archiveDocument(DocumentRequest doc) {
    record();
  }
}
