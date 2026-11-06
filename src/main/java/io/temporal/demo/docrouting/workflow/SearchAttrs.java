package io.temporal.demo.docrouting.workflow;

import io.temporal.common.SearchAttributeKey;

/**
 * Searchable progress, kept current by the Workflow at every meaningful step. These are what the
 * Temporal UI filters on: "how many are waiting on a person", "which are past SLA", and so on.
 */
public final class SearchAttrs {
  private SearchAttrs() {}

  public static final SearchAttributeKey<String> STEP = SearchAttributeKey.forKeyword("Step");
  public static final SearchAttributeKey<String> PATH = SearchAttributeKey.forKeyword("Path");
  public static final SearchAttributeKey<String> BUSINESS_UNIT = SearchAttributeKey.forKeyword("BusinessUnit");
  public static final SearchAttributeKey<Boolean> SLA_BREACHED = SearchAttributeKey.forBoolean("SlaBreached");
  public static final SearchAttributeKey<Long> RULES_VERSION = SearchAttributeKey.forLong("RulesVersion");

  /** Values of {@link #STEP}. */
  public static final class Steps {
    private Steps() {}

    public static final String RECEIVED = "Received";
    public static final String CLASSIFY = "Classify";
    public static final String EVALUATE_RULES = "EvaluateRules";
    public static final String HUMAN_TASK = "HumanTask";
    public static final String POST_TO_ERP = "PostToErp";
    public static final String AWAIT_ERP_CONFIRMATION = "AwaitErpConfirmation";
    public static final String NOTIFY = "NotifyRequester";
    public static final String CHECK_COST_CENTER = "CheckCostCenter";
    public static final String RESERVE_BUDGET = "ReserveBudget";
    public static final String SEND_PURCHASE_ORDER = "SendPurchaseOrder";
    public static final String COMPENSATING = "Compensating";
    public static final String ARCHIVE = "Archive";
    public static final String COMPLETED = "Completed";
    public static final String REJECTED = "Rejected";
  }
}
