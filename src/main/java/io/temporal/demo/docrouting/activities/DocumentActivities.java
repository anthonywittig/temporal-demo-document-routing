package io.temporal.demo.docrouting.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.demo.docrouting.model.RoutingDecision;

/**
 * Every step that touches the outside world. Side-effecting calls send an Idempotency-Key of
 * {@code <workflowId>/<activity type>}, which stays the same across retries and resets, so a
 * repeat never causes a second effect.
 *
 * <p>Each Activity takes a single input object and returns an output object, even where it has
 * nothing to return yet, so a field can be added to either later without changing the signature,
 * and the Temporal UI shows inputs and results with named fields.
 */
@ActivityInterface
public interface DocumentActivities {

  /** Returns the document type. */
  ClassifyOutput classify(ClassifyInput input);

  /** Reads the current routing rules; the decision, with its rules version, lands in history. */
  RoutingDecision evaluateRules(EvaluateRulesInput input);

  /** Invoice: post it to the ERP. Returns the ERP's posting ID; the confirmation arrives later as a Signal. */
  PostInvoiceToErpOutput postInvoiceToErp(PostInvoiceToErpInput input);

  /** Every path: tell the requester the outcome. */
  NotifyRequesterOutput notifyRequester(NotifyRequesterInput input);

  /** PurchaseOrder: reserve the cost against its cost center. Fails without retry if the cost center is unknown. */
  ReserveBudgetOutput reserveBudget(ReserveBudgetInput input);

  /** PurchaseOrder compensation: release the budget reservation. */
  ReleaseBudgetOutput releaseBudget(ReleaseBudgetInput input);

  /** PurchaseOrder: send the purchase order to the supplier portal. Fails without retry if the supplier rejects it. */
  SendPurchaseOrderOutput sendPurchaseOrder(SendPurchaseOrderInput input);

  /** v2 only: archive the processed document. */
  ArchiveDocumentOutput archiveDocument(ArchiveDocumentInput input);
}
