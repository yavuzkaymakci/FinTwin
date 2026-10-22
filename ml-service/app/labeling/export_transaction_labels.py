"""Export a stratified sample of transaction category labels.

    python -m app.labeling.export_transaction_labels [--per-category 12000]

Writes ``artifacts/data/transaction_labels.parquet`` with ``transaction_id, category`` only — no texts or
features, just like a labelling vendor would deliver labels for transaction ids. Sampling is deterministic
(hash of the id), at most ``--per-category`` rows per category, all rows of rarer categories.
"""

from __future__ import annotations

import argparse
import logging
import time

from app.db import read_frame
from app.labeling import labels_db_url
from app.models.registry import artifacts_dir

log = logging.getLogger("export_labels")

SAMPLE_SQL = """
SELECT transaction_id, category_code AS category
FROM (
    SELECT transaction_id, category_code,
           row_number() OVER (PARTITION BY category_code ORDER BY md5(transaction_id::text)) AS rn
    FROM ground_truth.transaction_label
) ranked
WHERE rn <= %(per_category)s
ORDER BY transaction_id
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--per-category", type=int, default=12_000)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    t0 = time.time()
    labels = read_frame(SAMPLE_SQL, {"per_category": args.per_category}, url=labels_db_url())
    target = artifacts_dir() / "data" / "transaction_labels.parquet"
    target.parent.mkdir(parents=True, exist_ok=True)
    labels.to_parquet(target, index=False)
    log.info("exported %d labels in %.1f s to %s", len(labels), time.time() - t0, target)
    log.info("per category:\n%s", labels["category"].value_counts().to_string())


if __name__ == "__main__":
    main()
