# Runbook: Document routing demo

This runbook is for presenting the demo. The [README](README.md) explains what it is and how it's built.

## What it shows

A document arrives, is classified, and business-unit rules route it down one of three paths: `HumanReview`, a human task; `Invoice`, an ERP posting with an asynchronous confirmation and a notification; or `PurchaseOrder`, a budget reservation against the PO's cost center and a purchase order sent to the supplier portal. Each document is one `DocumentRequestWorkflow`.

The external systems:

| System | What it does here | Path |
|---|---|---|
| **ERP** | Accepts the invoice posting now and confirms it about 4 seconds later, by callback | `Invoice` |
| **Budget** | Reserves the money against the PO's cost center before the PO goes out; 422 if the cost center isn't in the reference data. Undone if the supplier rejects | `PurchaseOrder` |
| **Supplier portal** | Receives the purchase order; the supplier may reject it | `PurchaseOrder` |

The story: an invoice is posted to the ERP, which confirms later, and the requester is notified. A purchase order has budget reserved against its cost center, then goes to the supplier portal; if the supplier says no, the budget is released.

During the talk you change the rules live, stop the worker and the external systems, break and fix reference data, and ship a second worker version. Nothing is lost, and nothing is done twice.

The demo makes five problems visible:

- **Routing rules change more often than code.** Rules live in a JSON file outside the workflow. A change takes effect on the next request with no deploy, and each decision records the rules version that made it. Beats 2 and 3.
- **People take time, and their decisions change the route.** The human task waits durably, with an SLA. A rejection ends the request; an approval re-evaluates it against the rules in force at that moment, and counts only if they still want a review. Beat 3.
- **External systems fail, slowly or for good.** Outages are retried with backoff. Rejections are not retried, and completed steps are undone in reverse. Beats 4 and 5.
- **Bad data needs a fix and a replay, not a dead-letter queue.** Failed requests stay where they failed, searchable, and are replayed in bulk after the data is fixed. Beat 6.
- **Shipping new code can't break requests in flight.** Each request is pinned to the worker version it started on, and old versions drain. Beat 7.

Beat 8 pulls it together in the Temporal UI: how many requests are at each step, which are past SLA, and where any one of them is.

## Before you start

You need JDK 21 or later on your `PATH`, Maven 3.9 or later, and Temporal CLI 1.9 or later. You need no Temporal Cloud account and no Docker.

Run `make test` once on the machine you'll present from. The first run downloads dependencies.

Have three terminals open in `temporal-demo-document-routing/`, plus a browser with two tabs, and an editor with `data/reference-data.json` open for beat 6. (`make up` creates the `data/` files on its first run.) Open `data/rules.json` as well if you'd rather show beat 2 as a file edit.

If the page doesn't show **rules v1** when you start, an earlier rehearsal left its edits behind: click **Reset demo data** in the **Routing rules** card.

For a rehearsal, start the worker with `SLA_DURATION=20s make worker` so SLA breaches happen quickly. For the live session, the default 60 seconds leaves time to talk.

## Start it

### Locally

In two terminals:

1. `make up`: starts three processes in this terminal, and `Ctrl-C` stops all three:
   - the Temporal dev server, with UI at http://localhost:8233. It registers the `Step`, `Path`, `BusinessUnit`, `SlaBreached` and `RulesVersion` search attributes;
   - the mock external systems on :8090. Beat 4's optional harder outage stops and starts these;
   - the demo app at http://localhost:8080.
2. `make worker`: worker version `v1`. You stop and start this one in beat 4. On a fresh dev server it logs `had no current version; made v1 current`. Start it once the dev server is up.

Keep a third terminal free for beat 6's reset command and beat 7's second worker. If you'll show beat 4's optional harder outage, keep a fourth for it.

A healthy start shows the following on http://localhost:8080:
- the top bar shows **rules v1** and `task queue · document-routing`;
- five document chips;
- the **Routing rules** card shows **v1**, with Services at 5000 and Equipment at 1000;
- **ERP is up** and **Supplier portal accepts purchase orders**;
- an empty **Reviewer inbox** ("No open tasks.") and an empty **Recent requests** list.

Open the **Temporal UI ↗** link in the second tab.

## The walkthrough

Everything the page shows comes from Temporal's visibility store. The page keeps no state of its own. Click any document ID to open that request in the Temporal UI.

Thirty-second version: "A document comes in, is classified, and business-unit rules send it to a person, post it to the ERP, or, for a purchase order, reserve the budget and send it to the supplier. Every document is a durable workflow. I'm going to change the rules while it runs, take the worker and the external systems away, break the data, and ship a new version, and you'll see nothing lost and nothing done twice."

| Beat | Minutes | Compress if short on time |
|---|---|---|
| 1. A document is posted to the ERP | 6 | |
| 2. Same cost, different unit; change a rule live | 10 | |
| 3. A person approves it; the SLA runs out | 10 | |
| 4. Take the ERP and the worker away | 8 | |
| 5. The supplier says no; the budget is released | 7 | |
| 6. Bad data: find, fix, replay in bulk | 10 | |
| 7. Ship v2 while v1 requests are still open | 10 | |
| 8. The operations view | 7 | yes, show only two filters |
| **Total** | **68** | leaves about 20 minutes for questions |

### 1. A document is posted to the ERP

1. Click **Services · invoice · $3,000**. A toast says "Started D…".
2. Within about 5 seconds, the row in **Recent requests** shows path **Invoice**, step **Completed**, version **v1**.
3. Click the document ID. In the Temporal UI's Event History, point out:
   - `Classify`;
   - `EvaluateRules`, whose result shows path `Invoice`, the reason ("cost 3000 is within 5000"), and `rulesVersion: 1`;
   - `PostInvoiceToErp`;
   - the timer `WaitForErpConfirmation` (2m) starts: the deadline for the ERP to answer;
   - the `ErpPostingConfirmed` Signal, about 4 seconds later, and the timer is canceled;
   - `NotifyRequester`.
4. Say: "The ERP took the invoice and confirmed the posting later, on its own schedule. That callback came in as a Signal. Temporal recorded it in the history, and the workflow carried on to notify the requester. The synchronous call and the asynchronous confirmation are both just steps in one durable function."

### 2. Same cost, different unit; change a rule live

1. Click **Equipment · invoice · $3,000**. It appears in the **Reviewer inbox** with the reason "Equipment: cost 3000 is above the human-review threshold 1000", rules **v1**, and an SLA countdown. **Leave it there**: it's the in-flight request for this beat and beat 3.
2. Say: "Same cost as the Services invoice, different business unit, different path. Each business unit has its own threshold. It comes from rules held outside the workflow, which stand in for Drools or any rules engine."
3. In the **Routing rules** card, change Equipment to `5000` and click **Save as v2**. A toast says "Saved rules v2: the next evaluation uses them", and the card and the top bar show **rules v2**. (The card writes `data/rules.json`. Editing that file by hand does the same thing: set `"version": 2` and the Equipment `"humanReviewAbove"` to `5000`, and save.)
4. Click **Equipment · invoice · $3,000** again. This time it goes straight to path **Invoice** and completes, with rules **v2** on its row.
5. Point at the inbox. The first Equipment request is still waiting, still on rules **v1**.
6. Say: "No deploy. The rules live outside the workflow, in a file this card writes, standing in for your rules engine and its admin screen. The next evaluation read the new version. The request already in flight keeps the decision it made, because that decision and its rules version are in its history. It won't silently change route under someone."
7. Optional, to show validation: in the card, type `-5` for a threshold and save. The toast refuses it ("must not be negative"), and the version doesn't change. A broken hand edit to `data/rules.json` (delete a closing brace) is rejected too: the rules badge turns amber, and a banner says "rules.json edit rejected; still using v2". Rollback is the same move as a change: save the previous threshold, which becomes the next version.

### 3. A person approves it; the SLA runs out

1. In the inbox, on the Equipment request from beat 2, type a comment and click **Approve**. A toast says "Approved by Sam Chen: recorded by Temporal".
2. The request goes back through the rules in force now, **v2**, carrying the approval. Under v2 the cost is within Equipment's new threshold, so the rules don't need a review any more and route it to `Invoice` on their own; it completes. Open it in the Temporal UI: there are two `EvaluateRules` results, v1 → `HumanReview` ("cost 3000 is above the human-review threshold 1000") and then v2 → `Invoice` ("cost 3000 is within 5000"), with the Update `Review` between them carrying the reviewer and the comment. Had v2 still put the cost above the threshold, the approval would have stood in for that review, and the reason would read "cost 3000 is above …, approved by a reviewer".
3. Say: "Approval is a loop in code, not a special feature. The request re-runs the rules in force now. The approval only matters if those rules still want a person to look; if they don't, the new rules route it. It arrived under v1, and its second decision was made by v2. Whoever opens the history sees both decisions, which rules made each one, and who made the call in between."
4. Click **Services · invoice · $12,000**. It lands in the inbox (above the Services threshold of 5000). Click **Waiting on a person** under **Temporal UI views** to show the same list in the Temporal UI.
5. Let the SLA run out while you talk (60 seconds by default). The row turns red with **SLA breached**. In the Temporal UI, **SLA breached** lists it, and the request's timeline shows the fired `ReviewSla` (1m) timer.
6. Say: "The SLA is a durable timer. Breaching it flags the request; it doesn't cancel it. A late decision still counts. In production the flag is what you alert on."
7. **Don't approve or reject this one.** It's the open v1 request for beat 7.
8. Optional, for a stale click: if anyone asks about double-clicks, act on a task twice. The second click says "Task … is no longer open". The Update's validator rejected it, and nothing was written to history.
9. Optional, for the other answer: click **Services · invoice · $12,000** again and **Reject** the new task (not the breached one). Its row in **Recent requests** reads **Rejected**, at step **Rejected**. In its history, the Update `Review` is followed only by `NotifyRequester`: no ERP posting, no archive.

### 4. Take the ERP and the worker away

1. Tick **ERP is up**. It now reads **ERP is DOWN (503)**.
2. Click **Services · invoice · $3,000**. The row stays at step **PostToErp**, status **Running**.
3. Open it in the Temporal UI. Under **Pending Activities**, `PostInvoiceToErp` shows attempt 3, 4, 5… with `POST /erp/invoices returned HTTP 503: The ERP is unavailable` as the last failure.
4. Say: "The ERP is down. The workflow isn't failing; it's retrying with exponential backoff, 1, 2, 4, up to 10 seconds, for about three minutes. Other requests aren't blocked behind this one. There's no queue head to get stuck."
5. Press `Ctrl-C` in the `make worker` terminal. Now nothing is running the workflow either.
6. Untick the toggle. It reads **ERP is up** again. The request is still waiting, because there's no worker.
7. Start `make worker` again. Within about 15 seconds, the next retry succeeds, the ERP's confirmation arrives, and the request completes.
8. Say: "Both the ERP and the worker went away mid-request. When they came back, the request picked up at the step it was on. It posted to the ERP once and notified once. The retry state, the timer and the progress all lived in Temporal, not in the process we killed."
9. Optional, for a harder outage: instead of the toggle in step 1, run `make stop-systems` in the fourth terminal. Every mock system goes away, the **External systems** card turns red ("Systems process unreachable"), and the last failure reads "Connection refused". In step 6, run `make systems` in that terminal instead of unticking. It keeps running there for the rest of the demo. The retry behavior is the same.

### 5. The supplier says no; the budget is released

1. Tick **Supplier portal accepts purchase orders**. It now reads **Supplier portal REJECTS purchase orders**.
2. Click **Equipment · purchase order · $800**. It goes down `PurchaseOrder`, and the row ends at step **Rejected**.
3. Open it in the Temporal UI:
   - `ReserveBudget` runs;
   - `SendPurchaseOrder` fails without retry;
   - then `ReleaseBudget`.
4. Say: "The supplier portal rejected the purchase order, so retrying is pointless. The workflow undoes what it already did: it releases the budget it reserved. The request completes as a business rejection, not a crash. Each step registers its undo as it succeeds, so with more steps they'd all be undone, in reverse order."
5. Untick the toggle.

### 6. Bad data: find, fix, replay in bulk

1. Click **3 × cost center CC-99**. Three requests start and almost at once show status **Failed**, at step **ReserveBudget**.
2. Click **Failed at budget reservation** under **Temporal UI views**. The Temporal UI lists all three.
3. Open one. The failure says `POST /tasks/reserve-budget returned HTTP 422: Budget: unknown cost center CC-99`. Point out that `ReserveBudget` ran once: a 422 is never retried. And there's no `ReleaseBudget`: nothing was reserved, so there's nothing to undo.
4. Say: "This is where a dead-letter queue would come in. Here, the failed request is the record: input, error, and where it stopped, searchable alongside everything else. And nothing else was blocked behind it."
5. Fix the data. In `data/reference-data.json`, add `"CC-99"` to `costCenters` and save. Nothing needs restarting.
6. In the third terminal, list what the replay will touch before running it. This is the same query the reset will use:

   ```sh
   temporal workflow list --disable-config-file \
     --query 'ExecutionStatus="Failed" AND Step="ReserveBudget"'
   ```

   It lists the three CC-99 documents, each with status `Failed`. Say: "Before changing anything in bulk, run the query on its own. Whatever it lists is exactly what the reset will act on, nothing more."
7. Replay every request that failed this way:

   ```sh
   temporal workflow reset --disable-config-file \
     --query 'ExecutionStatus="Failed" AND Step="ReserveBudget"' \
     --type FirstWorkflowTask --reason "CC-99 added to the cost-center reference data"
   ```

   The CLI asks `Start batch against approximately 3 workflow(s)? y/N`. Check that the count matches step 6, then type `y`.
8. Within about 15 seconds, the three documents show **Completed** in **Recent requests**.
9. Open one in the Temporal UI. The new run starts with a reset event carrying your reason and links back to the original run. Point at the **Reset** button on a workflow page: that's the same replay for a single request.
10. Say: "One query found everything that failed for this reason, and one command replayed all of it, with the reason on record. The original failed runs stay as the audit trail; the fix is a new run linked to each one. That's diagnosis, correction, replay and audit, without a separate queue to run."
11. If someone notices that **Failed at budget reservation** still lists three runs: those are the original runs, kept on purpose. Their documents now each have a newer, completed run.
12. Don't run the step 7 command a second time. The original failed runs still match its query, so each document gets another run. Here the idempotency keys stop a second reservation or purchase order: the budget service and the supplier portal see the same key and return their first answer. Not every system you call is that careful, so replay only what you mean to (step 13).
13. Optional, to replay only some documents. A visibility query can't say "skip documents that already have a completed run", so name them yourself. A document's workflow ID is `doc-` followed by its document ID. To list the failed runs except those of documents already replayed:

    ```sh
    temporal workflow list --disable-config-file \
      --query 'ExecutionStatus="Failed" AND Step="ReserveBudget" AND WorkflowId NOT IN ("doc-DLVX5")'
    ```

    To reset a batch of two by ID:

    ```sh
    temporal workflow reset --disable-config-file \
      --query 'ExecutionStatus="Failed" AND WorkflowId IN ("doc-DJVWO", "doc-D5B03")' \
      --type FirstWorkflowTask --reason "CC-99 added to the cost-center reference data"
    ```

    Keep `ExecutionStatus="Failed"` in the reset query. Without it, the query also matches any completed run those documents have, and the reset replays that too.

### 7. Ship v2 while v1 requests are still open

The Services $12,000 request from beat 3 should still be open in the inbox, on v1.

1. In the third terminal: `make worker BUILD_ID=v2`. It starts polling. v1 is still current, so new requests still go to v1.
2. `make promote BUILD_ID=v2`. It prints "Successfully set the current worker deployment version".
3. Click **Services · invoice · $3,000**. Its row shows version **v2**. In the Temporal UI, its history ends with an `ArchiveDocument` step that v1 doesn't have.
4. In the inbox, **Approve** the open $12,000 request. It goes on down `Invoice` on v1 code and completes. Its row shows **v1**, and its history has no `ArchiveDocument` step.
5. Say: "Two versions of the workflow ran side by side. Each request is pinned to the version it started on. v2 adds a step, and replaying an old history against new code is exactly what breaks naïve deploys. Old requests finish on old code, new requests get new code, and nobody had to stop anything."
6. Show it draining:

   ```sh
   temporal worker deployment describe --name document-routing --disable-config-file
   ```

   v1 shows `draining`, and after a few minutes `drained`. Then `Ctrl-C` the v1 worker.
7. Say: "Here we waited for v1 to drain. If a v1 request were going to wait for weeks, you can also move it onto the new version, as long as the new code is written to replay the old history. That's a decision per change, not a platform limitation."

### 8. The operations view

1. In the Temporal UI, use the links from the page:
   - **Waiting on a person**: the count at the top answers "how many requests are at this step";
   - **SLA breached**: "which ones are late";
   - **Services** and **Equipment**: per business unit.
2. Open any request and switch the Event History to **Timeline**. Say: "This is the 'where am I' view for UAT: completed steps, the current step, and how long each took. The `Step` search attribute is what the counts are built on."
3. Say: "For dashboards and alerting, the SDK and the service emit metrics through OpenTelemetry, so they go into the metrics and alerting tools you already run, like everything else. Workflow history can be exported for BI."

## Show it in code

Links are relative to this directory and point at line ranges. They are not pinned to a commit yet; see [Keeping this runbook current](#keeping-this-runbook-current).

### One execution per document

[`DocumentRequestWorkflow.java` lines 21–23](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflow.java#L21-L23) and [`WebServer.java` lines 137 and 155](src/main/java/io/temporal/demo/docrouting/web/WebServer.java#L133-L160):

```java
static String workflowId(String documentId) { return "doc-" + documentId; }
...
.setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
...
} catch (WorkflowExecutionAlreadyStarted e) { /* duplicate: nothing new starts */ }
```

Every side-effecting call also sends `Idempotency-Key: <workflowId>/<activity type>` ([`DocumentActivitiesImpl.java` line 93](src/main/java/io/temporal/demo/docrouting/activities/DocumentActivitiesImpl.java#L93)). The key is the same across retries and resets.

### Routing runs in an Activity, and the decision is recorded

[`DocumentRequestWorkflowImpl.java` lines 69–72](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L69-L72):

```java
RoutingDecision route = activities.evaluateRules(new EvaluateRulesInput(doc, docType, costApproved));
Workflow.upsertTypedSearchAttributes(
    PATH.valueSet(route.path().value()), RULES_VERSION.valueSet((long) route.rulesVersion()));
```

The rules file is read on every evaluation and validated, and the last good version is kept: [`RulesLoader.java` lines 62–82 and 93–142](src/main/java/io/temporal/demo/docrouting/rules/RulesLoader.java#L62-L142). Swap this class for a Drools or decision-table call and nothing else changes.

### A human task: an Update with a validator

[`DocumentRequestWorkflowImpl.java` lines 121–139](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L121-L139):

```java
public void validateReview(ReviewDecision d) {
  if (openTaskId == null || d == null || !openTaskId.equals(d.taskId())) {
    throw new IllegalArgumentException("Task " + d.taskId() + " is no longer open" ...);
  }
  ...
}
```

A rejected Update is never written to history. An accepted one carries the reviewer and the comment.

### An SLA that flags without cancelling

[`DocumentRequestWorkflowImpl.java` lines 107–118](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L107-L118):

```java
CancellationScope sla = Workflow.newCancellationScope(() ->
    Workflow.newTimer(settings.slaDuration(), named("ReviewSla")).handle((ignored, canceled) -> {
      if (canceled == null) Workflow.upsertTypedSearchAttributes(SLA_BREACHED.valueSet(true));
      return null;
    }));
sla.run();
Workflow.await(() -> decision != null);
sla.cancel();
```

### Turn a callback into a Signal

The web app receives the ERP's confirmation callback and signals the workflow ([`WebServer.java` lines 210–226](src/main/java/io/temporal/demo/docrouting/web/WebServer.java#L210-L226)). The workflow waits with a deadline ([`DocumentRequestWorkflowImpl.java` lines 152–163](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L152-L163)):

```java
// a named timer, so the Temporal UI shows "WaitForErpConfirmation (2m)"
if (!awaitWithTimer(settings.callbackTimeout(), "WaitForErpConfirmation", () -> erpConfirmation != null)) {
  throw ApplicationFailure.newNonRetryableFailure("The ERP did not confirm the posting within ...", "CallbackTimeout");
}
```

### Let the failure decide whether to retry

[`HttpCalls.java` lines 51–60](src/main/java/io/temporal/demo/docrouting/activities/HttpCalls.java#L51-L60), with the policy in [`DocumentRequestWorkflowImpl.java` lines 35–42](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L35-L42):

```java
if (isRetryable(status)) throw ApplicationFailure.newFailure(message, TRANSIENT);   // 5xx, 408, 429
throw ApplicationFailure.newNonRetryableFailure(message, REJECTED);                 // 400, 401, 403, 404, 422
```

### Undo completed steps

[`DocumentRequestWorkflowImpl.java` lines 174–194](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L174-L194):

```java
Saga saga = new Saga(new Saga.Options.Builder().setParallelCompensation(false).build());
activities.reserveBudget(doc);
saga.addCompensation(activities::releaseBudget, doc);   // more steps would register theirs here
try { activities.sendPurchaseOrder(doc); }
catch (ActivityFailure e) { saga.compensate(); ... }
```

### Progress you can search, and an inbox that needs no worker

The workflow upserts the `Step` search attribute at every step (the first is [`DocumentRequestWorkflowImpl.java` line 64](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L64)), and puts the task details in its memo ([line 104](src/main/java/io/temporal/demo/docrouting/workflow/DocumentRequestWorkflowImpl.java#L100-L104)). The inbox is a visibility query, not a Query, so it works with no worker running ([`WebServer.java` lines 240–247](src/main/java/io/temporal/demo/docrouting/web/WebServer.java#L240-L247)):

```java
client.listExecutions("WorkflowType=\"DocumentRequestWorkflow\" AND ExecutionStatus=\"Running\" AND Step=\"HumanTask\"")
```

### Pin each request to the version it started on

[`WorkerMain.java` lines 37–43](src/main/java/io/temporal/demo/docrouting/worker/WorkerMain.java#L37-L43):

```java
WorkerDeploymentOptions.newBuilder()
    .setUseVersioning(true)
    .setVersion(new WorkerDeploymentVersion(config.deploymentName(), config.buildId()))
    .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
```

[`HistoryReplayTest.java`](src/test/java/io/temporal/demo/docrouting/workflow/HistoryReplayTest.java) replays recorded v1 histories against the current code on every build. It also proves that v2 code *can't* replay a v1 history, which is why v1 requests are pinned.

### What's demo-only

- **Duplicate submissions aren't shown on screen.** If it comes up: the Workflow ID is the document ID, so Temporal refuses a second start for the same document, and every external call carries an idempotency key made from the Workflow ID, so a retry never posts to the ERP or notifies twice. Beat 4 shows the second part (one posting, one notification after the outage). Code: [One execution per document](#one-execution-per-document).
- **v2 is a flag, not a new build.** [`WorkerMain.settingsFor`](src/main/java/io/temporal/demo/docrouting/worker/WorkerMain.java#L69-L72) turns on the archive step for any Build ID other than `v1`. In a real release, v2 would be new code built and deployed with its own Build ID.
- **The rules engine is a JSON file.** It stands in for Drools or a decision-table service. The workflow calls it through one Activity, so swapping it is local to [`RulesLoader`](src/main/java/io/temporal/demo/docrouting/rules/RulesLoader.java). Scheduled activation isn't built.
- **The external systems are one mock process,** with an in-memory idempotency store that forgets everything on restart. Real systems keep idempotency keys durably.
- **Every activity pauses 500ms before it runs** (`ACTIVITY_DELAY`), so the steps are spaced out in the Temporal UI timeline. Without it they finish within milliseconds of each other. Set `ACTIVITY_DELAY=0s` to turn it off.
- **The classifier reads the scenario's type.** A real classifier would be an OCR or ML call behind the same Activity.
- **The web app is not a product UI.** It has no sign-in, no roles and no SSO; a single reviewer name is typed in. Role-based access and SSO are Temporal Cloud and application concerns, covered in the documentation.
- **Payload encryption is off.** Temporal's Data Converter encrypts payloads before they leave the worker, with your keys (one per customer if needed), so the service never sees document data. Not shown here.
- **Metrics and dashboards aren't wired up.** The SDK emits OpenTelemetry metrics (activity and workflow latencies, failures, task backlogs) that go to whatever metrics and alerting stack you already use (Prometheus and Grafana, Datadog and so on).
- **Draining is the only migration shown.** Moving in-flight requests to a new version (with the new code guarded so it replays old histories) is supported but not demonstrated.
- **`Retry-After` is ignored.** A 429 is retried like any other transient failure. An Activity can set the next retry delay from the header when an API needs it.
- **Correcting data inside a running request isn't shown.** Here the fix is in the cost-center reference data, followed by a replay. A workflow can also accept corrected data through an Update.
- **Scale isn't shown.** One local dev server and one worker. Thousands of requests a day are small for Temporal Cloud.
- **No circuit breaker.** Bounded retries with backoff and timeouts are shown. A breaker that stops calling a failing system for a while lives in the Activity or a shared client, not in Temporal.
- **No bulk-change preview.** Before a batch operation, the same visibility query shows exactly which requests it will touch (beat 6, steps 2 and 6). There's no separate dry-run screen.
- **One environment only.** Dev, test and production are separate Namespaces (or Temporal Cloud accounts). Promotion is your CI/CD deploying the same build through each one, with Worker Versioning in each.
- **Data residency, retention and deletion aren't shown.** Temporal Cloud runs in a region you choose (including EU regions for GDPR). Each Namespace has a retention period after which closed workflow histories are deleted, and payload encryption keeps document data unreadable to the service.
- **BPMN isn't produced.** The Temporal UI's timeline and the `Step` attribute are the "where am I" view. The workflow code, not a diagram, is the executable definition.

## Reset and tear down

- **Between rehearsals:** click **Reset demo data** (or run `make reset-data`), `Ctrl-C` every terminal, then start again from [Start it](#start-it). The dev server keeps everything in memory, so stopping `make up` clears every request and both worker versions, and the first `make worker` makes v1 current again.
- **Without restarting Temporal:** **Reset demo data** in the **Routing rules** card restores rules v1, removes CC-99 from the reference data, and clears both system toggles, after a confirmation. `make reset-data` restores the two files from a terminal. Old requests stay listed; that's harmless.
- **Before beat 7 in a rehearsal where v2 is already current:** restart `make up`. Otherwise new requests go straight to v2.

## When something goes wrong

| What you see | Likely cause | Fix |
|---|---|---|
| Banner "Reconnecting to Temporal…" | The web app can't reach the dev server | Start `make up`, then refresh |
| **External systems** card is red, "unreachable" | The systems process isn't running | `make systems` (or `make up` if nothing is running) |
| A request shows **Failed** at step **PostToErp** (or another external step) | The external system was down longer than the retry bound, about 3 minutes (20 attempts) | Bring the system back (untick **ERP is DOWN**, or start `make systems`), then replay as in beat 6: `temporal workflow reset --disable-config-file --query 'ExecutionStatus="Failed" AND Step="PostToErp"' --type FirstWorkflowTask --reason "ERP back"`, and type `y` to confirm |
| An `Invoice` request sits at **PostToErp** with attempts climbing | The **ERP is DOWN (503)** toggle is still on, perhaps from beat 4 | Untick it; the next retry succeeds |
| A new request stays at step **Received** | No worker is running, or the deployment has no current version | `make worker`. If it's running, check `temporal worker deployment describe --name document-routing --disable-config-file` and `make promote BUILD_ID=v1` |
| Toast "No worker has accepted the decision yet…" | The worker is stopped | Start `make worker`. The decision is applied when it starts; clicking again is safe |
| An `Invoice` request sits at **AwaitErpConfirmation** | The ERP's callback can't reach the web app | Keep `make up` running; the ERP retries the callback. After `CALLBACK_TIMEOUT` (2 minutes) the request fails with "The ERP did not confirm the posting" |
| The page shows **rules v2** (or later) right after starting | An earlier rehearsal's edits are still in `data/` | Click **Reset demo data** |
| Rules badge is amber and a banner says the edit was rejected | `data/rules.json` doesn't parse or validate | Fix the file, or save from the **Routing rules** card, which writes a valid file; the banner names the problem. The last good version stays in force meanwhile |
| After `make worker BUILD_ID=v2`, new requests still show **v1** | v2 isn't current yet | `make promote BUILD_ID=v2` |
| **Failed at budget reservation** still lists runs after the reset | Expected: those are the original failed runs, kept as the record | Each document has a newer completed run; open one to show the link |
| The batch reset command matches nothing | CC-99 requests haven't failed yet | Wait for **Failed**, or run the `temporal workflow list` query from beat 6 first |
| A CC-99 document has two completed runs after beat 6 | The reset command ran twice. The original failed runs still match its query, so the second run replayed them again. The idempotency keys kept it to one reservation and one purchase order per document | Nothing to clean up in the demo. Next time, replay only documents that haven't completed: filter by workflow ID as in beat 6, step 13 |
| `make` fails with "release version 21 not supported" | Maven is running an older JDK | Put JDK 21 or later first on your `PATH`; the Makefile uses that JDK |

## Keeping this runbook current

The code links point at line ranges in this directory. They are not yet pinned to a commit, because the repository isn't published. Once it's in git, replace the relative links with commit-pinned GitHub links, as the other catalog demos do.

Until then, after changing code, print the start of every linked range and check that each one still lands on the code the section describes:

```sh
grep -oE '\(src/[^)#]+#L[0-9]+' RUNBOOK.md | tr -d '(' | sort -u | while IFS='#' read -r file line; do
  n=${line#L}; printf '%s:%s  ' "$file" "$n"; sed -n "${n}p" "$file" | sed 's/^ *//'
done
```
