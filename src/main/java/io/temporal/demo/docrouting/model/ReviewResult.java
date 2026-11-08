package io.temporal.demo.docrouting.model;

public record ReviewResult(String taskId, ReviewAction action, String message) {}
