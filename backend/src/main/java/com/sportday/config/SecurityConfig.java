package com.sportday.config;

import com.sportday.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                // The mark-entry grid is staff-only and must be matched before
                // the public GET /api/events/** rule that follows it. An input
                // helper is staff for exactly this: keying in marks is their job.
                .requestMatchers("/api/events/*/marks").hasAnyRole("ADMIN", "MANAGER", "HELPER")
                .requestMatchers(HttpMethod.GET, "/api/events/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/results/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/results/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/results/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/results/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.POST, "/api/events/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PUT, "/api/events/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.PATCH, "/api/events/**").hasAnyRole("ADMIN", "MANAGER")
                .requestMatchers(HttpMethod.DELETE, "/api/events/**").hasAnyRole("ADMIN", "MANAGER")
                // Marking sheets are printed by staff, not by students — and an
                // input helper prints them, which is the other half of the job.
                //
                // Note the ordering trap this rule sits inside: the public
                // `GET /api/events/**` rule above matches
                // `GET /api/events/{id}/sheets.pdf` first, so on the URL layer a
                // helper's sheet download would look public. It is the
                // @PreAuthorize on EventGroupController that closes it, and that
                // is why the annotation there has to name HELPER as well — the
                // two layers are a conjunction, and method security runs second.
                .requestMatchers("/api/groups/*/sheet.pdf", "/api/events/*/sheets.pdf", "/api/sheets.pdf")
                    .hasAnyRole("ADMIN", "MANAGER", "HELPER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Helping a student enter or withdraw is what a TEACHER is for. The
                // endpoints are a separate family from the ADMIN-only
                // /api/admin/students/** ones on purpose, so a teacher is given the
                // entry paths — and only those — rather than the whole student
                // register API. The class rule itself is enforced in the service
                // (TeacherClassService), not here.
                .requestMatchers("/api/teacher/**").hasAnyRole("ADMIN", "TEACHER")
                .requestMatchers("/api/users/**").authenticated()
                .requestMatchers("/api/enrollments/**").authenticated()
                .requestMatchers("/api/groups/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
