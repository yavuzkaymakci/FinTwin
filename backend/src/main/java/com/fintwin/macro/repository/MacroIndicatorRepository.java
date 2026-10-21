package com.fintwin.macro.repository;

import com.fintwin.macro.domain.MacroIndicator;
import com.fintwin.macro.domain.MacroIndicatorType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MacroIndicatorRepository extends JpaRepository<MacroIndicator, Long> {

    List<MacroIndicator> findByIndicatorOrderByPeriodAsc(MacroIndicatorType indicator);

    List<MacroIndicator> findAllByOrderByPeriodAscIndicatorAsc();

    Optional<MacroIndicator> findByIndicatorAndPeriod(MacroIndicatorType indicator, LocalDate period);
}
