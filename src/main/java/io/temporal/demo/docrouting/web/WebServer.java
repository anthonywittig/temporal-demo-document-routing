package io.temporal.demo.docrouting.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowExecutionMetadata;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateException;
import io.temporal.common.SearchAttributes;
import io.temporal.demo.docrouting.Config;
import io.temporal.demo.docrouting.rules.RulesLoader;
import io.temporal.demo.docrouting.workflow.ErpConfirmation;
import io.temporal.demo.docrouting.workflow.DocumentRequest;
import io.temporal.demo.docrouting.workflow.DocumentRequestWorkflow;
import io.temporal.demo.docrouting.workflow.ReviewAction;
import io.temporal.demo.docrouting.workflow.ReviewDecision;
import io.temporal.demo.docrouting.workflow.ReviewResult;
import io.temporal.demo.docrouting.workflow.SearchAttrs;
import io.temporal.demo.docrouting.workflow.SearchAttrs.Steps;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The demo page's backend. It starts requests, relays reviewer decisions as Updates, turns
 * the ERP's confirmation callbacks into Signals, and reads everything it shows from Temporal visibility,
 * so the page keeps working while no worker is running.
 */
public final class WebServer {
  private static final Logger log = LoggerFactory.getLogger(WebServer.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String WORKFLOW_TYPE = "DocumentRequestWorkflow";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Config config;
  private final WorkflowClient client;
  private final RulesLoader rules;
  private final io.temporal.demo.docrouting.rules.RulesEditor rulesEditor;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
  private volatile DocumentRequest lastDocument;
  private Javalin app;

  public WebServer(Config config, WorkflowClient client, RulesLoader rules) {
    this.config = config;
    this.client = client;
    this.rules = rules;
    this.rulesEditor = new io.temporal.demo.docrouting.rules.RulesEditor(config.rulesFile(), rules);
  }

  public WebServer start(int port) {
    app = Javalin.create(cfg -> {
      cfg.showJavalinBanner = false;
      cfg.staticFiles.add(s -> {
        s.hostedPath = "/";
        s.directory = "/web";
        s.location = Location.CLASSPATH;
      });
    });

    app.get("/api/config", ctx -> ctx.json(Map.of(
        "temporalUiUrl", config.temporalUiUrl(),
        "namespace", config.temporalNamespace(),
        "taskQueue", config.taskQueue(),
        "scenarios", Scenarios.ALL)));
    app.post("/api/submit", this::submit);
    app.post("/api/review", this::review);
    app.get("/api/state", this::state);
    app.put("/api/control", this::putControl);
    app.put("/api/rules", this::putRules);
    app.post("/api/reset-data", this::resetData);
    app.post("/callbacks/erp", this::erpCallback);
    app.get("/healthz", ctx -> {
      client.getWorkflowServiceStubs().blockingStub().getSystemInfo(
          io.temporal.api.workflowservice.v1.GetSystemInfoRequest.getDefaultInstance());
      ctx.result("ok");
    });
    app.exception(io.grpc.StatusRuntimeException.class, (e, ctx) ->
        ctx.status(503).json(Map.of("error", "Can't reach Temporal: " + e.getStatus().getCode())));

    app.start(port);
    return this;
  }

  public int port() {
    return app.port();
  }

  public void stop() {
    if (app != null) app.stop();
  }

  // ---- submit -------------------------------------------------------------------------------

  private void submit(Context ctx) throws Exception {
    String scenarioId = JSON.readTree(ctx.body()).path("scenario").asText();
    List<Map<String, Object>> results = new ArrayList<>();

    if (Scenarios.DUPLICATE.equals(scenarioId)) {
      if (lastDocument == null) {
        ctx.status(400).json(Map.of("error", "Submit a document first, then resubmit it"));
        return;
      }
      // The same document again: same document ID, so the same Workflow ID.
      results.add(start(lastDocument));
    } else {
      Scenarios.Scenario scenario = Scenarios.find(scenarioId);
      if (scenario == null) {
        ctx.status(400).json(Map.of("error", "Unknown scenario " + scenarioId));
        return;
      }
      for (int i = 0; i < scenario.count(); i++) {
        DocumentRequest doc = scenario.document(newDocumentId());
        results.add(start(doc));
        lastDocument = doc;
      }
    }
    boolean anyDuplicate = results.stream().anyMatch(r -> Boolean.TRUE.equals(r.get("duplicate")));
    ctx.status(anyDuplicate ? 409 : 200).json(Map.of("results", results));
  }

  /** Starts one request. A second start for the same document is refused by Temporal. */
  Map<String, Object> start(DocumentRequest doc) {
    String workflowId = DocumentRequestWorkflow.workflowId(doc.documentId());
    DocumentRequestWorkflow wf = client.newWorkflowStub(DocumentRequestWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(config.taskQueue())
            .setWorkflowId(workflowId)
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
            .setTypedSearchAttributes(SearchAttributes.newBuilder()
                .set(SearchAttrs.STEP, Steps.RECEIVED)
                .set(SearchAttrs.BUSINESS_UNIT, doc.businessUnit())
                .build())
            .setMemo(Map.of(
                "documentId", doc.documentId(),
                "businessUnit", doc.businessUnit(),
                "docType", doc.docType(),
                "cost", doc.cost(),
                "costCenter", doc.costCenter(),
                "requester", doc.requester()))
            .build());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("documentId", doc.documentId());
    result.put("workflowId", workflowId);
    try {
      WorkflowClient.start(wf::process, doc);
      result.put("duplicate", false);
      log.info("Started {}", workflowId);
    } catch (WorkflowExecutionAlreadyStarted e) {
      result.put("duplicate", true);
      result.put("message", "Document " + doc.documentId() + " is already being processed or was processed; nothing new started");
      log.info("Refused duplicate {}", workflowId);
    }
    return result;
  }

  private static String newDocumentId() {
    return "D" + Integer.toString(0x10000 + RANDOM.nextInt(0xEFFFF), 36).toUpperCase();
  }

  // ---- review -------------------------------------------------------------------------------

  private void review(Context ctx) throws Exception {
    JsonNode body = JSON.readTree(ctx.body());
    String workflowId = body.path("workflowId").asText();
    ReviewAction action;
    try {
      action = ReviewAction.valueOf(body.path("action").asText(""));
    } catch (IllegalArgumentException e) {
      ctx.status(400).json(Map.of("error", "Choose APPROVE or REJECT"));
      return;
    }
    ReviewDecision decision = new ReviewDecision(
        body.path("taskId").asText(),
        action,
        body.path("reviewer").asText("Sam Chen"),
        body.path("comment").asText(""));

    DocumentRequestWorkflow wf = client.newWorkflowStub(DocumentRequestWorkflow.class, workflowId);
    // An Update needs a worker to validate and accept it. If none is running, the request keeps
    // waiting in the background and is applied when a worker starts. Don't claim otherwise.
    CompletableFuture<ReviewResult> pending = CompletableFuture.supplyAsync(() -> wf.review(decision));
    try {
      ctx.json(pending.get(15, TimeUnit.SECONDS));
    } catch (TimeoutException e) {
      ctx.status(202).json(Map.of("pending", true, "error",
          "No worker has accepted the decision yet; it will be applied when a worker starts (make worker). "
              + "Clicking again is safe: a second decision on the same task is rejected."));
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof WorkflowUpdateException u) {
        String reason = u.getCause() != null ? u.getCause().getMessage() : u.getMessage();
        ctx.status(409).json(Map.of("error", cleanFailureMessage(reason)));
      } else if (cause instanceof WorkflowNotFoundException) {
        ctx.status(404).json(Map.of("error", "This request has finished; the task is no longer open"));
      } else {
        throw e;
      }
    }
  }

  // ---- callbacks: external systems -> Signals ------------------------------------------------

  private void erpCallback(Context ctx) throws Exception {
    JsonNode body = JSON.readTree(ctx.body());
    String workflowId = body.path("workflowId").asText();
    signal(ctx, workflowId, wf -> wf.erpPostingConfirmed(new ErpConfirmation(
        body.path("postingId").asText(), body.path("confirmedAt").asText())));
  }

  private void signal(Context ctx, String workflowId, java.util.function.Consumer<DocumentRequestWorkflow> send) {
    try {
      send.accept(client.newWorkflowStub(DocumentRequestWorkflow.class, workflowId));
      ctx.json(Map.of("status", "SIGNALLED"));
    } catch (WorkflowNotFoundException e) {
      // The request has already finished; tell the system to stop retrying.
      ctx.json(Map.of("status", "IGNORED", "reason", "no open request " + workflowId));
    }
    // Any other failure (Temporal unreachable) surfaces as 5xx, so the system retries the callback.
  }

  // ---- state for the page -------------------------------------------------------------------

  private void state(Context ctx) {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("inbox", inbox());
    state.put("recent", recent());
    state.put("systems", systemsControl());
    state.put("rules", rulesView());
    ctx.json(state);
  }

  /** Open human tasks, straight from visibility: no Query, so no worker is needed. */
  List<Map<String, Object>> inbox() {
    return client.listExecutions(
            "WorkflowType=\"" + WORKFLOW_TYPE + "\" AND ExecutionStatus=\"Running\" AND Step=\"" + Steps.HUMAN_TASK + "\"")
        .limit(100)
        .map(this::summary)
        .sorted(Comparator.comparing(m -> String.valueOf(m.get("slaDeadline"))))
        .toList();
  }

  List<Map<String, Object>> recent() {
    return client.listExecutions("WorkflowType=\"" + WORKFLOW_TYPE + "\"")
        .limit(200)
        .sorted(Comparator.comparing(WorkflowExecutionMetadata::getStartTime).reversed())
        .limit(20)
        .map(this::summary)
        .toList();
  }

  private Map<String, Object> summary(WorkflowExecutionMetadata m) {
    SearchAttributes sa = m.getTypedSearchAttributes();
    var info = m.getWorkflowExecutionInfo();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("workflowId", m.getExecution().getWorkflowId());
    out.put("runId", m.getExecution().getRunId());
    out.put("status", statusName(m.getStatus()));
    out.put("startTime", m.getStartTime().toString());
    out.put("step", sa.get(SearchAttrs.STEP));
    out.put("path", sa.get(SearchAttrs.PATH));
    out.put("businessUnit", sa.get(SearchAttrs.BUSINESS_UNIT));
    out.put("slaBreached", Boolean.TRUE.equals(sa.get(SearchAttrs.SLA_BREACHED)));
    out.put("rulesVersion", sa.get(SearchAttrs.RULES_VERSION));
    out.put("version", buildId(sa, info));
    for (String key : List.of("documentId", "docType", "costCenter", "taskId", "slaDeadline", "routingReason")) {
      out.put(key, memo(m, key));
    }
    Object cost = m.getMemo("cost", Long.class);
    out.put("cost", cost);
    return out;
  }

  private static final io.temporal.common.SearchAttributeKey<String> DEPLOYMENT_VERSION =
      io.temporal.common.SearchAttributeKey.forKeyword("TemporalWorkerDeploymentVersion");

  /** The worker version a request is pinned to. List results carry it as a system search attribute. */
  private static String buildId(SearchAttributes sa, io.temporal.api.workflow.v1.WorkflowExecutionInfo info) {
    if (info.hasVersioningInfo() && !info.getVersioningInfo().getDeploymentVersion().getBuildId().isEmpty()) {
      return info.getVersioningInfo().getDeploymentVersion().getBuildId();
    }
    String version = sa.containsKey(DEPLOYMENT_VERSION) ? sa.get(DEPLOYMENT_VERSION) : null;
    if (version == null) return "";
    int sep = version.lastIndexOf(':');
    return sep >= 0 ? version.substring(sep + 1) : version;
  }

  private static Object memo(WorkflowExecutionMetadata m, String key) {
    return m.getMemo(key, String.class);
  }

  private static String statusName(WorkflowExecutionStatus s) {
    String name = s.name().replace("WORKFLOW_EXECUTION_STATUS_", "");
    return name.charAt(0) + name.substring(1).toLowerCase().replace('_', ' ');
  }

  // ---- system fault toggles (proxied to the systems process) ---------------------------------

  private Object systemsControl() {
    try {
      HttpResponse<String> res = http.send(
          HttpRequest.newBuilder(URI.create(config.systemsUrl() + "/control")).timeout(Duration.ofSeconds(1)).build(),
          HttpResponse.BodyHandlers.ofString());
      return JSON.readTree(res.body());
    } catch (Exception e) {
      return Map.of("unreachable", true);
    }
  }

  private void putControl(Context ctx) throws Exception {
    try {
      HttpResponse<String> res = http.send(
          HttpRequest.newBuilder(URI.create(config.systemsUrl() + "/control"))
              .timeout(Duration.ofSeconds(2))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString(ctx.body()))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      ctx.status(res.statusCode()).contentType("application/json").result(res.body());
    } catch (java.io.IOException e) {
      ctx.status(503).json(Map.of("error", "The systems process isn't running. Start `make systems`."));
    }
  }

  // ---- rules card: edit thresholds, reset demo data ------------------------------------------

  /** The version in force, the last rejected edit, and each unit's threshold for the rules card. */
  private Map<String, Object> rulesView() {
    RulesLoader.Status status = rules.status();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("version", status.version());
    view.put("lastRejection", status.lastRejection());
    Map<String, Long> thresholds = new LinkedHashMap<>();
    if (status.version() != null) {
      rules.current().businessUnits().forEach((unit, r) -> thresholds.put(unit, r.humanReviewAbove()));
    }
    view.put("thresholds", thresholds);
    return view;
  }

  /** Saves new human-review thresholds as the next rules version: the same file a hand edit changes. */
  private void putRules(Context ctx) throws Exception {
    JsonNode body = JSON.readTree(ctx.body()).path("thresholds");
    Map<String, Long> thresholds = new LinkedHashMap<>();
    var fields = body.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      if (!field.getValue().canConvertToLong() || !field.getValue().isIntegralNumber()) {
        ctx.status(400).json(Map.of("error", field.getKey() + " threshold must be a whole number"));
        return;
      }
      thresholds.put(field.getKey(), field.getValue().asLong());
    }
    if (thresholds.isEmpty()) {
      ctx.status(400).json(Map.of("error", "Nothing to save: send at least one business unit's threshold"));
      return;
    }
    try {
      var saved = rulesEditor.saveThresholds(thresholds);
      log.info("Rules card saved version {}: {}", saved.version(), thresholds);
      ctx.json(rulesView());
    } catch (io.temporal.demo.docrouting.rules.RulesEditor.InvalidEditException e) {
      ctx.status(400).json(Map.of("error", e.getMessage()));
    }
  }

  /** Restores default rules and reference data, and clears the fault toggles. */
  private void resetData(Context ctx) throws Exception {
    io.temporal.demo.docrouting.rules.RulesEditor.replace(config.defaultRulesFile(), config.rulesFile());
    io.temporal.demo.docrouting.rules.RulesEditor.replace(config.defaultReferenceDataFile(), config.referenceDataFile());
    String faults = "untouched (systems process not running)";
    try {
      http.send(HttpRequest.newBuilder(URI.create(config.systemsUrl() + "/control"))
              .timeout(Duration.ofSeconds(2))
              .header("Content-Type", "application/json")
              .PUT(HttpRequest.BodyPublishers.ofString("{\"erpDown\":false,\"supplierRejects\":false}"))
              .build(),
          HttpResponse.BodyHandlers.ofString());
      faults = "cleared";
    } catch (java.io.IOException e) {
      // The systems process restarts with healthy defaults anyway.
    }
    log.info("Demo data reset: rules and reference data restored, faults {}", faults);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rules", rulesView());
    result.put("faults", faults);
    ctx.json(result);
  }

  private static String cleanFailureMessage(String message) {
    if (message == null) return "Rejected";
    // Temporal prefixes validator failures with "message='...', type='...'"; keep the reason only.
    int start = message.indexOf("message='");
    if (start >= 0) {
      int end = message.indexOf("',", start);
      if (end > start) return message.substring(start + 9, end);
    }
    return message;
  }
}
