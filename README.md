# Document routing — a Temporal demo

A document arrives, is classified, and business-unit rules route it down one of three paths:

- **`HumanReview`.** A reviewer rejects the request, which ends it, or approves its cost, which sends it back through the rules with the cost threshold waived, so it continues down `Invoice` or `PurchaseOrder`.
- **`Invoice`.** The invoice is posted to the ERP, which confirms the posting asynchronously, and the requester is notified.
- **`PurchaseOrder`.** The purchase order's cost center is checked synchronously, budget is reserved, and the PO is sent to the supplier portal. If the supplier rejects it, the budget reservation is released.

Each document is one Temporal Workflow. You can stop the worker, take the external systems down, change routing rules live, and ship a new worker version while requests are in flight, and nothing is lost or done twice.

The [RUNBOOK](RUNBOOK.md) is the presenter's script.

**Built with:** Java 21 · Temporal Java SDK 1.40.0 · Javalin · vanilla JS. Runs locally only.

## Prerequisites

- JDK 21 or later on your `PATH`. The Makefile points Maven at that JDK even if `JAVA_HOME` is older.
- Maven 3.9 or later.
- [Temporal CLI](https://docs.temporal.io/cli) 1.9 or later, for Worker Deployments on the dev server. Tested with CLI 1.9.1 (Server 1.32.0, UI 2.54.1).

You need no Temporal Cloud account, API key, or Docker.

## Run it

In four terminals, from this directory:

```sh
make temporal   # Temporal dev server; UI at http://localhost:8233
make systems    # mock ERP, cost-center check, supplier portal and notifications on :8090
make worker     # the Temporal worker (version v1)
make web        # the demo app at http://localhost:8080
```

The first `make systems`, `make worker`, or `make web` copies the default rules and reference data into `data/`. Those copies are what changes live during the demo. The page's **Routing rules** card edits the per-unit thresholds in `data/rules.json`, and you can edit the file by hand too. **Reset demo data** on the page, or `make reset-data`, restores the defaults; the page button also clears the system fault toggles. The rules hold only those thresholds. Below its unit's threshold, a document's path follows from its type: invoices go to `Invoice` and purchase orders to `PurchaseOrder`. Any other type fails at `EvaluateRules` without retrying, before it reaches a reviewer. Older rules files that also list `purchaseOrderFor`, `externalChecksFor` or `default` still load, and those fields are ignored.

The dev server keeps everything in memory. Stopping `make temporal` clears every request.

## The mock systems

`make systems` runs every external system in one process on :8090. Stopping it is a real outage of all of them.

| Endpoint | Behaves like |
|---|---|
| `POST /erp/invoices` | ERP: accepts the invoice posting with `202` at once, then POSTs the confirmation to the `callbackUrl` after `ERP_CONFIRM_DELAY`. It retries the callback until the receiver answers 2xx |
| `POST /cost-centers/check` | Cost-center check, synchronous: `200` if the cost center is in `data/reference-data.json`, otherwise `422 unknown cost center` |
| `POST /tasks/reserve-budget` (and `/undo`) | The `PurchaseOrder` budget reservation and its compensation |
| `POST /supplier-portal/purchase-orders` | Supplier portal: `200` (PO accepted), or `422` while it's set to reject |
| `POST /notify`, `POST /archive` | Notification service and archive |
| `GET`/`PUT /control` | Fault toggles: `{"erpDown": false, "supplierRejects": false}` |
| `GET /effects`, `GET /notifications` | Every side effect so far, in order |

Side-effecting calls honor an `Idempotency-Key` header: a repeat gets the first response and causes no second effect. That store is in memory, so restarting the process clears it.

`data/reference-data.json` is read on every check. Adding a cost center there fixes later checks without a restart.

## Configuration

Every process reads its settings from environment variables. None reads a Temporal CLI profile.

| Variable | Default | Used for |
|---|---|---|
| `TEMPORAL_ADDRESS` | `localhost:7233` | Temporal frontend |
| `TEMPORAL_NAMESPACE` | `default` | Namespace |
| `TEMPORAL_WORKER_BUILD_ID` | `v1` | Worker version (`make worker BUILD_ID=v2` sets it) |
| `SLA_DURATION` | `60s` | Human-task SLA |
| `ERP_CONFIRM_DELAY` | `4s` | How long the ERP takes to confirm a posting |
| `CALLBACK_TIMEOUT` | `2m` | How long a request waits for the ERP's confirmation |
| `ACTIVITY_DELAY` | `500ms` | Demo-only pause at the start of every activity, so steps are spaced out in the Temporal UI. `0s` turns it off |
| `WEB_PORT` / `SYSTEMS_PORT` | `8080` / `8090` | Local ports |

## Tests

```sh
make test
```
