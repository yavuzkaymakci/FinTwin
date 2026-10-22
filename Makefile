# Common development commands. Requires GNU make and a POSIX shell (on Windows: Git Bash or WSL).

COMPOSE := docker compose -f infra/docker-compose.yml

ifeq ($(OS),Windows_NT)
  VENV_BIN := .venv/Scripts
else
  VENV_BIN := .venv/bin
endif

.PHONY: help up down logs ps backend-run backend-test generate ml-install ml-run ml-test ml-export-labels ml-train ml-train-nlp ml-evaluate ml-backtest ml-presets frontend-install frontend-run frontend-test frontend-build test

help:
	@echo "Targets: up down logs ps backend-run backend-test generate ml-install ml-run ml-test ml-export-labels ml-train ml-train-nlp ml-evaluate ml-backtest ml-presets frontend-install frontend-run frontend-test frontend-build test"

## Infrastructure (PostgreSQL, Kafka, Kafka UI)
up:
	$(COMPOSE) up -d --wait

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f

ps:
	$(COMPOSE) ps

## Backend
backend-run:
	cd backend && ./mvnw spring-boot:run

backend-test:
	cd backend && ./mvnw test

## Synthetic data (needs `make up`); override e.g. GENERATOR_CUSTOMERS=1000 make generate
generate:
	cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,generate

## ML service
ml-install:
	cd ml-service && python -m venv .venv && $(VENV_BIN)/python -m pip install -r requirements-dev.txt

ml-run:
	cd ml-service && $(VENV_BIN)/python -m uvicorn app.main:app --reload --port 8000

ml-test:
	cd ml-service && $(VENV_BIN)/python -m pytest

## Model training (needs `make up` and a replayed twin); output goes to logs/, artifacts to ml-service/artifacts/
ml-export-labels:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.labeling.export_transaction_labels > ../logs/export-labels-$$(date +%Y%m%d-%H%M%S).log 2>&1

ml-train:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.training.train_risk --target pd --activate > ../logs/train-risk-pd-$$(date +%Y%m%d-%H%M%S).log 2>&1
	cd ml-service && $(VENV_BIN)/python -m app.training.train_risk --target churn --activate > ../logs/train-risk-churn-$$(date +%Y%m%d-%H%M%S).log 2>&1

ml-train-nlp:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.training.train_nlp --activate > ../logs/train-nlp-$$(date +%Y%m%d-%H%M%S).log 2>&1

ml-evaluate:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.evaluation.stress_relationship > ../logs/evaluate-$$(date +%Y%m%d-%H%M%S).log 2>&1

ml-backtest:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.simulation.backtest --start 2025-09-01 --start 2025-12-01 --start 2026-03-01 --start 2026-04-01 --paths 200 > ../logs/backtest-$$(date +%Y%m%d-%H%M%S).log 2>&1

ml-presets:
	mkdir -p logs && cd ml-service && $(VENV_BIN)/python -m app.simulation.run_presets > ../logs/presets-$$(date +%Y%m%d-%H%M%S).log 2>&1

## Frontend
frontend-install:
	cd frontend && npm install

frontend-run:
	cd frontend && npm run dev

frontend-test:
	cd frontend && npm test

frontend-build:
	cd frontend && npm run build

## All tests
test: backend-test ml-test frontend-test
