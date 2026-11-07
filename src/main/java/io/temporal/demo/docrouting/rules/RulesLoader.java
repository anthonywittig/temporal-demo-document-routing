package io.temporal.demo.docrouting.rules;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.demo.docrouting.workflow.Route;
import io.temporal.demo.docrouting.workflow.RoutingDecision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Business-unit routing rules, kept outside the workflow code in a JSON file.
 *
 * <p>The file is read on every evaluation, so editing it changes routing with no deploy. A file
 * that doesn't parse or validate never takes effect: the last valid rules stay in force and the
 * rejection is kept for the web app to show. Rolling back is just restoring an earlier file.
 */
public final class RulesLoader {
  private static final Logger log = LoggerFactory.getLogger(RulesLoader.class);
  private static final ObjectMapper JSON =
      new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  public record UnitRules(
      long humanReviewAbove,
      List<String> externalChecksFor,
      @JsonProperty("default") Route defaultPath) {}

  public record Rules(int version, Map<String, UnitRules> businessUnits) {}

  /** What the web app shows: the rules in force and the most recent rejected edit, if any. */
  public record Status(Integer version, String lastRejection) {}

  /** No entry for the document's business unit: a configuration problem, never fixed by retrying. */
  public static final class UnknownBusinessUnitException extends RuntimeException {
    public UnknownBusinessUnitException(String unit) {
      super("No routing rules for business unit " + unit);
    }
  }

  private final Path file;
  private Rules lastValid;
  private String lastRejection;

  public RulesLoader(Path file) {
    this.file = file;
  }

  /** Reads and validates the file now; returns the rules in force afterwards. */
  public synchronized Rules current() {
    try {
      Rules candidate = JSON.readValue(Files.readString(file), Rules.class);
      List<String> problems = validate(candidate);
      if (problems.isEmpty()) {
        if (lastValid == null || lastValid.version() != candidate.version()) {
          log.info("Routing rules version {} in effect", candidate.version());
        }
        lastValid = candidate;
        lastRejection = null;
      } else {
        reject(String.join("; ", problems));
      }
    } catch (IOException e) {
      reject(file.getFileName() + " is not valid: " + firstLine(e.getMessage()));
    }
    if (lastValid == null) {
      throw new IllegalStateException("No valid routing rules yet: " + lastRejection);
    }
    return lastValid;
  }

  public synchronized Status status() {
    try {
      current();
    } catch (IllegalStateException ignored) {
      // status() reports the rejection instead of throwing
    }
    return new Status(lastValid == null ? null : lastValid.version(), lastRejection);
  }

  /**
   * Cost above the unit's human-review threshold goes to a person (HumanReview); otherwise document
   * types listed for external checks go to ExternalChecks; everything else takes the unit's default path.
   */
  public RoutingDecision evaluate(String businessUnit, String docType, long cost) {
    return evaluate(businessUnit, docType, cost, false);
  }

  /**
   * As {@link #evaluate(String, String, long)}, but when {@code costApproved} is true a reviewer
   * has already accepted the cost, so the human-review threshold is skipped and only the remaining
   * rules decide the path.
   */
  public RoutingDecision evaluate(String businessUnit, String docType, long cost, boolean costApproved) {
    Rules rules = current();
    UnitRules unit = rules.businessUnits().get(businessUnit);
    if (unit == null) throw new UnknownBusinessUnitException(businessUnit);

    if (!costApproved && cost > unit.humanReviewAbove()) {
      return new RoutingDecision(
          Route.HUMAN_REVIEW,
          "%s: cost %d is above the human-review threshold %d".formatted(businessUnit, cost, unit.humanReviewAbove()),
          rules.version());
    }
    String costNote = costApproved
        ? "cost %d approved by a reviewer".formatted(cost)
        : "cost %d is within %d".formatted(cost, unit.humanReviewAbove());
    if (unit.externalChecksFor().contains(docType)) {
      return new RoutingDecision(
          Route.EXTERNAL_CHECKS,
          "%s: %s documents need external checks (%s)".formatted(businessUnit, docType, costNote),
          rules.version());
    }
    return new RoutingDecision(
        unit.defaultPath(),
        "%s: default path for %s (%s)".formatted(businessUnit, docType, costNote),
        rules.version());
  }

  static List<String> validate(Rules rules) {
    List<String> problems = new ArrayList<>();
    if (rules.version() <= 0) problems.add("version must be a positive integer");
    if (rules.businessUnits() == null || rules.businessUnits().isEmpty()) {
      problems.add("at least one business unit is required");
      return problems;
    }
    rules.businessUnits().forEach((name, unit) -> {
      if (unit == null) {
        problems.add(name + " has no rules");
        return;
      }
      if (unit.humanReviewAbove() < 0) problems.add(name + ".humanReviewAbove must not be negative");
      if (unit.externalChecksFor() == null) problems.add(name + ".externalChecksFor is required");
      if (unit.defaultPath() == null) problems.add(name + ".default must be one of " + Route.allValues());
    });
    return problems;
  }

  private void reject(String reason) {
    if (!reason.equals(lastRejection)) {
      log.warn("Rejected routing rules edit, keeping version {}: {}",
          lastValid == null ? "none" : lastValid.version(), reason);
    }
    lastRejection = reason;
  }

  /** Keeps the parser's reason and drops its location boilerplate. */
  private static String firstLine(String s) {
    if (s == null) return "unknown error";
    int nl = s.indexOf('\n');
    String line = nl < 0 ? s : s.substring(0, nl);
    for (String noise : new String[] {" (start marker", " at [Source"}) {
      int i = line.indexOf(noise);
      if (i > 0) line = line.substring(0, i);
    }
    return line;
  }
}
