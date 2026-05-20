package com.retailmind.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.retailmind.entity.UsuarioSistema;

@Repository
public class ClickHouseUserRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<UsuarioSistema> ROW_MAPPER = (rs, rowNum) -> {
        UsuarioSistema u = new UsuarioSistema();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setPassword(rs.getString("password"));
        u.setNombre(rs.getString("nombre"));
        u.setRol(UsuarioSistema.Rol.valueOf(rs.getString("rol")));
        u.setActivo(rs.getInt("activo") == 1);
        u.setFechaCreacion(rs.getString("fecha_creacion"));
        return u;
    };

    public ClickHouseUserRepository(@Qualifier("clickHouseJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UsuarioSistema> findByUsername(String username) {
        List<UsuarioSistema> results = jdbc.query(
                "SELECT * FROM retailmind.usuarios_sistema WHERE username = ?",
                ROW_MAPPER, username);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public boolean existsByUsername(String username) {
        Long count = jdbc.queryForObject(
                "SELECT count() FROM retailmind.usuarios_sistema WHERE username = ?",
                Long.class, username);
        return count != null && count > 0;
    }

    public void save(UsuarioSistema usuario) {
        if (usuario.getId() == null || usuario.getId().isEmpty()) {
            usuario.setId(UUID.randomUUID().toString());
        }
        if (usuario.getFechaCreacion() == null) {
            usuario.setFechaCreacion(java.time.LocalDateTime.now().toString());
        }
        String sql = "INSERT INTO retailmind.usuarios_sistema " +
                "(id, username, password, nombre, rol, activo, fecha_creacion) VALUES (" +
                "'" + escape(usuario.getId()) + "', " +
                "'" + escape(usuario.getUsername()) + "', " +
                "'" + escape(usuario.getPassword()) + "', " +
                "'" + escape(usuario.getNombre() != null ? usuario.getNombre() : "") + "', " +
                "'" + escape(usuario.getRol().name()) + "', " +
                (usuario.getActivo() ? 1 : 0) + ", " +
                "'" + escape(usuario.getFechaCreacion()) + "')";
        jdbc.execute(sql);
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("'", "\\'");
    }

    public List<UsuarioSistema> findAll() {
        return jdbc.query("SELECT * FROM retailmind.usuarios_sistema ORDER BY username", ROW_MAPPER);
    }

    public void deleteByUsername(String username) {
        jdbc.execute(String.format(
                "ALTER TABLE retailmind.usuarios_sistema DELETE WHERE username = '%s' SETTINGS mutations_sync = 1",
                username));
    }
}
