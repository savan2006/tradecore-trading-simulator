package com.tradecore.market.nse;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class NseMcpPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "tradecore.market-data.nse-mcp.market-live-endpoint=http://127.0.0.1:8091/cmmkt/mcp",
                    "tradecore.market-data.nse-mcp.bhavcopy-endpoint=http://localhost:8091/bhavcopy/cm/mcp",
                    "tradecore.market-data.nse-mcp.connect-timeout=PT3S",
                    "tradecore.market-data.nse-mcp.request-timeout=PT9S");

    @Test
    void bindsNseEndpointsAndTimeoutsFromConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(NseMcpProperties.class);
            assertThat(properties.marketLiveEndpoint().getPath()).isEqualTo("/cmmkt/mcp");
            assertThat(properties.bhavcopyEndpoint().getPath()).isEqualTo("/bhavcopy/cm/mcp");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(9));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NseMcpProperties.class)
    static class PropertiesConfiguration {
    }
}
