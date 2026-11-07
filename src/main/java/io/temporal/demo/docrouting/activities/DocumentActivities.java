package io.temporal.demo.docrouting.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.demo.docrouting.workflow.DocumentRequest;
import io.temporal.demo.docrouting.workflow.RoutingDecision;

/**
 * Every step that touches the outside world. Side-effecting calls send an Idempotency-Key of
 * {@code <workflowId>/<activity type>}, which stays the same across retries and resets, so a
 * repeat never causes a second effect.
 */
@ActivityInterface
public interface DocumentActivities {

  /** Returns the document type. */
  String classify(DocumentRequest doc);

  /**
   * Reads the current routing rules; the decision, with its rules version, lands in history.
   *
   * @param costApproved a reviewer has approved the cost, so the human-review threshold is skipped
   */
  RoutingDecision evaluateRules(DocumentRequest doc, String docType, boolean costApproved);

  /** Invoice: post it to the ERP. Returns the ERP's posting ID; the confirmation arrives later as a Signal. */
  String postInvoiceToErp(DocumentRequest doc);

  /** Every path: tell the requester the outcome. */
  void notifyRequester(DocumentRequest doc, String message);

  /** PurchaseOrder: check the cost center. Fails without retry if it is unknown. */
  void checkCostCenter(DocumentRequest doc);

  void reserveBudget(DocumentRequest doc);

  void releaseBudget(DocumentRequest doc);

  /** PurchaseOrder: send the purchase order to the supplier portal. Fails without retry if the supplier rejects it. */
  void sendPurchaseOrder(DocumentRequest doc);

  /** v2 only: archive the processed document. */
  void archiveDocument(DocumentRequest doc);
}
