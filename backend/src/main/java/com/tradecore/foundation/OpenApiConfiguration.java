package com.tradecore.foundation;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "TradeCore API",
                version = "v1",
                description = "Educational virtual trading API using real NSE market data and simulated money, orders, and execution. TradeCore does not connect to a real broker or handle real money."),
        tags = {
                @Tag(name = "Auth", description = "Account registration and authentication"),
                @Tag(name = "Account", description = "Authenticated account details"),
                @Tag(name = "Market", description = "Persisted NSE market data and session status"),
                @Tag(name = "Orders", description = "Simulated order placement and history"),
                @Tag(name = "Trades", description = "Completed simulated executions"),
                @Tag(name = "Portfolio", description = "Virtual balances and positions"),
                @Tag(name = "Risk", description = "Risk limits applied to simulated orders"),
                @Tag(name = "Watchlists", description = "Saved company watchlists"),
                @Tag(name = "Alerts", description = "Price alerts based on persisted quotes"),
                @Tag(name = "Notifications", description = "Account notifications"),
                @Tag(name = "Learning", description = "Company learning profiles and comparison"),
                @Tag(name = "Strategy Lab", description = "Historical strategy simulations"),
                @Tag(name = "Journal", description = "Post-trade learning journal"),
                @Tag(name = "Performance", description = "Simulated trading performance"),
                @Tag(name = "Admin", description = "Administrator operations and audit data"),
                @Tag(name = "System", description = "Service readiness status")
        })
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
public class OpenApiConfiguration {
}
