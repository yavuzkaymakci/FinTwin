package com.fintwin.customer.repository;

import com.fintwin.RepositoryTest;
import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.CustomerEvent;
import com.fintwin.customer.domain.CustomerEventType;
import com.fintwin.customer.domain.CustomerStatus;
import com.fintwin.customer.domain.Segment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDate;
import java.util.List;

import static com.fintwin.TestEntities.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@RepositoryTest
class CustomerRepositoryTests {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private CustomerEventRepository eventRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void persistsCustomerAndCountsPerSegment() {
        customerRepository.saveAll(List.of(customer(Segment.STUDENT), customer(Segment.RETIREE),
                customer(Segment.RETIREE)));
        em.flush();
        em.clear();

        assertThat(customerRepository.countPerSegment())
                .extracting(CustomerRepository.SegmentCount::getSegment, CustomerRepository.SegmentCount::getCustomers)
                .containsExactlyInAnyOrder(tuple(Segment.STUDENT, 1L), tuple(Segment.RETIREE, 2L));
        assertThat(customerRepository.countBySegment(Segment.RETIREE)).isEqualTo(2);
    }

    @Test
    void roundTripsAllColumns() {
        Customer saved = customerRepository.save(customer(Segment.WHITE_COLLAR));
        saved.changeStatus(CustomerStatus.CHURNED);
        em.flush();
        em.clear();

        Customer loaded = customerRepository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(CustomerStatus.CHURNED);
        assertThat(loaded.getMonthlyNetIncome()).isEqualByComparingTo("45000");
        assertThat(loaded.getCustomerSince()).isEqualTo(LocalDate.of(2020, 3, 15));
    }

    @Test
    void returnsEventsInChronologicalOrder() {
        Customer c = customerRepository.save(customer(Segment.MIN_WAGE));
        eventRepository.save(new CustomerEvent(c, CustomerEventType.DEFAULT, LocalDate.of(2025, 6, 10),
                null, null, 90, "card 90+ dpd"));
        eventRepository.save(new CustomerEvent(c, CustomerEventType.LATE_PAYMENT, LocalDate.of(2025, 4, 10),
                null, null, 30, null));
        em.flush();
        em.clear();

        assertThat(eventRepository.findByCustomerIdOrderByEventDateAscIdAsc(c.getId()))
                .extracting(CustomerEvent::getEventType)
                .containsExactly(CustomerEventType.LATE_PAYMENT, CustomerEventType.DEFAULT);
        assertThat(eventRepository.countByEventType(CustomerEventType.DEFAULT)).isEqualTo(1);
    }
}
