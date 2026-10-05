package com.tradecore.risk;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/risk")
public class RiskController {
    private final RiskQueryService queryService;

    public RiskController(RiskQueryService queryService) { this.queryService = queryService; }

    @GetMapping("/me")
    public List<RiskLimitResponse> currentLimits(Authentication authentication) {
        return queryService.currentLimits(authentication.getName());
    }
}
