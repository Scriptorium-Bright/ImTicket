package org.example.ticket.util.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ticket.security.filter.JwtFilter;
import org.example.ticket.security.filter.MetamaskAuthenticationFilter;
import org.example.ticket.security.jwt.JwtUtil;
import org.example.ticket.security.provider.JwtTokenProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean; // Import 추가
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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
    public SecurityFilterChain filterChain(HttpSecurity http, MetamaskAuthenticationFilter metamaskAuthenticationFilter)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        http
                // 주입받은 필터 인스턴스를 사용
                .addFilterAt(metamaskAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtFilter(jwtUtil), MetamaskAuthenticationFilter.class);

        http.authorizeHttpRequests(authorizeRequests ->
                authorizeRequests
                        .requestMatchers( // 인증 없이 접근 허용할 경로 명시
                                "/api/user/nonce",          // Nonce 요청
                                "/api/user/register",       // 회원가입 요청
                                "/api/sms/certificate",     // SMS 인증 요청
                                "/api/user/signature/verify", // 로그인 처리 자체 (필터에서 인증 담당)
                                "/api/user/validate/{walletAddress}", // 지갑 주소 유효성 검사 (필요시)
                                "/"
                                // 다른 public API 경로가 있다면 추가
                        ).permitAll() // 위 경로들은 인증 없이 허용
                        .anyRequest().permitAll() // 그 외 모든 요청은 인증 필요
        );

        return http.build();
    }

    @Bean
    public MetamaskAuthenticationFilter metamaskAuthenticationFilter(AuthenticationManager authenticationManager,
                                                                     JwtTokenProvider jwtTokenProvider,
                                                                     ObjectMapper objectMapper) {
        MetamaskAuthenticationFilter filter = new MetamaskAuthenticationFilter(authenticationManager, jwtTokenProvider, objectMapper);
        filter.setAuthenticationFailureHandler(metamaskAuthenticationFailureHandler()); // 커스텀 실패 핸들러 사용
        return filter;
    }


    public AuthenticationFailureHandler metamaskAuthenticationFailureHandler() {
        return new AuthenticationFailureHandler() {
            // SecurityConfig에 이미 ObjectMapper 빈이 있다면 주입받아 사용하거나, 여기서 새로 생성
            private final ObjectMapper objectMapper = new ObjectMapper();

            @Override
            public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                                AuthenticationException exception) throws IOException, ServletException {
                log.warn("Metamask Authentication Failed: {}", exception.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");

                Map<String, Object> data = new HashMap<>();
                data.put("timestamp", System.currentTimeMillis());
                data.put("status", HttpServletResponse.SC_UNAUTHORIZED);
                data.put("error", "Unauthorized");
                // 실제 예외 메시지를 포함시켜 프론트엔드에서 참고할 수 있도록 함
                data.put("message", "certified failed: " + exception.getLocalizedMessage());
                data.put("path", request.getRequestURI());

                response.getOutputStream().println(objectMapper.writeValueAsString(data));
            }
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}