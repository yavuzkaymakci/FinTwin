package com.fintwin.merchant.repository;

import com.fintwin.merchant.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    List<Merchant> findByCityAndCategoryCode(String city, String categoryCode);
}
