package io.temporal.demo.docrouting.workflow;

public record ReviewResult(String taskId, ReviewAction action, String message) {}
