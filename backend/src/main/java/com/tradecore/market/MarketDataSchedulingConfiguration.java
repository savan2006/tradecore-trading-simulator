package com.tradecore.market;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.tradecore.execution.ExecutionSchedulingProperties;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({MarketDataRefreshProperties.class, ExecutionSchedulingProperties.class})
public class MarketDataSchedulingConfiguration {
}
