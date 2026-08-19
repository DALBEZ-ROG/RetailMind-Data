package com.retailmind.admin.red;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD de la RED LOGÍSTICA: bodegas, transportistas, métodos de envío, zonas y
 * tarifas.
 *
 * ── POR QUÉ EXISTE ESTA CLASE ──────────────────────────────────────────────
 * Estas cinco tablas sostienen el ciclo de venta entero —sin una bodega no se
 * puede crear un pedido, y sin zona y tarifa el checkout no encuentra
 * transportista— y hasta hoy **no tenían ni una sola ruta de escritura en el
 * backend**: solo se poblaban con los scripts de siembra. Consecuencias
 * medidas:
 *
 *   · una instalación nueva no podía tomar un pedido hasta que alguien
 *     ejecutara SQL a mano (defecto D-09, destapado al probar el estado E0);
 *   · una instalación en marcha no podía abrir una segunda bodega, contratar
 *     un transportista ni cambiar su cobertura de envío sin un DBA;
 *   · la pantalla de transferencias entre bodegas funcionaba… pero su operando
 *     no se podía crear desde la aplicación.
 *
 * ── SEGURIDAD ──────────────────────────────────────────────────────────────
 * El motor ya concedía a `grp_administrador` INSERT/UPDATE/DELETE sobre las
 * cinco, así que este cambio NO necesitó tocar un solo GRANT ni política: el
 * hueco estaba únicamente en la capa de aplicación. `SecurityConfig` reserva
 * las rutas a ADMIN y todo va dentro de `@Transactional`, para que
 * `PgSessionRoleAspect` asuma el rol y la escritura pase por la misma
 * autorización de motor que el resto del sistema.
 *
 * ── BAJA LÓGICA, NUNCA BORRADO ─────────────────────────────────────────────
 * No hay DELETE en ninguna de las cinco, a propósito. Una bodega o un
 * transportista está referenciado por pedidos, envíos y movimientos de kardex
 * históricos: borrarlo rompería la trazabilidad, y el motor lo impediría con
 * un error de clave foránea que al usuario no le dice nada. Se desactiva con
 * `activo = false`, que es lo que el resto del sistema ya interpreta —las
 * consultas de tarifa filtran por `activo`— y lo que hace el toggle del patrón
 * de interfaz.
 *
 * `fecha_actualizacion` NUNCA se escribe: la pone el trigger touch (regla 1).
 */
@Service
public class RedLogisticaService {

    private final JdbcTemplate pg;

    public RedLogisticaService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bodegas
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarBodegas() {
        return pg.queryForList("""
                SELECT b.id, b.codigo, b.nombre, b.ciudad_id, c.nombre AS ciudad,
                       b.direccion, b.telefono, b.es_principal, b.activo,
                       (SELECT count(*) FROM inventario i WHERE i.bodega_id = b.id)
                           AS posiciones_inventario
                FROM bodega b
                LEFT JOIN ciudad c ON c.id = b.ciudad_id
                ORDER BY b.es_principal DESC, b.nombre""");
    }

    @Transactional
    public long crearBodega(String codigo, String nombre, Long ciudadId,
                            String direccion, String telefono, Boolean esPrincipal) {
        exigirTexto(codigo, "codigo");
        exigirTexto(nombre, "nombre");
        boolean principal = Boolean.TRUE.equals(esPrincipal);
        if (principal) {
            despromoverPrincipales();
        }
        return idDe(pg.queryForObject("""
                INSERT INTO bodega (codigo, nombre, ciudad_id, direccion, telefono, es_principal)
                VALUES (?, ?, ?::bigint, ?, ?, ?) RETURNING id""",
                Long.class, codigo.trim(), nombre.trim(), ciudadId, texto(direccion),
                texto(telefono), principal));
    }

    @Transactional
    public void editarBodega(long id, String codigo, String nombre, Long ciudadId,
                             String direccion, String telefono, Boolean esPrincipal) {
        exigirTexto(codigo, "codigo");
        exigirTexto(nombre, "nombre");
        if (Boolean.TRUE.equals(esPrincipal)) {
            despromoverPrincipales();
        }
        exigir(pg.update("""
                UPDATE bodega
                   SET codigo = ?, nombre = ?, ciudad_id = ?::bigint,
                       direccion = ?, telefono = ?,
                       es_principal = COALESCE(?, es_principal)
                 WHERE id = ?""",
                codigo.trim(), nombre.trim(), ciudadId, texto(direccion),
                texto(telefono), esPrincipal, id), "bodega", id);
    }

    /**
     * Deja sin la marca de principal a la que la tuviera.
     *
     * No hay un UNIQUE que lo imponga en la tabla, así que dos bodegas podrían
     * quedar marcadas a la vez; y «la bodega principal» es la que el sistema
     * elige por defecto, de modo que dos serían una elección no determinista.
     * Se resuelve aquí, en la escritura, que es donde se sabe cuál acaba de
     * pedirse.
     */
    private void despromoverPrincipales() {
        pg.update("UPDATE bodega SET es_principal = false WHERE es_principal");
    }

    @Transactional
    public void activarBodega(long id, boolean activo) {
        exigir(pg.update("UPDATE bodega SET activo = ? WHERE id = ?", activo, id),
                "bodega", id);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Transportistas
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarTransportistas() {
        return pg.queryForList("""
                SELECT t.id, t.nombre, t.ruc, t.telefono, t.email, t.sitio_web,
                       t.url_seguimiento, t.activo,
                       (SELECT count(*) FROM metodo_envio m WHERE m.transportista_id = t.id)
                           AS metodos
                FROM transportista t ORDER BY t.nombre""");
    }

    @Transactional
    public long crearTransportista(String nombre, String ruc, String telefono,
                                   String email, String sitioWeb, String urlSeguimiento) {
        exigirTexto(nombre, "nombre");
        return idDe(pg.queryForObject("""
                INSERT INTO transportista (nombre, ruc, telefono, email, sitio_web, url_seguimiento)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Long.class, nombre.trim(), texto(ruc), texto(telefono), texto(email),
                texto(sitioWeb), texto(urlSeguimiento)));
    }

    @Transactional
    public void editarTransportista(long id, String nombre, String ruc, String telefono,
                                    String email, String sitioWeb, String urlSeguimiento) {
        exigirTexto(nombre, "nombre");
        exigir(pg.update("""
                UPDATE transportista
                   SET nombre = ?, ruc = ?, telefono = ?, email = ?,
                       sitio_web = ?, url_seguimiento = ?
                 WHERE id = ?""",
                nombre.trim(), texto(ruc), texto(telefono), texto(email),
                texto(sitioWeb), texto(urlSeguimiento), id), "transportista", id);
    }

    @Transactional
    public void activarTransportista(long id, boolean activo) {
        exigir(pg.update("UPDATE transportista SET activo = ? WHERE id = ?", activo, id),
                "transportista", id);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Métodos de envío
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarMetodos() {
        return pg.queryForList("""
                SELECT m.id, m.codigo, m.nombre, m.descripcion, m.transportista_id,
                       t.nombre AS transportista, m.dias_entrega_min, m.dias_entrega_max,
                       m.orden, m.activo
                FROM metodo_envio m
                LEFT JOIN transportista t ON t.id = m.transportista_id
                ORDER BY m.orden, m.nombre""");
    }

    @Transactional
    public long crearMetodo(String codigo, String nombre, String descripcion,
                            Long transportistaId, Integer diasMin, Integer diasMax,
                            Integer orden) {
        exigirTexto(codigo, "codigo");
        exigirTexto(nombre, "nombre");
        exigirPlazo(diasMin, diasMax);
        return idDe(pg.queryForObject("""
                INSERT INTO metodo_envio (codigo, nombre, descripcion, transportista_id,
                                          dias_entrega_min, dias_entrega_max, orden)
                VALUES (?, ?, ?, ?::bigint, ?::smallint, ?::smallint, COALESCE(?::int, 0))
                RETURNING id""",
                Long.class, codigo.trim(), nombre.trim(), texto(descripcion),
                transportistaId, diasMin, diasMax, orden));
    }

    @Transactional
    public void editarMetodo(long id, String codigo, String nombre, String descripcion,
                             Long transportistaId, Integer diasMin, Integer diasMax,
                             Integer orden) {
        exigirTexto(codigo, "codigo");
        exigirTexto(nombre, "nombre");
        exigirPlazo(diasMin, diasMax);
        exigir(pg.update("""
                UPDATE metodo_envio
                   SET codigo = ?, nombre = ?, descripcion = ?, transportista_id = ?::bigint,
                       dias_entrega_min = ?::smallint, dias_entrega_max = ?::smallint,
                       orden = COALESCE(?::int, orden)
                 WHERE id = ?""",
                codigo.trim(), nombre.trim(), texto(descripcion), transportistaId,
                diasMin, diasMax, orden, id), "metodo_envio", id);
    }

    @Transactional
    public void activarMetodo(long id, boolean activo) {
        exigir(pg.update("UPDATE metodo_envio SET activo = ? WHERE id = ?", activo, id),
                "metodo_envio", id);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Zonas de envío
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarZonas() {
        return pg.queryForList("""
                SELECT z.id, z.nombre, z.descripcion,
                       z.pais_id, pa.nombre AS pais,
                       z.provincia_id, pr.nombre AS provincia,
                       z.ciudad_id, ci.nombre AS ciudad,
                       CASE WHEN z.ciudad_id    IS NOT NULL THEN 'ciudad'
                            WHEN z.provincia_id IS NOT NULL THEN 'provincia'
                            ELSE 'pais' END AS nivel,
                       z.activo,
                       (SELECT count(*) FROM tarifa_envio t WHERE t.zona_envio_id = z.id)
                           AS tarifas
                FROM zona_envio z
                LEFT JOIN pais pa      ON pa.id = z.pais_id
                LEFT JOIN provincia pr ON pr.id = z.provincia_id
                LEFT JOIN ciudad ci    ON ci.id = z.ciudad_id
                ORDER BY (z.ciudad_id IS NOT NULL) DESC,
                         (z.provincia_id IS NOT NULL) DESC, z.nombre""");
    }

    @Transactional
    public long crearZona(String nombre, Long paisId, Long provinciaId, Long ciudadId,
                          String descripcion) {
        exigirTexto(nombre, "nombre");
        if (paisId == null) {
            throw new IllegalArgumentException("La zona necesita un país.");
        }
        exigirJerarquia(provinciaId, ciudadId);
        return idDe(pg.queryForObject("""
                INSERT INTO zona_envio (nombre, pais_id, provincia_id, ciudad_id, descripcion)
                VALUES (?, ?::bigint, ?::bigint, ?::bigint, ?) RETURNING id""",
                Long.class, nombre.trim(), paisId, provinciaId, ciudadId,
                texto(descripcion)));
    }

    @Transactional
    public void editarZona(long id, String nombre, Long paisId, Long provinciaId,
                           Long ciudadId, String descripcion) {
        exigirTexto(nombre, "nombre");
        if (paisId == null) {
            throw new IllegalArgumentException("La zona necesita un país.");
        }
        exigirJerarquia(provinciaId, ciudadId);
        exigir(pg.update("""
                UPDATE zona_envio
                   SET nombre = ?, pais_id = ?::bigint, provincia_id = ?::bigint,
                       ciudad_id = ?::bigint, descripcion = ?
                 WHERE id = ?""",
                nombre.trim(), paisId, provinciaId, ciudadId, texto(descripcion), id),
                "zona_envio", id);
    }

    @Transactional
    public void activarZona(long id, boolean activo) {
        exigir(pg.update("UPDATE zona_envio SET activo = ? WHERE id = ?", activo, id),
                "zona_envio", id);
    }

    /**
     * Una zona de CIUDAD tiene que declarar su provincia.
     *
     * La resolución de zona del checkout va por especificidad —ciudad, luego
     * provincia, luego país— y compara `provincia_id` contra la de la dirección.
     * Una zona con ciudad y sin provincia nunca gana ese desempate: quedaría
     * creada, visible y sin efecto, que es el peor de los tres resultados.
     */
    private static void exigirJerarquia(Long provinciaId, Long ciudadId) {
        if (ciudadId != null && provinciaId == null) {
            throw new IllegalArgumentException(
                    "Una zona de ciudad debe indicar también su provincia: la resolución "
                  + "del envío compara los dos niveles y sin la provincia la zona nunca aplica.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tarifas de envío
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarTarifas() {
        return pg.queryForList("""
                SELECT t.id, t.zona_envio_id, z.nombre AS zona,
                       t.metodo_envio_id, m.nombre AS metodo,
                       tr.nombre AS transportista,
                       t.costo_base, t.costo_por_kg, t.peso_min_kg, t.peso_max_kg,
                       t.envio_gratis_desde, t.activo
                FROM tarifa_envio t
                LEFT JOIN zona_envio z    ON z.id = t.zona_envio_id
                LEFT JOIN metodo_envio m  ON m.id = t.metodo_envio_id
                LEFT JOIN transportista tr ON tr.id = m.transportista_id
                ORDER BY z.nombre, m.nombre, t.peso_min_kg""");
    }

    @Transactional
    public long crearTarifa(Long zonaId, Long metodoId, BigDecimal costoBase,
                            BigDecimal costoPorKg, BigDecimal pesoMin, BigDecimal pesoMax,
                            BigDecimal gratisDesde) {
        exigirTarifa(zonaId, metodoId, costoBase, costoPorKg, pesoMin, pesoMax);
        return idDe(pg.queryForObject("""
                INSERT INTO tarifa_envio (zona_envio_id, metodo_envio_id, costo_base,
                                          costo_por_kg, peso_min_kg, peso_max_kg,
                                          envio_gratis_desde)
                VALUES (?, ?, ?, COALESCE(?, 0), COALESCE(?, 0), ?, ?) RETURNING id""",
                Long.class, zonaId, metodoId, costoBase, costoPorKg, pesoMin, pesoMax,
                gratisDesde));
    }

    @Transactional
    public void editarTarifa(long id, Long zonaId, Long metodoId, BigDecimal costoBase,
                             BigDecimal costoPorKg, BigDecimal pesoMin, BigDecimal pesoMax,
                             BigDecimal gratisDesde) {
        exigirTarifa(zonaId, metodoId, costoBase, costoPorKg, pesoMin, pesoMax);
        exigir(pg.update("""
                UPDATE tarifa_envio
                   SET zona_envio_id = ?, metodo_envio_id = ?, costo_base = ?,
                       costo_por_kg = COALESCE(?, 0), peso_min_kg = COALESCE(?, 0),
                       peso_max_kg = ?, envio_gratis_desde = ?
                 WHERE id = ?""",
                zonaId, metodoId, costoBase, costoPorKg, pesoMin, pesoMax,
                gratisDesde, id), "tarifa_envio", id);
    }

    @Transactional
    public void activarTarifa(long id, boolean activo) {
        exigir(pg.update("UPDATE tarifa_envio SET activo = ? WHERE id = ?", activo, id),
                "tarifa_envio", id);
    }

    /**
     * Reglas de una tarifa. Se validan EN LA APLICACIÓN aunque el motor tenga
     * sus CHECK, porque el motor admite cosas que el negocio no: un
     * `costo_base` de 0 es legal para PostgreSQL y significaría transportar
     * gratis por descuido — el mismo tipo de agujero que el `precio = 0` de una
     * variante (ficha C-19).
     */
    private static void exigirTarifa(Long zonaId, Long metodoId, BigDecimal costoBase,
                                     BigDecimal costoPorKg, BigDecimal pesoMin,
                                     BigDecimal pesoMax) {
        if (zonaId == null || metodoId == null) {
            throw new IllegalArgumentException("La tarifa necesita zona y método de envío.");
        }
        if (costoBase == null || costoBase.signum() < 0) {
            throw new IllegalArgumentException("El costo base no puede ser negativo.");
        }
        if (costoPorKg != null && costoPorKg.signum() < 0) {
            throw new IllegalArgumentException("El costo por kilo no puede ser negativo.");
        }
        if (pesoMin != null && pesoMin.signum() < 0) {
            throw new IllegalArgumentException("El peso mínimo no puede ser negativo.");
        }
        if (pesoMin != null && pesoMax != null && pesoMax.compareTo(pesoMin) <= 0) {
            throw new IllegalArgumentException(
                    "El peso máximo debe ser mayor que el mínimo; si no, el tramo no cubre "
                  + "ningún envío y las tarifas se solapan o dejan huecos.");
        }
    }

    private static void exigirPlazo(Integer diasMin, Integer diasMax) {
        if (diasMin != null && diasMin < 0) {
            throw new IllegalArgumentException("Los días de entrega no pueden ser negativos.");
        }
        if (diasMin != null && diasMax != null && diasMax < diasMin) {
            throw new IllegalArgumentException(
                    "El plazo máximo de entrega no puede ser menor que el mínimo.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Referencias para los desplegables del formulario
    // ─────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> referencias() {
        return Map.of(
                "paises", pg.queryForList("SELECT id, nombre FROM pais ORDER BY nombre"),
                "provincias", pg.queryForList(
                        "SELECT id, nombre, pais_id FROM provincia ORDER BY nombre"),
                "ciudades", pg.queryForList(
                        "SELECT id, nombre, provincia_id FROM ciudad ORDER BY nombre"),
                "transportistas", pg.queryForList(
                        "SELECT id, nombre FROM transportista WHERE activo ORDER BY nombre"),
                "zonas", pg.queryForList(
                        "SELECT id, nombre FROM zona_envio WHERE activo ORDER BY nombre"),
                "metodos", pg.queryForList(
                        "SELECT id, nombre FROM metodo_envio WHERE activo ORDER BY orden, nombre"));
    }

    // ── Utilidades ───────────────────────────────────────────────────────

    private static void exigirTexto(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("El campo «" + campo + "» es obligatorio.");
        }
    }

    /** Cadena vacía → NULL: un teléfono en blanco es «no hay», no «es ''». */
    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    private static long idDe(Long id) {
        if (id == null) {
            throw new IllegalStateException("El motor no devolvió el id del registro creado.");
        }
        return id;
    }

    private static void exigir(int filas, String entidad, long id) {
        if (filas == 0) {
            throw new NoSuchElementException("No existe " + entidad + " con id " + id);
        }
    }
}
