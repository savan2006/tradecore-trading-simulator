package com.tradecore.market.nse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NseMcpProperties.class)
class NseMcpConfiguration {
}
