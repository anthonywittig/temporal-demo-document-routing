package io.temporal.demo.docrouting.workflow;

/** The three processing paths from the reference workflow. */
public enum Route {
  /** Human task: a reviewer rejects the request, or approves its cost and sends it on through the rules. */
  A,
  /** The invoice is posted to the ERP, which confirms asynchronously, then the requester is notified. */
  B,
  /** Cost-center check, budget reservation, then the purchase order is sent to the supplier portal. */
  C
}
