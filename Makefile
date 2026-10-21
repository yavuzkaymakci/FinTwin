# Common development commands. Requires GNU make and a POSIX shell (on Windows: Git Bash or WSL).

COMPOSE := docker compose -f infra/docker-compose.yml

ifeq ($(OS),Windows_NT)
  VENV_BIN := .venv/Scripts
else
  VENV_BIN := .venv/bin
endif

.PHONY: help up down logs ps backend-run backend-test generate ml-install ml-run ml-test frontend-install frontend-run frontend-test frontend-build test

help:
	@echo "Targets: up down logs ps backend-run backend-test generate ml-install ml-run ml-test frontend-install frontend-run frontend-test frontend-build test"

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
