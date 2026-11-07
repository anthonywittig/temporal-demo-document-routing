package io.temporal.demo.docrouting.workflow;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.UpdateValidatorMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One execution per document. The Workflow ID is {@code doc-<documentId>}, so the same document
 * can't be processed twice.
 */
@WorkflowInterface
public interface DocumentRequestWorkflow {

  static String workflowId(String documentId) {
    return "doc-" + documentId;
  }

  @WorkflowMethod
  RequestOutcome process(DocumentRequest doc);

  /** HumanReview: a reviewer approves or rejects the open task. Refused if the task isn't open. */
  @UpdateMethod(name = "Review")
  ReviewResult review(ReviewDecision decision);

  @UpdateValidatorMethod(updateName = "Review")
  void validateReview(ReviewDecision decision);

  /** Invoice: the ERP's confirmation of the posting, delivered by callback. */
  @SignalMethod(name = "ERP Posting Confirmed")
  void erpPostingConfirmed(ErpConfirmation ack);

  /**
   * The same Signal under its earlier name, so requests started before the rename (and the
   * recorded v1 histories) still receive it. Nothing sends this name any more.
   */
  @SignalMethod(name = "ErpPostingConfirmed")
  default void erpPostingConfirmedLegacy(ErpConfirmation ack) {
    erpPostingConfirmed(ack);
  }
}
