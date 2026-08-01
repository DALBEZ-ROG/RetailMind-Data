"""
etl/dwh/tablas/fact_movimiento_inventario.py — F6 del modelo (§5.6 del diseño).

El kardex completo: todo el movimiento físico de mercancía.
Grano: un movimiento. 13.287 filas. Alimenta OTD-INV-04, INV-10 y la columna
«última salida» de VEN-04 — y es el insumo del que se deriva
`fact_stock_mensual`, la tabla más delicada del pipeline.

═══════════════════════════════════════════════════════════════════════════════
1. LA CARDINALIDAD QUE HACE SEGURA LA FASE — verificada, no supuesta
═══════════════════════════════════════════════════════════════════════════════

§5.6 afirma que el kardex cubre exactamente los 1.406 pares (variante, bodega)
de `inventario`. Se comprobó antes de escribir nada, porque de ello depende que
la reconstrucción de §5.7 sea posible:

    movimientos ............................... 13.287
    pares (variante, bodega) en el kardex ..... 1.406
    posiciones en `inventario` ................ 1.406
    pares del kardex SIN posición ............. 0
    posiciones SIN historia en el kardex ...... 0

Y, sobre todo, **la invariante del kardex se cumple par por par**:

    Σ (cantidad × factor) por (variante, bodega)  =  inventario.stock_actual
        pares que cuadran .......... 1.406 de 1.406
        pares que difieren ......... 0
        total ...................... 133.220 uds en ambos lados

Más la integridad de la cadena, sobre los 13.287 movimientos:

    stock_nuevo ≠ stock_anterior + cantidad×factor ......... 0
    stock_anterior ≠ stock_nuevo del movimiento previo ..... 0
    cadenas que NO arrancan en 0 ........................... 0
    saldos negativos en cualquier punto .................... 0

Ese último bloque es la licencia para el atajo de §5.7: como el sistema ya
encadenó `stock_nuevo` y la cadena arranca en 0 y no se rompe nunca, el saldo de
cierre de un mes es *leer* el último `stock_nuevo` del mes, no *recalcular* la
suma desde el principio de los tiempos.

═══════════════════════════════════════════════════════════════════════════════
2. EL ORDER BY REPLICA LA CADENA REAL, Y ESO NO ES ESTÉTICA
═══════════════════════════════════════════════════════════════════════════════

    ORDER BY (producto_variante_id, bodega, fecha, movimiento_id)

La regla operativa del sistema es que el kardex se encadena por
`(fecha_creacion, id)` **dentro de cada par (variante, bodega)** — está
documentada en `CLAUDE.md` (scripts 78 y 79-84) y costó reescribir 2.234 filas
en su momento. Ordenar la tabla columnar por esa misma clave hace que la
reconstrucción de §5.7 sea una **lectura secuencial**, sin `ORDER BY` en tiempo
de consulta.

`cantidad_con_signo` se precalcula porque INV-04 e INV-10 suman entradas y
salidas juntas: multiplicar por `factor` en cada consulta invita a que alguien lo
olvide una vez, y el error resultante —sumar salidas como si fueran entradas— da
un número plausible.

═══════════════════════════════════════════════════════════════════════════════
3. LA TRAMPA DE INV-10: `naturaleza = 'ajuste'` NO SON LOS AJUSTES
═══════════════════════════════════════════════════════════════════════════════

Corrección **C3B.1**, y la más cara de esta fase si se pasa por alto. §2.3 y §5.6
mandan INV-10 («mermas y sobrantes por motivo») contra esta tabla, y la columna
`naturaleza` ofrece el valor `'ajuste'` como filtro obvio. Es el filtro
equivocado:

    tipo_movimiento con naturaleza 'ajuste' ......... 399 movs / 34.437 uds
      ├─ entrada_ajuste ← referencia 'inventario_inicial' ... 343 / 34.210
      ├─ entrada_ajuste ← referencia 'ajuste_inventario' ....  20 /     90
      └─ salida_ajuste  ← referencia 'ajuste_inventario' ....  36 /    137

**La apertura de inventario se registró como `entrada_ajuste`.** Filtrar INV-10
por naturaleza mete 34.210 unidades de saldo inicial dentro de los «sobrantes»,
donde los sobrantes reales son 90: un error de **380×** que se lee como un
almacén con mermas catastróficas.

El filtro correcto es `referencia_tipo = 'ajuste_inventario'` (56 movimientos,
227 unidades), que es además lo que el catálogo táctico ya decía. Para que la
consulta correcta sea también la fácil, esta tabla trae `es_ajuste_real` como
columna: un `UInt8` precalculado que INV-10 usa directamente y que hace el error
imposible de cometer por descuido.

═══════════════════════════════════════════════════════════════════════════════
4. `ajuste_motivo` ES TEXTO LIBRE CON PREFIJO DE MÁQUINA — corrección C3B.2
═══════════════════════════════════════════════════════════════════════════════

§5.6 lo trata como una etiqueta lista para agrupar (`LowCardinality`), y el
catálogo habla de «7 motivos tipificados». No existe tabla `motivo_ajuste`:
`ajuste_inventario.motivo` es un `text` libre, y el seed le antepuso el SKU y la
cantidad porque la cabecera del ajuste no tiene detalle de líneas:

    [SKU-P1340 x4] Merma reportada por el operador de bodega · ANULADO: El conteo…
    └─── prefijo de máquina ──┘                                └── sufijo ──┘

Cargado en crudo, `ajuste_motivo` tiene **53 valores distintos sobre 53 ajustes**
—uno por ajuste, porque el SKU va dentro— y como clave de agrupación de INV-10 no
sirve para nada: el informe mostraría 53 «motivos» de una fila cada uno.

Se limpia en `transformar()` quitando el prefijo `[SKU xN]` y el sufijo
`· ANULADO: …`. Quedan **11 motivos**: los 8 del seed más 3 tecleados desde la
aplicación durante el desarrollo («Merma por producto danado», «Merma por 1
falta», «Sobrante en conteo fisico julio 2026»).

**Aquí NO se mapean sinónimos, a diferencia de C3.3.** Quitar una decoración que
puso una máquina es una limpieza; decidir que «Merma por producto danado» y
«Producto roto durante el almacenamiento» son el mismo motivo sería una opinión
sobre datos que un humano escribió a propósito. Se cargan los 11 y el informe
agrupa además por `ajuste_tipo`, que sí es una columna controlada del motor
(`negativo` / `positivo` / `conteo`).

Lo que sí hay es **detección de deriva**: un motivo que no esté en la lista
conocida se carga tal cual y se registra en la bitácora, para que aparezca en el
log en vez de colarse callando en el informe.

═══════════════════════════════════════════════════════════════════════════════
5. `costo_unitario`: EL DEL MOVIMIENTO, Y 177 QUE NO LO TIENEN — C3B.3
═══════════════════════════════════════════════════════════════════════════════

§5.6 lo declara `Decimal(14,2)` sin admitir ausencia, y avisa —con razón— de que
es el costo **del movimiento** y no el vigente de la variante. Lo que no dice es
que 177 movimientos no llevan ninguno:

    movimientos sin costo_unitario ......... 177
      ├─ referencia 'transferencia_bodega' ... 121   (mover no valoriza)
      └─ referencia 'ajuste_inventario' ......  56   (ajustar tampoco)

Es coherente: una transferencia entre bodegas no cambia el valor del inventario
y un ajuste tampoco lleva precio de compra. Se cargan con `costo_unitario = 0` y
`valor_movimiento = 0`, pero **marcados con `sin_costo = 1`**: sin esa marca, un
movimiento sin valorizar es indistinguible de uno gratuito, y una consulta que
promedie costos incluiría 177 ceros que bajan la media sin que nadie lo vea.

Consecuencia práctica: **INV-10 no puede valorizar la merma con el costo del
movimiento**, porque los 56 movimientos de ajuste son exactamente los que no lo
traen. Valoriza con `dim_producto.costo` (vigente) y lo declara, igual que
INV-09.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

import re
from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Prefijo de máquina que el seed antepone al motivo: `[SKU-P1340 x4] `.
#: La cabecera del ajuste no tiene detalle de líneas, así que la variante y la
#: cantidad viven en este texto (documentado en `CLAUDE.md`, scripts 79-84).
_PREFIJO_SKU = re.compile(r"^\s*\[[^\]]*\]\s*")

#: Sufijo que el seed añade al anular: ` · ANULADO: El conteo posterior…`.
#: El motivo de la ANULACIÓN no es el motivo del AJUSTE; el estado ya viaja en
#: su propia columna.
_SUFIJO_ANULADO = re.compile(r"\s*·\s*ANULADO:.*$", re.DOTALL)

#: Motivos vistos hasta hoy. NO es un mapa de normalización —aquí no se remapea
#: nada (ver el punto 4 de la cabecera)—: es un detector de deriva. Lo que no
#: esté aquí se carga igual y se registra.
MOTIVOS_CONOCIDOS = frozenset({
    "Merma reportada por el operador de bodega",
    "Producto roto durante el almacenamiento",
    "Diferencia de conteo fisico: faltante",
    "Diferencia de conteo fisico: sobrante",
    "Correccion de error de registro: unidades duplicadas",
    "Correccion de error de registro en recepcion",
    "Producto caducado retirado del stock vendible",
    "Merma por manipulacion en bodega",
    # Tecleados desde la aplicación durante el desarrollo (2026-07):
    "Merma por producto danado",
    "Merma por 1 falta",
    "Sobrante en conteo fisico julio 2026",
})

#: El movimiento que SÍ es un ajuste de inventario de verdad (C3B.1).
REFERENCIA_AJUSTE = "ajuste_inventario"


class FactMovimientoInventario(TareaCarga):

    nombre = "fact_movimiento_inventario"

    def __init__(self):
        super().__init__()
        #: Motivos de ajuste no vistos antes, con su recuento.
        self.motivos_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            movimiento_id        UInt64,
            fecha                DateTime('{ZONA_HORARIA}'),
            mes                  Date,
            producto_variante_id UInt32,
            sku                  String,
            producto_nombre      String,
            categoria            LowCardinality(String),
            marca                LowCardinality(String),
            bodega               LowCardinality(String),
            bodega_codigo        LowCardinality(String),
            tipo_movimiento      LowCardinality(String),
            naturaleza           LowCardinality(String),
            factor               Int8,
            cantidad             UInt32,
            cantidad_con_signo   Int32,
            stock_anterior       Int32,
            stock_nuevo          Int32,
            costo_unitario       Decimal(14,2),
            sin_costo            UInt8,
            valor_movimiento     Decimal(14,2),
            referencia_tipo      LowCardinality(String),
            referencia_id        UInt64,
            es_ajuste_real       UInt8,
            ajuste_motivo        LowCardinality(String),
            ajuste_tipo          LowCardinality(String),
            ajuste_estado        LowCardinality(String),
            fecha_carga          DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha)
        ORDER BY (producto_variante_id, bodega, fecha, movimiento_id)
        """

    def columnas(self) -> list[str]:
        return [
            "movimiento_id", "fecha", "mes", "producto_variante_id", "sku",
            "producto_nombre", "categoria", "marca", "bodega", "bodega_codigo",
            "tipo_movimiento",
            "naturaleza", "factor", "cantidad", "cantidad_con_signo",
            "stock_anterior", "stock_nuevo", "costo_unitario", "sin_costo",
            "valor_movimiento", "referencia_tipo", "referencia_id",
            "es_ajuste_real", "ajuste_motivo", "ajuste_tipo", "ajuste_estado",
            "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        El `ORDER BY` reproduce la clave de ordenamiento de la tabla destino, que
        a su vez reproduce la cadena real del kardex. No es obligatorio para
        ClickHouse —el motor reordena al fusionar—, pero hace la carga
        reproducible y deja los lotes ya agrupados por par.

        El LEFT JOIN a `ajuste_inventario` va condicionado por `referencia_tipo`
        DENTRO del ON: `referencia_id` es un id polimórfico (apunta a pedido, a
        recepción, a devolución…) y sin esa condición un id 40 de pedido casaría
        con el ajuste 40 y pegaría un motivo de merma a una venta.
        """
        return f"""
        SELECT
            mi.id                                              AS movimiento_id,
            mi.fecha_creacion                                  AS fecha,
            (date_trunc('month',
                mi.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            mi.producto_variante_id,
            v.sku,
            p.nombre                                           AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')                AS categoria,
            COALESCE(mar.nombre, 'sin_marca')                  AS marca,
            b.nombre                                           AS bodega,
            -- El CÓDIGO viaja además del nombre para que los informes
            -- compuestos acepten EXACTAMENTE el mismo filtro de bodega que los
            -- simples ('BOD-01'/'BOD-02'). Un usuario que salta de INV-05 a
            -- INV-09 no debería tener que aprender otro vocabulario.
            b.codigo                                           AS bodega_codigo,
            tm.codigo                                          AS tipo_movimiento,
            tm.naturaleza,
            tm.factor,
            mi.cantidad,
            (mi.cantidad * tm.factor)                          AS cantidad_con_signo,
            mi.stock_anterior,
            mi.stock_nuevo,
            COALESCE(mi.costo_unitario, 0)                     AS costo_unitario,
            CASE WHEN mi.costo_unitario IS NULL THEN 1 ELSE 0 END AS sin_costo,
            COALESCE(mi.cantidad * mi.costo_unitario, 0)       AS valor_movimiento,
            COALESCE(mi.referencia_tipo, '')                   AS referencia_tipo,
            COALESCE(mi.referencia_id, 0)                      AS referencia_id,
            -- C3B.1: el ajuste DE VERDAD, ya resuelto. `naturaleza='ajuste'`
            -- incluiría los 343 movimientos de apertura de inventario.
            CASE WHEN mi.referencia_tipo = '{REFERENCIA_AJUSTE}'
                 THEN 1 ELSE 0 END                             AS es_ajuste_real,
            COALESCE(a.motivo, '')                             AS ajuste_motivo,
            COALESCE(a.tipo, '')                               AS ajuste_tipo,
            COALESCE(a.estado, '')                             AS ajuste_estado
        FROM movimiento_inventario mi
        JOIN tipo_movimiento tm     ON tm.id = mi.tipo_movimiento_id
        JOIN producto_variante v    ON v.id  = mi.producto_variante_id
        JOIN producto p             ON p.id  = v.producto_id
        JOIN bodega b               ON b.id  = mi.bodega_id
        LEFT JOIN producto_categoria pc
                                    ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c       ON c.id  = pc.categoria_id
        LEFT JOIN marca mar         ON mar.id = p.marca_id
        LEFT JOIN ajuste_inventario a
               ON mi.referencia_tipo = '{REFERENCIA_AJUSTE}'
              AND a.id = mi.referencia_id
        ORDER BY mi.producto_variante_id, b.nombre, mi.fecha_creacion, mi.id
        """

    # ── Transformación: limpiar el motivo del ajuste ─────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_motivo = self.columnas().index("ajuste_motivo")

        salida = []
        for fila in lote:
            fila = list(fila)
            fila[i_motivo] = self._limpiar_motivo(fila[i_motivo])
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.motivos_nuevos.values())
        return salida

    def _limpiar_motivo(self, crudo: str | None) -> str:
        """
        Quita la decoración que puso la máquina y deja el motivo que escribió la
        persona. NO remapea sinónimos: ver el punto 4 de la cabecera.
        """
        if not crudo:
            return ""
        limpio = _SUFIJO_ANULADO.sub("", _PREFIJO_SKU.sub("", crudo)).strip()
        if not limpio:
            # Un motivo que era SOLO prefijo: se conserva el crudo antes que
            # perder la única pista de por qué se ajustó el stock.
            limpio = crudo.strip()

        if limpio not in MOTIVOS_CONOCIDOS:
            self.motivos_nuevos[limpio] = self.motivos_nuevos.get(limpio, 0) + 1
            if self.motivos_nuevos[limpio] == 1:
                logger.warning(
                    f"[{self.nombre}] motivo de ajuste NO visto antes: «{limpio}». Se "
                    f"carga TAL CUAL (aquí no se remapea nada) y queda contado en la "
                    f"bitácora. `ajuste_inventario.motivo` es TEXTO LIBRE (C3B.2): si "
                    f"la lista de motivos del negocio creció, añádelo a "
                    f"MOTIVOS_CONOCIDOS para que deje de avisar."
                )
        return limpio

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Además del conteo, las tres familias que sostienen la Fase 3B:

          * la CARDINALIDAD (1.406 pares = 1.406 posiciones, sin huérfanos),
          * la INVARIANTE del kardex (Σ con signo por par = stock_actual),
          * la INTEGRIDAD de la cadena (ecuación, enlace, arranque en 0).

        Las tres se miden en el ORIGEN aquí y se contrastan contra el destino en
        `validar()`. Si alguna falla, la reconstrucción de `fact_stock_mensual`
        heredaría el error, así que la carga aborta antes de publicar.
        """
        return f"""
        WITH signo AS (
            SELECT mi.producto_variante_id AS v, mi.bodega_id AS b,
                   mi.id, mi.cantidad, mi.stock_anterior, mi.stock_nuevo,
                   mi.cantidad * tm.factor AS con_signo,
                   LAG(mi.stock_nuevo) OVER (
                       PARTITION BY mi.producto_variante_id, mi.bodega_id
                       ORDER BY mi.fecha_creacion, mi.id) AS prev_nuevo,
                   ROW_NUMBER() OVER (
                       PARTITION BY mi.producto_variante_id, mi.bodega_id
                       ORDER BY mi.fecha_creacion, mi.id) AS rn
            FROM movimiento_inventario mi
            JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        ),
        saldo AS (
            SELECT v, b, SUM(con_signo) AS saldo FROM signo GROUP BY v, b
        )
        SELECT
            (SELECT count(*) FROM movimiento_inventario)              AS filas,
            (SELECT count(*) FROM saldo)                              AS pares,
            (SELECT count(*) FROM inventario)                         AS posiciones,
            (SELECT count(*) FROM saldo s JOIN inventario i
               ON i.producto_variante_id = s.v AND i.bodega_id = s.b
             WHERE s.saldo = i.stock_actual)                          AS pares_que_cuadran,
            (SELECT SUM(stock_actual) FROM inventario)                AS stock_total,
            (SELECT SUM(con_signo) FROM signo)                        AS suma_con_signo,
            (SELECT SUM(cantidad) FROM signo WHERE con_signo > 0)     AS unidades_entrada,
            (SELECT SUM(cantidad) FROM signo WHERE con_signo < 0)     AS unidades_salida,
            (SELECT count(*) FROM signo
             WHERE stock_nuevo <> stock_anterior + con_signo)         AS ecuacion_rota,
            (SELECT count(*) FROM signo
             WHERE rn > 1 AND stock_anterior <> prev_nuevo)           AS cadena_rota,
            (SELECT count(*) FROM signo WHERE rn = 1 AND stock_anterior <> 0)
                                                                      AS arranque_no_cero,
            (SELECT count(*) FROM movimiento_inventario WHERE stock_nuevo < 0)
                                                                      AS saldos_negativos,
            (SELECT count(DISTINCT tipo_movimiento_id) FROM movimiento_inventario)
                                                                      AS tipos_en_uso,
            (SELECT count(*) FROM movimiento_inventario WHERE costo_unitario IS NULL)
                                                                      AS sin_costo,
            (SELECT count(*) FROM movimiento_inventario
             WHERE referencia_tipo = '{REFERENCIA_AJUSTE}')           AS ajustes_reales,
            (SELECT SUM(mi.cantidad * tm.factor)
             FROM movimiento_inventario mi
             JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
             WHERE mi.referencia_tipo = '{REFERENCIA_AJUSTE}')        AS ajuste_neto,
            (SELECT count(*) FROM movimiento_inventario mi
             JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
             WHERE tm.naturaleza = 'ajuste')                          AS naturaleza_ajuste,
            (SELECT count(DISTINCT
                 date_trunc('month', fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))
             FROM movimiento_inventario)                              AS meses,
            (SELECT SUM(COALESCE(cantidad * costo_unitario, 0))
             FROM movimiento_inventario)                              AS valor_total
        """

    _EQUIVALENCIAS = (
        ("suma_con_signo",    "sum(cantidad_con_signo)"),
        ("unidades_entrada",  "sumIf(cantidad, cantidad_con_signo > 0)"),
        ("unidades_salida",   "sumIf(cantidad, cantidad_con_signo < 0)"),
        ("tipos_en_uso",      "countDistinct(tipo_movimiento)"),
        ("sin_costo",         "countIf(sin_costo = 1)"),
        ("ajustes_reales",    "countIf(es_ajuste_real = 1)"),
        ("ajuste_neto",       "sumIf(cantidad_con_signo, es_ajuste_real = 1)"),
        ("naturaleza_ajuste", "countIf(naturaleza = 'ajuste')"),
        ("meses",             "countDistinct(mes)"),
        ("valor_total",       "sum(valor_movimiento)"),
        ("saldos_negativos",  "countIf(stock_nuevo < 0)"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = self._validar_origen(controles)
        if errores:
            # Si el ORIGEN no es sano, no tiene sentido comparar el destino: lo
            # que hay que arreglar está en PostgreSQL.
            return errores

        seleccion = ", ".join(expr for _, expr in self._EQUIVALENCIAS)
        fila = client.query(f"SELECT {seleccion} FROM {tabla_staging}").result_rows[0]
        for (clave, _), valor_ch in zip(self._EQUIVALENCIAS, fila):
            valor_pg = controles.get(clave)
            if valor_pg is None:
                continue
            if type(valor_pg)(valor_ch) != valor_pg:
                errores.append(
                    f"{clave}: PostgreSQL {valor_pg} vs ClickHouse {valor_ch} "
                    f"(diferencia {type(valor_pg)(valor_ch) - valor_pg})."
                )

        errores += self._validar_cadena(client, tabla_staging, controles)
        errores += self._validar_ajustes(client, tabla_staging, controles)
        return errores

    def _validar_origen(self, controles: dict) -> list[str]:
        """
        La salud del ORIGEN. No valida la carga: valida el supuesto sobre el que
        la Fase 3B entera se apoya. Si el kardex de PostgreSQL no cuadra con
        `inventario`, `fact_stock_mensual` heredaría el error y produciría —esta
        es la palabra del diseño— «un número plausible y equivocado».
        """
        errores = []
        pares = int(controles["pares"])
        posiciones = int(controles["posiciones"])
        cuadran = int(controles["pares_que_cuadran"])

        if pares != posiciones:
            errores.append(
                f"El kardex cubre {pares} pares (variante, bodega) pero `inventario` "
                f"tiene {posiciones} posiciones. §5.6 declara que son los mismos "
                f"1.406; si no lo son, hay stock sin historia o historia sin stock y "
                f"la reconstrucción de §5.7 NO es segura."
            )
        if cuadran != pares:
            errores.append(
                f"La invariante del kardex se rompió: solo {cuadran} de {pares} pares "
                f"tienen Σ(cantidad × factor) = inventario.stock_actual. La "
                f"reconstrucción mensual daría cifras que no cuadran con el stock real."
            )
        if int(controles["suma_con_signo"]) != int(controles["stock_total"]):
            errores.append(
                f"Σ del kardex {controles['suma_con_signo']} ≠ Σ stock_actual "
                f"{controles['stock_total']}."
            )
        for clave, explicacion in (
            ("ecuacion_rota",   "stock_nuevo ≠ stock_anterior + cantidad×factor"),
            ("cadena_rota",     "stock_anterior ≠ stock_nuevo del movimiento previo"),
            ("arranque_no_cero", "una cadena no arranca en 0"),
            ("saldos_negativos", "un saldo quedó negativo"),
        ):
            if int(controles[clave]) != 0:
                errores.append(
                    f"{controles[clave]} movimientos con «{explicacion}». El atajo de "
                    f"§5.7 (leer `stock_nuevo` en vez de recalcular) SOLO es válido "
                    f"sobre una cadena íntegra."
                )
        return errores

    def _validar_cadena(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        El destino conserva la cardinalidad y la cadena. Se comprueba EN
        CLICKHOUSE, no solo en el origen: una carga que perdiera filas de un par
        concreto podría cuadrar en total y romper ese par.
        """
        errores = []
        total, ids, pares, cierres = client.query(f"""
            SELECT count(), countDistinct(movimiento_id),
                   countDistinct((producto_variante_id, bodega)),
                   countDistinct((producto_variante_id, bodega, tipo_movimiento))
            FROM {tabla_staging}
        """).result_rows[0]

        if ids != total:
            errores.append(f"movimiento_id duplicado: {total} filas pero {ids} ids "
                           f"distintos (fan-out del join de categoría o de ajuste).")
        if pares != int(controles["pares"]):
            errores.append(f"Pares (variante, bodega): origen {controles['pares']} vs "
                           f"destino {pares}.")

        # La prueba de que el destino puede reconstruir: leer el ÚLTIMO
        # `stock_nuevo` de cada par y exigir que su suma sea el stock total.
        # Es exactamente el atajo que usará `fact_stock_mensual`, ensayado aquí
        # sobre el conjunto entero antes de derivar nada.
        suma_final = client.query(f"""
            SELECT sum(ultimo) FROM (
                SELECT argMax(stock_nuevo, (fecha, movimiento_id)) AS ultimo
                FROM {tabla_staging}
                GROUP BY producto_variante_id, bodega)
        """).result_rows[0][0]
        if int(suma_final) != int(controles["stock_total"]):
            errores.append(
                f"El atajo de §5.7 no reproduce el stock: Σ del último `stock_nuevo` "
                f"por par = {suma_final} vs `inventario.stock_actual` = "
                f"{controles['stock_total']}. Con esto NO se puede derivar "
                f"`fact_stock_mensual`."
            )
        # `cierres` solo se lee para que la consulta anterior no quede sin uso
        # si alguien la recorta; no hay invariante que exigirle.
        del cierres
        return errores

    def _validar_ajustes(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        C3B.1 y C3B.2 en forma de control: el ajuste real está bien separado de
        la apertura, y el motivo quedó limpio de decoración.
        """
        errores = []
        fila = client.query(f"""
            SELECT
                countIf(es_ajuste_real = 1),
                countIf(naturaleza = 'ajuste'),
                countIf(es_ajuste_real = 1 AND ajuste_motivo = ''),
                countIf(es_ajuste_real = 0 AND ajuste_motivo != ''),
                countDistinctIf(ajuste_motivo, es_ajuste_real = 1),
                countIf(match(ajuste_motivo, '^\\\\s*\\\\[')),
                countIf(position(ajuste_motivo, 'ANULADO') > 0),
                countDistinctIf(ajuste_tipo, es_ajuste_real = 1)
            FROM {tabla_staging}
        """).result_rows[0]
        (reales, por_naturaleza, sin_motivo, motivo_colado,
         motivos, con_prefijo, con_sufijo, tipos) = fila

        if reales != int(controles["ajustes_reales"]):
            errores.append(f"Ajustes reales: origen {controles['ajustes_reales']} vs "
                           f"destino {reales}.")
        if sin_motivo:
            errores.append(
                f"{sin_motivo} movimientos de ajuste se quedaron SIN motivo. El join "
                f"a `ajuste_inventario` va condicionado por `referencia_tipo`; si "
                f"falla, INV-10 pierde su eje de agrupación."
            )
        if motivo_colado:
            errores.append(
                f"{motivo_colado} movimientos que NO son ajuste traen motivo: el "
                f"`referencia_id` polimórfico casó con un ajuste ajeno."
            )
        if con_prefijo or con_sufijo:
            errores.append(
                f"La limpieza de C3B.2 no se aplicó: {con_prefijo} motivos conservan "
                f"el prefijo `[SKU xN]` y {con_sufijo} el sufijo «ANULADO». En crudo "
                f"hay un motivo distinto por ajuste y INV-10 no puede agrupar."
            )
        if motivos > reales:
            errores.append(f"{motivos} motivos distintos sobre {reales} movimientos "
                           f"de ajuste: imposible.")
        if tipos == 0 and reales > 0:
            errores.append("Ningún `ajuste_tipo` llegó: INV-10 pierde la única "
                           "clasificación controlada por el motor.")

        # La trampa de C3B.1, vigilada: si algún día coincidieran, o bien la
        # apertura dejó de registrarse como ajuste o bien alguien la reclasificó.
        if por_naturaleza == reales:
            logger.info(
                f"[{self.nombre}] `naturaleza='ajuste'` y `es_ajuste_real` coinciden "
                f"({reales}). Hoy NO coinciden por los 343 movimientos de apertura "
                f"(C3B.1); si esto persiste, revisa si la distinción sigue haciendo "
                f"falta antes de simplificar INV-10."
            )
        elif por_naturaleza != int(controles["naturaleza_ajuste"]):
            errores.append(f"Movimientos con naturaleza 'ajuste': origen "
                           f"{controles['naturaleza_ajuste']} vs destino {por_naturaleza}.")
        return errores
