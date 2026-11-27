package io.openisoworld.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Everything is closed except the ticket endpoint and the WebSocket. There is no way to log in
 * yet (accounts come in v0.3), so the rest is denied rather than "authenticated".
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/tickets").permitAll()
                        .requestMatchers(HttpMethod.GET, "/ws").permitAll() // the ticket is checked on the handshake
                        .requestMatchers("/error").permitAll() // or every error would turn into a 403
                        .anyRequest().denyAll())
                // No cookies and no HTTP sessions: the WebSocket has its ticket, and from v0.3 the API
                // has a JWT in the Authorization header. Without cookies there is nothing for CSRF to abuse.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }
}
