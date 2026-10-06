package com.tradecore.strategylab;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/strategy-lab")
public class StrategyLabController {

    private final StrategyBacktestService service;

    public StrategyLabController(StrategyBacktestService service) {
        this.service = service;
    }

    @PostMapping("/backtests")
    public BacktestResponse runBacktest(@Valid @RequestBody BacktestRequest request) {
        return service.run(request);
    }
}
