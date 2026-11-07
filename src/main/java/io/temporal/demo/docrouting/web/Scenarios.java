package io.temporal.demo.docrouting.web;

import io.temporal.demo.docrouting.workflow.DocumentRequest;
import java.util.List;

/** The one-click documents on the demo page. */
public final class Scenarios {
  private Scenarios() {}

  public record Scenario(String id, String label, String hint, String businessUnit, String docType,
      long cost, String costCenter, int count) {
    DocumentRequest document(String documentId) {
      return new DocumentRequest(documentId, businessUnit, docType, cost, costCenter, "Dana Ortiz");
    }
  }

  /** Resubmits the most recent document ID. */
  public static final String DUPLICATE = "duplicate";

  public static final List<Scenario> ALL = List.of(
      new Scenario("nutrition-invoice-3000", "Nutrition · invoice · $3,000", "Path B: posted to the ERP", "Nutrition", "invoice", 3000, "CC-10", 1),
      new Scenario("devices-invoice-3000", "Devices · invoice · $3,000", "Same cost, Devices: human task", "Devices", "invoice", 3000, "CC-20", 1),
      new Scenario("nutrition-invoice-12000", "Nutrition · invoice · $12,000", "Above Nutrition's threshold: human task", "Nutrition", "invoice", 12000, "CC-10", 1),
      new Scenario("devices-po-800", "Devices · purchase order · $800", "Path C: cost-center check, then the supplier", "Devices", "purchase-order", 800, "CC-30", 1),
      new Scenario("cc99-batch", "3 × cost center CC-99", "Path C: an unknown cost center", "Devices", "purchase-order", 800, "CC-99", 3));

  public static Scenario find(String id) {
    return ALL.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }
}
