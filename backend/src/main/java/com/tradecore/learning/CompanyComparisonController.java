package com.tradecore.learning;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Learning")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/learning")
public class CompanyComparisonController {
    private final CompanyComparisonService service;

    public CompanyComparisonController(CompanyComparisonService service) {
        this.service = service;
    }

    @Operation(summary = "Compare", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/compare")
    public CompanyComparisonResponse compare(@RequestParam String symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.compare(symbols, from, to);
    }
}
