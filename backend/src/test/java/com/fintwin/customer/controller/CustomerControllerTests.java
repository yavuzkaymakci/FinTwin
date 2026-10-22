package com.fintwin.customer.controller;

import com.fintwin.account.domain.AccountType;
import com.fintwin.account.domain.Currency;
import com.fintwin.account.dto.AccountDto;
import com.fintwin.common.dto.PageResponse;
import com.fintwin.config.SecurityConfig;
import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.OccupationGroup;
import com.fintwin.customer.domain.Segment;
import com.fintwin.customer.dto.CustomerDetailDto;
import com.fintwin.customer.dto.CustomerSummaryDto;
import com.fintwin.customer.service.CustomerNotFoundException;
import com.fintwin.customer.service.CustomerQueryService;
import com.fintwin.transaction.domain.Channel;
import com.fintwin.transaction.domain.Direction;
import com.fintwin.transaction.dto.TransactionDto;
import com.fintwin.transaction.service.TransactionQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@Import(SecurityConfig.class)
class CustomerControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerQueryService customerQueryService;

    @MockitoBean
    private com.fintwin.customer.service.CustomerListService customerListService;

    @MockitoBean
    private TransactionQueryService transactionQueryService;

    @Test
    void returnsCustomerDetail() throws Exception {
        given(customerQueryService.getCustomer(7L)).willReturn(new CustomerDetailDto(
                new CustomerSummaryDto(7L, 30, "IZMIR", OccupationGroup.IT_PROFESSIONAL, Segment.WHITE_COLLAR,
                        new BigDecimal("80000.00"), LocalDate.of(2019, 5, 1), CustomerStatus.ACTIVE),
                List.of(new AccountDto(11L, AccountType.CREDIT_CARD, Currency.TRY, new BigDecimal("1200.50"),
                        LocalDate.of(2019, 5, 1), null, null, null, new BigDecimal("100000"), 5, 15)),
                List.of(), List.of()));

        mockMvc.perform(get("/api/customers/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.segment").value("WHITE_COLLAR"))
                .andExpect(jsonPath("$.accounts[0].type").value("CREDIT_CARD"))
                .andExpect(jsonPath("$.accounts[0].creditLimit").value(100000));
    }

    @Test
    void returns404ForUnknownCustomer() throws Exception {
        given(customerQueryService.getCustomer(99L)).willThrow(new CustomerNotFoundException(99L));

        mockMvc.perform(get("/api/customers/99")).andExpect(status().isNotFound());
    }

    @Test
    void returnsTransactionPageForHalfOpenDateRange() throws Exception {
        given(customerQueryService.exists(7L)).willReturn(true);
        given(transactionQueryService.findForCustomer(eq(7L), eq(Instant.parse("2025-01-01T00:00:00Z")),
                eq(Instant.parse("2025-02-01T00:00:00Z")), eq(0), anyInt()))
                .willReturn(new PageResponse<>(List.of(new TransactionDto(1L, 11L, new BigDecimal("99.90"),
                        Currency.TRY, Direction.DEBIT, Channel.POS, 1, "KARDESLER MKT 4123 KADIKOY IST",
                        "KARDESLER MARKET", Instant.parse("2025-01-03T10:15:00Z"))), 0, 50, 1, 1));

        mockMvc.perform(get("/api/customers/7/transactions").param("from", "2025-01-01").param("to", "2025-02-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rawDescription").value("KARDESLER MKT 4123 KADIKOY IST"));
    }

    @Test
    void rejectsInvertedDateRange() throws Exception {
        given(customerQueryService.exists(7L)).willReturn(true);
        given(transactionQueryService.findForCustomer(eq(7L), any(),
                any(), anyInt(), anyInt()))
                .willThrow(new IllegalArgumentException("'from' must be before 'to'"));

        mockMvc.perform(get("/api/customers/7/transactions").param("from", "2025-02-01").param("to", "2025-01-01"))
                .andExpect(status().isBadRequest());
    }
}
