package com.payguard.reconciliation.config;

import com.payguard.reconciliation.security.InternalServiceTokenFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Locks down two previously open surfaces.
     *
     * <ul>
     *   <li>{@code /internal/**} was {@code permitAll()}, so anyone with network reach into the
     *       cluster could {@code POST /internal/v1/runs?date=...} and kick off a settlement run —
     *       hammering the Stripe API and rewriting reconciliation state. It now requires the shared
     *       internal service token, matching fraud-engine and user-service.
     *   <li>{@code /actuator/**} was fully open, exposing every enabled endpoint anonymously. Only
     *       the probe and scrape endpoints stay anonymous.
     * </ul>
     */
    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Value("${payguard.internal.service-token:}") String internalServiceToken)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/internal/**")
                        .hasAuthority(InternalServiceTokenFilter.ROLE)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterBefore(
                        new InternalServiceTokenFilter(internalServiceToken),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
