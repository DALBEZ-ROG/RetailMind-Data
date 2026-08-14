package com.retailmind.comun;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Paginación en el SERVIDOR con el sobre estándar de la casa.
 *
 * <h3>Por qué existe</h3>
 * El patrón —{@code {items, total, page, size}} más un tope duro de filas— lo
 * estrenaron los informes tácticos en {@code InformeServiceBase.paginar}, y
 * allí funcionaba porque ningún informe descarga su tabla entera. Fuera de los
 * informes no había nada equivalente: {@code GET /api/ventas/pedidos}
 * materializaba en memoria los 2.999.993 pedidos con un
 * {@code queryForList} sin LIMIT. Medido: el montón del backend subía de 617
 * MiB a 2,03 GiB, saltaba {@code OutOfMemoryError: Java heap space} y con él
 * moría el hilo {@code http-nio-8080-Poller} de Tomcat — o sea que no caía la
 * petición, caía el SERVIDOR: a partir de ahí {@code /api/health} dejaba de
 * responder hasta reiniciar el contenedor.
 *
 * Esta clase saca ese mismo patrón a un sitio donde lo pueda usar cualquier
 * servicio, e {@code InformeServiceBase} pasa a delegar aquí para que haya UNA
 * sola implementación y no dos que se separen con el tiempo.
 *
 * <h3>El tope no es negociable</h3>
 * {@link #MAX_PAGINA} se aplica SIEMPRE, aunque el cliente pida más: es la
 * diferencia entre un endpoint lento y un endpoint que tumba el proceso. Pedir
 * {@code size=1000000} devuelve 200 filas, no un millón.
 *
 * <h3>Inyección</h3>
 * {@code sqlItems} y {@code sqlCount} son SIEMPRE constantes escritas por el
 * desarrollador; lo que viene del usuario viaja exclusivamente en {@code args}
 * como parámetro ligado, y el LIMIT/OFFSET se añade también como parámetro.
 * Aquí no se concatena nada del usuario, ni siquiera un número.
 */
public final class Paginacion {

    /** Tope duro de filas por página. Ninguna petición descarga la tabla entera. */
    public static final int MAX_PAGINA = 200;

    /** Tamaño por defecto cuando la petición no dice nada. */
    public static final int PAGINA_DEFECTO = 25;

    private Paginacion() {}

    /**
     * Ejecuta el par conteo + página.
     *
     * @param sqlItems SELECT completo SIN LIMIT/OFFSET — constante del código
     * @param sqlCount SELECT count(*) equivalente — constante del código
     * @param args     parámetros, los MISMOS y en el mismo orden para ambos SQL
     * @return sobre {items, total, page, size}
     */
    public static Map<String, Object> paginar(JdbcTemplate pg, String sqlItems, String sqlCount,
                                              Object[] args, int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Integer total = pg.queryForObject(sqlCount, Integer.class, args);

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = limit;
        pageArgs[args.length + 1] = offset;

        List<Map<String, Object>> items =
                pg.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total == null ? 0 : total);
        res.put("page", Math.max(page, 0));
        res.put("size", limit);
        return res;
    }

    /**
     * Página SIN conteo. {@code total} viaja como {@code -1}, que el cliente lee
     * como «no se recalculó, conserva el que ya tenías».
     *
     * Existe porque el conteo exacto es lo CARO cuando la tabla tiene RLS: sobre
     * `pedido` (2.999.993 filas) un {@code count(*)} pasa de 78 ms como
     * superusuario a 4.411 ms bajo `grp_administrador`, porque las políticas
     * llevan funciones no «leakproof» y el plan deja de ser paralelo. Recontar
     * en CADA cambio de página pagaría esos segundos una y otra vez para
     * devolver siempre el mismo número.
     */
    public static Map<String, Object> paginarSinTotal(JdbcTemplate pg, String sqlItems,
                                                      Object[] args, int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = limit;
        pageArgs[args.length + 1] = offset;

        List<Map<String, Object>> items =
                pg.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", -1);
        res.put("page", Math.max(page, 0));
        res.put("size", limit);
        return res;
    }

    /** Normaliza el tamaño pedido: nunca 0, nunca por encima del tope. */
    public static int tamano(Integer size) {
        return size == null ? PAGINA_DEFECTO : Math.min(Math.max(size, 1), MAX_PAGINA);
    }

    /** Normaliza la página pedida: nunca negativa. */
    public static int pagina(Integer page) {
        return page == null ? 0 : Math.max(page, 0);
    }
}
