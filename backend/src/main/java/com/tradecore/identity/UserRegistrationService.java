package com.tradecore.identity;

import com.tradecore.account.TradingAccount;
import com.tradecore.account.TradingAccountRepository;
import com.tradecore.audit.AuditService;
import com.tradecore.ledger.LedgerEntry;
import com.tradecore.ledger.LedgerEntryRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;

@Service
public class UserRegistrationService {
    private final UserRepository userRepository;
    private final TradingAccountRepository accountRepository;
    private final LedgerEntryRepository ledgerRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public UserRegistrationService(UserRepository userRepository, TradingAccountRepository accountRepository,
            LedgerEntryRepository ledgerRepository, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.ledgerRepository = ledgerRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public RegistrationResponse register(RegistrationRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        Instant now = Instant.now();
        User user = new User(email, passwordEncoder.encode(request.password()), request.displayName(), now);
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered", ex);
        }
        TradingAccount account = accountRepository.saveAndFlush(new TradingAccount(user, now));
        ledgerRepository.saveAndFlush(new LedgerEntry(account, TradingAccount.INITIAL_VIRTUAL_BALANCE, "INR",
                "Initial virtual capital", now));
        auditService.record(user.getEmail(), "USER_REGISTERED", "USER", user.getId(),
                "{\"accountId\":\"" + account.getId() + "\"}");
        return new RegistrationResponse(user.getId(), user.getEmail(), user.getDisplayName(), account.getId(),
                account.getStatus(), account.getCurrency(), account.getAvailableBalance(),
                account.getReservedBalance(), account.getCreatedAt());
    }
}
