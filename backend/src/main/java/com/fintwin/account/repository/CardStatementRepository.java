package com.fintwin.account.repository;

import com.fintwin.account.domain.CardStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardStatementRepository extends JpaRepository<CardStatement, Long> {

    List<CardStatement> findByAccountIdOrderByStatementDateAsc(Long accountId);
}
