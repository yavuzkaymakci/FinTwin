package com.fintwin.generator.catalog;

/**
 * Transaction categories; ids and codes mirror the {@code merchant_category} rows seeded by V3.
 */
public enum Category {
    GROCERY(1),
    FUEL(2),
    RESTAURANT(3),
    ECOMMERCE(4),
    UTILITIES(5),
    TELECOM(6),
    RENT(7),
    EDUCATION(8),
    HEALTH(9),
    ENTERTAINMENT(10),
    TRAVEL(11),
    CLOTHING(12),
    ELECTRONICS(13),
    TRANSPORT(14),
    CASH_WITHDRAWAL(15),
    SALARY(16),
    TRANSFER(17),
    LOAN(18),
    CARD_PAYMENT(19),
    FX(20);

    private final int id;

    Category(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public String code() {
        return name();
    }
}
