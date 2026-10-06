package com.tradecore.admin;

import com.tradecore.market.HistoricalBackfillRequest;
import com.tradecore.market.HistoricalBackfillService;
import com.tradecore.market.HistoricalBackfillStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only job control; provider calls stay in the background ingestion service. */
@RestController
@RequestMapping("/api/v1/admin/market-data/backfill")
public class AdminHistoricalBackfillController {
    private final HistoricalBackfillService backfill;

    public AdminHistoricalBackfillController(HistoricalBackfillService backfill) {
        this.backfill = backfill;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    HistoricalBackfillStatus start(@RequestBody(required = false) HistoricalBackfillRequest request,
            Authentication authentication) {
        return backfill.start(request);
    }

    @GetMapping
    HistoricalBackfillStatus status(Authentication authentication) {
        return backfill.status();
    }
}
