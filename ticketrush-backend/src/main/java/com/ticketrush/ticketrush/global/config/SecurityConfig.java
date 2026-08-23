package com.ticketrush.ticketrush.global.config;

import com.ticketrush.ticketrush.global.exception.ErrorCode;
import com.ticketrush.ticketrush.global.exception.ErrorResponse;
import com.ticketrush.ticketrush.global.jwt.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * JWT 기반 무상태 인증 설정 (decisions.md 3번 — 세션 대신 JWT를 쓰는 이유 참고).
 * 인증이 필요 없는 경로는 엔드포인트를 구현할 때마다 여기에 추가한다.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Value("${frontend.origin:http://localhost:5173}")
    private String frontendOrigin;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 데모용 프론트엔드(Vite 개발 서버)가 백엔드와 다른 포트(오리진)에서 돌기 때문에 필요하다.
     * Refresh Token을 httpOnly Cookie로 주고받으므로 allowCredentials가 필수이고,
     * 그러면 allowedOrigins에 "*"를 쓸 수 없어 프론트 오리진을 명시한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(frontendOrigin));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Entry-Token"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // refresh는 Access Token이 이미 만료된 상태에서 호출되므로 인증을 요구하면 안 된다.
                        // 대신 httpOnly Cookie의 Refresh Token을 Redis 값과 대조해 AuthService가 검증한다.
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 이벤트 조회는 로그인 없이도 가능(api-design.md 2번).
                        // 경로를 하나로 뭉뚱그리지 않고 메서드별로 명시한다 — /events/{id}/seats,
                        // /events/{id}/queue 처럼 다른 권한이 필요한 하위 경로가 뒤에 추가되기 때문.
                        .requestMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/events").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/events/*").hasRole("ORGANIZER")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/events/*").hasRole("ORGANIZER")
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 인증/인가 실패도 컨트롤러 예외와 같은 { code, message } 형식으로 응답한다. */
    private void writeError(HttpServletResponse response, ErrorCode errorCode) throws java.io.IOException {
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(errorCode));
    }
}
