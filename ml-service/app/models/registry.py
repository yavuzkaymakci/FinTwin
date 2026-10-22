"""On-disk model registry.

Layout (``ML_ARTIFACTS_DIR``, default ``ml-service/artifacts``, gitignored)::

    models/<name>/<version>/model.joblib
    models/<name>/<version>/metadata.json
    models/active.json            {"pd": "<version>", "churn": "<version>", "nlp": "<version>"}
"""

from __future__ import annotations

import json
import os
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import joblib

MODEL_NAMES = ("pd", "churn", "nlp")


def artifacts_dir() -> Path:
    default = Path(__file__).resolve().parents[2] / "artifacts"
    return Path(os.environ.get("ML_ARTIFACTS_DIR", default))


def new_version() -> str:
    return datetime.now(UTC).strftime("%Y%m%d-%H%M%S")


def save(name: str, version: str, model: Any, metadata: dict[str, Any]) -> Path:
    directory = artifacts_dir() / "models" / name / version
    directory.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, directory / "model.joblib", compress=3)
    (directory / "metadata.json").write_text(json.dumps(metadata, indent=2, default=str), encoding="utf-8")
    return directory


def activate(name: str, version: str) -> None:
    if not (artifacts_dir() / "models" / name / version / "model.joblib").exists():
        raise FileNotFoundError(f"No artifact for {name} {version}")
    active = active_versions()
    active[name] = version
    path = artifacts_dir() / "models" / "active.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(active, indent=2), encoding="utf-8")


def active_versions() -> dict[str, str]:
    path = artifacts_dir() / "models" / "active.json"
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def load(name: str, version: str | None = None) -> tuple[Any, dict[str, Any]]:
    version = version or active_versions().get(name)
    if version is None:
        raise FileNotFoundError(f"No active {name} model")
    directory = artifacts_dir() / "models" / name / version
    metadata = json.loads((directory / "metadata.json").read_text(encoding="utf-8"))
    return joblib.load(directory / "model.joblib"), metadata
