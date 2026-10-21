package com.fintwin.macro.controller;

import com.fintwin.macro.domain.MacroIndicatorType;
import com.fintwin.macro.dto.MacroIndicatorDto;
import com.fintwin.macro.service.MacroIndicatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/macro-indicators")
public class MacroIndicatorController {

    private final MacroIndicatorService service;

    public MacroIndicatorController(MacroIndicatorService service) {
        this.service = service;
    }

    @GetMapping
    public List<MacroIndicatorDto> list(@RequestParam(required = false) MacroIndicatorType indicator) {
        return service.list(indicator);
    }
}
