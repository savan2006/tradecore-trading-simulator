package com.tradecore.account;

import com.tradecore.identity.User;
import com.tradecore.identity.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AccountQueryService {
    private final UserRepository userRepository;
    private final TradingAccountRepository accountRepository;

    public AccountQueryService(UserRepository userRepository, TradingAccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse currentAccount(String authenticatedEmail, boolean admin) {
        User user = userRepository.findByEmail(authenticatedEmail.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User account not found"));
        TradingAccount account = accountRepository.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Trading account not found"));
        return AccountResponse.from(account, admin);
    }
}
