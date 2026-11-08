package io.temporal.demo.docrouting.model;

/** A reviewer's action on one human task, identified by its task ID. */
public record ReviewDecision(String taskId, ReviewAction action, String reviewer, String comment) {}
