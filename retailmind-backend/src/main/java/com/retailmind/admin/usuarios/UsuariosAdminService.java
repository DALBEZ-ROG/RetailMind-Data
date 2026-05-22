package com.retailmind.admin.usuarios;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.retailmind.auth.ClickHouseUserRepository;
import com.retailmind.auth.UsuarioSistema;

@Service
public class UsuariosAdminService {

    private final ClickHouseUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate ch;

    public UsuariosAdminService(ClickHouseUserRepository usuarioRepo,
                                PasswordEncoder passwordEncoder,
                                @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
        this.ch = ch;
    }

    public List<Map<String, Object>> listarUsuarios() {
        return usuarioRepo.findAll().stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("nombre", u.getNombre());
                    m.put("rol", u.getRol().name());
                    m.put("activo", u.getActivo());
                    m.put("fechaCreacion", u.getFechaCreacion());
                    return m;
                })
                .collect(Collectors.toList());
    }

    public void crearUsuario(String username, String password, String email, String rol) {
        if (usuarioRepo.existsByUsername(username)) {
            throw new IllegalStateException("El usuario '" + username + "' ya existe");
        }
        UsuarioSistema u = new UsuarioSistema();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setNombre(email != null ? email : "");
        u.setRol(UsuarioSistema.Rol.valueOf(rol.toUpperCase()));
        u.setActivo(true);
        usuarioRepo.save(u);
    }

    public void eliminarUsuario(String username) {
        if ("admin".equals(username)) {
            throw new IllegalStateException("No se puede eliminar al usuario admin");
        }
        if (!usuarioRepo.existsByUsername(username)) {
            throw new NoSuchElementException("Usuario no encontrado: " + username);
        }
        usuarioRepo.deleteByUsername(username);
    }

    public void toggleActivo(String username) {
        if ("admin".equals(username)) {
            throw new IllegalStateException("No se puede desactivar al usuario admin");
        }
        UsuarioSistema u = usuarioRepo.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("Usuario no encontrado"));
        int nuevoEstado = u.getActivo() ? 0 : 1;
        ch.execute("ALTER TABLE retailmind.usuarios_sistema UPDATE activo = " + nuevoEstado +
                " WHERE username = '" + username + "' SETTINGS mutations_sync = 1");
    }
}
