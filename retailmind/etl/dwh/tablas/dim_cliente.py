"""
etl/dwh/tablas/dim_cliente.py — D3 del modelo (§4.3 del diseño).

El cliente como entidad. 72 filas. Sirve OTD-VEN-05 («cuánto compra cada
cliente») uniéndose a `fact_pedido` por `cliente_id`.

═══════════════════════════════════════════════════════════════════════════════
`segmento` — LA AUSENCIA SE CARGA, NO SE DISIMULA
═══════════════════════════════════════════════════════════════════════════════

La columna `segmento` resuelve a `'sin_segmentar'` en los 72 clientes, y eso es
el dato, no un hueco pendiente de llenar. El diagnóstico
`docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md` (2026-07-30) cerró la
pregunta con veredicto **(c) población homogénea**: no hay dos poblaciones de
compra que segmentar, y las siete señales de esquema que podrían soportar un
corte B2B/B2C están todas vacías o desmentidas —

    cliente.grupo_cliente_id .......... 72 de 72 en NULL   (verificado hoy)
    grupo_cliente / segmento_cliente .. 0 filas
    cliente.tipo_identificacion ....... 72 de 72 'cedula', ningún RUC
    factura_venta.identificacion ...... 3.887 con 10 dígitos, ninguna con 13

Ese recuento es de los 72 clientes de ANTES de la carga masiva y se deja como
estaba porque es la medición que sostuvo el veredicto. Hoy (2026-08-21) la
cartera son 50.087 clientes con 45.911 'cedula', 4.166 'ruc' y **10 sin
identificación**, que son los que ha traído el alta pública de la tienda —ahí
el dato es opcional—. El veredicto NO cambia: el RUC identifica a una persona
jurídica, pero 4.166 RUC repartidos en la misma distribución de compra siguen
sin dibujar dos poblaciones. Lo que sí cambió es que la columna DEJÓ DE ESTAR
SIEMPRE LLENA, y de eso se enteró el control de más abajo antes que nadie.

La alternativa —derivar el segmento de la conducta de compra— se evaluó y se
descartó: 10.378 de 10.384 líneas piden entre 1 y 4 unidades, así que no hay
compra de volumen que distinga a un mayorista. La concentración que sí existe
(top 10 % de clientes = 49,3 % de la facturación) es de FRECUENCIA, no de
comportamiento.

Por eso la columna se carga con un valor constante y explícito en vez de
omitirse: un informe que agrupe por `segmento` debe ver **una sola fila
rotulada 'sin_segmentar'**, que es la verdad, y no una columna ausente que
invite a inventarla. `tipo_identificacion` viaja al lado por el mismo motivo:
es la señal que HABRÍA marcado al cliente empresa (RUC), y tenerla en el almacén
hace la afirmación comprobable sin volver a PostgreSQL. El tercer valor,
'sin_dato', es el cliente que se registró en la tienda sin darla.

═══════════════════════════════════════════════════════════════════════════════
CIUDAD Y PROVINCIA — VERIFICADO ANTES DE CONFIAR (2026-07-30)
═══════════════════════════════════════════════════════════════════════════════

La dirección NO cuelga del cliente: `direccion.usuario_id` apunta a `usuario`, y
el puente es `cliente.usuario_id`. Comprobado sobre los datos reales:

    clientes ................................... 72
    clientes con usuario_id .................... 72   (ninguno suelto)
    clientes con dirección predeterminada ...... 72
    clientes con MÁS DE UNA predeterminada ..... 0    ← sin fan-out

Ese último control es el que importa: si un cliente tuviera dos direcciones
marcadas como predeterminadas, el LEFT JOIN duplicaría su fila y la dimensión
saldría con 73 filas —un error que `count(*)` sí atrapa, pero que en una
dimensión de 72 filas pasa fácilmente por «casi bien». Hoy no ocurre; el
control de conteo lo vigila en cada corrida.

Los centinelas `'sin_direccion'` existen aunque hoy no se usen: un alta futura
sin dirección debe degradarse a una etiqueta legible, no a NULL en una columna
`LowCardinality` que además se agrupa en los informes.
"""

from datetime import datetime
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import ZONA_HORARIA
from etl.dwh.tarea import TareaCarga

#: Valor único de `segmento`. Constante del código y no literal suelto en el
#: SQL: si algún día el negocio registra segmentación de verdad, este es el
#: sitio donde deja de ser una constante.
SIN_SEGMENTAR = "sin_segmentar"


class DimCliente(TareaCarga):

    nombre = "dim_cliente"

    def ddl(self, nombre_tabla: str) -> str:
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            cliente_id          UInt32,
            nombre_completo     String,
            email               String,
            ciudad              LowCardinality(String),
            provincia           LowCardinality(String),
            segmento            LowCardinality(String),
            tipo_identificacion LowCardinality(String),
            fecha_alta          DateTime('{ZONA_HORARIA}'),
            activo              UInt8,
            fecha_carga         DateTime('{ZONA_HORARIA}')
        )
        ENGINE = ReplacingMergeTree(fecha_carga)
        ORDER BY cliente_id
        """

    def columnas(self) -> list[str]:
        return [
            "cliente_id", "nombre_completo", "email", "ciudad", "provincia",
            "segmento", "tipo_identificacion", "fecha_alta", "activo", "fecha_carga",
        ]

    # ── Extracción ───────────────────────────────────────────────────────────

    #: Dirección predeterminada del cliente, por su USUARIO. Se aísla en un CTE
    #: —y no se pega como LEFT JOIN suelto— para que extracción y controles
    #: compartan literalmente la misma definición: si la regla cambia, no puede
    #: cambiar en un sitio y no en el otro.
    _CTE_DIRECCION = """
        direccion_pred AS (
            SELECT DISTINCT ON (d.usuario_id)
                   d.usuario_id, d.ciudad_id
            FROM direccion d
            WHERE d.es_predeterminada AND d.activo
            ORDER BY d.usuario_id, d.id
        )
    """

    def sql_extraccion(self) -> str:
        return f"""
        WITH {self._CTE_DIRECCION}
        SELECT
            c.id                                               AS cliente_id,
            TRIM(c.nombre || ' ' || COALESCE(c.apellido, ''))  AS nombre_completo,
            c.email,
            COALESCE(ci.nombre, 'sin_direccion')               AS ciudad,
            COALESCE(pr.nombre, 'sin_direccion')               AS provincia,
            '{SIN_SEGMENTAR}'                                  AS segmento,
            COALESCE(c.tipo_identificacion, 'sin_dato')        AS tipo_identificacion,
            -- El alta real del cliente es la de su cuenta de usuario; el
            -- respaldo cubre un cliente sin usuario, que hoy no existe.
            COALESCE(u.fecha_creacion, c.fecha_creacion)       AS fecha_alta,
            CASE WHEN c.activo THEN 1 ELSE 0 END               AS activo
        FROM cliente c
        LEFT JOIN usuario u        ON u.id = c.usuario_id
        LEFT JOIN direccion_pred d ON d.usuario_id = c.usuario_id
        LEFT JOIN ciudad ci        ON ci.id = d.ciudad_id
        LEFT JOIN provincia pr     ON pr.id = ci.provincia_id
        ORDER BY c.id
        """

    def transformar(self, lote: list[tuple]) -> list[tuple]:
        ahora = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)
        return [tuple(fila) + (ahora,) for fila in lote]

    # ── Controles ────────────────────────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        Además del conteo: `emails` vigila el fan-out (72 filas con 71 correos
        distintos sería una fila duplicada que `count(*)` no distingue de un
        alta nueva), y `con_direccion` deja registrada en cada corrida la
        cobertura real de la denormalización geográfica.
        """
        return f"""
        WITH {self._CTE_DIRECCION}
        SELECT
            count(*)                                            AS filas,
            count(DISTINCT c.email)                             AS emails,
            count(*) FILTER (WHERE d.usuario_id IS NOT NULL)    AS con_direccion,
            count(*) FILTER (WHERE c.activo)                    AS activos,
            count(DISTINCT ci.nombre)                           AS ciudades,
            count(DISTINCT pr.nombre)                           AS provincias,
            -- El COALESCE va TAMBIEN aqui, y esa es la correccion: la
            -- extraccion convierte el NULL en 'sin_dato' (arriba), pero
            -- `count(DISTINCT col)` en SQL **ignora los NULL**, asi que el
            -- control de origen contaba 2 donde el destino tenia 3. Comparaba
            -- dos expresiones distintas y funcionaba solo mientras no hubiera
            -- ni un cliente sin identificacion.
            --
            -- Esto NO relaja el control —sigue exigiendo igualdad exacta—: se
            -- corrige la EXPRESION, no el umbral. Misma leccion que C6.4.
            count(DISTINCT COALESCE(c.tipo_identificacion, 'sin_dato'))
                                                                AS tipos_identificacion,
            count(*) FILTER (WHERE c.grupo_cliente_id IS NOT NULL) AS con_grupo_cliente
        FROM cliente c
        LEFT JOIN direccion_pred d ON d.usuario_id = c.usuario_id
        LEFT JOIN ciudad ci        ON ci.id = d.ciudad_id
        LEFT JOIN provincia pr     ON pr.id = ci.provincia_id
        """

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = []
        fila = client.query(f"""
            SELECT count(), countDistinct(email), countDistinct(cliente_id),
                   countIf(ciudad != 'sin_direccion'), countIf(activo = 1),
                   countDistinct(segmento), countIf(segmento = '{SIN_SEGMENTAR}'),
                   countDistinct(tipo_identificacion)
            FROM {tabla_staging}
        """).result_rows[0]
        (total, emails, ids, con_direccion, activos,
         segmentos, sin_segmentar, tipos) = fila

        if ids != total:
            errores.append(f"cliente_id duplicado: {total} filas pero {ids} ids "
                           f"distintos (fan-off del join de dirección).")
        if emails != int(controles["emails"]):
            errores.append(f"Correos distintos: origen {controles['emails']} "
                           f"vs destino {emails}.")
        if con_direccion != int(controles["con_direccion"]):
            errores.append(f"Clientes con dirección: origen {controles['con_direccion']} "
                           f"vs destino {con_direccion}.")
        if activos != int(controles["activos"]):
            errores.append(f"Clientes activos: origen {controles['activos']} "
                           f"vs destino {activos}.")
        if tipos != int(controles["tipos_identificacion"]):
            errores.append(f"Tipos de identificación: origen "
                           f"{controles['tipos_identificacion']} vs destino {tipos}.")

        # El invariante declarado de §4.3: la columna es constante HOY, y si
        # dejara de serlo hay que enterarse por aquí y no por un informe raro.
        if segmentos != 1 or sin_segmentar != total:
            errores.append(
                f"`segmento` dejó de ser constante: {segmentos} valores distintos y "
                f"{sin_segmentar} de {total} filas en '{SIN_SEGMENTAR}'. El diseño "
                f"declara la segmentación INEXISTENTE (§4.3 y "
                f"DIAGNOSTICO_SEGMENTO_CLIENTE.md); si el negocio empezó a "
                f"segmentar de verdad, hay que revisar el informe antes de publicar."
            )
        # Espejo del anterior en el ORIGEN: si alguien poblara grupo_cliente_id,
        # 'sin_segmentar' pasaría a ser mentira y no un dato.
        if int(controles["con_grupo_cliente"]) != 0:
            errores.append(
                f"{controles['con_grupo_cliente']} clientes tienen ya "
                f"`grupo_cliente_id`: la segmentación dejó de ser inexistente y "
                f"`segmento` no puede seguir siendo una constante."
            )
        return errores
