package com.fintwin.macro.service;

import com.fintwin.macro.domain.MacroIndicator;
import com.fintwin.macro.domain.MacroIndicatorType;
import com.fintwin.macro.dto.MacroIndicatorDto;
import com.fintwin.macro.repository.MacroIndicatorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class MacroIndicatorService {

    private final MacroIndicatorRepository repository;

    public MacroIndicatorService(MacroIndicatorRepository repository) {
        this.repository = repository;
    }

    /** All series, or a single one when {@code indicator} is given; ordered by period. */
    public List<MacroIndicatorDto> list(MacroIndicatorType indicator) {
        List<MacroIndicator> rows = indicator == null
                ? repository.findAllByOrderByPeriodAscIndicatorAsc()
                : repository.findByIndicatorOrderByPeriodAsc(indicator);
        return rows.stream().map(MacroIndicatorService::toDto).toList();
    }

    private static MacroIndicatorDto toDto(MacroIndicator m) {
        return new MacroIndicatorDto(m.getIndicator(), m.getPeriod(), m.getValue(), m.getSource());
    }
}
