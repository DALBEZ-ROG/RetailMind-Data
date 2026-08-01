"""
etl/dwh/tablas/fact_resena.py — F13 del modelo (§5.13 del diseño).

La voz del cliente sobre el producto. Grano: **una reseña**. 344 filas sobre 268
productos distintos. Alimenta OTD-VEN-11.

═══════════════════════════════════════════════════════════════════════════════
1. EL GRANO ES EL PRODUCTO PADRE, Y UNIR A `dim_producto` MULTIPLICA — C4.4
═══════════════════════════════════════════════════════════════════════════════

§5.13 lo advierte —«une a `dim_producto` por `producto_id` y no por
`producto_variante_id`»— pero deja implícito que ese JOIN es seguro. **No lo
es**: `dim_producto` tiene grano de VARIANTE, así que unir por `producto_id`
devuelve una fila por variante del producto.

    productos ......................... 1.214
    variantes ......................... 1.221
    productos con más de una variante ..    7   (hasta 3)
    reseñas de esos productos ..........    3   ← se convertirían en 4 o más

Tres reseñas sobre 344. El informe se pinta, la calificación media se mueve unas
milésimas, y **la reseña de un producto de tres variantes pesa el triple** que
la de cualquier otro. No hay error, no hay excepción, no hay suma que no cuadre:
solo un ranking de productos ligeramente equivocado y para siempre.

**Cómo se resolvió.** La tabla **denormaliza** `producto_nombre`, `categoria` y
`marca` desde `producto` y **nunca se une a `dim_producto`**. Es la misma
denormalización que llevan las demás tablas de hechos, con un motivo extra:
aquí el JOIN no es una comodidad que se evita, es un fan-out que se prohíbe.

El control cruzado con `dim_producto` se hace por EXISTENCIA (¿está el producto
en el almacén?) y no por unión, precisamente para no reproducir el error dentro
de la validación.

═══════════════════════════════════════════════════════════════════════════════
2. LO QUE SÍ SE SOSTUVO
═══════════════════════════════════════════════════════════════════════════════

    reseñas ........................ 344     compra verificada ....... 344 (100 %)
    productos distintos ............ 268     con pedido enlazado ..... 344 (100 %)
    calificación media ............. 3,9331  moderadas ............... 290
    estados: aprobada 270 · pendiente 53 · rechazada 21

`compra_verificada` está en el 100 % porque el sistema **exige compra** para
reseñar (409 «Solo puedes reseñar productos que has comprado»). La columna se
carga igualmente: el día que se abra la reseña sin compra, VEN-11 tendrá que
poder separar las dos poblaciones, y una columna constante hoy es más barata que
una migración mañana.

`dias_hasta_moderacion` se mide desde la creación hasta `fecha_moderacion`, y
existe en las 290 moderadas — `moderado_por` y `fecha_moderacion` se pueblan
siempre juntas (0 discrepancias en las dos direcciones).

Va en `Decimal(12,2)` y no en el `Float32` de §5.13, por lo ya registrado en C2.5.

═══════════════════════════════════════════════════════════════════════════════
3. ORDEN POR PRODUCTO PRIMERO
═══════════════════════════════════════════════════════════════════════════════

Igual que `fact_compra_linea` y por la misma razón: VEN-11 pide «la calificación
de cada producto y **cómo evoluciona**», que es una serie por producto. Con 344
filas la diferencia es teórica; se hace así para que el criterio del modelo sea
uno solo y no dependa del tamaño de cada tabla.

Bitácora completa: `docs/estrategico/CORRECCIONES_DISENO_ETL.md`.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA, logger
from etl.dwh.tarea import TareaCarga

#: Los 3 estados de moderación de una reseña, todos en uso.
ESTADOS_CONOCIDOS = frozenset({"pendiente", "aprobada", "rechazada"})


class FactResena(TareaCarga):

    nombre = "fact_resena"

    def __init__(self):
        super().__init__()
        self.valores_nuevos: dict[str, int] = {}

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            resena_id             UInt32,
            fecha_creacion        DateTime('{ZONA_HORARIA}'),
            mes                   Date,
            producto_id           UInt32,
            producto_nombre       String,
            categoria             LowCardinality(String),
            marca                 LowCardinality(String),
            cliente_id            UInt32,
            pedido_id             UInt32,
            calificacion          UInt8,
            compra_verificada     UInt8,
            estado                LowCardinality(String),
            moderada              UInt8,
            fecha_moderacion      Nullable(DateTime('{ZONA_HORARIA}')),
            dias_hasta_moderacion Nullable(Decimal(12,2)),
            fecha_carga           DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYYYYMM(fecha_creacion)
        ORDER BY (producto_id, fecha_creacion)
        """

    def columnas(self) -> list[str]:
        return [
            "resena_id", "fecha_creacion", "mes", "producto_id", "producto_nombre",
            "categoria", "marca", "cliente_id", "pedido_id", "calificacion",
            "compra_verificada", "estado", "moderada", "fecha_moderacion",
            "dias_hasta_moderacion", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    def sql_extraccion(self) -> str:
        """
        Se une a `producto` DIRECTAMENTE y jamás a `producto_variante`: unir por
        el padre a una tabla de variantes es exactamente el fan-out de C4.4.

        La categoría sale del mismo JOIN que `dim_producto` (`es_principal`),
        para que VEN-11 y VEN-06 clasifiquen igual el mismo producto.
        """
        return f"""
        SELECT
            r.id                                        AS resena_id,
            r.fecha_creacion,
            (date_trunc('month',
                r.fecha_creacion AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
            r.producto_id,
            p.nombre                                    AS producto_nombre,
            COALESCE(c.nombre, 'sin_categoria')         AS categoria,
            COALESCE(m.nombre, 'sin_marca')             AS marca,
            r.cliente_id,
            COALESCE(r.pedido_id, 0)                    AS pedido_id,
            r.calificacion,
            CASE WHEN r.compra_verificada THEN 1 ELSE 0 END AS compra_verificada,
            r.estado,
            CASE WHEN r.fecha_moderacion IS NOT NULL THEN 1 ELSE 0 END AS moderada,
            r.fecha_moderacion,
            CASE WHEN r.fecha_moderacion IS NULL THEN NULL
                 ELSE ROUND((EXTRACT(EPOCH FROM
                      (r.fecha_moderacion - r.fecha_creacion)) / 86400.0)::numeric, 2)
            END                                         AS dias_hasta_moderacion
        FROM resena r
        JOIN producto p        ON p.id = r.producto_id
        LEFT JOIN marca m      ON m.id = p.marca_id
        LEFT JOIN producto_categoria pc
                               ON pc.producto_id = p.id AND pc.es_principal
        LEFT JOIN categoria c  ON c.id = pc.categoria_id
        ORDER BY r.producto_id, r.fecha_creacion, r.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        i_estado = self.columnas().index("estado")
        i_calif = self.columnas().index("calificacion")

        salida = []
        for fila in lote:
            if fila[i_estado] not in ESTADOS_CONOCIDOS:
                self._avisar("estado", fila[i_estado])
            # La escala 1-5 la impone un CHECK; si algún día se ampliara, VEN-11
            # seguiría promediando sin decir que la escala cambió.
            if not 1 <= int(fila[i_calif]) <= 5:
                self._avisar("calificacion", str(fila[i_calif]))
            salida.append(tuple(fila) + (ahora,))

        self.excepciones = sum(self.valores_nuevos.values())
        return salida

    def _avisar(self, campo: str, valor: str) -> None:
        clave = f"{campo}={valor}"
        self.valores_nuevos[clave] = self.valores_nuevos.get(clave, 0) + 1
        if self.valores_nuevos[clave] == 1:
            logger.warning(
                f"[{self.nombre}] valor NO previsto en `{campo}`: «{valor}». Se carga "
                f"tal cual y queda contado en la bitácora."
            )

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        return f"""
        SELECT
            (SELECT count(*) FROM resena)                              AS filas,
            (SELECT count(DISTINCT producto_id) FROM resena)           AS productos,
            (SELECT count(DISTINCT cliente_id) FROM resena)            AS clientes,
            (SELECT SUM(calificacion) FROM resena)                     AS suma_calificacion,
            (SELECT min(calificacion) FROM resena)                     AS calif_min,
            (SELECT max(calificacion) FROM resena)                     AS calif_max,
            (SELECT count(*) FROM resena WHERE compra_verificada)      AS verificadas,
            (SELECT count(*) FROM resena WHERE fecha_moderacion IS NOT NULL)
                                                                       AS moderadas,
            (SELECT count(DISTINCT estado) FROM resena)                AS estados,
            (SELECT count(*) FROM resena WHERE estado = 'aprobada')    AS aprobadas,
            (SELECT count(*) FROM resena WHERE estado = 'pendiente')   AS pendientes,
            (SELECT count(*) FROM resena WHERE estado = 'rechazada')   AS rechazadas,
            (SELECT count(*) FROM resena WHERE pedido_id IS NULL)      AS sin_pedido,
            (SELECT count(DISTINCT date_trunc('month',
                 fecha_creacion AT TIME ZONE '{ZONA_HORARIA}')) FROM resena) AS meses,
            (SELECT ROUND(SUM(ROUND((EXTRACT(EPOCH FROM
                     (fecha_moderacion - fecha_creacion)) / 86400.0)::numeric, 2)), 2)
               FROM resena WHERE fecha_moderacion IS NOT NULL)         AS suma_dias_moderacion,
            -- ── Integridad ────────────────────────────────────────────────────
            (SELECT count(*) FROM resena r
              WHERE NOT EXISTS (SELECT 1 FROM producto p WHERE p.id = r.producto_id))
                                                                       AS sin_producto,
            (SELECT count(*) FROM resena r
              WHERE NOT EXISTS (SELECT 1 FROM cliente c WHERE c.id = r.cliente_id))
                                                                       AS sin_cliente,
            -- El producto reseñado tiene que estar en `dim_producto`, que se
            -- construye desde `producto_variante`: un producto sin variantes no
            -- llegaría al almacén y VEN-11 lo mostraría sin poder cruzarlo con
            -- la venta.
            (SELECT count(*) FROM resena r
              WHERE NOT EXISTS (SELECT 1 FROM producto_variante v
                                 WHERE v.producto_id = r.producto_id)) AS producto_sin_variante,
            -- C4.4 en cifra: cuánto crecería la tabla si alguien uniera por
            -- producto_id a una dimensión con grano de variante.
            (SELECT count(*) FROM resena r
               JOIN producto_variante v ON v.producto_id = r.producto_id)
                                                                       AS filas_si_se_uniera,
            (SELECT count(*) FROM resena
              WHERE fecha_moderacion < fecha_creacion)                 AS moderada_antes,
            (SELECT count(*) FROM resena
              WHERE (moderado_por IS NULL) <> (fecha_moderacion IS NULL))
                                                                       AS moderacion_incoherente
        """

    _EQUIVALENCIAS = (
        ("productos",            "countDistinct(producto_id)"),
        ("clientes",             "countDistinct(cliente_id)"),
        ("suma_calificacion",    "sum(calificacion)"),
        ("calif_min",            "min(calificacion)"),
        ("calif_max",            "max(calificacion)"),
        ("verificadas",          "countIf(compra_verificada = 1)"),
        ("moderadas",            "countIf(moderada = 1)"),
        ("estados",              "countDistinct(estado)"),
        ("aprobadas",            "countIf(estado = 'aprobada')"),
        ("pendientes",           "countIf(estado = 'pendiente')"),
        ("rechazadas",           "countIf(estado = 'rechazada')"),
        ("meses",                "countDistinct(mes)"),
        ("suma_dias_moderacion", "sum(ifNull(dias_hasta_moderacion, 0))"),
    )

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []

        for clave, explicacion in (
            ("sin_producto",           "reseñas de un producto inexistente"),
            ("sin_cliente",            "reseñas de un cliente inexistente"),
            ("producto_sin_variante",  "reseñas de un producto SIN variantes (no llega "
                                       "a dim_producto y no se puede cruzar con la venta)"),
            ("moderada_antes",         "reseñas moderadas antes de haberse escrito"),
            ("moderacion_incoherente", "reseñas donde `moderado_por` y `fecha_moderacion` "
                                       "no van juntas"),
        ):
            if int(controles[clave]) != 0:
                errores.append(f"{controles[clave]} {explicacion}.")

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

        errores += self._validar_grano(client, tabla_staging, controles)
        return errores

    def _validar_grano(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """
        C4.4 en forma de control: la tabla tiene UNA fila por reseña, y el
        fan-out que se evitó se deja MEDIDO para que la nota no envejezca.
        """
        errores = []
        fila = client.query(f"""
            SELECT count(),
                   countDistinct(resena_id),
                   countIf(calificacion < 1 OR calificacion > 5),
                   countIf(moderada = 1 AND fecha_moderacion IS NULL),
                   countIf(moderada = 0 AND fecha_moderacion IS NOT NULL),
                   countIf(dias_hasta_moderacion < 0),
                   countIf(producto_nombre = '')
            FROM {tabla_staging}
        """).result_rows[0]
        (total, ids, fuera_escala, moderada_sin_fecha, fecha_sin_moderada,
         dias_negativos, sin_nombre) = fila

        if ids != total:
            errores.append(f"resena_id duplicado: {total} filas pero {ids} ids "
                           f"distintos. Es el síntoma del fan-out de C4.4.")
        # La cifra que prueba que evitar el JOIN valía la pena.
        inflado = int(controles["filas_si_se_uniera"])
        if inflado != int(controles["filas"]):
            if total != int(controles["filas"]):
                errores.append(
                    f"La tabla trae {total} filas donde hay {controles['filas']} "
                    f"reseñas. Unir por `producto_id` contra una dimensión con grano "
                    f"de VARIANTE daría {inflado} (C4.4): comprueba que la extracción "
                    f"une a `producto` y no a `producto_variante`."
                )
        if fuera_escala:
            errores.append(f"{fuera_escala} reseñas con calificación fuera de 1-5.")
        if moderada_sin_fecha or fecha_sin_moderada:
            errores.append(f"Marca de moderación incoherente ({moderada_sin_fecha} "
                           f"marcadas sin fecha, {fecha_sin_moderada} al revés).")
        if dias_negativos:
            errores.append(f"{dias_negativos} reseñas con días hasta la moderación "
                           f"NEGATIVOS.")
        if sin_nombre:
            errores.append(f"{sin_nombre} reseñas sin nombre de producto: el ranking "
                           f"de VEN-11 mostraría filas en blanco.")
        return errores
