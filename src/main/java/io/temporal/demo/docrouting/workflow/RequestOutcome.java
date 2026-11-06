package io.temporal.demo.docrouting.workflow;

/** How a request ended. {@code status} is COMPLETED or REJECTED. */
public record RequestOutcome(String status, Route path, int rulesVersion, String detail) {
  public static final String COMPLETED = "COMPLETED";
  public static final String REJECTED = "REJECTED";
}
