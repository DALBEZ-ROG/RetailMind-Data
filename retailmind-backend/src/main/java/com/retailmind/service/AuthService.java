package com.retailmind.service;

import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;
import com.retailmind.entity.UsuarioSistema;
import com.retailmind.repository.UsuarioSistemaRepository;
import com.retailmind.security.JwtUtil;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService implements UserDetailsService {

    private final UsuarioSistemaRepository usuarioRepo;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthService(UsuarioSistemaRepository usuarioRepo,
                       JwtUtil jwtUtil,
                       @Lazy AuthenticationManager authenticationManager) {
        this.usuarioRepo = usuarioRepo;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UsuarioSistema usuario = usuarioRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + username));

        if (!usuario.getActivo()) {
            throw new UsernameNotFoundException("Usuario desactivado: " + username);
        }

        return new User(
                usuario.getUsername(),
                usuario.getPassword(),
                List.of(new SimpleGrantedAuthority(usuario.getRol().name()))
        );
    }

    public LoginResponseDTO login(LoginRequestDTO request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(), request.getPassword()));

        UserDetails userDetails = loadUserByUsername(request.getUsername());
        UsuarioSistema usuario = usuarioRepo.findByUsername(request.getUsername()).orElseThrow();

        String token        = jwtUtil.generateToken(userDetails);
        String refreshToken = jwtUtil.generateRefreshToken(userDetails);

        return new LoginResponseDTO(
                token,
                refreshToken,
                usuario.getUsername(),
                usuario.getNombre(),
                usuario.getRol().name(),
                jwtUtil.getExpirationTime()
        );
    }

    public LoginResponseDTO refreshToken(String refreshToken) {
        String username = jwtUtil.extractUsername(refreshToken);
        UserDetails userDetails = loadUserByUsername(username);

        if (!jwtUtil.isTokenValid(refreshToken, userDetails)) {
            throw new RuntimeException("Refresh token invalido o expirado");
        }

        UsuarioSistema usuario = usuarioRepo.findByUsername(username).orElseThrow();
        String newToken = jwtUtil.generateToken(userDetails);

        return new LoginResponseDTO(
                newToken,
                refreshToken,
                usuario.getUsername(),
                usuario.getNombre(),
                usuario.getRol().name(),
                jwtUtil.getExpirationTime()
        );
    }
}
