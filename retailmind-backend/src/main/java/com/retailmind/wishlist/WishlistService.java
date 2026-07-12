package com.retailmind.wishlist;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.auth.AppUserPrincipal;
import com.retailmind.catalogo.EventoTiendaService;

/**
 * Wishlist del cliente sobre PostgreSQL (wishlist/wishlist_item), aislada por
 * RLS (app.cliente_id). Una lista por cliente ("Mi lista"), creada bajo
 * demanda. Los ids expuestos son de producto_variante.
 */
@Service
public class WishlistService {

    private final JdbcTemplate pg;
    private final EventoTiendaService eventos;

    public WishlistService(@Qualifier("pgJdbcTemplate") JdbcTemplate pg,
                           EventoTiendaService eventos) {
        this.pg = pg;
        this.eventos = eventos;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getItems() {
        return pg.query("""
                SELECT wi.producto_variante_id, wi.fecha_creacion,
                       pr.nombre, m.nombre AS marca, pv.precio,
                       COALESCE(cat.categoria_id, 0) AS categoria_id,
                       (SELECT COALESCE(SUM(stock_actual), 0) FROM inventario i
                        WHERE i.producto_variante_id = wi.producto_variante_id) AS stock
                FROM wishlist_item wi
                JOIN wishlist w ON w.id = wi.wishlist_id
                JOIN producto_variante pv ON pv.id = wi.producto_variante_id
                JOIN producto pr ON pr.id = pv.producto_id
                LEFT JOIN marca m ON m.id = pr.marca_id
                LEFT JOIN LATERAL (SELECT pc.categoria_id FROM producto_categoria pc
                                   WHERE pc.producto_id = pr.id
                                   ORDER BY pc.es_principal DESC, pc.id LIMIT 1) cat ON true
                ORDER BY wi.id DESC""",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("productoId", rs.getLong("producto_variante_id"));
                    r.put("fechaAgregado", rs.getString("fecha_creacion"));
                    r.put("nombre", rs.getString("nombre"));
                    r.put("brand", rs.getString("marca"));
                    r.put("price", rs.getBigDecimal("precio"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    r.put("stock", rs.getLong("stock"));
                    r.put("imagenUrl", null);
                    return r;
                });
    }

    @Transactional
    public void agregar(long varianteId) {
        Long existeVariante = pg.queryForObject("""
                SELECT count(*) FROM producto_variante pv
                JOIN producto pr ON pr.id = pv.producto_id
                WHERE pv.id = ? AND pv.activo AND pr.publicado AND pr.activo""",
                Long.class, varianteId);
        if (existeVariante == null || existeVariante == 0) {
            throw new NoSuchElementException("El producto no existe o no está disponible");
        }

        long wishlistId = wishlistPropiaOCrear();
        Long duplicado = pg.queryForObject(
                "SELECT count(*) FROM wishlist_item WHERE wishlist_id = ? AND producto_variante_id = ?",
                Long.class, wishlistId, varianteId);
        if (duplicado != null && duplicado > 0) {
            throw new IllegalStateException("El producto ya esta en tu wishlist");
        }
        pg.update("INSERT INTO wishlist_item (wishlist_id, producto_variante_id) VALUES (?, ?)",
                wishlistId, varianteId);
        eventos.registrar(usuarioEmail(), String.valueOf(varianteId), "wishlist", "web", null, null);
    }

    @Transactional
    public void eliminar(long varianteId) {
        int filas = pg.update("""
                DELETE FROM wishlist_item wi
                USING wishlist w
                WHERE wi.wishlist_id = w.id AND wi.producto_variante_id = ?""", varianteId);
        if (filas == 0) {
            throw new NoSuchElementException("El producto no está en tu wishlist");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private long wishlistPropiaOCrear() {
        List<Long> ids = pg.queryForList("SELECT id FROM wishlist ORDER BY id LIMIT 1", Long.class);
        if (!ids.isEmpty()) return ids.get(0);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long clienteId = auth != null && auth.getPrincipal() instanceof AppUserPrincipal p
                ? p.getClienteId() : null;
        if (clienteId == null) {
            throw new IllegalStateException("Solo un cliente puede usar la wishlist");
        }
        Long id = pg.queryForObject(
                "INSERT INTO wishlist (cliente_id) VALUES (?) RETURNING id", Long.class, clienteId);
        return id != null ? id : 0L;
    }

    private String usuarioEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AppUserPrincipal p
                ? p.getUsername() : null;
    }
}
