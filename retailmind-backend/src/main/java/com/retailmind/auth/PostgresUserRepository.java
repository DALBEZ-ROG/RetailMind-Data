package com.retailmind.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceso a usuario / rol / usuario_rol en PostgreSQL (reemplaza el camino
 * viejo de ClickHouseUserRepository para autenticación).
 *
 * El login corre ANTES de conocer el rol: esas consultas usan los privilegios
 * directos de retailmind_app (bootstrap). Las operaciones de gestión llegan
 * con JWT de ADMIN y el aspecto PgSessionRoleAspect asume grp_administrador.
 * El cliente_id se resuelve con fn_cliente_id_de_usuario() (SECURITY DEFINER)
 * porque la tabla cliente tiene RLS.
 */
@Repository
public class PostgresUserRepository {

    public record PgUsuario(Long id, String email, String passwordHash, String nombre,
                            String apellido, boolean activo, String rolCodigo, Long clienteId) {}

    private static final String BASE_SELECT = """
            SELECT u.id, u.email, u.password_hash, u.nombre, u.apellido, u.activo,
                   r.codigo AS rol_codigo,
                   fn_cliente_id_de_usuario(u.id) AS cliente_id
            FROM usuario u
            LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
            LEFT JOIN rol r ON r.id = ur.rol_id AND r.activo
            """;

    private static final RowMapper<PgUsuario> ROW_MAPPER = (rs, rowNum) -> new PgUsuario(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("nombre"),
            rs.getString("apellido"),
            rs.getBoolean("activo"),
            rs.getString("rol_codigo"),
            rs.getObject("cliente_id") == null ? null : rs.getLong("cliente_id"));

    private final JdbcTemplate pg;

    public PostgresUserRepository(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    @Transactional(readOnly = true)
    public Optional<PgUsuario> findByEmail(String email) {
        List<PgUsuario> res = pg.query(
                BASE_SELECT + " WHERE lower(u.email) = lower(?) LIMIT 1", ROW_MAPPER, email);
        return res.isEmpty() ? Optional.empty() : Optional.of(res.get(0));
    }

    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        Long n = pg.queryForObject(
                "SELECT count(*) FROM usuario WHERE lower(email) = lower(?)", Long.class, email);
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public boolean rolExiste(String codigo) {
        Long n = pg.queryForObject(
                "SELECT count(*) FROM rol WHERE codigo = ? AND activo", Long.class, codigo);
        return n != null && n > 0;
    }

    @Transactional(readOnly = true)
    public List<PgUsuario> findAll() {
        return pg.query(BASE_SELECT + " ORDER BY u.email", ROW_MAPPER);
    }

    /** Crea usuario + vínculo usuario_rol. El password ya viene en BCrypt. */
    @Transactional
    public long crearUsuario(String email, String passwordHash, String nombre,
                             String apellido, String rolCodigo) {
        Long id = pg.queryForObject("""
                INSERT INTO usuario (email, password_hash, nombre, apellido, email_verificado, activo)
                VALUES (?, ?, ?, ?, true, true)
                RETURNING id
                """, Long.class, email, passwordHash, nombre, apellido);
        pg.update("""
                INSERT INTO usuario_rol (usuario_id, rol_id)
                SELECT ?, id FROM rol WHERE codigo = ?
                ON CONFLICT (usuario_id, rol_id) DO NOTHING
                """, id, rolCodigo);
        return id;
    }

    @Transactional
    public void actualizarUltimoAcceso(long usuarioId) {
        pg.update("UPDATE usuario SET ultimo_acceso = now() WHERE id = ?", usuarioId);
    }

    @Transactional
    public void eliminarPorEmail(String email) {
        // usuario_rol cae por ON DELETE CASCADE
        pg.update("DELETE FROM usuario WHERE lower(email) = lower(?)", email);
    }
}
