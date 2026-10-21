package com.fintwin.customer.repository;

import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.Segment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    long countBySegment(Segment segment);

    @Query("select c.segment as segment, count(c) as customers from Customer c group by c.segment order by c.segment")
    List<SegmentCount> countPerSegment();

    interface SegmentCount {
        Segment getSegment();

        long getCustomers();
    }
}
