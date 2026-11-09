package io.temporal.demo.docrouting.rules;

import static org.junit.jupiter.api.Assertions.*;

import io.temporal.demo.docrouting.model.Route;
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
          "Services": { "humanReviewAbove": 5000 },
          "Equipment": { "humanReviewAbove": 1000 } } }
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
  void sameCostIsReviewedInOneUnitButNotTheOther() {
    var services = loader.evaluate("Services", "invoice", 3000);
    var equipment = loader.evaluate("Equipment", "invoice", 3000);
    assertEquals(Route.INVOICE, services.path());
    assertFalse(services.humanReviewRequired());
    assertEquals(Route.INVOICE, equipment.path());
    assertTrue(equipment.humanReviewRequired());
    assertEquals(1, services.rulesVersion());
    assertEquals("Equipment: invoice documents are posted to the ERP after human review (cost 3000 is above 1000)",
        equipment.reason());
  }

  @Test
  void documentTypeAloneChoosesThePath() {
    for (String unit : new String[] {"Services", "Equipment"}) {
      for (long cost : new long[] {500, 12000}) {
        assertEquals(Route.PURCHASE_ORDER, loader.evaluate(unit, "purchase-order", cost).path(), unit + " " + cost);
        assertEquals(Route.INVOICE, loader.evaluate(unit, "invoice", cost).path(), unit + " " + cost);
      }
    }
    var po = loader.evaluate("Equipment", "purchase-order", 800);
    assertEquals("Equipment: purchase-order documents go out as a purchase order (cost 800 is within 1000)", po.reason());
    var invoice = loader.evaluate("Services", "invoice", 300);
    assertEquals("Services: invoice documents are posted to the ERP (cost 300 is within 5000)", invoice.reason());
  }

  @Test
  void costAboveTheThresholdRequiresHumanReview() {
    assertTrue(loader.evaluate("Services", "invoice", 12000).humanReviewRequired());
    assertTrue(loader.evaluate("Equipment", "purchase-order", 1001).humanReviewRequired());
    assertFalse(loader.evaluate("Equipment", "purchase-order", 1000).humanReviewRequired(), "at the threshold is within it");
  }

  @Test
  void unknownBusinessUnitIsRejected() {
    var e = assertThrows(RulesLoader.UnknownBusinessUnitException.class, () -> loader.evaluate("BU9", "invoice", 1));
    assertTrue(e.getMessage().contains("BU9"));
  }

  @Test
  void liveEditTakesEffectOnNextEvaluation() throws IOException {
    assertFalse(loader.evaluate("Services", "invoice", 3000).humanReviewRequired());
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    var decision = loader.evaluate("Services", "invoice", 3000);
    assertEquals(Route.INVOICE, decision.path());
    assertTrue(decision.humanReviewRequired());
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
    assertTrue(loader.status().lastRejection().contains("Services.humanReviewAbove"));
  }

  @Test
  void unknownDocumentTypeIsRefusedBeforeTheCostCheck() {
    var e = assertThrows(RulesLoader.UnknownDocumentTypeException.class,
        () -> loader.evaluate("Services", "contract", 500));
    assertTrue(e.getMessage().contains("contract"), e.getMessage());
    // Above the threshold too: it fails now rather than after a reviewer approves it.
    assertThrows(RulesLoader.UnknownDocumentTypeException.class, () -> loader.evaluate("Services", "contract", 99000));
  }

  @Test
  void pathNamesRoundTrip() {
    for (var r : Route.values()) assertEquals(r, Route.fromValue(r.value()));
    assertNull(Route.fromValue("ErpPosting"));
  }

  @Test
  void rollbackByRestoringEarlierContent() throws IOException {
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    assertEquals(2, loader.current().version());
    Files.writeString(file, V1);
    var decision = loader.evaluate("Services", "invoice", 3000);
    assertEquals(1, decision.rulesVersion());
    assertEquals(Route.INVOICE, decision.path());
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
