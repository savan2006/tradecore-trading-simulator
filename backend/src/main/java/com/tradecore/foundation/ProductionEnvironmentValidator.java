package com.tradecore.foundation;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile("production")
final class ProductionEnvironmentValidator implements EnvironmentAware {
    private static final List<String> REQUIRED = List.of(
            "SPRING_DATASOURCE_URL",
            "SPRING_DATASOURCE_USERNAME",
            "SPRING_DATASOURCE_PASSWORD",
            "SPRING_DATA_REDIS_HOST",
            "SPRING_DATA_REDIS_PORT",
            "SPRING_DATA_REDIS_PASSWORD",
            "TRADECORE_CORS_ALLOWED_ORIGINS",
            "TRADECORE_WEBSOCKET_ALLOWED_ORIGINS");

    @Override
    public void setEnvironment(Environment environment) {
        List<String> missing = REQUIRED.stream()
                .filter(name -> environment.getProperty(name) == null || environment.getProperty(name).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required production environment variables: "
                    + missing.stream().collect(Collectors.joining(", ")));
        }
    }
}
