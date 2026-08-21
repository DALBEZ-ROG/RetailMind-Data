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
                            String apellido, String telefono, boolean activo, String rolCodigo,
                            Long clienteId, java.time.OffsetDateTime fechaCreacion,
                            java.time.OffsetDateTime ultimoAcceso,
                            /** grp_* del rol PERSONALIZADO; null en los 9 del sistema. */
                            String rolMotor,
                            /** Rol del sistema al que imita para las RUTAS; null = ninguno. */
                            String rolBaseCodigo) {}

    /**
     * El LEFT JOIN a {@code rol_personalizado} (script 87) es lo que permite que
     * un rol creado en caliente funcione: trae el rol de motor a asumir y el rol
     * base que decide qué pantallas ve. Para los 9 del sistema no hay fila y las
     * dos columnas llegan NULL, así que el comportamiento es exactamente el de
     * antes.
     */
    private static final String BASE_SELECT = """
            SELECT u.id, u.email, u.password_hash, u.nombre, u.apellido, u.telefono, u.activo,
                   u.fecha_creacion, u.ultimo_acceso,
                   r.codigo AS rol_codigo,
                   rp.rol_grupo AS rol_motor,
                   rp.rol_base_codigo,
                   fn_cliente_id_de_usuario(u.id) AS cliente_id
            FROM usuario u
            LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
            LEFT JOIN rol r ON r.id = ur.rol_id AND r.activo
            LEFT JOIN rol_personalizado rp ON rp.rol_id = r.id
            """;

    private static final RowMapper<PgUsuario> ROW_MAPPER = (rs, rowNum) -> new PgUsuario(
            rs.getLong("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("nombre"),
            rs.getString("apellido"),
            rs.getString("telefono"),
            rs.getBoolean("activo"),
            rs.getString("rol_codigo"),
            rs.getObject("cliente_id") == null ? null : rs.getLong("cliente_id"),
            rs.getObject("fecha_creacion", java.time.OffsetDateTime.class),
            rs.getObject("ultimo_acceso", java.time.OffsetDateTime.class),
            rs.getString("rol_motor"),
            rs.getString("rol_base_codigo"));

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

    /** Lista blanca de roles asignables, tomada del propio motor. */
    @Transactional(readOnly = true)
    public List<java.util.Map<String, Object>> rolesActivos() {
        return pg.queryForList(
                "SELECT codigo, nombre FROM rol WHERE activo ORDER BY id");
    }

    @Transactional(readOnly = true)
    public List<PgUsuario> findAll() {
        return pg.query(BASE_SELECT + " ORDER BY u.email", ROW_MAPPER);
    }

    /** Crea usuario + vínculo usuario_rol. El password ya viene en BCrypt. */
    @Transactional
    public long crearUsuario(String email, String passwordHash, String nombre,
                             String apellido, String rolCodigo) {
        return crearUsuario(email, passwordHash, nombre, apellido, null, rolCodigo);
    }

    @Transactional
    public long crearUsuario(String email, String passwordHash, String nombre,
                             String apellido, String telefono, String rolCodigo) {
        Long id = pg.queryForObject("""
                INSERT INTO usuario (email, password_hash, nombre, apellido, telefono,
                                     email_verificado, activo)
                VALUES (?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), true, true)
                RETURNING id
                """, Long.class, email, passwordHash, nombre, apellido, telefono);
        pg.update("""
                INSERT INTO usuario_rol (usuario_id, rol_id)
                SELECT ?, id FROM rol WHERE codigo = ?
                ON CONFLICT (usuario_id, rol_id) DO NOTHING
                """, id, rolCodigo);
        asegurarFichaCliente(id, rolCodigo, email, nombre, apellido, telefono);
        return id;
    }

    /**
     * Da al usuario su ficha en `cliente` cuando el rol es CLIENTE.
     *
     * Sin esta fila el alta produce un usuario que ENTRA pero no es un cliente:
     * el login resuelve `cliente_id` uniendo `cliente.usuario_id`, y con eso en
     * null el aspecto {@code PgSessionRoleAspect} no fija `app.cliente_id`, que
     * es la variable de la que cuelga toda la RLS de la tienda. El resultado no
     * es un error claro sino un cliente a medias — `/api/perfil` responde 200
     * con `esCliente: false` y el perfil rebota con un 409 «solo para clientes
     * de la tienda»—, y la pantalla de administración no tiene forma de
     * avisarlo porque para ella el alta fue un éxito.
     *
     * Va en la MISMA transacción que el usuario y su rol a propósito: las tres
     * filas son un solo hecho, y un usuario sin ficha es precisamente el estado
     * que esto viene a impedir.
     *
     * No hizo falta ni un GRANT ni un script: `grp_administrador` ya tenía
     * INSERT sobre `cliente` y la política `pol_horario` lo cubre. El hueco era
     * solo de aplicación.
     *
     * El `ON CONFLICT` no cubre ningún caso conocido —el UNIQUE de
     * `usuario_id` y la comprobación de email duplicado del controlador ya
     * lo hacen imposible—: está para que un reintento del alta no convierta
     * un choque de clave en un 500.
     */
    private void asegurarFichaCliente(long usuarioId, String rolCodigo, String email,
                                      String nombre, String apellido, String telefono) {
        if (!"CLIENTE".equals(rolCodigo)) { return; }
        pg.update("""
                INSERT INTO cliente (usuario_id, nombre, apellido, email, telefono)
                VALUES (?, ?, NULLIF(?, ''), ?, NULLIF(?, ''))
                ON CONFLICT (usuario_id) DO NOTHING
                """, usuarioId, nombre, apellido, email, telefono);
    }

    @Transactional(readOnly = true)
    public Optional<PgUsuario> findById(long id) {
        List<PgUsuario> res = pg.query(BASE_SELECT + " WHERE u.id = ? LIMIT 1", ROW_MAPPER, id);
        return res.isEmpty() ? Optional.empty() : Optional.of(res.get(0));
    }

    /**
     * Datos de perfil del usuario. NO toca `email` (es la credencial de login
     * y la clave con la que le apunta media aplicación), NO toca
     * `password_hash` y NO escribe `fecha_actualizacion` (trg_usuario_touch).
     */
    @Transactional
    public void actualizarDatos(long id, String nombre, String apellido, String telefono) {
        pg.update("""
                UPDATE usuario
                SET nombre = ?, apellido = NULLIF(?, ''), telefono = NULLIF(?, '')
                WHERE id = ?""", nombre, apellido, telefono, id);
    }

    /**
     * Deja al usuario con EXACTAMENTE un rol. El código ya viene validado
     * contra la tabla `rol` por {@link #rolExiste(String)} (lista blanca del
     * propio motor), nunca se concatena en el SQL.
     */
    @Transactional
    public void asignarRolUnico(long id, String rolCodigo) {
        pg.update("""
                DELETE FROM usuario_rol
                WHERE usuario_id = ?
                  AND rol_id <> (SELECT id FROM rol WHERE codigo = ?)""", id, rolCodigo);
        pg.update("""
                INSERT INTO usuario_rol (usuario_id, rol_id)
                SELECT ?, id FROM rol WHERE codigo = ?
                ON CONFLICT (usuario_id, rol_id) DO NOTHING""", id, rolCodigo);
        // Aquí NO se crea ficha de cliente, y no es un olvido:
        // `UsuarioAdminService.modificar` prohibe con un 409 cruzar la frontera
        // CLIENTE / personal interno en los dos sentidos —la ficha y sus pedidos
        // quedarían huérfanos—, así que este método nunca recibe un cambio A o
        // DESDE cliente. Añadirla sería código muerto que además daría a
        // entender que la conversión es posible.
    }

    /** Baja/alta lógica: un usuario inactivo no puede iniciar sesión (AuthService). */
    @Transactional
    public void cambiarActivo(long id, boolean activo) {
        pg.update("UPDATE usuario SET activo = ? WHERE id = ?", activo, id);
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
