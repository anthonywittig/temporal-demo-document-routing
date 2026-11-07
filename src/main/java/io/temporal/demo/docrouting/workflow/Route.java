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
  HUMAN_REVIEW("HumanReview", "A"),
  /** The invoice is posted to the ERP, which confirms asynchronously, then the requester is notified. */
  ERP_POSTING("ErpPosting", "B"),
  /** Cost-center check, budget reservation, then the purchase order is sent to the supplier portal. */
  EXTERNAL_CHECKS("ExternalChecks", "C");

  private final String value;
  /** What the path was called before it had a name, still accepted so older rules files load. */
  private final String legacyLetter;

  Route(String value, String legacyLetter) {
    this.value = value;
    this.legacyLetter = legacyLetter;
  }

  @JsonValue
  public String value() {
    return value;
  }

  /** The route written as {@code s}, or null if {@code s} names none. */
  @JsonCreator
  public static Route fromValue(String s) {
    for (Route r : values()) {
      if (r.value.equals(s) || r.legacyLetter.equals(s)) return r;
    }
    return null;
  }

  /** "HumanReview, ErpPosting, ExternalChecks", for error messages. */
  public static String allValues() {
    return Arrays.stream(values()).map(Route::value).collect(Collectors.joining(", "));
  }

  @Override
  public String toString() {
    return value;
  }
}
