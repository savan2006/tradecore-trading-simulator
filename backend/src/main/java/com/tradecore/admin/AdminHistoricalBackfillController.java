package com.tradecore.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.tradecore.market.HistoricalBackfillRequest;
import com.tradecore.market.HistoricalBackfillService;
import com.tradecore.market.HistoricalBackfillStatus;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import com.tradecore.audit.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only job control; provider calls stay in the background ingestion service. */
@Tag(name = "Admin")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/admin/market-data/backfill")
public class AdminHistoricalBackfillController {
    private final HistoricalBackfillService backfill;
    private final AuditService audit;

    public AdminHistoricalBackfillController(HistoricalBackfillService backfill, AuditService audit) {
        this.backfill = backfill;
        this.audit = audit;
    }

    @Operation(summary = "Start", responses = {@ApiResponse(responseCode = "202", description = "Accepted for processing"), @ApiResponse(responseCode = "400", description = "Invalid request or parameters"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required"), @ApiResponse(responseCode = "409", description = "The request conflicts with the current resource state")})

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    HistoricalBackfillStatus start(@Valid @RequestBody(required = false) HistoricalBackfillRequest request,
            Authentication authentication) {
        try {
            HistoricalBackfillStatus status = backfill.start(request);
            audit.recordOutcome(authentication.getName(), "ADMIN_HISTORICAL_BACKFILL_START", "HISTORICAL_BACKFILL",
                    status.jobId(), "SUCCESS");
            return status;
        } catch (RuntimeException failure) {
            audit.recordOutcome(authentication.getName(), "ADMIN_HISTORICAL_BACKFILL_START", "HISTORICAL_BACKFILL",
                    null, "FAILURE");
            throw failure;
        }
    }

    @Operation(summary = "Status", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required"), @ApiResponse(responseCode = "403", description = "Administrator access is required")})

    @GetMapping
    HistoricalBackfillStatus status(Authentication authentication) {
        return backfill.status();
    }
}
