package io.temporal.demo.docrouting.model;

public record ReviewResult(String taskId, boolean approved, String message) {}
