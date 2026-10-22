package com.fintwin.customer.controller;

import com.fintwin.common.dto.PageResponse;
import com.fintwin.customer.dto.CustomerDetailDto;
import com.fintwin.customer.dto.CustomerListItem;
import com.fintwin.customer.dto.CustomerListQuery;
import com.fintwin.customer.service.CustomerNotFoundException;
import com.fintwin.customer.service.CustomerListService;
import com.fintwin.customer.service.CustomerQueryService;
import com.fintwin.transaction.dto.TransactionDto;
import com.fintwin.transaction.service.TransactionQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerQueryService customerQueryService;
    private final TransactionQueryService transactionQueryService;
    private final CustomerListService customerListService;

    public CustomerController(CustomerQueryService customerQueryService,
                              TransactionQueryService transactionQueryService,
                              CustomerListService customerListService) {
        this.customerQueryService = customerQueryService;
        this.transactionQueryService = transactionQueryService;
        this.customerListService = customerListService;
    }

    /**
     * Customers with their latest risk score and twin features. {@code search} matches the customer id or the city;
     * {@code sort} is one of id, pd, churn, riskGrade, age, income, utilization, buffer, debt.
     */
    @GetMapping
    public PageResponse<CustomerListItem> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String segment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minGrade,
            @RequestParam(required = false) Integer maxGrade,
            @RequestParam(defaultValue = "id") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return customerListService.list(new CustomerListQuery(search, segment, status, minGrade, maxGrade, sort,
                "desc".equalsIgnoreCase(direction), page, size));
    }

    @GetMapping("/{id}")
    public CustomerDetailDto getCustomer(@PathVariable Long id) {
        return customerQueryService.getCustomer(id);
    }

    /** Transactions between {@code from} (inclusive) and {@code to} (exclusive), as UTC calendar dates. */
    @GetMapping("/{id}/transactions")
    public PageResponse<TransactionDto> getTransactions(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (!customerQueryService.exists(id)) {
            throw new CustomerNotFoundException(id);
        }
        return transactionQueryService.findForCustomer(id,
                from.atStartOfDay().toInstant(ZoneOffset.UTC), to.atStartOfDay().toInstant(ZoneOffset.UTC),
                page, size);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
