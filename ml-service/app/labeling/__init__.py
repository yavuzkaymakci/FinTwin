"""Label export: the only place outside offline evaluation that reads the generator's ground_truth schema.

In a real bank the categorisation model would be trained on a hand-labelled sample of transactions. Here the
generator's true categories play that role: this package exports a stratified sample of
``(transaction_id, category)`` pairs to a file, and the training pipeline only ever reads that file. It connects
with the separate read-only role ``fintwin_ml_labels``.
"""

import os

DEFAULT_LABELS_DB_URL = "postgresql://fintwin_ml_labels:fintwin_ml_labels@localhost:5432/fintwin"


def labels_db_url() -> str:
    return os.environ.get("ML_LABELS_DB_URL", DEFAULT_LABELS_DB_URL)
