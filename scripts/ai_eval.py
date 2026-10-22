"""Evaluates the assistants against a real LLM (manual only, never in CI).

Runs the question sets in docs/ai-eval/ through a running backend (profile `anthropic` or `ollama`) and reports
tool selection accuracy, parameter accuracy, number-check pass rate, average duration and tokens. Without an
`ANTHROPIC_API_KEY` (or `--provider ollama`) it prints how to run it and exits.

    # terminal 1: infrastructure, ML service and backend with a real model
    make up && make ml-run
    export ANTHROPIC_API_KEY=...          # never commit it
    cd backend && SPRING_PROFILES_ACTIVE=local,anthropic ./mvnw spring-boot:run
    # terminal 2
    python scripts/ai_eval.py --run-a 1 --run-b 2 --customer-id 6

Only the standard library is used. Results go to logs/ai-eval-<time>.json and .md.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SETS = [ROOT / "docs" / "ai-eval" / "scenario-assistant.json", ROOT / "docs" / "ai-eval" / "customer-coach.json"]

HOW_TO = """No ANTHROPIC_API_KEY in the environment: the evaluation against a real model is skipped.

To run it:
  1. make up && make ml-run              (PostgreSQL/Kafka and the ML service with trained models)
  2. export ANTHROPIC_API_KEY=sk-ant-...  (or PowerShell: $env:ANTHROPIC_API_KEY="sk-ant-...")
     optional: export ANTHROPIC_MODEL=claude-sonnet-5-5
  3. cd backend && SPRING_PROFILES_ACTIVE=local,anthropic ./mvnw spring-boot:run
  4. python scripts/ai_eval.py --run-a <BASE run id> --run-b <shock run id> --customer-id 6
With a local Ollama instead: SPRING_PROFILES_ACTIVE=local,ollama and python scripts/ai_eval.py --provider ollama.
"""


def post(base: str, path: str, body: dict[str, Any], headers: dict[str, str]) -> tuple[int, dict[str, Any]]:
    request = urllib.request.Request(base + path, data=json.dumps(body).encode("utf-8"), method="POST",
                                     headers={"Content-Type": "application/json; charset=utf-8", **headers})
    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except ValueError:
            return e.code, {}


def get(base: str, path: str) -> Any:
    with urllib.request.urlopen(base + path, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def substitute(value: Any, values: dict[str, str]) -> Any:
    if isinstance(value, str):
        for key, replacement in values.items():
            value = value.replace("{" + key + "}", replacement)
        return int(value) if re.fullmatch(r"\d+", value) and value in values.values() else value
    if isinstance(value, list):
        return [substitute(v, values) for v in value]
    if isinstance(value, dict):
        return {k: substitute(v, values) for k, v in value.items()}
    return value


def extract(document: Any, path: str) -> list[Any]:
    """Values at a simple JSON path: a.b[0].c, a[*].b."""
    values = [document]
    for part in re.findall(r"[^.\[\]]+|\[\d+\]|\[\*\]", path):
        nxt: list[Any] = []
        for v in values:
            if part == "[*]" and isinstance(v, list):
                nxt.extend(v)
            elif part.startswith("[") and isinstance(v, list):
                i = int(part[1:-1])
                if i < len(v):
                    nxt.append(v[i])
            elif isinstance(v, dict) and part in v:
                nxt.append(v[part])
        values = nxt
    return values


def check_param(check: dict[str, Any], calls: list[dict[str, Any]], scenario_ids: dict[str, int]) -> bool:
    for call in calls:
        if call["name"] != check["tool"]:
            continue
        try:
            arguments = json.loads(call["arguments"] or "{}")
        except ValueError:
            continue
        found = extract(arguments, check["path"])
        if "scenarioCode" in check:
            if scenario_ids.get(check["scenarioCode"]) in [int(v) for v in found if str(v).isdigit()]:
                return True
        elif "contains" in check:
            if check["contains"] in found or str(check["contains"]) in [str(v) for v in found]:
                return True
        elif "equals" in check:
            expected = check["equals"]
            for v in found:
                if isinstance(expected, (int, float)) and isinstance(v, (int, float)):
                    if abs(v - expected) <= check.get("tolerance", 0):
                        return True
                elif str(v).upper() == str(expected).upper():
                    return True
    return False


def run_case(base: str, headers: dict[str, str], case: dict[str, Any], scenario_ids: dict[str, int]) -> dict[str, Any]:
    started = time.perf_counter()
    turns = []
    conversation = None
    for message in [case["question"], *case.get("followUps", [])]:
        body = {"message": message} if conversation is None else {"conversationId": conversation, "message": message}
        status, answer = post(base, f"/api/assistants/{case['assistant']}/chat", body, headers)
        turns.append({"message": message, "httpStatus": status, **answer})
        conversation = answer.get("conversationId", conversation)
        if status != 200:
            break
    calls = [c for t in turns for c in t.get("toolCalls", [])]
    names = [c["name"] for c in calls]
    if case.get("expectRefusal"):
        tools_ok = turns[0].get("status") == "REFUSED" or not names
    elif case.get("expectNoTools"):
        tools_ok = not names
    else:
        tools_ok = set(case.get("expectedTools", [])) <= set(names) and not set(case.get("forbiddenTools", [])) & set(
            names)
    params = case.get("params", [])
    params_ok = [check_param(p, calls, scenario_ids) for p in params]
    started_runs = [r for t in turns for r in t.get("runIds", [])]
    # runIds of a turn list the runs it started or looked at; S-cases with expectStarted only start runs.
    started_ok = bool(started_runs) == case["expectStarted"] if "expectStarted" in case else None
    validations = [t.get("validation") for t in turns if t.get("validation")]
    return {
        "id": case["id"], "question": case["question"], "toolsCalled": names, "toolSelectionCorrect": tools_ok,
        "paramsCorrect": sum(params_ok), "paramsTotal": len(params), "startedAsExpected": started_ok,
        "runIds": started_runs, "numberCheckPassed": all(v["passed"] for v in validations) if validations else True,
        "unverifiedNumbers": [n for v in validations for n in v.get("unverified", [])],
        "durationSeconds": round(time.perf_counter() - started, 1),
        "tokens": sum((t.get("usage") or {}).get("totalTokens") or 0 for t in turns),
        "answers": [t.get("answer") for t in turns], "statuses": [t.get("status", t.get("httpStatus")) for t in turns]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--provider", choices=["anthropic", "ollama"], default="anthropic")
    parser.add_argument("--run-a", default="1", help="id of a finished BASE run")
    parser.add_argument("--run-b", default="2", help="id of a finished shock run (against BASE)")
    parser.add_argument("--customer-id", default="6")
    parser.add_argument("--only", help="comma-separated case ids")
    args = parser.parse_args()
    if args.provider == "anthropic" and not os.environ.get("ANTHROPIC_API_KEY"):
        print(HOW_TO)
        return 0

    base = args.base_url.rstrip("/")
    scenario_ids = {s["code"]: s["id"] for s in get(base, "/api/scenarios")}
    values = {"RUN_A": str(args.run_a), "RUN_B": str(args.run_b)}
    only = set(args.only.split(",")) if args.only else None
    results = []
    for path in SETS:
        spec = json.loads(path.read_text(encoding="utf-8"))
        headers = {"X-Demo-User": "eval-" + spec["assistant"], "X-Demo-Role": spec["role"]}
        if spec["role"] == "CUSTOMER":
            headers["X-Demo-Customer-Id"] = str(args.customer_id)
        for case in spec["cases"]:
            if only and case["id"] not in only:
                continue
            case = substitute({**case, "assistant": spec["assistant"]}, values)
            result = run_case(base, headers, case, scenario_ids)
            results.append({"assistant": spec["assistant"], **result})
            print(f"{result['id']}: tools {'OK ' if result['toolSelectionCorrect'] else 'BAD'} "
                  f"params {result['paramsCorrect']}/{result['paramsTotal']} "
                  f"numbers {'OK' if result['numberCheckPassed'] else 'FLAG ' + str(result['unverifiedNumbers'])} "
                  f"{result['durationSeconds']} s {result['tokens']} tokens -> {result['toolsCalled']}")

    def summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
        if not rows:
            return {}
        params_total = sum(r["paramsTotal"] for r in rows)
        return {"cases": len(rows),
                "toolSelectionAccuracy": round(sum(r["toolSelectionCorrect"] for r in rows) / len(rows), 3),
                "parameterAccuracy": round(sum(r["paramsCorrect"] for r in rows) / params_total, 3) if params_total
                else None,
                "confirmationGateCorrect": [r["startedAsExpected"] for r in rows if r["startedAsExpected"] is not None],
                "numberCheckPassRate": round(sum(r["numberCheckPassed"] for r in rows) / len(rows), 3),
                "avgDurationSeconds": round(sum(r["durationSeconds"] for r in rows) / len(rows), 1),
                "avgTokens": round(sum(r["tokens"] for r in rows) / len(rows))}

    report = {"provider": args.provider, "model": os.environ.get("ANTHROPIC_MODEL") or os.environ.get("OLLAMA_MODEL"),
              "summary": {a: summary([r for r in results if r["assistant"] == a]) for a in ("scenario", "coach")},
              "results": results}
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out = ROOT / "logs" / f"ai-eval-{stamp}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    lines = ["| Assistant | Cases | Tool selection | Parameters | Number check | Avg s | Avg tokens |",
             "|---|---|---|---|---|---|---|"]
    for name, s in report["summary"].items():
        if s:
            lines.append(f"| {name} | {s['cases']} | {s['toolSelectionAccuracy']:.0%} | "
                         f"{'' if s['parameterAccuracy'] is None else format(s['parameterAccuracy'], '.0%')} | "
                         f"{s['numberCheckPassRate']:.0%} | {s['avgDurationSeconds']} | {s['avgTokens']} |")
    (out.with_suffix(".md")).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    print(f"report: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
