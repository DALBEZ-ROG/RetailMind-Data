package com.retailmind.seguridad;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auditoria.AuditoriaService;

/**
 * Crear y eliminar ROLES PROPIOS desde la pantalla de Permisos del Motor
 * (script 87).
 *
 * <h2>Para qué existe</h2>
 * Para poder experimentar sin tocar los 9 roles que ya funcionan. El
 * administrador crea, por ejemplo, {@code PRUEBA}, le enciende privilegios con
 * los interruptores, se lo asigna a un usuario y comprueba en vivo qué ve y qué
 * no. Cuando termina, lo borra y no queda rastro.
 *
 * <h2>Las seis piezas</h2>
 * Un {@code CREATE ROLE} a secas da un rol INSERVIBLE en este sistema, y falla
 * en silencio. La función del script 87 monta las seis a la vez: el rol NOLOGIN,
 * el {@code USAGE} sobre el esquema (el script 19 se lo revocó a PUBLIC), la
 * membresía en {@code retailmind_app} (sin ella {@code SET LOCAL ROLE} falla),
 * las 7 ventanas horarias (sin ellas el login queda bloqueado), <b>una política
 * RLS por cada una de las 50 tablas con RLS</b> —sin ella el rol leería CERO
 * FILAS sin un solo error— y la fila en {@code rol}.
 *
 * <h2>Qué NO puede</h2>
 * Tocar los 9 del sistema: la función exige {@code es_sistema = false}, fila
 * propia en {@code rol_personalizado} <b>y</b> la marca del catálogo. Y no
 * elimina un rol que tenga usuarios asignados.
 */
@Service
public class RolPersonalizadoService {

    private final JdbcTemplate pg;
    private final AuditoriaService auditoria;

    public RolPersonalizadoService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                                   AuditoriaService auditoria) {
        this.pg = pg;
        this.auditoria = auditoria;
    }

    /** Los roles creados desde aquí, con su rol base y cuántos usuarios tienen. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listar() {
        return pg.queryForList("""
                SELECT r.id, r.codigo, r.nombre, r.activo,
                       rp.rol_grupo, rp.rol_base_codigo, rp.fecha_creacion,
                       (SELECT count(*) FROM usuario_rol ur WHERE ur.rol_id = r.id) AS usuarios,
                       (SELECT count(*) FROM pg_policies p
                         WHERE p.policyname = 'pol_' || rp.rol_grupo)               AS politicas,
                       (SELECT count(*) FROM grupo_horario gh
                         WHERE gh.rol_grupo = rp.rol_grupo)                         AS ventanas
                FROM rol r
                JOIN rol_personalizado rp ON rp.rol_id = r.id
                WHERE NOT r.es_sistema
                ORDER BY r.codigo""");
    }

    /**
     * Crea el rol con sus seis piezas. Todo en UNA transacción con la fila de
     * auditoría: un rol creado sin rastro —o un rastro de un rol que no llegó a
     * crearse— serían las dos mitades del mismo fallo.
     */
    @Transactional
    public Map<String, Object> crear(String codigo, String nombre, String rolBase) {
        String cod = exigir(codigo, "codigo").toUpperCase();
        String nom = exigir(nombre, "nombre");
        String base = (rolBase == null || rolBase.isBlank()) ? null : rolBase.trim().toUpperCase();

        Map<String, Object> creado = pg.queryForMap("""
                SELECT rol_id, rol_grupo, politicas, ventanas
                FROM fn_admin_crear_rol(?, ?, ?, ?)""",
                cod, nom, base, usuarioActual());

        Map<String, Object> despues = new LinkedHashMap<>(creado);
        despues.put("codigo", cod);
        despues.put("nombre", nom);
        despues.put("rol_base", base);
        auditoria.registrar("rol_personalizado",
                ((Number) creado.get("rol_id")).longValue(), "INSERT", null, despues);

        Map<String, Object> sobre = new LinkedHashMap<>(despues);
        sobre.put("mensaje", "Rol «" + cod + "» creado: rol de motor "
                + creado.get("rol_grupo") + ", " + creado.get("politicas")
                + " políticas RLS y " + creado.get("ventanas") + " ventanas horarias. "
                + "Todavía no tiene ningún privilegio: enciéndelos con los interruptores.");
        return sobre;
    }

    /** Elimina el rol y sus seis piezas. Solo personalizados y sin usuarios. */
    @Transactional
    public Map<String, Object> eliminar(String codigo) {
        String cod = exigir(codigo, "codigo").toUpperCase();

        // Se comprueba ANTES de consultar la ficha: un queryForMap sin filas
        // lanza EmptyResultDataAccessException, que el handler global traduce a
        // un 404 pelado — y «no encontrado» no explica que lo que pasa es que
        // ese rol es del SISTEMA y no se elimina nunca.
        List<Map<String, Object>> ficha = pg.queryForList("""
                SELECT r.id AS rol_id, r.codigo, r.nombre, r.es_sistema,
                       rp.rol_grupo, rp.rol_base_codigo
                FROM rol r LEFT JOIN rol_personalizado rp ON rp.rol_id = r.id
                WHERE r.codigo = ?""", cod);

        if (ficha.isEmpty()) {
            throw new java.util.NoSuchElementException("No existe el rol " + cod);
        }
        Map<String, Object> antes = ficha.get(0);
        if (Boolean.TRUE.equals(antes.get("es_sistema")) || antes.get("rol_grupo") == null) {
            throw new IllegalStateException(
                    "ROL PROTEGIDO. «" + cod + "» es uno de los 9 roles del sistema: no se "
                    + "elimina. Desde aquí solo se borran los roles creados en esta pantalla.");
        }

        Map<String, Object> borrado = pg.queryForMap(
                "SELECT rol_grupo, politicas, ventanas FROM fn_admin_eliminar_rol(?)", cod);

        auditoria.registrar("rol_personalizado",
                ((Number) antes.get("rol_id")).longValue(), "DELETE", antes, borrado);

        Map<String, Object> sobre = new LinkedHashMap<>(borrado);
        sobre.put("codigo", cod);
        sobre.put("mensaje", "Rol «" + cod + "» eliminado junto a sus "
                + borrado.get("politicas") + " políticas RLS, sus "
                + borrado.get("ventanas") + " ventanas horarias y todos sus privilegios.");
        return sobre;
    }

    private Long usuarioActual() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof com.retailmind.auth.AppUserPrincipal p) {
            return p.getUsuarioId();
        }
        return null;
    }

    private static String exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("El campo «" + campo + "» es requerido");
        }
        return valor.trim();
    }
}
