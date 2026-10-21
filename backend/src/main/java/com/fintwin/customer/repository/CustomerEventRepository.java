package com.fintwin.customer.repository;

import com.fintwin.customer.domain.CustomerEvent;
import com.fintwin.customer.domain.CustomerEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerEventRepository extends JpaRepository<CustomerEvent, Long> {

    List<CustomerEvent> findByCustomerIdOrderByEventDateAscIdAsc(Long customerId);

    long countByEventType(CustomerEventType eventType);
}
