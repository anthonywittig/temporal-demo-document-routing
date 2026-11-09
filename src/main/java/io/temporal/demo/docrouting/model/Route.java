package io.temporal.demo.docrouting.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The two processing paths. Each is written as its {@link #value()} wherever people see it: the
 * {@code Path} search attribute and Workflow payloads. Human review isn't a path: it's a gate the
 * rules can put in front of either one ({@link RoutingDecision#humanReviewRequired()}).
 */
public enum Route {
  /** The invoice is posted to the ERP, which confirms asynchronously, then the requester is notified. */
  INVOICE("Invoice"),
  /** Budget is reserved against the cost center, then the purchase order is sent to the supplier portal. */
  PURCHASE_ORDER("PurchaseOrder");

  private final String value;

  Route(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  /** The route written as {@code s}, or null if {@code s} names none. */
  @JsonCreator
  public static Route fromValue(String s) {
    for (Route r : values()) {
      if (r.value.equals(s)) return r;
    }
    return null;
  }

  @Override
  public String toString() {
    return value;
  }
}
