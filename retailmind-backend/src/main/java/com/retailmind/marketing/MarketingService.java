package com.retailmind.marketing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD de marketing sobre PostgreSQL (cupon, uso_cupon, promocion,
 * promocion_producto, campana, banner, newsletter_suscriptor).
 * Solo gestión: la aplicación de descuentos a pedidos es una tarea posterior.
 *
 * Todo dentro de @Transactional para que PgSessionRoleAspect asuma el rol de
 * grupo (grp_administrador escribe; grp_gerente solo SELECT). Nunca se
 * escriben usos_actuales (lo alimenta el flujo de pedidos) ni las fechas
 * fecha_creacion / fecha_actualizacion (default + trigger touch de la BD).
 */
@Service
public class MarketingService {

    /** Listas blancas que espejan los CHECK de la BD (mensaje claro antes del 400 genérico). */
    private static final Set<String> TIPOS_CUPON = Set.of("porcentaje", "monto_fijo", "envio_gratis");
    private static final Set<String> TIPOS_PROMOCION = Set.of("porcentaje", "monto_fijo");
    private static final Set<String> CANALES_CAMPANA = Set.of("email", "redes", "web", "sms", "mixto");
    private static final Set<String> ESTADOS_CAMPANA = Set.of("borrador", "activa", "pausada", "finalizada");

    private final JdbcTemplate pg;

    public MarketingService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg) {
        this.pg = pg;
    }

    // ── Cupones ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCupones() {
        return pg.queryForList("""
                SELECT id, codigo, descripcion, tipo_descuento, valor, monto_minimo_pedido,
                       usos_maximos, usos_por_cliente, usos_actuales,
                       fecha_inicio, fecha_fin, activo, fecha_creacion
                FROM cupon ORDER BY fecha_creacion DESC""");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarUsosCupon(long cuponId) {
        return pg.queryForList("""
                SELECT uc.id, uc.pedido_id, uc.cliente_id, c.nombre AS cliente,
                       uc.monto_descontado, uc.fecha_creacion
                FROM uso_cupon uc LEFT JOIN cliente c ON c.id = uc.cliente_id
                WHERE uc.cupon_id = ? ORDER BY uc.fecha_creacion DESC""", cuponId);
    }

    @Transactional
    public long crearCupon(String codigo, String descripcion, String tipoDescuento,
                           BigDecimal valor, BigDecimal montoMinimoPedido, Integer usosMaximos,
                           Integer usosPorCliente, String fechaInicio, String fechaFin) {
        validarDescuento(tipoDescuento, valor, TIPOS_CUPON, "cupón");
        exigirTexto(codigo, "El código del cupón es requerido");
        exigirTexto(fechaInicio, "La fecha de inicio es requerida");
        exigirCodigoLibre(codigo, null);
        return idDe(pg.queryForObject("""
                INSERT INTO cupon (codigo, descripcion, tipo_descuento, valor, monto_minimo_pedido,
                                   usos_maximos, usos_por_cliente, fecha_inicio, fecha_fin)
                VALUES (?, ?, ?, ?, COALESCE(?, 0), ?, COALESCE(?, 1),
                        ?::timestamptz, NULLIF(?, '')::timestamptz)
                RETURNING id""",
                Long.class, codigo.trim().toUpperCase(), descripcion, tipoDescuento, valor,
                montoMinimoPedido, usosMaximos, usosPorCliente, fechaInicio, fechaFin));
    }

    @Transactional
    public void editarCupon(long id, String codigo, String descripcion, String tipoDescuento,
                            BigDecimal valor, BigDecimal montoMinimoPedido, Integer usosMaximos,
                            Integer usosPorCliente, String fechaInicio, String fechaFin) {
        validarDescuento(tipoDescuento, valor, TIPOS_CUPON, "cupón");
        exigirTexto(codigo, "El código del cupón es requerido");
        exigirTexto(fechaInicio, "La fecha de inicio es requerida");
        exigirCodigoLibre(codigo, id);
        exigir(pg.update("""
                UPDATE cupon
                SET codigo = ?, descripcion = ?, tipo_descuento = ?, valor = ?,
                    monto_minimo_pedido = COALESCE(?, 0), usos_maximos = ?,
                    usos_por_cliente = COALESCE(?, 1),
                    fecha_inicio = ?::timestamptz, fecha_fin = NULLIF(?, '')::timestamptz
                WHERE id = ?""",
                codigo.trim().toUpperCase(), descripcion, tipoDescuento, valor, montoMinimoPedido,
                usosMaximos, usosPorCliente, fechaInicio, fechaFin, id), "cupon", id);
    }

    @Transactional
    public void activarCupon(long id, boolean activo) {
        exigir(pg.update("UPDATE cupon SET activo = ? WHERE id = ?", activo, id), "cupon", id);
    }

    /** Guardia: código único (la BD también lo exige, pero aquí el mensaje es claro). */
    private void exigirCodigoLibre(String codigo, Long idActual) {
        Integer repetidos = pg.queryForObject("""
                SELECT count(*) FROM cupon
                WHERE upper(codigo) = upper(?) AND id <> COALESCE(?::bigint, -1)""",
                Integer.class, codigo.trim(), idActual);
        if (repetidos != null && repetidos > 0) {
            throw new IllegalStateException("Ya existe un cupón con el código '"
                    + codigo.trim().toUpperCase() + "'");
        }
    }

    // ── Promociones ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarPromociones() {
        return pg.queryForList("""
                SELECT p.id, p.nombre, p.descripcion, p.tipo_descuento, p.valor,
                       p.fecha_inicio, p.fecha_fin, p.prioridad, p.acumulable, p.activo,
                       p.fecha_creacion,
                       (SELECT count(*) FROM promocion_producto pp WHERE pp.promocion_id = p.id) AS productos
                FROM promocion p ORDER BY p.fecha_creacion DESC""");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> obtenerPromocion(long id) {
        Map<String, Object> promo = pg.queryForMap("""
                SELECT id, nombre, descripcion, tipo_descuento, valor, fecha_inicio, fecha_fin,
                       prioridad, acumulable, activo
                FROM promocion WHERE id = ?""", id);
        promo.put("productos", pg.queryForList("""
                SELECT pp.id, pp.producto_id, pr.nombre AS producto, pr.activo AS producto_activo
                FROM promocion_producto pp JOIN producto pr ON pr.id = pp.producto_id
                WHERE pp.promocion_id = ? ORDER BY pr.nombre""", id));
        return promo;
    }

    @Transactional
    public long crearPromocion(String nombre, String descripcion, String tipoDescuento,
                               BigDecimal valor, String fechaInicio, String fechaFin,
                               Integer prioridad, boolean acumulable) {
        validarDescuento(tipoDescuento, valor, TIPOS_PROMOCION, "promoción");
        exigirTexto(nombre, "El nombre de la promoción es requerido");
        exigirTexto(fechaInicio, "La fecha de inicio es requerida");
        return idDe(pg.queryForObject("""
                INSERT INTO promocion (nombre, descripcion, tipo_descuento, valor,
                                       fecha_inicio, fecha_fin, prioridad, acumulable)
                VALUES (?, ?, ?, ?, ?::timestamptz, NULLIF(?, '')::timestamptz, COALESCE(?, 0), ?)
                RETURNING id""",
                Long.class, nombre, descripcion, tipoDescuento, valor,
                fechaInicio, fechaFin, prioridad, acumulable));
    }

    @Transactional
    public void editarPromocion(long id, String nombre, String descripcion, String tipoDescuento,
                                BigDecimal valor, String fechaInicio, String fechaFin,
                                Integer prioridad, Boolean acumulable) {
        validarDescuento(tipoDescuento, valor, TIPOS_PROMOCION, "promoción");
        exigirTexto(nombre, "El nombre de la promoción es requerido");
        exigirTexto(fechaInicio, "La fecha de inicio es requerida");
        exigir(pg.update("""
                UPDATE promocion
                SET nombre = ?, descripcion = ?, tipo_descuento = ?, valor = ?,
                    fecha_inicio = ?::timestamptz, fecha_fin = NULLIF(?, '')::timestamptz,
                    prioridad = COALESCE(?, prioridad), acumulable = COALESCE(?, acumulable)
                WHERE id = ?""",
                nombre, descripcion, tipoDescuento, valor, fechaInicio, fechaFin,
                prioridad, acumulable, id), "promocion", id);
    }

    @Transactional
    public void activarPromocion(long id, boolean activo) {
        exigir(pg.update("UPDATE promocion SET activo = ? WHERE id = ?", activo, id),
                "promocion", id);
    }

    /** Asocia un producto a la promoción (N:M). Idempotente por uq_promocion_producto. */
    @Transactional
    public void asociarProducto(long promocionId, long productoId) {
        existePromocion(promocionId);
        int filas = pg.update("""
                INSERT INTO promocion_producto (promocion_id, producto_id)
                VALUES (?, ?) ON CONFLICT (promocion_id, producto_id) DO NOTHING""",
                promocionId, productoId);
        if (filas == 0) {
            throw new IllegalStateException("El producto ya está asociado a esta promoción");
        }
    }

    @Transactional
    public void quitarProducto(long promocionId, long productoId) {
        int filas = pg.update("""
                DELETE FROM promocion_producto WHERE promocion_id = ? AND producto_id = ?""",
                promocionId, productoId);
        if (filas == 0) {
            throw new IllegalArgumentException("El producto no está asociado a esta promoción");
        }
    }

    /** Productos activos para el selector de asociación (solo id + nombre). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarProductosRef() {
        return pg.queryForList(
                "SELECT id, nombre FROM producto WHERE activo ORDER BY nombre");
    }

    private void existePromocion(long id) {
        Integer n = pg.queryForObject("SELECT count(*) FROM promocion WHERE id = ?",
                Integer.class, id);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("No existe promocion con id " + id);
        }
    }

    // ── Campañas ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarCampanas() {
        return pg.queryForList("""
                SELECT c.id, c.nombre, c.descripcion, c.canal, c.presupuesto, c.estado,
                       c.fecha_inicio, c.fecha_fin, c.fecha_creacion,
                       (SELECT count(*) FROM banner b WHERE b.campana_id = c.id) AS banners
                FROM campana c ORDER BY c.fecha_creacion DESC""");
    }

    @Transactional
    public long crearCampana(String nombre, String descripcion, String canal,
                             BigDecimal presupuesto, String fechaInicio, String fechaFin) {
        exigirTexto(nombre, "El nombre de la campaña es requerido");
        validarEnLista(canal, CANALES_CAMPANA, "canal de campaña");
        return idDe(pg.queryForObject("""
                INSERT INTO campana (nombre, descripcion, canal, presupuesto, fecha_inicio, fecha_fin)
                VALUES (?, ?, ?, ?, NULLIF(?, '')::date, NULLIF(?, '')::date)
                RETURNING id""",
                Long.class, nombre, descripcion, canal, presupuesto, fechaInicio, fechaFin));
    }

    @Transactional
    public void editarCampana(long id, String nombre, String descripcion, String canal,
                              BigDecimal presupuesto, String fechaInicio, String fechaFin) {
        exigirTexto(nombre, "El nombre de la campaña es requerido");
        validarEnLista(canal, CANALES_CAMPANA, "canal de campaña");
        exigir(pg.update("""
                UPDATE campana
                SET nombre = ?, descripcion = ?, canal = ?, presupuesto = ?,
                    fecha_inicio = NULLIF(?, '')::date, fecha_fin = NULLIF(?, '')::date
                WHERE id = ?""",
                nombre, descripcion, canal, presupuesto, fechaInicio, fechaFin, id),
                "campana", id);
    }

    /** La campaña no tiene bandera activo: su ciclo es el estado (CHECK de la BD). */
    @Transactional
    public void cambiarEstadoCampana(long id, String estado) {
        validarEnLista(estado, ESTADOS_CAMPANA, "estado de campaña");
        String actual = pg.queryForObject("SELECT estado FROM campana WHERE id = ?",
                String.class, id);
        if (estado.equals(actual)) {
            throw new IllegalStateException("La campaña ya está en estado '" + estado + "'");
        }
        if ("finalizada".equals(actual)) {
            throw new IllegalStateException("Una campaña finalizada no puede cambiar de estado");
        }
        pg.update("UPDATE campana SET estado = ? WHERE id = ?", estado, id);
    }

    // ── Banners ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarBanners() {
        return pg.queryForList("""
                SELECT b.id, b.campana_id, c.nombre AS campana, b.titulo, b.imagen_url,
                       b.url_destino, b.posicion, b.orden, b.fecha_inicio, b.fecha_fin,
                       b.activo, b.fecha_creacion
                FROM banner b LEFT JOIN campana c ON c.id = b.campana_id
                ORDER BY b.posicion, b.orden, b.id""");
    }

    @Transactional
    public long crearBanner(String titulo, String imagenUrl, String urlDestino, String posicion,
                            Integer orden, Long campanaId, String fechaInicio, String fechaFin) {
        exigirTexto(titulo, "El título del banner es requerido");
        exigirTexto(imagenUrl, "La URL de la imagen es requerida");
        return idDe(pg.queryForObject("""
                INSERT INTO banner (titulo, imagen_url, url_destino, posicion, orden, campana_id,
                                    fecha_inicio, fecha_fin)
                VALUES (?, ?, ?, COALESCE(NULLIF(?, ''), 'home_principal'), COALESCE(?, 0), ?,
                        NULLIF(?, '')::timestamptz, NULLIF(?, '')::timestamptz)
                RETURNING id""",
                Long.class, titulo, imagenUrl, urlDestino, posicion, orden, campanaId,
                fechaInicio, fechaFin));
    }

    @Transactional
    public void editarBanner(long id, String titulo, String imagenUrl, String urlDestino,
                             String posicion, Integer orden, Long campanaId,
                             String fechaInicio, String fechaFin) {
        exigirTexto(titulo, "El título del banner es requerido");
        exigirTexto(imagenUrl, "La URL de la imagen es requerida");
        exigir(pg.update("""
                UPDATE banner
                SET titulo = ?, imagen_url = ?, url_destino = ?,
                    posicion = COALESCE(NULLIF(?, ''), 'home_principal'),
                    orden = COALESCE(?, orden), campana_id = ?,
                    fecha_inicio = NULLIF(?, '')::timestamptz,
                    fecha_fin = NULLIF(?, '')::timestamptz
                WHERE id = ?""",
                titulo, imagenUrl, urlDestino, posicion, orden, campanaId,
                fechaInicio, fechaFin, id), "banner", id);
    }

    @Transactional
    public void activarBanner(long id, boolean activo) {
        exigir(pg.update("UPDATE banner SET activo = ? WHERE id = ?", activo, id), "banner", id);
    }

    // ── Newsletter ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listarSuscriptores() {
        return pg.queryForList("""
                SELECT ns.id, ns.email, ns.cliente_id, c.nombre AS cliente, ns.confirmado,
                       ns.fecha_suscripcion, ns.fecha_baja, ns.activo
                FROM newsletter_suscriptor ns LEFT JOIN cliente c ON c.id = ns.cliente_id
                ORDER BY ns.fecha_suscripcion DESC""");
    }

    /** Alta manual desde back-office: entra confirmado (no hay flujo de doble opt-in aquí). */
    @Transactional
    public long altaSuscriptor(String email, Long clienteId) {
        exigirTexto(email, "El email es requerido");
        // La tabla no tiene UNIQUE sobre email: la unicidad se garantiza aquí.
        Integer repetidos = pg.queryForObject(
                "SELECT count(*) FROM newsletter_suscriptor WHERE lower(email) = lower(?)",
                Integer.class, email.trim());
        if (repetidos != null && repetidos > 0) {
            throw new IllegalStateException("El email '" + email.trim()
                    + "' ya está suscrito al newsletter");
        }
        return idDe(pg.queryForObject("""
                INSERT INTO newsletter_suscriptor (email, cliente_id, confirmado)
                VALUES (?, ?, true) RETURNING id""",
                Long.class, email.trim().toLowerCase(), clienteId));
    }

    /** Baja lógica (activo=false + fecha_baja) o reactivación (limpia fecha_baja). */
    @Transactional
    public void activarSuscriptor(long id, boolean activo) {
        exigir(pg.update("""
                UPDATE newsletter_suscriptor
                SET activo = ?, fecha_baja = CASE WHEN ? THEN NULL ELSE now() END
                WHERE id = ?""", activo, activo, id), "newsletter_suscriptor", id);
    }

    // ── Utilitarios ──────────────────────────────────────────────────────

    private static void validarDescuento(String tipo, BigDecimal valor,
                                         Set<String> tiposValidos, String entidad) {
        validarEnLista(tipo, tiposValidos, "tipo de descuento de " + entidad);
        if (valor == null || valor.signum() < 0) {
            throw new IllegalArgumentException("El valor del descuento debe ser mayor o igual a 0");
        }
        if ("porcentaje".equals(tipo) && valor.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new IllegalArgumentException("Un descuento porcentual no puede superar 100%");
        }
    }

    private static void validarEnLista(String valor, Set<String> validos, String campo) {
        if (valor == null || !validos.contains(valor)) {
            throw new IllegalArgumentException("Valor inválido para " + campo
                    + ". Permitidos: " + String.join(", ", validos.stream().sorted().toList()));
        }
    }

    private static void exigirTexto(String valor, String mensaje) {
        if (valor == null || valor.isBlank()) throw new IllegalArgumentException(mensaje);
    }

    private static long idDe(Long id) {
        if (id == null) throw new IllegalStateException("INSERT no devolvio id");
        return id;
    }

    private static void exigir(int filas, String tabla, long id) {
        if (filas == 0) throw new IllegalArgumentException("No existe " + tabla + " con id " + id);
    }
}
