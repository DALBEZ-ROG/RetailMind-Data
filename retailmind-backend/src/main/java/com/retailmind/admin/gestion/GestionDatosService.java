package com.retailmind.admin.gestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GestionDatosService {

    private final JdbcTemplate ch;

    public GestionDatosService(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FACT_EVENTOS
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getFactEventos(int page, int size, Integer semana) {
        String where = (semana != null) ? " WHERE semana = " + semana : "";
        Long total = ch.queryForObject("SELECT count() FROM retailmind.fact_eventos" + where, Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT event_pk, session_id, user_id, timestamp_utc, event_index, " +
                "user_action, product_id, time_spent_sec, session_length, interaction_count, " +
                "is_conversion, drop_off_flag, price, channel, semana " +
                "FROM retailmind.fact_eventos" + where +
                " ORDER BY event_pk LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("eventPk", rs.getLong("event_pk"));
                    r.put("sessionId", rs.getString("session_id"));
                    r.put("userId", rs.getString("user_id"));
                    r.put("timestampUtc", rs.getString("timestamp_utc"));
                    r.put("eventIndex", rs.getInt("event_index"));
                    r.put("userAction", rs.getString("user_action"));
                    r.put("productId", rs.getString("product_id"));
                    r.put("timeSpentSec", rs.getFloat("time_spent_sec"));
                    r.put("sessionLength", rs.getFloat("session_length"));
                    r.put("interactionCount", rs.getInt("interaction_count"));
                    r.put("isConversion", rs.getInt("is_conversion"));
                    r.put("dropOffFlag", rs.getInt("drop_off_flag"));
                    r.put("price", rs.getFloat("price"));
                    r.put("channel", rs.getString("channel"));
                    r.put("semana", rs.getInt("semana"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    // ── `fact_eventos` ES DE SOLO LECTURA. NO REINTRODUCIR ESCRITURA. ─────────
    //
    // Aquí vivían `getFactEventoById`, `updateFactEvento` y `deleteFactEvento`,
    // los tres filtrando por `WHERE event_pk = <id>`. Se suprimieron el
    // 2026-08-07 (deuda A-3) porque `event_pk` NO IDENTIFICA UNA FILA:
    //
    //   * está declarada `UInt64 DEFAULT rowNumberInAllBlocks()`, y ese contador
    //     REINICIA en cada bloque de inserción;
    //   * medido sobre la tabla real: 50.000 valores distintos para 2.823.245
    //     filas, con entre 52 y 139 filas compartiendo cada valor.
    //
    // Consecuencia: el `SELECT ... WHERE event_pk` devolvía una fila ARBITRARIA
    // de un centenar, el `UPDATE` las reescribía todas y el `DELETE` BORRABA LAS
    // 52-139 —de otras tantas sesiones distintas— informando de un borrado
    // correcto de «un evento». `fact_eventos` no es reproducible: por eso su
    // volumen va declarado `external: true` en el compose.
    //
    // NO se arregla con una clave compuesta: se verificó que no existe ninguna
    // practicable. `(session_id, event_index)` deja 253.372 pares repetidos;
    // añadiendo `timestamp_utc` aún queda 1 colisión; solo la fila entera
    // (15 columnas) identifica. Si algún día hace falta escribir aquí, primero
    // hay que reconstruir la tabla con un identificador de verdad —con respaldo,
    // y sin volver a usar `rowNumberInAllBlocks()`, que reproduce el defecto—.
    // Queda como fragilidad C-15 en `DEUDA_TECNICA.md`.
    //
    // Con estos tres métodos desapareció además la única concatenación de un
    // NOMBRE DE COLUMNA en el SQL de este archivo: `updateFactEvento` construía
    // `k + " = '" + v + "'"` con las claves del cuerpo de la petición, o sea con
    // un identificador SQL que venía del usuario (regla de oro n.º 2).
    //
    // Lo que SÍ sigue: `getFactEventos` (listado paginado + filtro por semana),
    // que es de lectura y no necesita identificar una fila.

    // ══════════════════════════════════════════════════════════════════════════
    // DIMENSIONES GENÉRICAS
    // ══════════════════════════════════════════════════════════════════════════

    public List<Map<String, Object>> getDimension(String table, String idCol, String nameCol) {
        return ch.query("SELECT " + idCol + ", " + nameCol + " FROM retailmind." + table + " ORDER BY " + idCol,
                (rs, rn) -> Map.of("id", rs.getLong(idCol), "nombre", rs.getString(nameCol)));
    }

    public void insertDimension(String table, String idCol, String nameCol, long id, String nombre) {
        ch.execute("INSERT INTO retailmind." + table + " (" + idCol + ", " + nameCol + ") VALUES (" +
                id + ", '" + nombre + "')");
    }

    public void insertDimensionAutoId(String table, String idCol, String nameCol, String nombre) {
        Long maxId = ch.queryForObject(
                "SELECT max(" + idCol + ") FROM retailmind." + table, Long.class);
        long nextId = (maxId != null ? maxId : 0) + 1;
        ch.execute("INSERT INTO retailmind." + table + " (" + idCol + ", " + nameCol + ") VALUES (" +
                nextId + ", '" + nombre + "')");
    }

    public void updateDimension(String table, String idCol, String nameCol, long id, String nombre) {
        ch.execute("ALTER TABLE retailmind." + table + " UPDATE " + nameCol + " = '" + nombre +
                "' WHERE " + idCol + " = " + id + " SETTINGS mutations_sync = 1");
    }

    public void deleteDimension(String table, String idCol, long id) {
        ch.execute("ALTER TABLE retailmind." + table + " DELETE WHERE " + idCol + " = " + id +
                " SETTINGS mutations_sync = 1");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_PRODUCTO
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getProductos(int page, int size) {
        Long total = ch.queryForObject("SELECT count() FROM retailmind.dim_producto", Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT producto_id, categoria_id, brand, price FROM retailmind.dim_producto " +
                "ORDER BY producto_id LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("productoId", rs.getString("producto_id"));
                    r.put("categoriaId", rs.getInt("categoria_id"));
                    r.put("brand", rs.getString("brand"));
                    r.put("price", rs.getFloat("price"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    public void insertProducto(String productoId, int categoriaId, String brand, float price) {
        ch.execute("INSERT INTO retailmind.dim_producto (producto_id, categoria_id, brand, price) VALUES ('" +
                productoId + "', " + categoriaId + ", '" + brand + "', " + price + ")");
    }

    public void updateProducto(String productoId, String brand, float price) {
        ch.execute("ALTER TABLE retailmind.dim_producto UPDATE brand = '" + brand + "', price = " + price +
                " WHERE producto_id = '" + productoId + "' SETTINGS mutations_sync = 1");
    }

    public void deleteProducto(String productoId) {
        ch.execute("ALTER TABLE retailmind.dim_producto DELETE WHERE producto_id = '" + productoId +
                "' SETTINGS mutations_sync = 1");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIM_USUARIO
    // ══════════════════════════════════════════════════════════════════════════

    public Map<String, Object> getUsuarios(int page, int size) {
        Long total = ch.queryForObject("SELECT count() FROM retailmind.dim_usuario", Long.class);
        List<Map<String, Object>> rows = ch.query(
                "SELECT user_id, region_id, dispositivo_id FROM retailmind.dim_usuario " +
                "ORDER BY user_id LIMIT " + size + " OFFSET " + (page * size),
                (rs, rn) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("userId", rs.getString("user_id"));
                    r.put("regionId", rs.getInt("region_id"));
                    r.put("dispositivoId", rs.getInt("dispositivo_id"));
                    return r;
                });
        long t = total != null ? total : 0L;
        return Map.of("content", rows, "totalElements", t,
                "totalPages", (int) Math.ceil((double) t / size), "number", page, "size", size);
    }

    public void deleteUsuario(String userId) {
        ch.execute("ALTER TABLE retailmind.dim_usuario DELETE WHERE user_id = '" + userId +
                "' SETTINGS mutations_sync = 1");
    }
}
