package io.temporal.demo.docrouting.workflow;

public enum ReviewAction {
  /**
   * Accept the cost: the request goes back through the rules in force now, with the cost
   * threshold waived, and continues down whichever path they pick.
   */
  APPROVE,
  /** Turn the request down. The requester is notified and the workflow ends as REJECTED. */
  REJECT
}
