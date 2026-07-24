package com.retailmind.auth;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.retailmind.auth.PostgresUserRepository.PgUsuario;
import com.retailmind.dto.LoginRequestDTO;
import com.retailmind.dto.LoginResponseDTO;
import com.retailmind.seguridad.AccesoService;

/**
 * Autenticación contra la tabla usuario de PostgreSQL (BCrypt).
 * El "username" del login es el EMAIL del usuario.
 * El JWT lleva el código del rol (rol.codigo) como authority, con lo que las
 * reglas hasAuthority("ADMIN") de SecurityConfig siguen funcionando.
 */
@Service
public class AuthService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final PostgresUserRepository usuarioRepo;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final AccesoService accesoService;

    // @Lazy en el encoder: el bean PasswordEncoder vive en SecurityConfig, que
    // depende de JwtAuthenticationFilter, que a su vez usa este AuthService como
    // UserDetailsService. La inicialización perezosa rompe ese ciclo (mismo
    // patrón que tenía antes el AuthenticationManager).
    public AuthService(PostgresUserRepository usuarioRepo,
                       JwtUtil jwtUtil,
                       @Lazy PasswordEncoder passwordEncoder,
                       AccesoService accesoService) {
        this.usuarioRepo = usuarioRepo;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = passwordEncoder;
        this.accesoService = accesoService;
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

    /**
     * Inicio de sesión. Determina el resultado y, cuando falla, el MOTIVO
     * detallado (correo no registrado, contraseña incorrecta, usuario inactivo
     * o acceso fuera del horario del rol) para el registro de acceso — sin
     * cambiar NUNCA la respuesta genérica al cliente (eso lo hace
     * AuthController). Cada intento, exitoso o fallido, se registra en
     * log_acceso; un fallo del registro se degrada con elegancia y jamás rompe
     * el login.
     *
     * La verificación de contraseña usa el MISMO BCryptPasswordEncoder de
     * siempre, así que la decisión de autenticar es idéntica a la anterior; lo
     * único que se añade es la clasificación del motivo y la compuerta horaria.
     */
    public LoginResponseDTO login(LoginRequestDTO request, String ip, String userAgent) {
        String email = request.getUsername();
        try {
            PgUsuario usuario = usuarioRepo.findByEmail(email).orElse(null);
            if (usuario == null) {
                throw new LoginFallidoException(LoginFallidoException.EMAIL_NO_REGISTRADO, null);
            }
            if (!usuario.activo()) {
                throw new LoginFallidoException(LoginFallidoException.USUARIO_INACTIVO, usuario.id());
            }
            if (request.getPassword() == null
                    || !passwordEncoder.matches(request.getPassword(), usuario.passwordHash())) {
                throw new LoginFallidoException(LoginFallidoException.PASSWORD_INCORRECTO, usuario.id());
            }
            if (!accesoService.estaEnHorario(usuario.rolCodigo())) {
                throw new LoginFallidoException(LoginFallidoException.FUERA_HORARIO, usuario.id());
            }

            AppUserPrincipal principal = loadUserByUsername(email);
            usuarioRepo.actualizarUltimoAcceso(principal.getUsuarioId());

            String token        = jwtUtil.generateToken(principal);
            String refreshToken = jwtUtil.generateRefreshToken(principal);

            registrarIntento(principal.getUsuarioId(), email, true, null, ip, userAgent);

            return new LoginResponseDTO(
                    token,
                    refreshToken,
                    principal.getUsername(),
                    principal.getNombre(),
                    rolDe(principal),
                    jwtUtil.getExpirationTime()
            );
        } catch (LoginFallidoException e) {
            registrarIntento(e.getUsuarioId(), email, false, e.getMotivo(), ip, userAgent);
            throw e;
        }
    }

    /**
     * Registro del intento en log_acceso, blindado: un fallo de auditoría no
     * puede impedir que un usuario legítimo entre ni convertir un rechazo en un
     * error 500. Solo queda en el log de aplicación.
     */
    private void registrarIntento(Long usuarioId, String email, boolean exitoso,
                                  String motivo, String ip, String userAgent) {
        try {
            accesoService.registrar(usuarioId, email, exitoso, motivo, ip, userAgent);
        } catch (Exception ex) {
            logger.warn("No se pudo registrar el intento de acceso de '{}' (exitoso={}): {}",
                    email, exitoso, ex.getMessage());
        }
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
