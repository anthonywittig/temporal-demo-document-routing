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

  /** Path A: a reviewer approves or rejects the open task. Refused if the task isn't open. */
  @UpdateMethod(name = "Review")
  ReviewResult review(ReviewDecision decision);

  @UpdateValidatorMethod(updateName = "Review")
  void validateReview(ReviewDecision decision);

  /** Path B: the ERP's confirmation of the posting, delivered by callback. */
  @SignalMethod(name = "ErpPostingConfirmed")
  void erpPostingConfirmed(ErpConfirmation ack);
}
