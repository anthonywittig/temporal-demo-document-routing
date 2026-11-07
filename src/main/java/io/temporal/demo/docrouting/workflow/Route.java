package io.temporal.demo.docrouting.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The three processing paths. Each is written as its {@link #value()} wherever people see it: the
 * {@code Path} search attribute, the rules file's {@code "default"}, and Workflow payloads.
 */
public enum Route {
  /** A reviewer rejects the request, or approves its cost and sends it on through the rules. */
  HUMAN_REVIEW("HumanReview"),
  /** The invoice is posted to the ERP, which confirms asynchronously, then the requester is notified. */
  ERP_POSTING("ErpPosting"),
  /** Cost-center check, budget reservation, then the purchase order is sent to the supplier portal. */
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

  /** "HumanReview, ErpPosting, PurchaseOrder", for error messages. */
  public static String allValues() {
    return Arrays.stream(values()).map(Route::value).collect(Collectors.joining(", "));
  }

  @Override
  public String toString() {
    return value;
  }
}
