package com.fintwin;

import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class TestEntities {

    private TestEntities() {
    }

    public static Customer customer(Segment segment) {
        return new Customer(35, "ISTANBUL", OccupationGroup.OFFICE_WORKER, segment,
                new BigDecimal("45000.00"), LocalDate.of(2020, 3, 15));
    }
}
