"""Helper steps for scripts/setup-fresh.bat: waiting on the backend, portfolio scoring, preset runs and the
comparison with the reference values in docs/reproducibility.json.

Runs with the ml-service virtual environment (standard library + psycopg):

    ml-service\\.venv\\Scripts\\python scripts\\setup_steps.py <command> [options]

Commands:
    wait-http URL [--timeout S]   wait until URL answers 200
    twin                          reset the twin, rebuild the outbox, bulk-replay everything, wait until every
                                  event is applied
    score                         score the whole portfolio (needs ml-service) and wait
    presets                       run the preset scenarios through the backend (BASE alone, the others against
                                  BASE), one at a time
    check                         compare this installation with docs/reproducibility.json (warnings only)
    snapshot                      print the current values in the reference format (to refresh the JSON)

Environment: FINTWIN_API (default http://localhost:8080), FINTWIN_DB_URL
(default postgresql://fintwin:fintwin@localhost:5432/fintwin).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
API = os.environ.get("FINTWIN_API", "http://localhost:8080")
DB_URL = os.environ.get("FINTWIN_DB_URL", "postgresql://fintwin:fintwin@localhost:5432/fintwin")
REFERENCE = ROOT / "docs" / "reproducibility.json"
MODELS = ROOT / "ml-service" / "artifacts" / "models"
HEADERS = {"Content-Type": "application/json", "X-Demo-User": "setup", "X-Demo-Role": "RISK_ANALYST"}

TABLES = ["customer", "account", "card_statement", "loan", "loan_installment", "bank_transaction", "customer_event",
          "merchant", "ground_truth.customer_monthly_state"]
SAMPLE_CUSTOMER = 6


def log(message: str) -> None:
    print(f"{time.strftime('%H:%M:%S')} {message}", flush=True)


def fail(message: str) -> None:
    log(f"ERROR: {message}")
    sys.exit(1)


def call(method: str, path: str, body: Any = None, timeout: float = 60) -> Any:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(API + path, data=data, method=method, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        text = response.read().decode()
        return json.loads(text) if text else None


def wait_http(url: str, timeout: float) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=5) as response:
                if response.status == 200:
                    log(f"{url} is up")
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(3)
    fail(f"{url} did not answer within {timeout:.0f} s")


def query(sql: str) -> list[tuple[Any, ...]]:
    import psycopg  # from the ml-service venv

    with psycopg.connect(DB_URL, connect_timeout=10) as conn:
        return conn.execute(sql).fetchall()


def scalar(sql: str) -> Any:
    return query(sql)[0][0]


# ------------------------------------------------------------------------------------------------ twin


def build_twin(timeout: float) -> None:
    log(f"twin reset: {call('POST', '/api/admin/twins/reset', timeout=600)}")
    prepared = call("POST", "/api/admin/replay/prepare", timeout=1800)
    total = int(prepared["totalEvents"])
    log(f"outbox prepared: {total:,} events")
    call("POST", "/api/admin/replay/bulk")
    started = time.time()
    while True:
        status = call("GET", "/api/admin/replay/status")
        applied = int(scalar("SELECT COALESCE(SUM(last_seq), 0) FROM twin.customer_twin_state"))
        elapsed = time.time() - started
        log(f"t={elapsed:4.0f}s replay={status['mode']} published={status['position']:,}/{total:,} "
            f"applied={applied:,}")
        if status.get("lastError"):
            fail(f"replay error: {status['lastError']}")
        if status["mode"] == "IDLE" and status["position"] >= total and applied >= total:
            break
        if elapsed > timeout:
            fail(f"twin not complete after {timeout:.0f} s (applied {applied:,} of {total:,})")
        time.sleep(15)
    twins = scalar("SELECT COUNT(*) FROM twin.customer_twin_state")
    log(f"twin complete: {applied:,} events applied, {twins:,} twins, {elapsed:.0f} s")


# ------------------------------------------------------------------------------------------------ scoring


def score(timeout: float) -> None:
    call("POST", "/api/admin/risk/score-portfolio")
    deadline = time.time() + timeout
    while time.time() < deadline:
        status = call("GET", "/api/admin/risk/status")
        if status["state"] == "SUCCEEDED":
            log(f"portfolio scored: {status['scored']:,} twins in {status.get('durationSeconds')} s")
            return
        if status["state"] == "FAILED":
            fail(f"scoring failed: {status.get('error')} (circuit breaker {status.get('circuitBreaker')})")
        time.sleep(3)
    fail("portfolio scoring did not finish in time")


# ------------------------------------------------------------------------------------------------ presets


def run_and_wait(scenario: dict[str, Any], baseline_id: int | None, timeout: float) -> dict[str, Any]:
    body: dict[str, Any] = {"baselineScenarioId": baseline_id} if baseline_id else {}
    run = call("POST", f"/api/scenarios/{scenario['id']}/run", body)
    log(f"{scenario['code']}: run #{run['id']} submitted")
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(10)
        run = call("GET", f"/api/simulation-runs/{run['id']}")
        if run["status"] == "SUCCEEDED":
            p = run["result"]["portfolio"]
            log(f"{scenario['code']}: default rate {p['defaultRate']['mean']:.4%}, EL {p['expectedLoss']:,.0f} TRY "
                f"({run.get('durationSeconds')} s)")
            return run
        if run["status"] == "FAILED":
            fail(f"{scenario['code']} failed: {run.get('error')}")
    fail(f"{scenario['code']} did not finish within {timeout:.0f} s")
    raise AssertionError  # unreachable


def presets(timeout: float) -> None:
    scenarios = [s for s in call("GET", "/api/scenarios") if s["preset"]]
    base = next((s for s in scenarios if s["code"] == "BASE"), None)
    if base is None:
        fail("preset scenario BASE missing (Flyway V9 seeds the presets)")
    run_and_wait(base, None, timeout)
    for scenario in scenarios:
        if scenario["code"] != "BASE":
            run_and_wait(scenario, base["id"], timeout)


# ------------------------------------------------------------------------------------------------ check


def active_metrics() -> dict[str, Any]:
    active = json.loads((MODELS / "active.json").read_text())
    out: dict[str, Any] = {}
    for name, version in active.items():
        meta = json.loads((MODELS / name / version / "metadata.json").read_text())
        if name == "nlp":
            variant = meta["variants"][meta["selected_variant"]]
            out[name] = {"macroF1": variant["macro_f1"]}
        else:
            test = meta["metrics"]["lightgbm"]["test"]
            out[name] = {k: test[k] for k in ("auc", "gini", "ks", "brier")}
    return out


def latest_preset_results() -> dict[str, Any]:
    out: dict[str, Any] = {}
    for run in call("GET", "/api/simulation-runs?limit=200"):  # newest first
        if run["status"] != "SUCCEEDED" or run["scenarioCode"] in out:
            continue
        detail = call("GET", f"/api/simulation-runs/{run['id']}")
        p = detail["result"]["portfolio"]
        out[run["scenarioCode"]] = {"defaultRate": p["defaultRate"]["mean"], "expectedLoss": p["expectedLoss"]}
    return out


def current() -> dict[str, Any]:
    values: dict[str, Any] = {"tables": {t: scalar(f"SELECT COUNT(*) FROM {t}") for t in TABLES}}
    values["twin"] = {
        "twins": scalar("SELECT COUNT(*) FROM twin.customer_twin_state"),
        "snapshots": scalar("SELECT COUNT(*) FROM twin.customer_twin_snapshot"),
        "events": scalar("SELECT COALESCE(SUM(last_seq), 0) FROM twin.customer_twin_state"),
    }
    row = query(f"SELECT last_seq, checking_balance FROM twin.customer_twin_state WHERE customer_id = {SAMPLE_CUSTOMER}")
    values["sampleCustomer"] = {"customerId": SAMPLE_CUSTOMER, "lastSequence": row[0][0] if row else None,
                                "checkingBalance": float(row[0][1]) if row else None}
    try:
        risk = call("GET", f"/api/twins/{SAMPLE_CUSTOMER}/risk")
        values["sampleCustomer"].update({"pd": risk["pd"], "riskGrade": risk["riskGrade"]})
        summary = call("GET", "/api/portfolio/summary?top=1")
        values["portfolio"] = {"scoredCustomers": summary["scoredCustomers"], "meanPd": summary["meanPd"],
                               "highRiskCustomers": summary["highRiskCustomers"]}
        values["presets"] = latest_preset_results()
    except (urllib.error.URLError, OSError) as e:
        log(f"backend values skipped: {e}")
    values["models"] = active_metrics()
    return values


def compare(expected: Any, actual: Any, path: str, tolerances: dict[str, float], diffs: list[str]) -> None:
    if isinstance(expected, dict):
        for key, value in expected.items():
            if key.startswith("_"):
                continue
            compare(value, actual.get(key) if isinstance(actual, dict) else None, f"{path}.{key}".lstrip("."),
                    tolerances, diffs)
        return
    if actual is None:
        diffs.append(f"{path}: expected {expected}, missing here")
    elif isinstance(expected, (int, float)) and not isinstance(expected, bool):
        tolerance = next((t for prefix, t in tolerances.items() if path.startswith(prefix)), 0.0)
        if tolerance < 1 and expected:  # relative tolerance
            ok = abs(float(actual) - expected) <= tolerance * abs(expected)
        else:
            ok = abs(float(actual) - expected) <= tolerance
        if not ok:
            diffs.append(f"{path}: expected {expected}, got {actual}")
    elif expected != actual:
        diffs.append(f"{path}: expected {expected}, got {actual}")


def check() -> None:
    reference = json.loads(REFERENCE.read_text(encoding="utf-8"))
    diffs: list[str] = []
    compare(reference["values"], current(), "", reference.get("tolerances", {}), diffs)
    if diffs:
        log(f"WARNING: {len(diffs)} value(s) differ from docs/reproducibility.json:")
        for d in diffs:
            print(f"  - {d}")
        print("  Exact table counts must match (same seed); see docs/reproducibility.md for what may drift.")
    else:
        log("all reference values match docs/reproducibility.json")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["wait-http", "twin", "score", "presets", "check", "snapshot"])
    parser.add_argument("url", nargs="?")
    parser.add_argument("--timeout", type=float, default=3600)
    args = parser.parse_args()
    try:
        if args.command == "wait-http":
            wait_http(args.url or f"{API}/actuator/health", args.timeout)
        elif args.command == "twin":
            build_twin(args.timeout)
        elif args.command == "score":
            score(args.timeout)
        elif args.command == "presets":
            presets(args.timeout)
        elif args.command == "check":
            check()
        else:
            print(json.dumps(current(), indent=2, default=str))
    except urllib.error.HTTPError as e:
        fail(f"{e.code} {e.url}: {e.read().decode(errors='replace')[:500]}")
    except urllib.error.URLError as e:
        fail(f"cannot reach {API}: {e.reason}")


if __name__ == "__main__":
    main()
