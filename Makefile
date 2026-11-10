# Local demo, in two terminals:
#
#   make up         # Temporal dev server, mock systems and the demo app; Ctrl-C stops all three
#   make worker     # the Temporal worker (BUILD_ID=v1 by default); stopped and restarted in beat 5
#
#   make worker BUILD_ID=v2   # start a second, newer worker version
#   make promote BUILD_ID=v2  # make v2 current: new requests go to v2
#   make migrate DOC=<id>     # move one running request from v1 to v2 (FROM=v1 BUILD_ID=v2)
#   make migrate              # move every running request from v1 to v2, as one batch
#   make stop-systems         # take every mock system down (beat 5's harder outage)
#   make reset-data           # restore default rules, reference data
#
# `make up` runs these, which also work on their own, one per terminal:
#
#   make temporal   # Temporal dev server, UI on http://localhost:8233
#   make systems    # mock ERP, budget service, supplier portal + notifications on :8090
#   make web        # the demo app on http://localhost:8080
#
# Maven runs on the `java` found on your PATH (JDK 21 or later), even if
# JAVA_HOME points somewhere older.

export JAVA_HOME := $(shell java -XshowSettings:properties -version 2>&1 | awk '/java.home/{print $$3}')

BUILD_ID ?= v1
DEPLOYMENT ?= document-routing
MVN := mvn -q
PKG := io.temporal.demo.docrouting

TEMPORAL_FLAGS := --disable-config-file

SEARCH_ATTRIBUTES := \
	--search-attribute Step=Keyword \
	--search-attribute Path=Keyword \
	--search-attribute BusinessUnit=Keyword \
	--search-attribute SlaBreached=Bool \
	--search-attribute RulesVersion=Int \
	--search-attribute Cost=Int \
	--search-attribute CostCenter=Keyword \
	--search-attribute DocType=Keyword \
	--search-attribute Assignee=Keyword \
	--search-attribute TaskId=Keyword \
	--search-attribute SlaDeadline=Datetime

# Each process runs in its own JVM, not inside Maven, so its logs reach the
# terminal and Ctrl-C stops just that process.
CLASSPATH_FILE := target/classpath.txt
RUN := java -cp target/classes:$$(cat $(CLASSPATH_FILE))

.PHONY: up temporal systems stop-systems worker web promote migrate reset-data test build data

TEMPORAL_START := temporal server start-dev $(TEMPORAL_FLAGS) --ip 127.0.0.1 $(SEARCH_ATTRIBUTES)
SYSTEMS_START := $(RUN) $(PKG).systems.SystemsMain
WEB_START := $(RUN) $(PKG).web.WebMain

# Everything but the worker, in one terminal. Background jobs ignore Ctrl-C in
# a non-interactive shell, so the trap passes it on as SIGTERM, which runs
# each process's shutdown hook. The web app connects to Temporal lazily, so
# starting it alongside the dev server is fine.
up: data build
	@trap 'kill $$pids 2>/dev/null; wait' INT TERM; \
	$(TEMPORAL_START) & pids="$$!"; \
	$(SYSTEMS_START) & pids="$$pids $$!"; \
	$(WEB_START) & pids="$$pids $$!"; \
	wait

temporal:
	$(TEMPORAL_START)

# Runtime copies of the editable JSON files; created on first run.
data:
	@mkdir -p data
	@test -f data/rules.json || cp config/rules.default.json data/rules.json
	@test -f data/reference-data.json || cp config/reference-data.default.json data/reference-data.json

reset-data:
	@mkdir -p data
	cp config/rules.default.json data/rules.json
	cp config/reference-data.default.json data/reference-data.json

systems: data build
	$(SYSTEMS_START)

# Stops the systems process however it was started (`make up` or `make systems`).
# Anchored on `java` so it skips the `make up` shell, whose command line names
# the class too; the brackets keep it from matching this command's own shell.
stop-systems:
	@pkill -f '^java .*docrouting[.]systems[.]SystemsMain' && echo "Mock systems stopped" || echo "Mock systems weren't running"

worker: data build
	TEMPORAL_WORKER_BUILD_ID=$(BUILD_ID) $(RUN) $(PKG).worker.WorkerMain

web: data build
	$(WEB_START)

promote:
	temporal worker deployment set-current-version $(TEMPORAL_FLAGS) \
		--deployment-name $(DEPLOYMENT) --build-id $(BUILD_ID) --yes

# Pins running requests on FROM to the new version. DOC=<document ID> moves that one request (its
# workflow ID is doc-<document ID>); without DOC, every running request on FROM moves as one batch
# with a reason. The new version's code must replay the old histories (see HistoryReplayTest).
# Requests not moved stay on the version they started on. The move reaches visibility a moment
# later, so this waits until no running request it targeted is still on FROM.
# BUILD_ID means v2 here unless given on the command line.
FROM ?= v1
migrate: MIGRATE_TO = $(if $(filter command line,$(origin BUILD_ID)),$(BUILD_ID),v2)
migrate: MIGRATE_WF = $(if $(DOC),doc-$(patsubst doc-%,%,$(DOC)))
migrate:
	@q='TemporalWorkerDeploymentVersion="$(DEPLOYMENT):$(FROM)" AND ExecutionStatus="Running"$(if $(MIGRATE_WF), AND WorkflowId="$(MIGRATE_WF)")'; \
	n=$$(temporal workflow count $(TEMPORAL_FLAGS) --query "$$q" | awk '/Total:/{print $$2}'); \
	if [ "$${n:-0}" = 0 ]; then echo "Nothing to migrate: no running $(if $(MIGRATE_WF),request $(MIGRATE_WF),requests) on $(FROM)"; exit 0; fi; \
	echo "Moving $$n running request(s) from $(FROM) to $(MIGRATE_TO)"; \
	temporal workflow update-options $(TEMPORAL_FLAGS) \
		$(if $(MIGRATE_WF),--workflow-id $(MIGRATE_WF),--query "$$q" --reason "Migrate running requests from $(FROM) to $(MIGRATE_TO)" --yes) \
		--versioning-override-behavior pinned \
		--versioning-override-deployment-name $(DEPLOYMENT) \
		--versioning-override-build-id $(MIGRATE_TO) >/dev/null || exit 1; \
	for i in $$(seq 1 60); do \
		left=$$(temporal workflow count $(TEMPORAL_FLAGS) --query "$$q" | awk '/Total:/{print $$2}'); \
		if [ "$${left:-0}" = 0 ]; then echo "Moved $$n request(s) from $(FROM) to $(MIGRATE_TO)"; exit 0; fi; \
		sleep 1; \
	done; \
	echo "Not moved yet; check with: temporal batch list $(TEMPORAL_FLAGS)"; exit 1

build: $(CLASSPATH_FILE)
	$(MVN) compile

$(CLASSPATH_FILE): pom.xml
	$(MVN) dependency:build-classpath -Dmdep.outputFile=$(CLASSPATH_FILE) -Dmdep.includeScope=runtime

test:
	$(MVN) test
