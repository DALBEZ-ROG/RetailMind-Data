package com.retailmind.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PedidosService {

    private final JdbcTemplate ch;

    public PedidosService(@Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    public List<Map<String, Object>> getPedidosUsuario(String userId) {
        // Obtener órdenes
        List<Map<String, Object>> ordenes = ch.query(
                "SELECT orden_id, total, estado, fecha_orden, canal " +
                "FROM retailmind.ordenes WHERE user_id = '" + userId + "' " +
                "ORDER BY fecha_orden DESC",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ordenId", rs.getString("orden_id"));
                    r.put("total", rs.getFloat("total"));
                    r.put("estado", rs.getString("estado"));
                    r.put("fechaOrden", rs.getString("fecha_orden"));
                    r.put("canal", rs.getString("canal"));
                    return r;
                });

        // Para cada orden, obtener items
        for (Map<String, Object> orden : ordenes) {
            String ordenId = (String) orden.get("ordenId");
            List<Map<String, Object>> items = ch.query(
                    "SELECT oi.producto_id, oi.cantidad, oi.precio_unitario, p.nombre " +
                    "FROM retailmind.orden_items oi " +
                    "LEFT JOIN retailmind.productos_catalogo p ON oi.producto_id = p.producto_id " +
                    "WHERE oi.orden_id = '" + ordenId + "'",
                    (rs, rn) -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("productoId", rs.getString("producto_id"));
                        item.put("cantidad", rs.getInt("cantidad"));
                        item.put("precioUnitario", rs.getFloat("precio_unitario"));
                        item.put("nombre", rs.getString("nombre"));
                        return item;
                    });
            orden.put("items", items);
            orden.put("numItems", items.size());
        }
        return ordenes;
    }

    public List<Map<String, Object>> getTodosLosPedidos() {
        List<Map<String, Object>> ordenes = ch.query(
                "SELECT orden_id, user_id, total, estado, fecha_orden, canal " +
                "FROM retailmind.ordenes ORDER BY fecha_orden DESC",
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("ordenId", rs.getString("orden_id"));
                    r.put("userId", rs.getString("user_id"));
                    r.put("total", rs.getFloat("total"));
                    r.put("estado", rs.getString("estado"));
                    r.put("fechaOrden", rs.getString("fecha_orden"));
                    r.put("canal", rs.getString("canal"));
                    return r;
                });

        for (Map<String, Object> orden : ordenes) {
            String ordenId = (String) orden.get("ordenId");
            Long itemCount = ch.queryForObject(
                    "SELECT count() FROM retailmind.orden_items WHERE orden_id = '" + ordenId + "'",
                    Long.class);
            orden.put("numItems", itemCount != null ? itemCount : 0L);
        }
        return ordenes;
    }
}
