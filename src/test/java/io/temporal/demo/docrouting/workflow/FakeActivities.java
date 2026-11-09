package io.temporal.demo.docrouting.workflow;

import io.temporal.activity.Activity;
import io.temporal.demo.docrouting.activities.ArchiveDocumentInput;
import io.temporal.demo.docrouting.activities.ArchiveDocumentOutput;
import io.temporal.demo.docrouting.activities.ClassifyInput;
import io.temporal.demo.docrouting.activities.ClassifyOutput;
import io.temporal.demo.docrouting.activities.DocumentActivities;
import io.temporal.demo.docrouting.activities.EvaluateRulesInput;
import io.temporal.demo.docrouting.activities.HttpCalls;
import io.temporal.demo.docrouting.activities.NotifyRequesterInput;
import io.temporal.demo.docrouting.activities.NotifyRequesterOutput;
import io.temporal.demo.docrouting.activities.PostInvoiceToErpInput;
import io.temporal.demo.docrouting.activities.PostInvoiceToErpOutput;
import io.temporal.demo.docrouting.activities.ReleaseBudgetInput;
import io.temporal.demo.docrouting.activities.ReleaseBudgetOutput;
import io.temporal.demo.docrouting.activities.ReserveBudgetInput;
import io.temporal.demo.docrouting.activities.ReserveBudgetOutput;
import io.temporal.demo.docrouting.activities.SendPurchaseOrderInput;
import io.temporal.demo.docrouting.activities.SendPurchaseOrderOutput;
import io.temporal.demo.docrouting.model.Route;
import io.temporal.demo.docrouting.model.RoutingDecision;
import io.temporal.failure.ApplicationFailure;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scriptable activities for workflow tests. Records every call by activity type, in order. */
public class FakeActivities implements DocumentActivities {
  public final List<String> calls = new CopyOnWriteArrayList<>();
  /** The message of each notifyRequester call, in order. */
  public final List<String> notifications = new CopyOnWriteArrayList<>();
  private volatile RoutingDecision decision;
  volatile boolean costCenterUnknown;
  volatile boolean supplierRejects;

  public FakeActivities route(Route path, int version) {
    decision = (new RoutingDecision(path, "test routes to " + path, version));
    return this;
  }

  public List<String> calls() {
    return Collections.unmodifiableList(calls);
  }

  private void record() {
    calls.add(Activity.getExecutionContext().getInfo().getActivityType());
  }

  @Override
  public ClassifyOutput classify(ClassifyInput input) {
    record();
    return new ClassifyOutput(input.doc().docType());
  }

  @Override
  public RoutingDecision evaluateRules(EvaluateRulesInput input) {
    record();
    return decision;
  }

  @Override
  public PostInvoiceToErpOutput postInvoiceToErp(PostInvoiceToErpInput input) {
    record();
    return new PostInvoiceToErpOutput("A-test");
  }

  @Override
  public NotifyRequesterOutput notifyRequester(NotifyRequesterInput input) {
    record();
    notifications.add(input.message());
    return new NotifyRequesterOutput();
  }

  @Override
  public ReserveBudgetOutput reserveBudget(ReserveBudgetInput input) {
    record();
    if (costCenterUnknown) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Budget: unknown cost center " + input.doc().costCenter(), HttpCalls.REJECTED);
    }
    return new ReserveBudgetOutput();
  }

  @Override
  public ReleaseBudgetOutput releaseBudget(ReleaseBudgetInput input) {
    record();
    return new ReleaseBudgetOutput();
  }

  @Override
  public SendPurchaseOrderOutput sendPurchaseOrder(SendPurchaseOrderInput input) {
    record();
    if (supplierRejects) {
      throw ApplicationFailure.newNonRetryableFailure("Supplier portal rejected the purchase order", HttpCalls.REJECTED);
    }
    return new SendPurchaseOrderOutput();
  }

  @Override
  public ArchiveDocumentOutput archiveDocument(ArchiveDocumentInput input) {
    record();
    return new ArchiveDocumentOutput();
  }
}
