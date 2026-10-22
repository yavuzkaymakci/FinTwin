package com.fintwin.customer.service;

import com.fintwin.common.dto.PageResponse;
import com.fintwin.customer.dto.CustomerListItem;
import com.fintwin.customer.dto.CustomerListQuery;
import com.fintwin.customer.repository.CustomerListRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerListService {

    private final CustomerListRepository repository;

    public CustomerListService(CustomerListRepository repository) {
        this.repository = repository;
    }

    public PageResponse<CustomerListItem> list(CustomerListQuery query) {
        if (!CustomerListRepository.validSort(query.sort())) {
            throw new IllegalArgumentException("Unknown sort key: " + query.sort());
        }
        if (query.size() < 1 || query.size() > 200 || query.page() < 0) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and 200");
        }
        List<CustomerListItem> rows = repository.find(query);
        long total = repository.count(query);
        return new PageResponse<>(rows, query.page(), query.size(), total,
                (int) Math.ceil(total / (double) query.size()));
    }
}
