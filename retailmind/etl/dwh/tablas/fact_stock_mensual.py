"""
etl/dwh/tablas/fact_stock_mensual.py — F7 del modelo (§5.7 del diseño).

El saldo y el valor del inventario AL CIERRE DE CADA MES.
Grano: (mes, bodega, variante). **21.122 filas.** Alimenta OTD-INV-09 (el
objetivo) y OTD-INV-04 (stock promedio como denominador de la rotación).

Es la ÚNICA tabla del modelo que no es una agregación de un hecho atómico sino
una **reconstrucción**, y la única `TareaDerivada`: se calcula DENTRO de
ClickHouse desde `fact_movimiento_inventario` y no vuelve a consultar
PostgreSQL. El diseño la describe como «la única transformación del pipeline que
puede dar un número plausible y equivocado». De ahí que traiga su propia prueba
definitiva, que corre ANTES de publicar.

═══════════════════════════════════════════════════════════════════════════════
1. LA PRUEBA DEFINITIVA — y por qué gatilla la publicación
═══════════════════════════════════════════════════════════════════════════════

    El `stock_cierre` del ÚLTIMO mes debe coincidir EXACTAMENTE con
    `inventario.stock_actual`, en las 1.406 posiciones, UNA POR UNA.

No en agregado. Un total que cuadra con posiciones cruzadas es justo la clase de
número plausible contra la que avisa el diseño: dos variantes que se
intercambian el saldo dan la misma suma. `validar()` abre su propia conexión a
PostgreSQL, trae las 1.406 posiciones y las compara par a par contra el último
mes del almacén. Si UNA sola difiere, la tarea aborta y la tabla no se publica.

Es una prueba fuerte porque recorre el pipeline entero: si el kardex perdió un
movimiento, si el pivote mensual tomó el saldo equivocado, si el arrastre de los
meses sin movimiento se desalineó o si el mes se calculó en otra zona horaria,
la posición afectada deja de cuadrar.

Y no es trivial de pasar: solo **332 de los 1.406** pares tienen movimiento en el
último mes (2026-07). Los otros **1.074 llegan por arrastre**, es decir, el
camino que la prueba ejercita de verdad es el del punto 3.

═══════════════════════════════════════════════════════════════════════════════
2. LA RECONSTRUCCIÓN: LEER EL SALDO, NO RECALCULARLO
═══════════════════════════════════════════════════════════════════════════════

El instinto es sumar `cantidad_con_signo` desde el principio de los tiempos
hasta el cierre de cada mes. Funciona, es O(n²) y —peor— **repite un cálculo que
el sistema ya hizo y garantiza**. `fact_movimiento_inventario` verificó antes de
publicarse que la cadena de `stock_nuevo` está íntegra: arranca en 0, la
ecuación `stock_nuevo = stock_anterior + cantidad×factor` se cumple en los
13.287 movimientos, ningún eslabón está roto y la suma por par reproduce
`inventario.stock_actual` en los 1.406. Sobre esa garantía:

    stock_cierre(v, b, mes) = argMax(stock_nuevo, (fecha, movimiento_id))

El saldo que dejó el ÚLTIMO movimiento del mes. Una pasada, y la desviación
entre ambos métodos es imposible por construcción porque solo hay un método.

El desempate `(fecha, movimiento_id)` no es decorativo: hay movimientos con la
misma marca de tiempo dentro de un mismo par, y `argMax` sobre `fecha` a secas
elegiría uno arbitrariamente. La tupla replica exactamente la clave
`(fecha_creacion, id)` con la que PostgreSQL encadenó el kardex.

═══════════════════════════════════════════════════════════════════════════════
3. LOS MESES SIN MOVIMIENTO — 12.529 de las 21.122 filas
═══════════════════════════════════════════════════════════════════════════════

Solo 8.593 celdas (variante, bodega, mes) tienen movimiento. Las otras 12.529
—el **59 %** de la tabla— son meses en que la posición no se movió, y el informe
las necesita: una serie mensual con huecos no se puede leer, y una posición que
desaparece del gráfico se interpreta como stock cero.

Se arrastra el último saldo conocido con
`last_value(...) IGNORE NULLS OVER (PARTITION BY variante, bodega ORDER BY mes
ROWS UNBOUNDED PRECEDING)` y la fila se marca con `mes_sin_movimiento = 1`.

DOS DETALLES QUE HACEN QUE ESTO SEA CORRECTO Y NO CASI:

  * **El `NULL` se fabrica desde `movimientos_mes`, no desde el saldo.** En
    ClickHouse un `LEFT JOIN` sin `join_use_nulls` rellena el lado ausente con
    el valor DEFECTO del tipo, no con NULL: `stock_cierre` llegaría como 0 y
    `IGNORE NULLS` no tendría nada que ignorar — el arrastre se convertiría en
    «cada mes sin movimiento vale 0», que es plausible y falso. Se usa
    `if(movs = 0, NULL, cierre)`, donde `movs = 0` SÍ distingue con certeza la
    celda ausente, porque una celda presente tiene por construcción al menos un
    movimiento. Un cierre legítimo de 0 unidades se conserva.

  * **La malla arranca en el primer movimiento de CADA par, no en el primer mes
    del período.** Ver la corrección C3B.4.

═══════════════════════════════════════════════════════════════════════════════
4. 21.122 FILAS, NO 26.700 — corrección C3B.4
═══════════════════════════════════════════════════════════════════════════════

§5.7 estima «19 meses × 1.406 posiciones ≈ 26.700 filas» y su paso 2 pide un
«producto cartesiano contra los 19 meses». Eso fabricaría **5.592 filas de stock
0 para posiciones que todavía no existían**:

    pares (variante, bodega) ......................... 1.406
    pares cuyo primer movimiento es el primer mes ....   515
    filas del cartesiano completo (19 × 1.406) ....... 26.714
    filas reales (desde el primer movimiento) ........ 21.122
                                          diferencia    5.592

Un stock 0 en marzo de 2025 para una variante que entró al catálogo en octubre
NO es «había cero unidades»: es «esa posición no existía». La diferencia importa
en INV-09, que es una serie de evolución: 5.592 ceros al principio de la serie
aplanan la curva del capital inmovilizado y hacen que el almacén parezca haber
crecido desde la nada cuando en realidad se fueron incorporando posiciones.

La malla se genera por tanto desde `min(mes)` de cada par hasta el último mes del
período, sin huecos — que es también la definición de continuidad que valida
`validar_dwh.py`.

═══════════════════════════════════════════════════════════════════════════════
5. LA VALORIZACIÓN ES A COSTO VIGENTE, Y HAY QUE DECIRLO EN PANTALLA
═══════════════════════════════════════════════════════════════════════════════

`valor_cierre = stock_cierre × dim_producto.costo`, donde `costo` es el **costo
de hoy**. No existe costo histórico en el sistema (§8.3 del diseño; el margen se
computa en vivo contra `producto_variante.costo`, documentado en `CLAUDE.md`).

Por tanto **INV-09 responde «cuántas unidades había cada mes, valoradas a precio
de hoy»**, que NO es «cuánto valía la bodega aquel mes». Es una serie de volumen
a moneda constante — que para la pregunta del objetivo («¿se está llenando o
vaciando de capital la bodega?») es incluso más limpia, porque aísla el efecto
volumen del efecto precio. Pero presentarla como valor histórico sería falso, y
por eso el informe la declara en pantalla y no solo en la documentación.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from etl.dwh.conexiones import CH_DATABASE, ZONA_HORARIA, get_pg_connection
from etl.dwh.tarea import TareaDerivada

#: Tabla de la que se deriva. Se nombra una sola vez.
ORIGEN = "fact_movimiento_inventario"


class FactStockMensual(TareaDerivada):

    nombre = "fact_stock_mensual"

    #: `run_etl.py` debe correr estas dos antes. Es la ÚNICA dependencia real
    #: del grafo del modelo (§7.1), más `dim_producto` por la valorización.
    depende_de = (ORIGEN, "dim_producto")

    def __init__(self):
        super().__init__()
        #: Resultado de la prueba definitiva, para que `cargar.py` pueda
        #: informarlo aunque la validación haya pasado.
        self.coinciden: int = 0
        self.difieren: int = 0

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        """
        Partición por AÑO y no por mes: 19 particiones de ~1.100 filas ya serían
        demasiado pequeñas, y ClickHouse penaliza el exceso de particiones más
        de lo que premia el podado (§5.7).
        """
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            mes                  Date,
            bodega               LowCardinality(String),
            bodega_codigo        LowCardinality(String),
            producto_variante_id UInt32,
            sku                  String,
            producto_nombre      String,
            categoria            LowCardinality(String),
            marca                LowCardinality(String),
            stock_cierre         Int32,
            entradas_mes         Int32,
            salidas_mes          Int32,
            movimientos_mes      UInt16,
            costo_unitario       Decimal(14,2),
            valor_cierre         Decimal(14,2),
            mes_sin_movimiento   UInt8,
            fecha_carga          DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYear(mes)
        ORDER BY (mes, bodega, categoria, producto_variante_id)
        """

    # ── Derivación dentro de ClickHouse ──────────────────────────────────────

    def sql_insert(self, tabla_destino: str) -> str:
        """
        Los cuatro CTE son los cuatro pasos de §5.7, en orden:

            celda  → el saldo de cierre de cada (variante, bodega, mes) CON
                     movimiento, leído y no recalculado (punto 2).
            meses  → los 19 meses del período, tomados del propio hecho para no
                     depender de `dim_fecha` ni de una constante.
            par    → cada posición con el mes de su PRIMER movimiento (C3B.4).
            malla  → la rejilla sin huecos desde ese primer mes hasta el final.

        Y la ventana del `serie` hace el arrastre. La valorización entra al
        final, uniendo `dim_producto` con `FINAL` porque es un
        `ReplacingMergeTree`.
        """
        return f"""
        INSERT INTO {tabla_destino}
        (mes, bodega, bodega_codigo, producto_variante_id, sku, producto_nombre,
         categoria, marca, stock_cierre, entradas_mes, salidas_mes, movimientos_mes,
         costo_unitario, valor_cierre, mes_sin_movimiento, fecha_carga)
        WITH
        celda AS (
            SELECT producto_variante_id                            AS variante,
                   bodega,
                   mes,
                   argMax(stock_nuevo, (fecha, movimiento_id))     AS cierre,
                   sumIf(cantidad_con_signo, cantidad_con_signo > 0)  AS entradas,
                   -sumIf(cantidad_con_signo, cantidad_con_signo < 0) AS salidas,
                   toUInt16(count())                               AS movs
            FROM {CH_DATABASE}.{ORIGEN}
            GROUP BY variante, bodega, mes
        ),
        meses AS (
            SELECT DISTINCT mes FROM {CH_DATABASE}.{ORIGEN}
        ),
        par AS (
            -- `bodega_codigo` es constante dentro de la bodega, así que `any()`
            -- no elige nada: solo la arrastra por la malla sin un JOIN más.
            SELECT producto_variante_id AS variante, bodega,
                   any(bodega_codigo) AS bodega_codigo, min(mes) AS mes_ini
            FROM {CH_DATABASE}.{ORIGEN}
            GROUP BY variante, bodega
        ),
        malla AS (
            SELECT p.variante AS variante, p.bodega AS bodega,
                   p.bodega_codigo AS bodega_codigo, m.mes AS mes
            FROM par p
            CROSS JOIN meses m
            WHERE m.mes >= p.mes_ini
        ),
        serie AS (
            SELECT
                ma.variante                AS variante,
                ma.bodega                  AS bodega,
                ma.bodega_codigo           AS bodega_codigo,
                ma.mes                     AS mes,
                c.movs                     AS movimientos_mes,
                c.entradas                 AS entradas_mes,
                c.salidas                  AS salidas_mes,
                -- El NULL se fabrica desde `movs` y NO desde `cierre`: un LEFT
                -- JOIN de ClickHouse rellena con el DEFECTO del tipo (0), no con
                -- NULL, y `IGNORE NULLS` no tendría nada que ignorar.
                last_value(if(c.movs = 0, NULL, c.cierre)) IGNORE NULLS OVER (
                    PARTITION BY ma.variante, ma.bodega
                    ORDER BY ma.mes
                    ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                )                          AS stock_cierre
            FROM malla ma
            LEFT JOIN celda c
                   ON  c.variante = ma.variante
                   AND c.bodega   = ma.bodega
                   AND c.mes      = ma.mes
        )
        SELECT
            s.mes,
            s.bodega,
            s.bodega_codigo,
            s.variante                                       AS producto_variante_id,
            d.sku,
            d.producto_nombre,
            d.categoria,
            d.marca,
            assumeNotNull(s.stock_cierre)                    AS stock_cierre,
            s.entradas_mes,
            s.salidas_mes,
            s.movimientos_mes,
            d.costo                                          AS costo_unitario,
            CAST(assumeNotNull(s.stock_cierre) * d.costo AS Decimal(14,2))
                                                             AS valor_cierre,
            if(s.movimientos_mes = 0, 1, 0)                  AS mes_sin_movimiento,
            now()                                            AS fecha_carga
        FROM serie s
        JOIN (SELECT producto_variante_id, sku, producto_nombre, categoria, marca, costo
              FROM {CH_DATABASE}.dim_producto FINAL) d
          ON d.producto_variante_id = s.variante
        """

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Las cifras se calculan en PostgreSQL con la MISMA definición que la
        derivación usa en ClickHouse, pero escritas de otra manera (aquí
        `generate_series`, allí un `CROSS JOIN` contra los meses del hecho). Es
        deliberado: si la validación reusara la consulta de la tarea, ambas
        compartirían el error y el control sería una tautología.
        """
        return f"""
        WITH mov AS (
            SELECT mi.producto_variante_id AS v, mi.bodega_id AS b,
                   (date_trunc('month',
                       mi.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
                   mi.cantidad * tm.factor AS con_signo
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        ),
        meses AS (
            SELECT generate_series((SELECT min(mes) FROM mov),
                                   (SELECT max(mes) FROM mov),
                                   interval '1 month')::date AS mes
        ),
        par AS (
            SELECT v, b, min(mes) AS mes_ini FROM mov GROUP BY v, b
        ),
        malla AS (
            SELECT p.v, p.b, m.mes FROM par p JOIN meses m ON m.mes >= p.mes_ini
        )
        SELECT
            (SELECT count(*) FROM malla)                             AS filas,
            (SELECT count(*) FROM par)                               AS pares,
            (SELECT count(*) FROM meses)                             AS meses,
            (SELECT count(*) FROM (SELECT DISTINCT v, b, mes FROM mov) x)
                                                                     AS celdas_con_movimiento,
            (SELECT SUM(cantidad) FROM movimiento_inventario mi
             JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
             WHERE tm.factor > 0)                                    AS entradas,
            (SELECT SUM(cantidad) FROM movimiento_inventario mi
             JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
             WHERE tm.factor < 0)                                    AS salidas,
            (SELECT SUM(stock_actual) FROM inventario)               AS stock_final,
            (SELECT count(*) FROM inventario)                        AS posiciones,
            (SELECT (SELECT max(mes) FROM meses))                    AS ultimo_mes
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = self._validar_forma(client, tabla_staging, controles)
        errores += self._validar_continuidad(client, tabla_staging)
        errores += self.prueba_definitiva(client, tabla_staging, controles)
        return errores

    def _validar_forma(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []
        fila = client.query(f"""
            SELECT count(),
                   countDistinct((producto_variante_id, bodega)),
                   countDistinct(mes),
                   countIf(movimientos_mes > 0),
                   countIf(mes_sin_movimiento = 1),
                   countIf(mes_sin_movimiento = 1 AND movimientos_mes > 0),
                   countIf(stock_cierre < 0),
                   sum(entradas_mes), sum(salidas_mes),
                   countIf(costo_unitario = 0),
                   countIf(valor_cierre != CAST(stock_cierre * costo_unitario
                                                AS Decimal(14,2)))
            FROM {tabla_staging}
        """).result_rows[0]
        (total, pares, meses, con_mov, arrastradas, marca_incoherente,
         negativos, entradas, salidas, sin_costo, valor_roto) = fila

        for clave, valor_ch, etiqueta in (
            ("pares", pares, "pares (variante, bodega)"),
            ("meses", meses, "meses del período"),
            ("celdas_con_movimiento", con_mov, "celdas con movimiento"),
            ("entradas", entradas, "unidades de entrada"),
            ("salidas", salidas, "unidades de salida"),
        ):
            valor_pg = controles.get(clave)
            if valor_pg is not None and int(valor_ch) != int(valor_pg):
                errores.append(f"{etiqueta}: PostgreSQL {valor_pg} vs ClickHouse "
                               f"{valor_ch} (diferencia {int(valor_ch) - int(valor_pg)}).")

        if arrastradas != total - con_mov:
            errores.append(f"`mes_sin_movimiento` no cubre los huecos: {arrastradas} "
                           f"marcadas frente a {total - con_mov} filas sin movimiento.")
        if marca_incoherente:
            errores.append(f"{marca_incoherente} filas marcadas como mes sin "
                           f"movimiento tienen movimientos.")
        # Exigido explícitamente por el encargo de la fase, y además invariante
        # del negocio: un almacén no debe cerrar ningún mes en negativo.
        if negativos:
            errores.append(f"{negativos} filas con `stock_cierre` NEGATIVO. El origen "
                           f"no tiene ninguna: el arrastre o el pivote mensual está mal.")
        if valor_roto:
            errores.append(f"{valor_roto} filas cuyo `valor_cierre` no se reproduce "
                           f"desde `stock_cierre × costo_unitario`.")
        if sin_costo:
            # No aborta: una variante sin costo es un dato del catálogo, no un
            # fallo de la derivación. Pero INV-09 la valoriza en 0 y conviene saberlo.
            errores.append(f"{sin_costo} filas con costo 0: INV-09 las valoriza en "
                           f"cero. Revisa `producto_variante.costo` antes de publicar "
                           f"una serie de capital inmovilizado.")
        return errores

    def _validar_continuidad(self, client, tabla_staging: str) -> list[str]:
        """
        Cada par debe tener fila en TODOS los meses desde su primer movimiento
        hasta el último del período, sin huecos. Un hueco no rompe ninguna suma
        —por eso hace falta buscarlo a propósito—, pero parte la serie de INV-09
        y hace que una posición desaparezca del gráfico un mes y vuelva al
        siguiente.
        """
        huecos, cortos = client.query(f"""
            SELECT
                countIf(meses_reales != meses_esperados),
                countIf(mes_max != (SELECT max(mes) FROM {tabla_staging}))
            FROM (
                SELECT producto_variante_id, bodega,
                       count() AS meses_reales,
                       min(mes) AS mes_min, max(mes) AS mes_max,
                       dateDiff('month', min(mes),
                                (SELECT max(mes) FROM {tabla_staging})) + 1
                           AS meses_esperados
                FROM {tabla_staging}
                GROUP BY producto_variante_id, bodega)
        """).result_rows[0]

        errores = []
        if huecos:
            errores.append(
                f"{huecos} pares (variante, bodega) tienen HUECOS en su serie "
                f"mensual: les faltan meses entre su primer movimiento y el final "
                f"del período. INV-09 los mostraría entrando y saliendo del gráfico.")
        if cortos:
            errores.append(
                f"{cortos} pares no llegan al último mes del período. Toda posición "
                f"viva debe arrastrarse hasta el cierre, o la prueba definitiva no "
                f"tiene contra qué comparar.")
        return errores

    # ── LA PRUEBA DEFINITIVA ─────────────────────────────────────────────────

    def prueba_definitiva(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        Posición por posición: el `stock_cierre` del último mes contra
        `inventario.stock_actual`.

        Abre su propia conexión a PostgreSQL —lo que ninguna otra tarea del
        pipeline necesita hacer— porque el contrato de `sql_controles()` devuelve
        UNA fila y esta prueba necesita 1.406. Es el precio de la única
        validación del modelo que no se puede resumir en un escalar: un total
        que cuadra con dos posiciones intercambiadas da exactamente la misma
        suma, y ese es el «número plausible y equivocado» del que avisa §5.7.
        """
        conn = get_pg_connection()
        try:
            with conn.cursor() as cur:
                cur.execute("""
                    SELECT i.producto_variante_id, b.nombre, i.stock_actual
                    FROM inventario i
                    JOIN bodega b ON b.id = i.bodega_id
                    ORDER BY 1, 2
                """)
                esperado = {(int(v), b): int(s) for v, b, s in cur.fetchall()}
        finally:
            conn.close()

        filas = client.query(f"""
            SELECT producto_variante_id, bodega, stock_cierre
            FROM {tabla_staging}
            WHERE mes = (SELECT max(mes) FROM {tabla_staging})
            ORDER BY 1, 2
        """).result_rows
        obtenido = {(int(v), b): int(s) for v, b, s in filas}

        faltan = sorted(set(esperado) - set(obtenido))
        sobran = sorted(set(obtenido) - set(esperado))
        difieren = sorted(
            (k, esperado[k], obtenido[k])
            for k in set(esperado) & set(obtenido)
            if esperado[k] != obtenido[k]
        )

        self.coinciden = len(esperado) - len(faltan) - len(difieren)
        self.difieren = len(difieren) + len(faltan) + len(sobran)

        if not (faltan or sobran or difieren):
            return []

        detalle = []
        if faltan:
            detalle.append(f"{len(faltan)} posiciones de `inventario` NO están en el "
                           f"último mes del almacén (p. ej. {faltan[:3]})")
        if sobran:
            detalle.append(f"{len(sobran)} posiciones del almacén no existen en "
                           f"`inventario` (p. ej. {sobran[:3]})")
        if difieren:
            muestra = ", ".join(f"{k}: PG {pg_v} vs CH {ch_v}"
                                for k, pg_v, ch_v in difieren[:3])
            detalle.append(f"{len(difieren)} posiciones con saldo DISTINTO ({muestra})")

        return [
            "PRUEBA DEFINITIVA FALLIDA — la reconstrucción no reproduce el stock "
            f"real: {self.coinciden} de {len(esperado)} posiciones coinciden. "
            + " · ".join(detalle)
            + ". La tabla NO se publica: una serie de inventario que no cierra "
              "contra `inventario.stock_actual` produce cifras plausibles y falsas."
        ]
