package com.tradecore.account;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Account")
@SecurityRequirement(name = "basicAuth")
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountQueryService accountQueryService;

    public AccountController(AccountQueryService accountQueryService) {
        this.accountQueryService = accountQueryService;
    }

    @Operation(summary = "Current Account", responses = {@ApiResponse(responseCode = "200", description = "Successful response"), @ApiResponse(responseCode = "401", description = "Authentication is required")})

    @GetMapping("/me")
    public AccountResponse currentAccount(Authentication authentication) {
        return accountQueryService.currentAccount(authentication.getName());
    }
}
