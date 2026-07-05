package com.retailmind.auth;

import java.util.List;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.retailmind.auth.PostgresUserRepository.PgUsuario;
import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;

/**
 * Autenticación contra la tabla usuario de PostgreSQL (BCrypt).
 * El "username" del login es el EMAIL del usuario.
 * El JWT lleva el código del rol (rol.codigo) como authority, con lo que las
 * reglas hasAuthority("ADMIN") de SecurityConfig siguen funcionando.
 */
@Service
public class AuthService implements UserDetailsService {

    private final PostgresUserRepository usuarioRepo;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthService(PostgresUserRepository usuarioRepo,
                       JwtUtil jwtUtil,
                       @Lazy AuthenticationManager authenticationManager) {
        this.usuarioRepo = usuarioRepo;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public AppUserPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
        PgUsuario usuario = usuarioRepo.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + email));

        if (!usuario.activo()) {
            throw new UsernameNotFoundException("Usuario desactivado: " + email);
        }

        String rol = usuario.rolCodigo() != null ? usuario.rolCodigo() : "SIN_ROL";
        String nombreCompleto = usuario.apellido() != null
                ? usuario.nombre() + " " + usuario.apellido()
                : usuario.nombre();

        return new AppUserPrincipal(
                usuario.email(),
                usuario.passwordHash(),
                List.of(new SimpleGrantedAuthority(rol)),
                usuario.id(),
                usuario.clienteId(),
                usuario.rolCodigo(),
                nombreCompleto);
    }

    public LoginResponseDTO login(LoginRequestDTO request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword()));

        AppUserPrincipal principal = loadUserByUsername(request.getUsername());
        usuarioRepo.actualizarUltimoAcceso(principal.getUsuarioId());

        String token        = jwtUtil.generateToken(principal);
        String refreshToken = jwtUtil.generateRefreshToken(principal);

        return new LoginResponseDTO(
                token,
                refreshToken,
                principal.getUsername(),
                principal.getNombre(),
                rolDe(principal),
                jwtUtil.getExpirationTime()
        );
    }

    public LoginResponseDTO refreshToken(String refreshToken) {
        String email = jwtUtil.extractUsername(refreshToken);
        AppUserPrincipal principal = loadUserByUsername(email);

        if (!jwtUtil.isTokenValid(refreshToken, principal)) {
            throw new RuntimeException("Refresh token invalido o expirado");
        }

        String newToken = jwtUtil.generateToken(principal);

        return new LoginResponseDTO(
                newToken,
                refreshToken,
                principal.getUsername(),
                principal.getNombre(),
                rolDe(principal),
                jwtUtil.getExpirationTime()
        );
    }

    private static String rolDe(UserDetails userDetails) {
        return userDetails.getAuthorities().iterator().next().getAuthority();
    }
}
