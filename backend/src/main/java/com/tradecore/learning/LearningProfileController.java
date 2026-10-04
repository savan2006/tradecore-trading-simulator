package com.tradecore.learning;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learning/companies")
public class LearningProfileController {
    private final LearningProfileService service;
    private final LearningCompanyOverviewService overviewService;

    public LearningProfileController(LearningProfileService service, LearningCompanyOverviewService overviewService) {
        this.service = service;
        this.overviewService = overviewService;
    }

    @GetMapping
    public List<LearningProfileResponse> list() { return service.list(); }

    @GetMapping("/{symbol}")
    public LearningProfileResponse get(@PathVariable String symbol) { return service.get(symbol); }

    @GetMapping("/{symbol}/overview")
    public LearningCompanyOverviewResponse overview(@PathVariable String symbol,
            @RequestParam(required = false) Integer limit) {
        return overviewService.overview(symbol, limit);
    }
}
