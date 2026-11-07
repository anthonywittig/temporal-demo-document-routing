package io.temporal.demo.docrouting.rules;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.demo.docrouting.workflow.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesLoaderTest {
  @TempDir Path dir;
  Path file;
  RulesLoader loader;

  static final String V1 = """
      { "version": 1,
        "businessUnits": {
          "Nutrition": { "humanReviewAbove": 5000, "externalChecksFor": ["purchase-order"], "default": "ErpPosting" },
          "Devices": { "humanReviewAbove": 1000, "externalChecksFor": ["purchase-order", "contract"], "default": "ErpPosting" } } }
      """;

  @BeforeEach
  void setUp() throws IOException {
    file = dir.resolve("rules.json");
    Files.copy(Path.of("config/rules.default.json"), file);
    loader = new RulesLoader(file);
  }

  @Test
  void committedDefaultsAreValid() {
    assertEquals(1, loader.current().version());
    assertNull(loader.status().lastRejection());
  }

  @Test
  void sameCostRoutesDifferentlyPerBusinessUnit() {
    var nutrition = loader.evaluate("Nutrition", "invoice", 3000);
    var devices = loader.evaluate("Devices", "invoice", 3000);
    assertEquals(Route.ERP_POSTING, nutrition.path());
    assertEquals(Route.HUMAN_REVIEW, devices.path());
    assertEquals(1, nutrition.rulesVersion());
    assertTrue(devices.reason().contains("1000"), devices.reason());
  }

  @Test
  void eachPath() {
    assertEquals(Route.HUMAN_REVIEW, loader.evaluate("Nutrition", "invoice", 12000).path());
    assertEquals(Route.ERP_POSTING, loader.evaluate("Nutrition", "invoice", 300).path());
    assertEquals(Route.EXTERNAL_CHECKS, loader.evaluate("Devices", "purchase-order", 800).path());
  }

  @Test
  void approvedCostSkipsTheHumanReviewThreshold() {
    var approvedInvoice = loader.evaluate("Nutrition", "invoice", 12000, true);
    assertEquals(Route.ERP_POSTING, approvedInvoice.path());
    assertTrue(approvedInvoice.reason().contains("approved by a reviewer"), approvedInvoice.reason());
    assertEquals(Route.EXTERNAL_CHECKS, loader.evaluate("Devices", "purchase-order", 12000, true).path());
    assertEquals(Route.HUMAN_REVIEW, loader.evaluate("Nutrition", "invoice", 12000, false).path());
  }

  @Test
  void unknownBusinessUnitIsRejected() {
    var e = assertThrows(RulesLoader.UnknownBusinessUnitException.class, () -> loader.evaluate("BU9", "invoice", 1));
    assertTrue(e.getMessage().contains("BU9"));
  }

  @Test
  void liveEditTakesEffectOnNextEvaluation() throws IOException {
    assertEquals(Route.ERP_POSTING, loader.evaluate("Nutrition", "invoice", 3000).path());
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    var decision = loader.evaluate("Nutrition", "invoice", 3000);
    assertEquals(Route.HUMAN_REVIEW, decision.path());
    assertEquals(2, decision.rulesVersion());
  }

  @Test
  void invalidJsonKeepsLastValidVersion() throws IOException {
    loader.current();
    Files.writeString(file, "{ \"version\": 2, ");
    assertEquals(1, loader.current().version());
    assertNotNull(loader.status().lastRejection());
    assertEquals(1, loader.status().version());
  }

  @Test
  void negativeThresholdKeepsLastValidVersion() throws IOException {
    loader.current();
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "-5"));
    assertEquals(1, loader.current().version());
    assertTrue(loader.status().lastRejection().contains("Nutrition.humanReviewAbove"));
  }

  @Test
  void unknownPathNameIsRejected() throws IOException {
    loader.current();
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("\"ErpPosting\"", "\"Z\""));
    assertEquals(1, loader.current().version());
    assertTrue(loader.status().lastRejection().contains("must be one of HumanReview, ErpPosting, ExternalChecks"),
        loader.status().lastRejection());
  }

  @Test
  void olderRulesFilesWithPathLettersStillLoad() throws IOException {
    Files.writeString(file, V1.replace("\"ErpPosting\"", "\"B\""));
    assertEquals(Route.ERP_POSTING, loader.current().businessUnits().get("Nutrition").defaultPath());
    assertNull(loader.status().lastRejection());
    for (var r : Route.values()) assertEquals(r, Route.fromValue(r.value()));
    assertEquals(Route.HUMAN_REVIEW, Route.fromValue("A"));
    assertEquals(Route.EXTERNAL_CHECKS, Route.fromValue("C"));
  }

  @Test
  void rollbackByRestoringEarlierContent() throws IOException {
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    assertEquals(2, loader.current().version());
    Files.writeString(file, V1);
    var decision = loader.evaluate("Nutrition", "invoice", 3000);
    assertEquals(1, decision.rulesVersion());
    assertEquals(Route.ERP_POSTING, decision.path());
  }

  @Test
  void noValidRulesEverMeansNoDecision() throws IOException {
    Files.writeString(file, "not json");
    var fresh = new RulesLoader(file);
    assertThrows(IllegalStateException.class, fresh::current);
    assertNull(fresh.status().version());
    assertNotNull(fresh.status().lastRejection());
  }
}
