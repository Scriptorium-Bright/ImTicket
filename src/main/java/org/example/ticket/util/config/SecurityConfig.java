package org.example.ticket.util.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.filter.JwtFilter;
import org.example.ticket.security.filter.MetamaskAuthenticationFilter;
import org.example.ticket.security.handler.LoginFailureHandler;
import org.example.ticket.security.handler.LoginSuccessHandler;
import org.example.ticket.security.jwt.JwtUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final AuthenticationConfiguration authenticationConfiguration;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Bean
    public AuthenticationManager authManager() throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public LoginSuccessHandler loginSuccessHandler() {
        return new LoginSuccessHandler(jwtUtil, objectMapper);
    }

    @Bean
    public LoginFailureHandler loginFailureHandler() {
        return new LoginFailureHandler(objectMapper);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, MetamaskAuthenticationFilter metamaskAuthenticationFilter)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http
                .addFilterAt(metamaskAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtFilter(jwtUtil), MetamaskAuthenticationFilter.class);

        http.authorizeHttpRequests(authorizeRequests -> authorizeRequests
                .requestMatchers(
                        "/",
                        "/error",
                        "/uploads/**",
                        "/actuator/health",
                        "/actuator/info",
                        "/actuator/prometheus"
                ).permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/api/user/nonce",
                        "/api/user/validate/**",
                        "/api/performance/intro",
                        "/api/performance/intro/**",
                        "/api/venue/halls",
                        "/api/seats/**"
                ).permitAll()
                .requestMatchers(HttpMethod.POST,
                        "/api/user/register",
                        "/api/user/signature/verify",
                        "/api/sms/certificate",
                        "/api/sms/verify"
                ).permitAll()
                .anyRequest().authenticated()
        );

        return http.build();
    }

    @Bean
    public MetamaskAuthenticationFilter metamaskAuthenticationFilter(AuthenticationManager authenticationManager,
            ObjectMapper objectMapper) {
        MetamaskAuthenticationFilter filter = new MetamaskAuthenticationFilter(authenticationManager, objectMapper,
                loginSuccessHandler(), loginFailureHandler());
        return filter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
