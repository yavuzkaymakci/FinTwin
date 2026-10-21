package com.fintwin.macro.controller;

import com.fintwin.config.SecurityConfig;
import com.fintwin.macro.domain.MacroIndicatorType;
import com.fintwin.macro.dto.MacroIndicatorDto;
import com.fintwin.macro.service.MacroIndicatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MacroIndicatorController.class)
@Import(SecurityConfig.class)
class MacroIndicatorControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MacroIndicatorService service;

    @Test
    void filtersByIndicator() throws Exception {
        given(service.list(MacroIndicatorType.USD_TRY)).willReturn(List.of(
                new MacroIndicatorDto(MacroIndicatorType.USD_TRY, LocalDate.of(2025, 1, 1),
                        new BigDecimal("35.5500"), "SEED")));

        mockMvc.perform(get("/api/macro-indicators").param("indicator", "USD_TRY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].period").value("2025-01-01"))
                .andExpect(jsonPath("$[0].value").value(35.55));
    }
}
