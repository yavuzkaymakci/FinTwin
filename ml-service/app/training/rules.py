"""Python port of the twin's rule-based ``TransactionClassifier`` (backend
``com.fintwin.twin.projection.TransactionClassifier``), used as the baseline for the categorisation model.

The Java rules return a flow (spending, outflow, income, internal, neutral) and sometimes a category; this port maps
that outcome onto the 21 label categories the way the twin effectively treats it. The loan-movement matching of the
twin (which needs the loan event stream) is not available here, so loan movements fall into ``TRANSFER``.
"""

from __future__ import annotations

import re

SALARY = re.compile(r"\b(MAAS|EMEKLI|EMK|SGK|KYK|BURS|ISKUR|ISSIZLIK|PRIM|IKRAMIYE|TAZMINAT|AYLIG|AYLIK)")
RENT = re.compile(r"\bKIRA")
FALLBACK = "TRANSFER"


def classify(description: str | None, channel: str, direction: str, currency: str, account_type: str | None,
             merchant_category: str | None, internal: bool) -> str:
    """Category the twin's rules assign. ``merchant_category`` is the acquirer category (MCC group), if known."""
    text = (description or "").upper()
    credit = direction == "CREDIT"
    if internal:
        return FALLBACK
    if currency != "TRY":
        return FALLBACK
    if channel == "SYSTEM":
        return "INTEREST"
    if credit:
        if account_type == "CREDIT_CARD":
            return FALLBACK
        return "SALARY" if SALARY.search(text) else FALLBACK
    if merchant_category:
        return merchant_category
    if channel == "ATM":
        return "CASH_WITHDRAWAL"
    if RENT.search(text):
        return "RENT"
    return FALLBACK
