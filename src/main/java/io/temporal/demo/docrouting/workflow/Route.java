package io.temporal.demo.docrouting.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The three processing paths. Each is written as its {@link #value()} wherever people see it: the
 * {@code Path} search attribute, the rules file's {@code "default"}, and Workflow payloads.
 */
public enum Route {
  /** A reviewer rejects the request, or approves its cost and sends it on through the rules. */
  HUMAN_REVIEW("HumanReview", "A"),
  /** The invoice is posted to the ERP, which confirms asynchronously, then the requester is notified. */
  ERP_POSTING("ErpPosting", "B"),
  /**
   * Cost-center check, budget reservation, then the purchase order is sent to the supplier portal.
   * Formerly ExternalChecks: requests started under that name, and rules files that use it, still
   * read it.
   */
  PURCHASE_ORDER("PurchaseOrder", "C", "ExternalChecks");

  private final String value;
  /**
   * Earlier names for the path (its letter, and any name it had before), still accepted so older
   * rules files and Workflow histories load.
   */
  private final List<String> legacyNames;

  Route(String value, String... legacyNames) {
    this.value = value;
    this.legacyNames = List.of(legacyNames);
  }

  @JsonValue
  public String value() {
    return value;
  }

  /** The route written as {@code s}, or null if {@code s} names none. */
  @JsonCreator
  public static Route fromValue(String s) {
    for (Route r : values()) {
      if (r.value.equals(s) || r.legacyNames.contains(s)) return r;
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
