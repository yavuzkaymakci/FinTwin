package com.fintwin.merchant.repository;

import com.fintwin.merchant.domain.MerchantCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantCategoryRepository extends JpaRepository<MerchantCategory, Integer> {

    Optional<MerchantCategory> findByCode(String code);
}
