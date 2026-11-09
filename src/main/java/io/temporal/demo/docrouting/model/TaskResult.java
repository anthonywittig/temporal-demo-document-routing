package io.temporal.demo.docrouting.model;

/** What a Complete or Reassign did, for the person who clicked. */
public record TaskResult(String taskId, String message) {}
