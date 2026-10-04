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
import jakarta.servlet.DispatcherType;
import com.tradecore.identity.UserRepository;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import java.util.Locale;

@Configuration
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/api/v1/auth/register").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    UserDetailsService userDetailsService(
            UserRepository userRepository,
            @Value("${tradecore.security.user}") String username,
            @Value("${tradecore.security.password}") String password,
            PasswordEncoder passwordEncoder) {
        String configuredPasswordHash = passwordEncoder.encode(password);
        return login -> {
            if (username.equals(login)) {
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
