"""Read-only database access for training and feature pipelines.

Connects as ``fintwin_ml`` (see backend migration V8): it can read the twin schema and the core banking tables
needed for training, and has no privilege on the generator's hidden-variable schema. Every session is
additionally forced read-only.
"""

from __future__ import annotations

import os
from typing import Any

import pandas as pd
import psycopg

DEFAULT_ML_DB_URL = "postgresql://fintwin_ml:fintwin_ml@localhost:5432/fintwin"


def ml_db_url() -> str:
    return os.environ.get("ML_DB_URL", DEFAULT_ML_DB_URL)


def connect(url: str | None = None) -> psycopg.Connection:
    conn = psycopg.connect(url or ml_db_url())
    conn.read_only = True
    return conn


def read_frame(sql: str, params: Any = None, url: str | None = None) -> pd.DataFrame:
    """Runs a query and returns the rows as a DataFrame (column names from the cursor)."""
    with connect(url) as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        columns = [d.name for d in cur.description] if cur.description else []
        return pd.DataFrame(cur.fetchall(), columns=columns)
