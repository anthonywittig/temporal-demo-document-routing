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
          "BU1": { "humanReviewAbove": 5000, "externalChecksFor": ["purchase-order"], "default": "B" },
          "BU2": { "humanReviewAbove": 1000, "externalChecksFor": ["purchase-order", "contract"], "default": "B" } } }
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
    var bu1 = loader.evaluate("BU1", "invoice", 3000);
    var bu2 = loader.evaluate("BU2", "invoice", 3000);
    assertEquals(Route.B, bu1.path());
    assertEquals(Route.A, bu2.path());
    assertEquals(1, bu1.rulesVersion());
    assertTrue(bu2.reason().contains("1000"), bu2.reason());
  }

  @Test
  void eachPath() {
    assertEquals(Route.A, loader.evaluate("BU1", "invoice", 12000).path());
    assertEquals(Route.B, loader.evaluate("BU1", "invoice", 300).path());
    assertEquals(Route.C, loader.evaluate("BU2", "purchase-order", 800).path());
  }

  @Test
  void unknownBusinessUnitIsRejected() {
    var e = assertThrows(RulesLoader.UnknownBusinessUnitException.class, () -> loader.evaluate("BU9", "invoice", 1));
    assertTrue(e.getMessage().contains("BU9"));
  }

  @Test
  void liveEditTakesEffectOnNextEvaluation() throws IOException {
    assertEquals(Route.B, loader.evaluate("BU1", "invoice", 3000).path());
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    var decision = loader.evaluate("BU1", "invoice", 3000);
    assertEquals(Route.A, decision.path());
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
    assertTrue(loader.status().lastRejection().contains("BU1.humanReviewAbove"));
  }

  @Test
  void unknownPathNameIsRejected() throws IOException {
    loader.current();
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("\"default\": \"B\"", "\"default\": \"Z\""));
    assertEquals(1, loader.current().version());
    assertNotNull(loader.status().lastRejection());
  }

  @Test
  void rollbackByRestoringEarlierContent() throws IOException {
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    assertEquals(2, loader.current().version());
    Files.writeString(file, V1);
    var decision = loader.evaluate("BU1", "invoice", 3000);
    assertEquals(1, decision.rulesVersion());
    assertEquals(Route.B, decision.path());
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
