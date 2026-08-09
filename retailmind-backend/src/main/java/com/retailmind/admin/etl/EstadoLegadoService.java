package com.retailmind.admin.etl;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Estado REAL de la capa analitica LEGADA (base `retailmind` de ClickHouse).
 *
 * Existe para que las pantallas `/inicializacion` y `/admin-etl` dejen de
 * DEDUCIR el estado del sistema por expresiones regulares sobre el `stdout` de
 * un proceso Python. Aquel montaje tenia dos defectos que no se arreglan
 * retocando el parseo:
 *
 *   1. medía el CODIGO DE SALIDA de un proceso, no la base: con el proceso
 *      caido los indicadores se apagaban aunque ClickHouse estuviera sano
 *      —que es exactamente lo que pasaba—;
 *   2. el indicador de Pocketbase no consultaba nada en absoluto: era la
 *      constante `true` en el componente Angular.
 *
 * Aqui cada cifra sale de una consulta o de una llamada al sistema de
 * archivos, y todas son de SOLO LECTURA. Este servicio no escribe jamas.
 */
@Service
public class EstadoLegadoService {

    private static final Logger logger = LoggerFactory.getLogger(EstadoLegadoService.class);

    /** Base LEGADA. Se cualifica SIEMPRE: el almacen vivo es `retailmind_dwh`. */
    private static final String BASE = "retailmind";

    /** Las 13 dimensiones/tablas auxiliares que acompañan a `fact_eventos`. */
    private static final String[] TABLAS_AUXILIARES = {
        "dim_canal", "dim_categoria", "dim_dispositivo", "dim_fuente_trafico",
        "dim_producto", "dim_region", "dim_usuario",
        "productos_catalogo", "usuarios_sistema",
        "carrito_items", "ordenes", "orden_items", "wishlist_items"
    };

    /**
     * Prefijo del `session_id` con el que `EventoTiendaService` marca los
     * eventos de la TIENDA REAL. Es lo unico que distingue un evento vivo de
     * uno sintetico una vez insertado, porque la columna `semana` no lo dice.
     */
    private static final String PREFIJO_TIENDA = "sess_shop_";

    private final JdbcTemplate ch;

    /** Ruta del parquet de la etapa PocketBase, relativa a la raiz del ETL. */
    private final String rutaParquet;

    public EstadoLegadoService(@Lazy @Qualifier("clickHouseJdbc") JdbcTemplate ch,
                               @Value("${init.scripts.path}") String scriptsPath) {
        this.ch = ch;
        this.rutaParquet = new File(new File(scriptsPath), "data/stage/datos.parquet").getPath();
    }

    // ── Estado general (pantalla /inicializacion) ────────────────────────────

    /**
     * Foto del estado de la capa legada. Nunca lanza: si ClickHouse no
     * responde, lo DICE en `clickhouseConectado` con el motivo, que es
     * justamente lo que la pantalla necesita mostrar.
     */
    public Map<String, Object> estado() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("base", BASE);

        // 1. Conexion real: la consulta mas barata que obliga a ir al motor.
        boolean conectado;
        String version = null;
        String error = null;
        try {
            version = ch.queryForObject("SELECT version()", String.class);
            conectado = true;
        } catch (Exception e) {
            conectado = false;
            error = e.getMessage();
            logger.warn("ClickHouse no responde al sondeo de estado: {}", e.getMessage());
        }
        r.put("clickhouseConectado", conectado);
        r.put("clickhouseVersion", version);
        r.put("errorConexion", error);

        // 2. Filas REALES de fact_eventos y semanas distintas.
        long filas = 0;
        int semanas = 0;
        if (conectado) {
            filas = contar("SELECT count() FROM " + BASE + ".fact_eventos");
            Integer s = consultarEntero("SELECT uniqExact(semana) FROM " + BASE + ".fact_eventos");
            semanas = s != null ? s : 0;
        }
        r.put("factEventos", filas);
        r.put("factEventosConDatos", filas > 0);
        r.put("semanasDistintas", semanas);

        // 3. Dimensiones, una a una y con su conteo real.
        List<Map<String, Object>> dims = new ArrayList<>();
        int dimsConDatos = 0;
        if (conectado) {
            for (String tabla : TABLAS_AUXILIARES) {
                long n = contar("SELECT count() FROM " + BASE + "." + tabla);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("tabla", tabla);
                d.put("filas", n);
                dims.add(d);
                if (n > 0) dimsConDatos++;
            }
        }
        r.put("dimensiones", dims);
        r.put("dimensionesConDatos", dimsConDatos);
        r.put("dimensionesTotales", TABLAS_AUXILIARES.length);

        // 4. Parquet: se MIRA EL DISCO. El indicador anterior buscaba la
        //    palabra «fact_eventos» en la salida de un script, de modo que
        //    daba rojo con el archivo delante (2.000.479 bytes) y habria dado
        //    verde con el archivo borrado si el script imprimia esa palabra.
        File parquet = new File(rutaParquet);
        boolean existe = parquet.isFile();
        r.put("parquetExiste", existe);
        r.put("parquetRuta", rutaParquet);
        r.put("parquetBytes", existe ? parquet.length() : 0L);
        r.put("parquetFecha", existe ? parquet.lastModified() : 0L);

        return r;
    }

    // ── Semanas (pantallas /inicializacion y /admin-etl) ─────────────────────

    /**
     * Semanas con su conteo REAL, agrupando por la columna `semana`.
     *
     * Sustituye al calculo que hacia el navegador, que dividia el total entre
     * la constante 108.584 y redondeaba: sobre las 2.823.245 filas de hoy eso
     * da 26 donde hay 27, inventa 26 tramos exactamente iguales y borra del
     * mapa las cuatro semanas de conteo irregular (23, 25, 26 y 27).
     *
     * Cada semana declara ademas cuantos de sus eventos son de la TIENDA REAL,
     * que es lo que decide si puede generarse encima (ver {@link #PREFIJO_TIENDA}).
     */
    public Map<String, Object> semanas() {
        Map<String, Object> r = new LinkedHashMap<>();

        List<Map<String, Object>> filas;
        long total;
        try {
            filas = ch.query(
                    "SELECT semana, count() AS filas, " +
                    "       countIf(session_id LIKE '" + PREFIJO_TIENDA + "%') AS eventos_tienda " +
                    "FROM " + BASE + ".fact_eventos " +
                    "GROUP BY semana ORDER BY semana",
                    (rs, rn) -> {
                        Map<String, Object> f = new LinkedHashMap<>();
                        int semana = rs.getInt("semana");
                        long n = rs.getLong("filas");
                        long tienda = rs.getLong("eventos_tienda");
                        f.put("semana", semana);
                        f.put("filas", n);
                        f.put("eventosTienda", tienda);
                        f.put("estado", tienda > 0 ? "tienda" : "ocupada");
                        f.put("motivo", tienda > 0
                                ? "Contiene " + tienda + " evento(s) reales de la tienda; "
                                  + "generar encima los volveria indistinguibles"
                                : "Ya tiene " + n + " registros cargados");
                        return f;
                    });
            total = contar("SELECT count() FROM " + BASE + ".fact_eventos");
        } catch (Exception e) {
            logger.warn("No se pudieron leer las semanas: {}", e.getMessage());
            r.put("disponible", false);
            r.put("error", e.getMessage());
            r.put("semanas", List.of());
            r.put("totalRegistros", 0L);
            r.put("libres", List.of());
            return r;
        }

        r.put("disponible", true);
        r.put("semanas", filas);
        r.put("totalRegistros", total);
        r.put("semanasCargadas", filas.size());
        r.put("eventosTienda", filas.stream().mapToLong(f -> (long) f.get("eventosTienda")).sum());

        // Libres = las del rango admitido por el generador (2..52) sin una sola
        // fila. El generador es MAS conservador aun: aborta con `count() > 0`,
        // y ese guardia se deja intacto a proposito.
        List<Integer> ocupadas = filas.stream().map(f -> (Integer) f.get("semana")).toList();
        List<Integer> libres = new ArrayList<>();
        for (int s = 2; s <= 52; s++) {
            if (!ocupadas.contains(s)) libres.add(s);
        }
        r.put("libres", libres);
        r.put("proximaLibre", libres.isEmpty() ? null : libres.get(0));

        return r;
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private long contar(String sql) {
        Long n = consultarLargo(sql);
        return n != null ? n : 0L;
    }

    private Long consultarLargo(String sql) {
        try {
            return ch.queryForObject(sql, Long.class);
        } catch (Exception e) {
            logger.warn("Consulta de estado fallida [{}]: {}", sql, e.getMessage());
            return null;
        }
    }

    private Integer consultarEntero(String sql) {
        try {
            return ch.queryForObject(sql, Integer.class);
        } catch (Exception e) {
            logger.warn("Consulta de estado fallida [{}]: {}", sql, e.getMessage());
            return null;
        }
    }
}
