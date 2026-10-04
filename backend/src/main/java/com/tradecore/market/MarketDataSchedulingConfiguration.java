package com.tradecore.market;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketDataRefreshProperties.class)
public class MarketDataSchedulingConfiguration {
}
