package io.temporal.demo.docrouting.model;

/**
 * A reviewer's decision on one human task, identified by its task ID. Approved: the request goes
 * on down the path the rules chose. Not approved: the requester is notified and the workflow ends
 * as REJECTED.
 */
public record ReviewDecision(String taskId, boolean approved, String reviewer, String comment) {}
