# Local demo. Each target is one process; run them in separate terminals so
# you can stop any one of them on its own during the demo.
#
#   make temporal   # Temporal dev server, UI on http://localhost:8233
#   make systems    # mock ERP, cost-center check, supplier portal + notifications on :8090
#   make worker     # the Temporal worker (BUILD_ID=v1 by default)
#   make web        # the demo app on http://localhost:8080
#
#   make worker BUILD_ID=v2   # start a second, newer worker version
#   make promote BUILD_ID=v2  # make v2 current: new requests go to v2
#   make reset-data           # restore default rules, reference data
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
	--search-attribute RulesVersion=Int

# Each process runs in its own JVM, not inside Maven, so its logs reach the
# terminal and Ctrl-C stops just that process.
CLASSPATH_FILE := target/classpath.txt
RUN := java -cp target/classes:$$(cat $(CLASSPATH_FILE))

.PHONY: temporal systems worker web promote reset-data test build data

temporal:
	temporal server start-dev $(TEMPORAL_FLAGS) --ip 127.0.0.1 $(SEARCH_ATTRIBUTES)

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
	$(RUN) $(PKG).systems.SystemsMain

worker: data build
	TEMPORAL_WORKER_BUILD_ID=$(BUILD_ID) $(RUN) $(PKG).worker.WorkerMain

web: data build
	$(RUN) $(PKG).web.WebMain

promote:
	temporal worker deployment set-current-version $(TEMPORAL_FLAGS) \
		--deployment-name $(DEPLOYMENT) --build-id $(BUILD_ID) --yes

build: $(CLASSPATH_FILE)
	$(MVN) compile

$(CLASSPATH_FILE): pom.xml
	$(MVN) dependency:build-classpath -Dmdep.outputFile=$(CLASSPATH_FILE) -Dmdep.includeScope=runtime

test:
	$(MVN) test
