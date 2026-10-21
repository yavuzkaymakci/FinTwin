package com.fintwin.macro.dto;

import com.fintwin.macro.domain.MacroIndicatorType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MacroIndicatorDto(MacroIndicatorType indicator, LocalDate period, BigDecimal value, String source) {
}
