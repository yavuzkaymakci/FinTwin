package com.fintwin.account.repository;

import com.fintwin.RepositoryTest;
import com.fintwin.account.domain.Account;
import com.fintwin.account.domain.AccountType;
import com.fintwin.account.domain.CardStatement;
import com.fintwin.account.domain.Currency;
import com.fintwin.customer.domain.Customer;
import com.fintwin.customer.domain.Segment;
import com.fintwin.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static com.fintwin.TestEntities.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@RepositoryTest
class AccountRepositoryTests {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private CardStatementRepository statementRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager em;

    @Test
    void storesEachAccountTypeWithItsAttributes() {
        Customer c = customerRepository.save(customer(Segment.WHITE_COLLAR));
        LocalDate opened = LocalDate.of(2023, 1, 10);
        accountRepository.save(Account.checking(c, Currency.TRY, opened));
        accountRepository.save(Account.checking(c, Currency.USD, opened));
        accountRepository.save(Account.timeDeposit(c, opened, new BigDecimal("0.42"), opened.plusDays(32)));
        Account card = accountRepository.save(Account.creditCard(c, opened, new BigDecimal("60000"), 5, 15));
        em.flush();
        em.clear();

        assertThat(accountRepository.findByCustomerIdOrderByIdAsc(c.getId()))
                .extracting(Account::getAccountType, Account::getCurrency)
                .containsExactly(
                        tuple(AccountType.CHECKING, Currency.TRY),
                        tuple(AccountType.CHECKING, Currency.USD),
                        tuple(AccountType.TIME_DEPOSIT, Currency.TRY),
                        tuple(AccountType.CREDIT_CARD, Currency.TRY));
        Account loadedCard = accountRepository.findById(card.getId()).orElseThrow();
        assertThat(loadedCard.getCreditLimit()).isEqualByComparingTo("60000");
        assertThat(loadedCard.getStatementDay()).isEqualTo(5);
    }

    @Test
    void storesStatementsInDateOrder() {
        Customer c = customerRepository.save(customer(Segment.MIN_WAGE));
        Account card = accountRepository.save(
                Account.creditCard(c, LocalDate.of(2024, 1, 1), new BigDecimal("20000"), 10, 20));
        CardStatement feb = new CardStatement(card, LocalDate.of(2025, 2, 10), LocalDate.of(2025, 2, 20),
                new BigDecimal("5000"), new BigDecimal("1000"));
        feb.recordPayment(new BigDecimal("1000"), LocalDate.of(2025, 2, 19), 0);
        statementRepository.save(feb);
        statementRepository.save(new CardStatement(card, LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 20),
                new BigDecimal("4000"), new BigDecimal("800")));
        em.flush();
        em.clear();

        assertThat(statementRepository.findByAccountIdOrderByStatementDateAsc(card.getId()))
                .extracting(CardStatement::getStatementDate)
                .containsExactly(LocalDate.of(2025, 1, 10), LocalDate.of(2025, 2, 10));
    }

    @Test
    void schemaRejectsCardWithoutLimit() {
        Customer c = customerRepository.save(customer(Segment.STUDENT));
        em.flush();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO account (customer_id, account_type, currency, opened_at) VALUES (?, 'CREDIT_CARD', 'TRY', ?)",
                c.getId(), LocalDate.of(2024, 1, 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_account_card_fields");
    }
}
