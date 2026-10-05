package com.tradecore.risk;

import com.tradecore.account.TradingAccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RiskQueryService {
    private final TradingAccountRepository accountRepository;
    private final RiskLimitRepository riskLimitRepository;

    public RiskQueryService(TradingAccountRepository accountRepository, RiskLimitRepository riskLimitRepository) {
        this.accountRepository = accountRepository;
        this.riskLimitRepository = riskLimitRepository;
    }

    @Transactional(readOnly = true)
    public List<RiskLimitResponse> currentLimits(String authenticatedEmail) {
        String email = authenticatedEmail.trim().toLowerCase(Locale.ROOT);
        var account = accountRepository.findByUser_Email(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Trading account was not found"));
        return riskLimitRepository.findCurrentForAccount(account.getId(), Instant.now()).stream()
                .map(RiskLimitResponse::from)
                .toList();
    }
}
