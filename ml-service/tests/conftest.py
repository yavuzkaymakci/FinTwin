from __future__ import annotations

import pytest

from app.db import connect


def _database_available() -> bool:
    try:
        with connect() as conn:
            conn.execute("SELECT 1")
        return True
    except Exception:  # noqa: BLE001 - any connection problem means "no database"
        return False


@pytest.fixture(scope="session")
def database() -> None:
    """Skips tests that need the local PostgreSQL (``make up``) with generated and replayed data."""
    if not _database_available():
        pytest.skip("PostgreSQL with the fintwin_ml role is not reachable (make up)")
