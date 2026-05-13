# Pantera scaling-benchmark harness.
# See ./README.md for the full guide.
.PHONY: help setup build smoke cell matrix summary clean inspect

SHELL := /usr/bin/env bash
COMPOSE := docker compose -f docker-compose-scaling.yml
REPO_ROOT := $(shell cd .. && pwd)
PANTERA_VERSION := 2.2.0
IMAGE_TAG := pantera-bench:local
JAR_FILE := pantera-main-$(PANTERA_VERSION).jar

help:
	@echo "Pantera scaling benchmark — available targets:"
	@echo "  make setup           Generate mock-upstream body files (~12 MB, uncommitted)"
	@echo "  make build           mvn package + docker build $(IMAGE_TAG) (~10 min first time)"
	@echo "  make smoke           Run one cell (C2/V0) end-to-end (~15-20 min; implies build+setup)"
	@echo "  make cell CFG=C2 VAR=V0   Run an arbitrary cell (implies build+setup)"
	@echo "  make matrix          Run the full 6-cell scaling matrix (~2 h; implies build+setup)"
	@echo "  make summary         Re-render scaling-summary.md from scaling-raw.csv"
	@echo "  make inspect         k6 inspect the scenario (no traffic)"
	@echo "  make clean           Tear down any running stack + clear results/"

setup:
	./wiremock/__files/generate.sh

build:
	@echo "=== Building Pantera SUT image $(IMAGE_TAG) ==="
	cd $(REPO_ROOT) && mvn package -pl pantera-main -am -DskipTests -T 2C
	cd $(REPO_ROOT) && docker build \
	    --build-arg JAR_FILE=$(JAR_FILE) \
	    -t $(IMAGE_TAG) \
	    -f pantera-main/Dockerfile \
	    pantera-main
	@docker image inspect $(IMAGE_TAG) --format 'Built: {{.Id}} ({{.Size}} bytes)' | head -1

smoke: | _bootstrap
	./scripts/run-cell.sh C2 V0

cell: | _bootstrap
	@test -n "$(CFG)" || { echo "CFG=C1|C2|C3|C4 required"; exit 1; }
	@test -n "$(VAR)" || { echo "VAR=V0|V1|V2 required"; exit 1; }
	./scripts/run-cell.sh $(CFG) $(VAR)

matrix: | _bootstrap
	./scripts/run-matrix.sh

# Order-only pre-req: ensures image + bodies exist, but does not re-run on every target invocation.
.PHONY: _bootstrap
_bootstrap:
	@docker image inspect $(IMAGE_TAG) >/dev/null 2>&1 || $(MAKE) build
	@test -f wiremock/__files/body-100k.bin || $(MAKE) setup

summary:
	python3 scripts/parse-k6.py results results/scaling-raw.csv
	python3 scripts/render-summary.py results/scaling-raw.csv results/scaling-summary.md
	@echo "Rendered: results/scaling-summary.md"

inspect:
	k6 inspect --env CELL_LABEL=inspect k6/scenario.js

clean:
	-$(COMPOSE) down -v 2>/dev/null || true
	find results -type f ! -name '.gitkeep' -delete 2>/dev/null || true
	rm -f wiremock/__files/*.bin 2>/dev/null || true
	@echo "Cleaned results/ and stack."
