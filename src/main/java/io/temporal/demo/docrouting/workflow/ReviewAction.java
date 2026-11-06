package io.temporal.demo.docrouting.workflow;

public enum ReviewAction {
  /** Close the request. */
  COMPLETE,
  /** Route the request back for re-evaluation against the current rules. */
  SEND_BACK
}
