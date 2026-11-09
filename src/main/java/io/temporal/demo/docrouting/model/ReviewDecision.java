package io.temporal.demo.docrouting.model;

/**
 * A reviewer's decision on one human task, identified by its task ID. Approved: the request goes
 * back through the rules in force now, with the cost threshold waived. Not approved: the requester
 * is notified and the workflow ends as REJECTED.
 */
public record ReviewDecision(String taskId, boolean approved, String reviewer, String comment) {}
