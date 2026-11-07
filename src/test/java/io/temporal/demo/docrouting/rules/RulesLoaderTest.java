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
  void sameCostRoutesDifferentlyPerBusinessUnit() {
    var services = loader.evaluate("Services", "invoice", 3000);
    var equipment = loader.evaluate("Equipment", "invoice", 3000);
    assertEquals(Route.ERP_POSTING, services.path());
    assertEquals(Route.HUMAN_REVIEW, equipment.path());
    assertEquals(1, services.rulesVersion());
    assertTrue(equipment.reason().contains("1000"), equipment.reason());
  }

  @Test
  void eachPath() {
    assertEquals(Route.HUMAN_REVIEW, loader.evaluate("Services", "invoice", 12000).path());
    assertEquals(Route.ERP_POSTING, loader.evaluate("Services", "invoice", 300).path());
    assertEquals(Route.PURCHASE_ORDER, loader.evaluate("Equipment", "purchase-order", 800).path());
  }

  @Test
  void documentTypeAloneChoosesBetweenErpPostingAndPurchaseOrder() {
    for (String unit : new String[] {"Services", "Equipment"}) {
      assertEquals(Route.PURCHASE_ORDER, loader.evaluate(unit, "purchase-order", 500).path(), unit);
      assertEquals(Route.ERP_POSTING, loader.evaluate(unit, "invoice", 500).path(), unit);
      assertEquals(Route.ERP_POSTING, loader.evaluate(unit, "contract", 500).path(), unit);
    }
    var po = loader.evaluate("Equipment", "purchase-order", 800);
    assertEquals("Equipment: purchase-order documents go out as a purchase order (cost 800 is within 1000)", po.reason());
    var invoice = loader.evaluate("Services", "invoice", 300);
    assertEquals("Services: invoice documents are posted to the ERP (cost 300 is within 5000)", invoice.reason());
  }

  @Test
  void approvedCostSkipsTheHumanReviewThreshold() {
    var approvedInvoice = loader.evaluate("Services", "invoice", 12000, true);
    assertEquals(Route.ERP_POSTING, approvedInvoice.path());
    assertTrue(approvedInvoice.reason().contains("approved by a reviewer"), approvedInvoice.reason());
    assertEquals(Route.PURCHASE_ORDER, loader.evaluate("Equipment", "purchase-order", 12000, true).path());
    assertEquals(Route.HUMAN_REVIEW, loader.evaluate("Services", "invoice", 12000, false).path());
  }

  @Test
  void unknownBusinessUnitIsRejected() {
    var e = assertThrows(RulesLoader.UnknownBusinessUnitException.class, () -> loader.evaluate("BU9", "invoice", 1));
    assertTrue(e.getMessage().contains("BU9"));
  }

  @Test
  void liveEditTakesEffectOnNextEvaluation() throws IOException {
    assertEquals(Route.ERP_POSTING, loader.evaluate("Services", "invoice", 3000).path());
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    var decision = loader.evaluate("Services", "invoice", 3000);
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
    assertTrue(loader.status().lastRejection().contains("Services.humanReviewAbove"));
  }

  @Test
  void pathNamesAndTheirOlderNamesParse() {
    for (var r : Route.values()) assertEquals(r, Route.fromValue(r.value()));
    assertEquals(Route.HUMAN_REVIEW, Route.fromValue("A"));
    assertEquals(Route.PURCHASE_ORDER, Route.fromValue("C"));
    assertEquals(Route.PURCHASE_ORDER, Route.fromValue("ExternalChecks"));
  }

  /** Rules files from before thresholds were all they held: the path mappings load, and are ignored. */
  @Test
  void olderRulesFilesWithPathMappingsStillLoad() throws IOException {
    Files.writeString(file, """
        { "version": 1,
          "businessUnits": {
            "Services": { "humanReviewAbove": 5000, "purchaseOrderFor": ["purchase-order"], "default": "ErpPosting" },
            "Equipment": { "humanReviewAbove": 1000, "externalChecksFor": ["purchase-order", "contract"], "default": "C" } } }
        """);
    assertEquals(1000, loader.current().businessUnits().get("Equipment").humanReviewAbove());
    assertNull(loader.status().lastRejection());
    assertEquals(Route.ERP_POSTING, loader.evaluate("Equipment", "contract", 500).path());
  }

  @Test
  void rollbackByRestoringEarlierContent() throws IOException {
    Files.writeString(file, V1.replace("\"version\": 1", "\"version\": 2").replace("5000", "2000"));
    assertEquals(2, loader.current().version());
    Files.writeString(file, V1);
    var decision = loader.evaluate("Services", "invoice", 3000);
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
