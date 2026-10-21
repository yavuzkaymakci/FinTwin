package com.fintwin.transaction.repository;

import com.fintwin.RepositoryTest;
import com.fintwin.account.domain.Account;
import com.fintwin.account.domain.Currency;
import com.fintwin.account.repository.AccountRepository;
import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.Segment;
import com.fintwin.customer.repository.CustomerRepository;
import com.fintwin.merchant.domain.Merchant;
import com.fintwin.merchant.repository.MerchantCategoryRepository;
import com.fintwin.merchant.repository.MerchantRepository;
import com.fintwin.transaction.domain.BankTransaction;
import com.fintwin.transaction.domain.Channel;
import com.fintwin.transaction.domain.Direction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static com.fintwin.TestEntities.customer;
import static org.assertj.core.api.Assertions.assertThat;

@RepositoryTest
class BankTransactionRepositoryTests {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantCategoryRepository categoryRepository;

    @Autowired
    private BankTransactionRepository transactionRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    void pagesThroughCustomerTransactionsInHalfOpenTimeRange() {
        Customer c = customerRepository.save(customer(Segment.WHITE_COLLAR));
        Account checking = accountRepository.save(Account.checking(c, Currency.TRY, LocalDate.of(2020, 1, 1)));
        Merchant market = merchantRepository.save(
                new Merchant("KARDESLER MARKET", categoryRepository.findByCode("GROCERY").orElseThrow(), "ISTANBUL"));
        Instant base = Instant.parse("2025-03-01T00:00:00Z");
        for (int day = 0; day < 5; day++) {
            transactionRepository.save(new BankTransaction(checking, c, market, new BigDecimal("150.25"),
                    Currency.TRY, Direction.DEBIT, Channel.POS, 1, "KARDESLER MKT 4123 KADIKOY IST",
                    base.plusSeconds(day * 86_400L)));
        }
        em.flush();
        em.clear();

        Page<BankTransaction> page = transactionRepository
                .findByCustomerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(c.getId(), base,
                        base.plusSeconds(3 * 86_400L), PageRequest.of(0, 2, Sort.by("occurredAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().getFirst().getMerchant().getName()).isEqualTo("KARDESLER MARKET");
        assertThat(page.getContent().getFirst().getOccurredAt()).isEqualTo(base);
        assertThat(transactionRepository.countByCustomerId(c.getId())).isEqualTo(5);
    }
}
