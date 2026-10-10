package com.tradecore.foundation.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
import com.tradecore.identity.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiRateLimiter rateLimiter,
            @Value("${tradecore.cors.allowed-origins:http://localhost:3000}") String allowedOrigins,
            @Value("${tradecore.security.trusted-proxy-cidrs:}") String trustedProxyCidrs) throws Exception {
        AuthenticationEntryPoint rateLimitedEntryPoint = (request, response, failure) -> {
            boolean admitted = rateLimiter.allow("authentication-failure", "ip:" + request.getRemoteAddr());
            if (!admitted) {
                response.setStatus(429);
                response.setHeader("Retry-After", Long.toString(rateLimiter.retryAfterSeconds("authentication-failure")));
            } else {
                response.setStatus(401);
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"TradeCore\"");
            }
        };
        return http
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/register").permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers("/ws/market-quotes").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(rateLimitedEntryPoint))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(false)))
                .addFilterAfter(new ApiRateLimitFilter(rateLimiter,
                        Arrays.stream(trustedProxyCidrs.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList()),
                        BasicAuthenticationFilter.class)
                .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${tradecore.cors.allowed-origins:http://localhost:3000}") String allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        cors.setAllowedMethods(List.of(HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                HttpMethod.DELETE.name(), HttpMethod.OPTIONS.name()));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, "Idempotency-Key"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

    @Bean
    UserDetailsService userDetailsService(
            UserRepository userRepository,
            @Value("${tradecore.security.user:tradecore-dev}") String username,
            @Value("${tradecore.security.password:}") String password,
            @Value("${tradecore.security.dev-user-enabled:true}") boolean devUserEnabled,
            PasswordEncoder passwordEncoder) {
        if (devUserEnabled && password.isBlank()) {
            throw new IllegalStateException("TRADECORE_SECURITY_PASSWORD is required when the development user is enabled");
        }
        String configuredPasswordHash = devUserEnabled ? passwordEncoder.encode(password) : null;
        return login -> {
            if (devUserEnabled && username.equals(login)) {
                return User.withUsername(username)
                        .password(configuredPasswordHash)
                        .roles("USER")
                        .build();
            }
            return userRepository.findByEmail(login.trim().toLowerCase(Locale.ROOT))
                    .<org.springframework.security.core.userdetails.UserDetails>map(appUser ->
                            User.withUsername(appUser.getEmail())
                                    .password(appUser.getPasswordHash())
                                    .roles(appUser.getRole())
                                    .disabled(!"ACTIVE".equals(appUser.getStatus()))
                                    .build())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
