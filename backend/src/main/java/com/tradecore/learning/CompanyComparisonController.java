package com.tradecore.learning;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/learning")
public class CompanyComparisonController {
    private final CompanyComparisonService service;

    public CompanyComparisonController(CompanyComparisonService service) {
        this.service = service;
    }

    @GetMapping("/compare")
    public CompanyComparisonResponse compare(@RequestParam String symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.compare(symbols, from, to);
    }
}
