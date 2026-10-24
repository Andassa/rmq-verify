# rmq-verify developer entry points. Every target is idempotent; run `make help` for the list.

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

MVN ?= ./mvnw
MVN_FLAGS ?= -B -ntp

.PHONY: help
help: ## List the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

.PHONY: build
build: ## Compile and package every module without running tests
	$(MVN) $(MVN_FLAGS) -DskipTests package

.PHONY: test
test: ## Run the unit and property-based tests
	$(MVN) $(MVN_FLAGS) test

.PHONY: verify
verify: ## Full quality gate: format, lint, tests, coverage, ArchUnit, SpotBugs, mutation
	$(MVN) $(MVN_FLAGS) -Pmutation clean verify

.PHONY: mutation
mutation: ## PIT mutation testing on the model and checker modules
	$(MVN) $(MVN_FLAGS) -Pmutation -pl rmq-verify-model,rmq-verify-checker -am verify

.PHONY: fmt
fmt: ## Format Java sources and POMs in place
	$(MVN) $(MVN_FLAGS) -pl '!rmq-verify-bom' com.diffplug.spotless:spotless-maven-plugin:apply

.PHONY: clean
clean: ## Remove build outputs
	$(MVN) $(MVN_FLAGS) clean
