"""
etl/dwh/tablas/fact_devolucion_proveedor.py — F14 del modelo (§5.14 del diseño).

La logística inversa hacia el proveedor: el espejo del RMA. Grano: **un ítem
defectuoso** con su devolución y su resolución. **38 filas** (8 devoluciones, 6
resueltas). Alimenta OTD-COM-09 — el objetivo declarado *REQUIERE VOLUMEN*.

═══════════════════════════════════════════════════════════════════════════════
1. LOS VALORES DE `origen` NO SON LOS DEL DISEÑO — C4.7
═══════════════════════════════════════════════════════════════════════════════

§5.14 declara «`origen LowCardinality(String)` (`inspeccion_rma` /
`recepcion_compra`)». Lo que guarda la base —y lo que impone su CHECK— es otra
cosa:

    CHECK (origen IN ('rma', 'recepcion'))

        rma ........... 36 ítems / 56 uds
        recepcion .....  2 ítems /  3 uds

    SELECT count(*) FROM item_defectuoso WHERE origen = 'inspeccion_rma'  →  0

**Un filtro escrito desde el diseño casa con CERO filas.** Y no falla: devuelve
una tabla vacía que en un informe de recuperaciones se lee como «no hemos
devuelto nada al proveedor». Es literalmente el mismo hallazgo que C3C.3 con
`accion`, una fase más tarde y en otra tabla — de ahí la regla: **la lista
blanca sale de los DATOS y del CHECK del motor, nunca del documento**.

═══════════════════════════════════════════════════════════════════════════════
2. DIEZ ÍTEMS NO TIENEN PROVEEDOR, Y ES EL FLUJO — NO UN HUECO
═══════════════════════════════════════════════════════════════════════════════

    ítems defectuosos ................ 38
      sin `proveedor_id` ............. 10   ← los 10 son `rma` + `pendiente`
      con `costo_unitario` ............ 38   (0 sin costo)

El pool los crea la inspección del RMA, que rastrea el proveedor por la última
orden de compra de la variante; cuando no la encuentra, deja NULL y **COMPRAS lo
asigna a mano** antes de agrupar la devolución (`PATCH /proveedor`, script 45).
Un ítem sin proveedor es por tanto un ítem **pendiente de clasificar**, y los 10
lo confirman: ninguno ha entrado todavía en una devolución.

Resuelven al centinela `'sin_asignar'` con `proveedor_id = 0`. Agruparlos bajo
un NULL los mostraría como un proveedor más en el eje de COM-09, que es
exactamente el informe que compara proveedores.

═══════════════════════════════════════════════════════════════════════════════
3. `valor_recuperado`: LA NOTA DE CRÉDITO CUADRA CON EL COSTE, AL CENTAVO
═══════════════════════════════════════════════════════════════════════════════

§5.14 pide `valor_recuperado` a grano de ÍTEM, pero `monto_credito` vive en la
CABECERA de la devolución. Medido devolución a devolución, no hay que prorratear
nada — el crédito ES la suma del coste de sus ítems:

    DP-20260718-100011  nota_credito   $    8,00  ítems $    8,00
    DP-20250621-112425  nota_credito   $  300,79  ítems $  300,79
    DP-20250320-112429  nota_credito   $3.888,06  ítems $3.888,06
                                       ─────────
                        Σ nota de crédito $4.196,85   ← cuadra con el catálogo

    DP-20260718-100012  reposicion     (sin crédito) ítems $   28,50
    DP-20251227-112424  reposicion     (sin crédito) ítems $  680,98
    DP-20250619-112428  reposicion     (sin crédito) ítems $  314,61
                                                     ─────────
                        Σ reposición                 $1.024,09

    RECUPERADO TOTAL en las 6 resoluciones ......... $5.220,94

Por eso `valor_recuperado = cantidad × costo_unitario` **cuando hay resolución**
y 0 cuando no la hay, para los dos tipos: en la nota de crédito es el dinero que
el proveedor abona, y en la reposición es la mercancía que repone valorada a su
costo. Son cosas distintas y la columna `tipo_resolucion` las separa; sumarlas
sin ese corte mezclaría dólares con unidades repuestas.

El control comprueba que la suma de `valor_recuperado` de los ítems de una
devolución `nota_credito` sea EXACTAMENTE su `monto_credito`. Si algún día el
crédito se negocia por debajo del coste, este control lo dice en vez de dejar
que COM-09 reporte una recuperación que nadie cobró.

═══════════════════════════════════════════════════════════════════════════════
4. EL RELOJ DE COM-09 ES LA DEVOLUCIÓN, NO LA DETECCIÓN — C4.8
═══════════════════════════════════════════════════════════════════════════════

§5.14 pide `dias_hasta_resolucion` en una tabla cuyo grano es el ÍTEM y cuya
fecha declarada es `fecha_deteccion`. La lectura natural —resolución menos
detección— **sale negativa en 18 de los 28 ítems agrupados**:

    ítems agrupados en una devolución ............................ 28
      detectados DESPUÉS de crearse la devolución que los agrupa .. 19
      detectados DESPUÉS de que esa devolución ya estuviera resuelta 18

Ejemplo real: `DP-20250320-112429` se registró el 2025-03-20 y se resolvió el
2025-04-01, y **14 de sus 15 ítems** llevan fecha de detección de hasta
2026-05-25 — más de un año después de cobrarse su nota de crédito. Es un
artefacto del seed, que agrupó ítems en devoluciones retrodatadas sin respetar
la cronología.

Lo que **sí** es coherente es el ciclo de la devolución en sí:

    devoluciones con envío anterior al registro ........ 0
    con resolución anterior al envío .................. 0
    con resolución anterior al registro ............... 0
    ciclo registro → resolución: media 7,30 días (0,00 a 12,66)

**Cómo se resolvió.** `dias_hasta_resolucion` mide el **ciclo de la devolución**
—`fecha_resolucion − fecha_devolucion`— que es además la pregunta de COM-09
(«cuánto tardamos en recuperar»), y no la espera del ítem en el pool. La
anomalía no se oculta: viaja en la columna `deteccion_posterior` (19 ítems) para
que quien mire el pool sepa que sus fechas no ordenan el proceso.

Con la resta ingenua, COM-09 habría mostrado un ciclo medio **negativo** — o,
peor, si alguien lo hubiera acotado con `GREATEST(…, 0)` para «arreglarlo»,
un tiempo de recuperación de cero días que parecería una eficiencia notable.

═══════════════════════════════════════════════════════════════════════════════
5. LA MUESTRA ES DÉBIL Y SE DECLARA, NO SE MAQUILLA
═══════════════════════════════════════════════════════════════════════════════

6 resoluciones sobre 11 proveedores y 19 meses. El catálogo lo clasifica
*REQUIERE VOLUMEN* y el diseño lo repite: el informe **debe mostrar la muestra
junto al número**, o inducirá a decisiones sobre ruido.

El pipeline no puede arreglarlo —el flujo escribe todo correctamente, sencillamente
no ha ocurrido más— así que la tabla se carga completa y la responsabilidad pasa
al informe: COM-09 lleva `salvedad` en el sobre y una columna `resoluciones` en
cada fila. Un promedio por proveedor calculado sobre 1 caso no es un promedio.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los valores REALES de `item_defectuoso.origen` — del CHECK del motor, no del
#: diseño (C4.7). `inspeccion_rma` / `recepcion_compra` casan con 0 filas.
ORIGENES_CONOCIDOS = frozenset({"rma", "recepcion"})

#: Los 3 estados del pool.
ESTADOS_ITEM = frozenset({"pendiente", "en_devolucion", "resuelto"})

#: Los 4 estados de la devolución al proveedor.
ESTADOS_DEVOLUCION = frozenset({"registrada", "enviada", "resuelta", "cerrada"})

#: Los 2 tipos de resolución del CHECK.
RESOLUCIONES = frozenset({"nota_credito", "reposicion"})

#: Estados en que la devolución YA tiene desenlace (el CHECK
#: `devolucion_proveedor_resolucion_coherente` ata esto con `tipo_resolucion`).
ESTADOS_RESUELTOS = frozenset({"resuelta", "cerrada"})

#: Ausencias que son el flujo, no un hueco.
SIN_PROVEEDOR = "sin_asignar"
SIN_DEVOLUCION = "sin_agrupar"
SIN_RESOLUCION = "sin_resolver"


class FactDevolucionProveedor(TareaCarga):

    nombre = "fact_devolucion_proveedor"

    def __init__(self):
        super().__init__()
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            item_defectuoso_id     UInt32,
            devolucion_proveedor_id UInt32,
            numero                 String,
            fecha_deteccion        DateTime('{ZONA_HORARIA}'),
            mes                    Date,
            proveedor_id           UInt16,
            proveedor              LowCardinality(String),
            producto_variante_id   UInt32,
            sku                    String,
            producto_nombre        String,
            categoria              LowCardinality(String),
            bodega                 LowCardinality(String),
            cantidad               UInt32,
            origen                 LowCardinality(String),
            estado                 LowCardinality(String),
            estado_devolucion      LowCardinality(String),
            tipo_resolucion        LowCardinality(String),
            costo_unitario         Decimal(14,2),
            costo_total            Decimal(14,2),
            valor_recuperado       Decimal(14,2),
            resuelto               UInt8,
            fecha_devolucion       Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_envio            Nullable(DateTime('{ZONA_HORARIA}')),
            fecha_resolucion       Nullable(DateTime('{ZONA_HORARIA}')),
            mes_resolucion         Nullable(Date),
            dias_hasta_resolucion  Nullable(Decimal(12,2)),
            deteccion_posterior    UInt8,
            fecha_carga            DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_deteccion)
        ORDER BY (fecha_deteccion, proveedor_id)
        """

    def columnas(self) -> list[str]:
        return [
            "item_defectuoso_id", "devolucion_proveedor_id", "numero",
            "fecha_deteccion", "mes", "proveedor_id", "proveedor",
            "producto_variante_id", "sku", "producto_nombre", "categoria", "bodega",
            "cantidad", "origen", "estado", "estado_devolucion", "tipo_resolucion",
            "costo_unitario", "costo_total", "valor_recuperado", "resuelto",
            "fecha_devolucion", "fecha_envio", "fecha_resolucion", "mes_resolucion",
            "dias_hasta_resolucion", "deteccion_posterior", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        resueltos = ", ".join(f"'{e}'" for e in sorted(ESTADOS_RESUELTOS))
        return f"""
        SELECT
            i.id                                        AS item_defectuoso_id,
            COALESCE(dp.id, 0)                          AS devolucion_proveedor_id,
            COALESCE(dp.numero, '{SIN_DEVOLUCION}')     AS numero,
            i.fecha_creacion                            AS fecha_deteccion,
            (date_trunc('month',
                i.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            -- 10 de 38 esperan a que COMPRAS les asigne proveedor (§2). Van al
            -- centinela y no a NULL: en el eje de COM-09 un NULL se lee como
            -- un proveedor más.
            COALESCE(pv.id, 0)                          AS proveedor_id,
            COALESCE(NULLIF(TRIM(pv.nombre_comercial), ''), pv.razon_social,
                     '{SIN_PROVEEDOR}')                 AS proveedor,
            i.producto_variante_id,
            v.sku,
            p.nombre                                    AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')         AS categoria,
            COALESCE(b.nombre, 'sin_bodega')            AS bodega,
            i.cantidad,
            i.origen,
            i.estado,
            COALESCE(dp.estado, '{SIN_DEVOLUCION}')     AS estado_devolucion,
            COALESCE(dp.tipo_resolucion, '{SIN_RESOLUCION}') AS tipo_resolucion,
            COALESCE(i.costo_unitario, 0)               AS costo_unitario,
            ROUND(i.cantidad * COALESCE(i.costo_unitario, 0), 2) AS costo_total,
            -- Solo se recupera lo que YA tiene desenlace (§3). Sin este filtro,
            -- COM-09 contaría como recuperado el pool pendiente: $9.349,93 en
            -- vez de $5.220,94, un 79 % de más sobre un flujo que aún no cobró.
            CASE WHEN dp.estado IN ({resueltos})
                 THEN ROUND(i.cantidad * COALESCE(i.costo_unitario, 0), 2)
                 ELSE 0 END                             AS valor_recuperado,
            CASE WHEN dp.estado IN ({resueltos}) THEN 1 ELSE 0 END AS resuelto,
            dp.fecha_creacion                           AS fecha_devolucion,
            dp.fecha_envio,
            dp.fecha_resolucion,
            (date_trunc('month',
                dp.fecha_resolucion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes_resolucion,
            -- El ciclo de la DEVOLUCIÓN, no la espera del ítem en el pool
            -- (C4.8): 19 ítems se detectaron después de crearse la devolución
            -- que los agrupa y esta resta saldría negativa en 18 de 28.
            CASE WHEN dp.fecha_resolucion IS NULL THEN NULL
                 ELSE ROUND((EXTRACT(EPOCH FROM
                      (dp.fecha_resolucion - dp.fecha_creacion)) / 86400.0)::numeric, 2)
            END                                         AS dias_hasta_resolucion,
            -- La anomalía, marcada y no escondida.
            CASE WHEN dp.fecha_creacion IS NOT NULL
                  AND i.fecha_creacion > dp.fecha_creacion THEN 1 ELSE 0 END
                                                        AS deteccion_posterior
        FROM item_defectuoso i
        JOIN producto_variante v ON v.id = i.producto_variante_id
        JOIN producto p          ON p.id = v.producto_id
        LEFT JOIN producto_categoria pc
                                 ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c    ON c.id = pc.categoria_id
        LEFT JOIN bodega b       ON b.id = i.bodega_id
        LEFT JOIN proveedor pv   ON pv.id = i.proveedor_id
        -- El detalle es opcional: 10 ítems siguen en el pool sin agrupar.
        LEFT JOIN devolucion_proveedor_detalle dpd ON dpd.item_defectuoso_id = i.id
        LEFT JOIN devolucion_proveedor dp          ON dp.id = dpd.devolucion_proveedor_id
        ORDER BY i.fecha_creacion, i.id
        """

    # ── Transformación ───────────────────────────────────────────────────────

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_origen = self.columnas().index("origen")
        i_estado = self.columnas().index("estado")
        i_res = self.columnas().index("tipo_resolucion")

        salida = []
        for fila in lote:
            self._vigilar(fila[i_origen], ORIGENES_CONOCIDOS, "origen")
            self._vigilar(fila[i_estado], ESTADOS_ITEM, "estado")
            self._vigilar(fila[i_res], RESOLUCIONES | {SIN_RESOLUCION}, "tipo_resolucion")
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.valores_nuevos.values())
        return salida

    def _vigilar(self, valor: str, conocidos: frozenset, campo: str) -> None:
        if valor in conocidos:
            return
        clave = f"{campo}={valor}"
        self.valores_nuevos[clave] = self.valores_nuevos.get(clave, 0) + 1
        if self.valores_nuevos[clave] == 1:
            logger.warning(
                f"[{self.nombre}] valor NO previsto en `{campo}`: «{valor}». Se carga "
                f"tal cual y queda contado. Recuerda C4.7: los valores de `origen` que "
                f"declara §5.14 ('inspeccion_rma' / 'recepcion_compra') NO existen en "
                f"la base — la lista blanca sale del CHECK del motor, no del documento."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        resueltos = ", ".join(f"'{e}'" for e in sorted(ESTADOS_RESUELTOS))
        return f"""
        SELECT
            (SELECT count(*) FROM item_defectuoso)                     AS filas,
            (SELECT SUM(cantidad) FROM item_defectuoso)                AS unidades,
            (SELECT count(*) FROM item_defectuoso WHERE proveedor_id IS NULL)
                                                                       AS sin_proveedor,
            (SELECT count(DISTINCT proveedor_id) FROM item_defectuoso
              WHERE proveedor_id IS NOT NULL)                          AS proveedores,
            (SELECT count(*) FROM item_defectuoso WHERE costo_unitario IS NULL)
                                                                       AS sin_costo,
            (SELECT ROUND(SUM(cantidad * COALESCE(costo_unitario, 0)), 2)
               FROM item_defectuoso)                                   AS costo_total,
            (SELECT count(DISTINCT origen) FROM item_defectuoso)       AS origenes,
            (SELECT count(*) FROM item_defectuoso WHERE origen = 'rma')      AS de_rma,
            (SELECT count(*) FROM item_defectuoso WHERE origen = 'recepcion') AS de_recepcion,
            -- C4.7: el filtro que el DISEÑO habría escrito. Debe dar 0, y ese 0
            -- es la prueba viva de la corrección.
            (SELECT count(*) FROM item_defectuoso
              WHERE origen IN ('inspeccion_rma', 'recepcion_compra'))  AS origen_segun_diseno,
            (SELECT count(DISTINCT estado) FROM item_defectuoso)       AS estados_item,
            (SELECT count(*) FROM item_defectuoso WHERE estado = 'pendiente') AS pendientes,
            (SELECT count(*) FROM item_defectuoso WHERE estado = 'resuelto')  AS resueltos_item,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_creacion AT TIME ZONE '{ZONA_HORARIA}')) FROM item_defectuoso)
                                                                       AS meses,
            -- ── La devolución al proveedor ────────────────────────────────────
            (SELECT count(*) FROM devolucion_proveedor)                AS devoluciones,
            (SELECT count(*) FROM devolucion_proveedor
              WHERE estado IN ({resueltos}))                           AS devoluciones_resueltas,
            (SELECT ROUND(COALESCE(SUM(monto_credito), 0), 2) FROM devolucion_proveedor)
                                                                       AS suma_credito,
            (SELECT count(*) FROM devolucion_proveedor_detalle)        AS detalles,
            (SELECT count(DISTINCT item_defectuoso_id)
               FROM devolucion_proveedor_detalle)                      AS items_agrupados,
            -- Lo recuperado: solo los ítems de devoluciones YA resueltas.
            (SELECT ROUND(COALESCE(SUM(i.cantidad * COALESCE(i.costo_unitario, 0)), 0), 2)
               FROM devolucion_proveedor_detalle dpd
               JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
               JOIN item_defectuoso i       ON i.id  = dpd.item_defectuoso_id
              WHERE dp.estado IN ({resueltos}))                        AS valor_recuperado,
            (SELECT count(*) FROM devolucion_proveedor_detalle dpd
               JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
              WHERE dp.estado IN ({resueltos}))                        AS items_resueltos,
            -- §3: el crédito de una nota TIENE que ser el coste de sus ítems.
            (SELECT count(*) FROM (
                SELECT dp.id
                  FROM devolucion_proveedor dp
                  JOIN devolucion_proveedor_detalle dpd ON dpd.devolucion_proveedor_id = dp.id
                  JOIN item_defectuoso i ON i.id = dpd.item_defectuoso_id
                 WHERE dp.tipo_resolucion = 'nota_credito'
                 GROUP BY dp.id, dp.monto_credito
                HAVING ROUND(SUM(i.cantidad * COALESCE(i.costo_unitario, 0)), 2)
                       <> ROUND(dp.monto_credito, 2)) x)               AS credito_descuadrado,
            -- ── Integridad ────────────────────────────────────────────────────
            (SELECT count(*) FROM item_defectuoso i
              WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                 WHERE v.id = i.producto_variante_id)) AS sin_variante,
            (SELECT count(*) FROM devolucion_proveedor_detalle dpd
              WHERE NOT EXISTS (SELECT 1 FROM item_defectuoso i
                                 WHERE i.id = dpd.item_defectuoso_id)) AS detalle_huerfano,
            -- Un ítem en DOS devoluciones duplicaría su fila y su recuperación.
            (SELECT count(*) FROM (SELECT item_defectuoso_id
                                     FROM devolucion_proveedor_detalle
                                    GROUP BY 1 HAVING count(*) > 1) d)  AS item_en_2_devoluciones,
            -- ── Cronología (C4.8) ─────────────────────────────────────────────
            -- El ciclo de la DEVOLUCIÓN sí está ordenado: los tres controles
            -- deben dar 0 y son la razón de que `dias_hasta_resolucion` se mida
            -- sobre él y no sobre la detección del ítem.
            (SELECT count(*) FROM devolucion_proveedor
              WHERE fecha_envio < fecha_creacion)                      AS envio_antes_registro,
            (SELECT count(*) FROM devolucion_proveedor
              WHERE fecha_resolucion < fecha_creacion)                 AS resol_antes_registro,
            (SELECT count(*) FROM devolucion_proveedor
              WHERE fecha_resolucion < fecha_envio)                    AS resol_antes_envio,
            -- La anomalía MEDIDA: no aborta la carga (es un hecho del dato, no
            -- un fallo del ETL), pero tiene que llegar al almacén con su marca
            -- y con el mismo recuento.
            (SELECT count(*) FROM devolucion_proveedor_detalle dpd
               JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
               JOIN item_defectuoso i       ON i.id  = dpd.item_defectuoso_id
              WHERE i.fecha_creacion > dp.fecha_creacion)              AS deteccion_posterior,
            (SELECT ROUND(SUM(ROUND((EXTRACT(EPOCH FROM
                     (dp.fecha_resolucion - dp.fecha_creacion)) / 86400.0)::numeric, 2)), 2)
               FROM devolucion_proveedor_detalle dpd
               JOIN devolucion_proveedor dp ON dp.id = dpd.devolucion_proveedor_id
              WHERE dp.fecha_resolucion IS NOT NULL)                   AS suma_dias_ciclo
        """

    _EQUIVALENCIAS = (
        ("unidades",        "sum(cantidad)"),
        ("sin_proveedor",   f"countIf(proveedor = '{SIN_PROVEEDOR}')"),
        ("costo_total",     "sum(costo_total)"),
        ("de_rma",          "countIf(origen = 'rma')"),
        ("de_recepcion",    "countIf(origen = 'recepcion')"),
        ("estados_item",    "countDistinct(estado)"),
        ("pendientes",      "countIf(estado = 'pendiente')"),
        ("resueltos_item",  "countIf(estado = 'resuelto')"),
        ("meses",           "countDistinct(mes)"),
        ("items_agrupados", "countIf(devolucion_proveedor_id != 0)"),
        ("valor_recuperado", "sum(valor_recuperado)"),
        ("items_resueltos", "countIf(resuelto = 1)"),
        ("deteccion_posterior", "countIf(deteccion_posterior = 1)"),
        ("suma_dias_ciclo", "sum(ifNull(dias_hasta_resolucion, 0))"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []

        for clave, explicacion in (
            ("sin_variante",           "ítems que apuntan a una variante inexistente"),
            ("detalle_huerfano",       "líneas de devolución sin ítem"),
            ("item_en_2_devoluciones", "ítems agrupados en DOS devoluciones (su "
                                       "recuperación se contaría dos veces)"),
            ("envio_antes_registro",   "devoluciones enviadas antes de registrarse"),
            ("resol_antes_registro",   "devoluciones resueltas antes de registrarse"),
            ("resol_antes_envio",      "devoluciones resueltas antes de enviarse"),
            ("credito_descuadrado",    "notas de crédito cuyo importe NO es el coste de "
                                       "sus ítems (§3 del docstring: hoy cuadran las 3)"),
        ):
            if int(controles[clave]) != 0:
                errores.append(f"{controles[clave]} {explicacion}.")

        if int(controles["sin_costo"]) != 0:
            errores.append(
                f"{controles['sin_costo']} ítems sin `costo_unitario`. COM-09 valora la "
                f"recuperación con él: un ítem sin costo se recuperaría por $0,00 y "
                f"bajaría la media sin dejar rastro (mismo mecanismo que C3B.3)."
            )
        if int(controles["origen_segun_diseno"]) != 0:
            errores.append(
                f"Aparecieron ítems con los orígenes que declara §5.14 "
                f"('inspeccion_rma' / 'recepcion_compra'): "
                f"{controles['origen_segun_diseno']}. La corrección C4.7 dice que los "
                f"valores reales son 'rma' y 'recepcion'. Si el negocio cambió, revisa "
                f"el CHECK, el informe y la nota."
            )

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

        errores += self._validar_resolucion(client, tabla_staging, controles)
        return errores

    def _validar_resolucion(self, client, tabla_staging: str,
                            controles: dict) -> list[str]:
        """
        La resolución es lo que COM-09 mide, y sobre 6 casos cada uno pesa el
        17 %: aquí no hay margen para un ítem mal clasificado.
        """
        errores = []
        fila = client.query(f"""
            SELECT countIf(resuelto = 1 AND tipo_resolucion = '{SIN_RESOLUCION}'),
                   countIf(resuelto = 0 AND valor_recuperado > 0),
                   countIf(resuelto = 1 AND valor_recuperado != costo_total),
                   countIf(devolucion_proveedor_id = 0 AND numero != '{SIN_DEVOLUCION}'),
                   countIf(dias_hasta_resolucion < 0),
                   -- Devoluciones REALES: el 0 del centinela no es una de ellas.
                   countDistinctIf(devolucion_proveedor_id, devolucion_proveedor_id != 0),
                   sumIf(valor_recuperado, tipo_resolucion = 'nota_credito'),
                   sumIf(valor_recuperado, tipo_resolucion = 'reposicion'),
                   countDistinct(item_defectuoso_id),
                   count()
            FROM {tabla_staging}
        """).result_rows[0]
        (resuelto_sin_tipo, recuperado_sin_resolver, recuperado_parcial, numero_mal,
         dias_negativos, devoluciones, credito, reposicion, ids, total) = fila

        if ids != total:
            errores.append(f"item_defectuoso_id duplicado: {total} filas pero {ids} ids. "
                           f"Un ítem agrupado en dos devoluciones duplicaría su valor.")
        if resuelto_sin_tipo:
            errores.append(f"{resuelto_sin_tipo} ítems marcados como resueltos y sin "
                           f"tipo de resolución. El CHECK del motor lo prohíbe.")
        if recuperado_sin_resolver:
            errores.append(
                f"{recuperado_sin_resolver} ítems SIN resolver traen valor recuperado. "
                f"COM-09 sumaría como recuperado el pool pendiente: dinero que aún no "
                f"se ha cobrado ni repuesto."
            )
        if recuperado_parcial:
            errores.append(f"{recuperado_parcial} ítems resueltos cuyo valor recuperado "
                           f"no es su coste total.")
        if numero_mal:
            errores.append(f"{numero_mal} ítems sin devolución pero con número.")
        if dias_negativos:
            errores.append(f"{dias_negativos} ítems con días hasta la resolución "
                           f"NEGATIVOS.")

        # Las 8 devoluciones tienen que estar TODAS representadas: una que se
        # perdiera en el LEFT JOIN se llevaría su recuperación con ella.
        if devoluciones != int(controles["devoluciones"]):
            errores.append(f"Devoluciones a proveedor: origen "
                           f"{controles['devoluciones']} vs destino {devoluciones} "
                           f"(sin contar el centinela '{SIN_DEVOLUCION}').")

        # El crédito de las notas TIENE que ser el del origen (§3).
        if round(float(credito), 2) != round(float(controles["suma_credito"]), 2):
            errores.append(
                f"Nota de crédito: origen {controles['suma_credito']} vs destino "
                f"{credito}. §3 del docstring: el crédito de la cabecera ES la suma del "
                f"coste de sus ítems; si dejan de cuadrar, COM-09 reporta una "
                f"recuperación que nadie cobró."
            )
        total_recuperado = round(float(credito) + float(reposicion), 2)
        if total_recuperado != round(float(controles["valor_recuperado"]), 2):
            errores.append(f"Valor recuperado total: origen "
                           f"{controles['valor_recuperado']} vs destino "
                           f"{total_recuperado}.")
        return errores
