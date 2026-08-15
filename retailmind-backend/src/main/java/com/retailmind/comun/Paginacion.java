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

    /**
     * Tope del CONTEO. Hasta aquí el total es exacto; a partir de aquí el sobre
     * dice {@code totalEsMinimo = true} y la cifra significa «al menos esto».
     *
     * <h3>Por qué hay tope y por qué es este</h3>
     * El conteo bajo RLS cuesta una llamada a {@code esta_en_horario(
     * fn_grupo_actual())} POR FILA EXAMINADA. Ese predicado no referencia
     * ninguna columna, así que debería izarse a un {@code One-Time Filter}; como
     * qual de SEGURIDAD no lo hace, y PostgreSQL lo evalúa fila a fila. Medido
     * sobre `pedido`: {@code count(*)} tarda 77 ms como superusuario y
     * <b>4.341 ms</b> bajo `grp_administrador`, con la función llamada 2.999.993
     * veces. Comprobado que NO se puede evitar desde la consulta: repetir el
     * mismo predicado en el WHERE sí produce un {@code One-Time Filter} para la
     * copia del usuario, pero el de la política sigue ejecutándose por fila
     * (4.541 ms). La única salida sin tocar la política —y no se toca— es no
     * examinar los 3 M de filas.
     *
     * El coste es lineal: ~1,5 µs por fila examinada (10.001 → 29 ms;
     * 100.001 → 160 ms; 1.000.001 → 1.662 ms). Con 200.000 el conteo queda
     * acotado en ~300 ms y solo lo alcanzan los listados verdaderamente
     * gigantes; todo lo demás sigue siendo EXACTO.
     *
     * <h3>Por qué no se usa `reltuples`</h3>
     * La estimación del planificador ignora RLS. Para `pedido` acierta al
     * 0,11 % —2.996.725 contra 2.999.993— con el rol de administración, pero a
     * un CLIENTE, cuya `pol_cliente_propio` lo deja en 21 pedidos, le diría
     * 2.999.993. Eso no es un total aproximado: es un total FALSO, y declararlo
     * como aproximado no lo arregla. El conteo con tope, en cambio, cuenta la
     * consulta REAL, así que respeta RLS por construcción.
     */
    public static final int TOPE_CONTEO = 200_000;

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
        return paginar(pg, sqlItems, args, sqlCount, args, page, size);
    }

    /**
     * Variante con listas de parámetros DISTINTAS para la página y el conteo.
     *
     * Existe porque no siempre coinciden: la bandeja de tickets calcula
     * {@code asignado_a_mi} en el SELECT, así que su consulta de página lleva un
     * parámetro que el {@code count(*)} no tiene —y los parámetros de JDBC son
     * POSICIONALES, no por nombre—. Reutilizar la misma lista desplazaría todos
     * los filtros una posición y la consulta fallaría o, peor, filtraría por el
     * valor equivocado.
     *
     * @param argsItems parámetros de {@code sqlItems}, en su orden textual
     * @param argsCount parámetros de {@code sqlCount}, en su orden textual
     */
    public static Map<String, Object> paginar(JdbcTemplate pg,
                                              String sqlItems, Object[] argsItems,
                                              String sqlCount, Object[] argsCount,
                                              int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Integer total = pg.queryForObject(sqlCount, Integer.class, argsCount);

        Object[] pageArgs = new Object[argsItems.length + 2];
        System.arraycopy(argsItems, 0, pageArgs, 0, argsItems.length);
        pageArgs[argsItems.length] = limit;
        pageArgs[argsItems.length + 1] = offset;

        List<Map<String, Object>> items =
                pg.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total == null ? 0 : total);
        res.put("totalEsMinimo", false);   // conteo exacto: no hubo tope
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
        res.put("totalEsMinimo", false);   // no se recontó: manda el total previo
        res.put("page", Math.max(page, 0));
        res.put("size", limit);
        return res;
    }

    /**
     * Página con el conteo ACOTADO a {@link #TOPE_CONTEO}.
     *
     * A diferencia de {@link #paginar}, aquí no se recibe un
     * {@code SELECT count(*) …} ya montado sino el CUERPO de la consulta —el
     * {@code FROM … WHERE …}, sin SELECT y sin ORDER BY—, porque el tope tiene
     * que ir DENTRO: {@code SELECT count(*) FROM (SELECT 1 <cuerpo> LIMIT ?) t}.
     * Envolver el conteo ya escrito no serviría (el LIMIT quedaría fuera y no
     * cortaría nada), y envolver el SQL de la página tampoco: lleva ORDER BY, y
     * ordenar 3 M de filas para luego cortarlas es peor que contarlas.
     *
     * El resultado añade {@code totalEsMinimo} al sobre: {@code false} cuando el
     * número es exacto y {@code true} cuando se llegó al tope, en cuyo caso el
     * total significa «hay AL MENOS esto» y la pantalla debe decirlo. Un total
     * que miente sin avisar es peor que uno lento.
     *
     * @param cuerpoConteo {@code FROM … WHERE …} — constante del código
     */
    public static Map<String, Object> paginarConTope(JdbcTemplate pg, String sqlItems,
                                                     String cuerpoConteo, Object[] args,
                                                     int page, int size) {
        int limit = Math.min(Math.max(size, 1), MAX_PAGINA);
        int offset = Math.max(page, 0) * limit;

        Object[] argsConteo = new Object[args.length + 1];
        System.arraycopy(args, 0, argsConteo, 0, args.length);
        argsConteo[args.length] = TOPE_CONTEO + 1;
        Integer contado = pg.queryForObject(
                "SELECT count(*) FROM (SELECT 1 " + cuerpoConteo + " LIMIT ?) t",
                Integer.class, argsConteo);
        int total = contado == null ? 0 : contado;
        boolean esMinimo = total > TOPE_CONTEO;
        if (esMinimo) {
            total = TOPE_CONTEO;   // se publica el tope, no el tope+1 del sondeo
        }

        Object[] pageArgs = new Object[args.length + 2];
        System.arraycopy(args, 0, pageArgs, 0, args.length);
        pageArgs[args.length] = limit;
        pageArgs[args.length + 1] = offset;
        List<Map<String, Object>> items =
                pg.queryForList(sqlItems + " LIMIT ? OFFSET ?", pageArgs);

        Map<String, Object> res = new HashMap<>();
        res.put("items", items);
        res.put("total", total);
        res.put("totalEsMinimo", esMinimo);
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
