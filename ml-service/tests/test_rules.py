"""The Python port must agree with the Java TransactionClassifier (same cases as TransactionClassifierTests)."""

from __future__ import annotations

from app.training.rules import classify


def c(description: str, channel: str = "POS", direction: str = "DEBIT", currency: str = "TRY",
      account_type: str = "CHECKING", mcc: str | None = None, internal: bool = False) -> str:
    return classify(description, channel, direction, currency, account_type, mcc, internal)


def test_merchant_category_drives_spending() -> None:
    assert c("BEREKET MRKT 6482 YENIMAHALLE ANK", mcc="GROCERY") == "GROCERY"


def test_cash_rent_transfers_and_interest() -> None:
    assert c("ATM NAKIT CEKIM 1 KADIKOY IST", channel="ATM") == "CASH_WITHDRAWAL"
    assert c("FAST MERVE SEN EV KIRASI OCAK", channel="EFT_FAST") == "RENT"
    assert c("FAST ALI KAYA HEDIYE", channel="EFT_FAST") == "TRANSFER"
    assert c("AKDI FAIZ", channel="SYSTEM", account_type="CREDIT_CARD") == "INTEREST"
    assert c("MEVDUAT FAIZI NET", channel="SYSTEM", direction="CREDIT", account_type="TIME_DEPOSIT") == "INTEREST"


def test_income_credits() -> None:
    assert c("SGK EMEKLI AYLIGI 03/2025", channel="EFT_FAST", direction="CREDIT") == "SALARY"
    assert c("FAST GELEN ALI KAYA FATURA ODEMESI", channel="EFT_FAST", direction="CREDIT") == "TRANSFER"


def test_internal_and_foreign_currency_fall_back() -> None:
    assert c("VADELI HESABA VIRMAN", channel="EFT_FAST", internal=True) == "TRANSFER"
    assert c("DOVIZ ALIS USD", channel="EFT_FAST", direction="CREDIT", currency="USD") == "TRANSFER"
    # Without the merchant category (text only) a grocery purchase is not recognised.
    assert c("BEREKET MRKT 6482 YENIMAHALLE ANK") == "TRANSFER"
