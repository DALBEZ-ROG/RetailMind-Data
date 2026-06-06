package com.retailmind.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Rutas publicas
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Catalogo publico (sin auth)
                .requestMatchers(HttpMethod.GET, "/api/catalogo/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/catalogo/eventos").permitAll()
                // Gestion de usuarios solo ADMIN
                .requestMatchers(HttpMethod.POST, "/api/auth/register").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/auth/usuarios").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/auth/usuarios/**").hasAuthority("ADMIN")
                // ETL solo para ADMIN
                .requestMatchers("/api/etl/**").hasAuthority("ADMIN")
                // Inicializacion solo para ADMIN
                .requestMatchers("/api/init/**").hasAuthority("ADMIN")
                // Gestion de datos solo para ADMIN
                .requestMatchers("/api/gestion/**").hasAuthority("ADMIN")
                // Admin usuarios y pedidos solo ADMIN
                .requestMatchers("/api/admin/**").hasAuthority("ADMIN")
                .requestMatchers("/api/pedidos/admin/**").hasAuthority("ADMIN")
                // Funnel solo ADMIN
                .requestMatchers("/api/funnel/**").hasAuthority("ADMIN")
                // Analytics avanzado solo ADMIN
                .requestMatchers("/api/analytics/region/**").hasAuthority("ADMIN")
                .requestMatchers("/api/analytics/dispositivo/**").hasAuthority("ADMIN")
                .requestMatchers("/api/analytics/trafico/**").hasAuthority("ADMIN")
                // Reportes solo ADMIN
                .requestMatchers("/api/reportes/**").hasAuthority("ADMIN")
                // Dashboard refrescar vistas solo ADMIN
                .requestMatchers(HttpMethod.POST, "/api/dashboard/refrescar-vistas").hasAuthority("ADMIN")
                // Perfil y recomendaciones — usuario autenticado (cualquier rol)
                .requestMatchers("/api/perfil/**").authenticated()
                .requestMatchers("/api/recomendaciones/**").authenticated()
                // Todo lo demas requiere autenticacion
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
