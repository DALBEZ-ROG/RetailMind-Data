"""
etl/dwh/tablas/fact_alerta_cliente.py — FASE E3 del nivel estratégico
(§5.2 de `docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`).

La **alerta de abandono de cliente**: la foto fechada de qué clientes llevan un
silencio estadísticamente inusual **según su propio ritmo de compra**, ordenada
por valor en riesgo. Alimenta D-08.1 (sobre qué clientes se actúa para
recuperarlos, y con qué gesto) y realiza el propuesto OTD-VEN-19.

Es la **vigésima primera** tabla del almacén y la segunda `TareaModelo`.

═══════════════════════════════════════════════════════════════════════════════
1. POR QUÉ UNA TABLA NUEVA
═══════════════════════════════════════════════════════════════════════════════

`fact_pedido` guarda las compras; **la alerta es sobre la AUSENCIA de compra**,
que ninguna fila representa. Y arrastra columnas que ningún hecho tiene: la
probabilidad, el nivel, y —lo más importante— el **veredicto medido del propio
modelo**, que viaja en cada fila para que la pantalla no pueda enseñar la lista
sin enseñar lo que la lista vale.

═══════════════════════════════════════════════════════════════════════════════
2. LA VALIDACIÓN QUE SÍ ABORTA, Y LA QUE DELIBERADAMENTE NO
═══════════════════════════════════════════════════════════════════════════════

**ABORTA** la concentración: si algún mes de la ventana tiene un cliente por
encima de `CONCENTRACION_MAXIMA`, la tabla no se publica. Es el artefacto de la
rampa de cartera, y es el único modo de fallo de esta fase que produce una
pantalla perfectamente coherente y exactamente al revés: con la historia
completa, el segundo cliente de la cartera ($399.425 facturados) sale como la
alerta más fuerte del sistema con P = 4·10⁻¹⁷.

**NO ABORTA** el lift. §5.2.6 llama «resultado inaceptable» a un lift ≤ 1,0,
pero la decisión de negocio —tomada, y por escrito— es que la alerta **se
publica igual, con su lift a la vista en la cabecera de la pantalla**. Un lift de
1,0 no invalida la lista: la convierte en una lista de silencios inusuales
ordenada por dinero, que sigue siendo mejor punto de partida para una llamada
que el orden alfabético. Lo inaceptable no es el lift bajo; es el lift oculto.

═══════════════════════════════════════════════════════════════════════════════
3. LAS DOS TRAMPAS DE ESTA FASE
═══════════════════════════════════════════════════════════════════════════════

**Los clientes sin muestra son los candidatos más fuertes, y el modelo los
expulsa.** Un cliente con un silencio de 179 días tiene, por eso mismo, menos de
tres pedidos en la ventana — y queda fuera del cálculo de λ. Si la tabla
publicara solo lo evaluable, la pantalla nunca mostraría al cliente que de
verdad se fue, sin que fallara ninguna suma. Se publican los 69 con su silencio
REAL y el nivel `sin_muestra`, y la salvedad los nombra con su cifra.

**El recorte del VENDEDOR no puede hacerse por id.** §5.2.8 dice «el mismo
mecanismo de OTD-VEN-02», que en PostgreSQL es `pedido.vendedor_id = <id del
JWT>`. En el almacén **no existe `vendedor_id`**: `fact_pedido` guarda el NOMBRE
del vendedor. La tabla publica por eso `vendedores`, el conjunto de nombres que
atendieron al cliente en la ventana, y el control de PostgreSQL comprueba que
esos nombres son ÚNICOS entre los usuarios —si dos vendedores se llamaran igual,
compartirían cartera y la carga aborta—.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from etl.dwh.conexiones import CH_DATABASE, ZONA_HORARIA, logger
from etl.dwh.modelos import alerta_abandono as modelo
from etl.dwh.tarea import TareaModelo

#: Vendedor con el que el ETL etiqueta el pedido nacido en el checkout online.
#: No es una persona y por tanto NO forma cartera de nadie.
VENDEDOR_ONLINE = "(canal en línea)"


def _dec(valor: float | None, decimales: int = 2) -> Decimal:
    if valor is None:
        return Decimal("0." + "0" * decimales) if decimales else Decimal(0)
    return Decimal(str(round(float(valor), decimales)))


class FactAlertaCliente(TareaModelo):

    nombre = "fact_alerta_cliente"

    #: §5.2.5 declara `{fact_pedido, dim_cliente}`, y con eso NO se pueden
    #: producir dos columnas que la propia §5.2.7 exige: `reclamos_abiertos` y
    #: `devoluciones_12m`. Se añaden las dos tablas que las sostienen. Sin ellas
    #: la tarea no fallaría: publicaría ceros, y el gerente leería «este cliente
    #: no tiene reclamos» de un cliente con dos abiertos — que es justo el dato
    #: que decide el gesto comercial.
    depende_de = ("fact_pedido", "dim_cliente", "fact_ticket", "fact_devolucion")

    def __init__(self):
        super().__init__()
        self.informe: dict = {}
        self.calculado_en = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        """
        Las columnas de §5.2.7 más OCHO que la pantalla necesita y el diseño no
        previó:

          * `ventana_inicio` y `meses_ventana` — sin ellas, una fila con λ = 0,25
            no se puede auditar: no se sabe sobre qué período se midió, y ése es
            el parámetro del que depende todo el resultado.
          * `fecha_ultima_compra` — `dias_silencio` es una resta contra el ancla,
            y quien llama al cliente necesita la fecha, no la resta.
          * `valor_en_riesgo` — la regla 3 de §5.2.9 ordena la lista por
            «facturación × (1 − P)». Calcularlo en la consulta obligaría a
            repetir la fórmula en cada informe que lea la tabla; calcularlo aquí
            garantiza que todos ordenen por lo mismo.
          * `compras_por_mes` y `sparkline_desde` — la regla 2 exige el
            *sparkline* EN LA FILA. Es la defensa contra el artefacto de la
            rampa: permite ver de un golpe si la caída es un hueco o una
            pendiente.
          * `vendedores` — el recorte del VENDEDOR, que sin esto no es posible
            (ver el bloque 3 de la cabecera).
          * `p_valor_backtest` — el lift agrupado de esta base sale ≈ 2,0 y su
            valor p ≈ 0,11. Publicar el primero sin el segundo convierte un
            resultado indistinguible del azar en un titular.
          * `evaluados_backtest`, `precision_backtest` y `tasa_base_backtest` —
            §5.2.7 guarda el lift y sus positivos, pero un lift sin su
            denominador ni su precisión no se puede reconstruir ni discutir.

        `activo` viene de `dim_cliente` porque un cliente dado de baja no se
        llama, y sin la columna la lista lo pondría en cabeza por facturación.
        """
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            fecha_calculo             Date,
            fecha_ancla               Date,
            ventana_inicio            Date,
            meses_ventana             UInt8,
            concentracion_maxima      Decimal(5,2),
            cliente_id                UInt32,
            cliente_nombre            String,
            email                     String,
            ciudad                    LowCardinality(String),
            activo                    UInt8,
            pedidos_ventana           UInt16,
            dias_observados           UInt16,
            tasa_diaria               Decimal(10,4),
            intervalo_medio_dias      Decimal(8,2),
            fecha_ultima_compra       Date,
            dias_silencio             UInt16,
            silencio_en_intervalos    Decimal(8,2),
            prob_silencio             Decimal(8,6),
            nivel_alerta              LowCardinality(String),
            facturacion_12m           Decimal(14,2),
            percentil_valor           Decimal(5,2),
            valor_en_riesgo           Decimal(14,2),
            reclamos_abiertos         UInt16,
            devoluciones_12m          UInt16,
            compras_por_mes           Array(UInt16),
            sparkline_desde           Date,
            vendedores                Array(String),
            lift_backtest             Decimal(6,2),
            -- UInt32 y no UInt16 desde la Fase 2 de la carga masiva.
            -- `evaluados_backtest` cuenta CLIENTE x ORIGEN del backtest, así que
            -- crece con la cartera: con 69 clientes evaluables cabía de sobra;
            -- con 49.312 en la ventana y tres orígenes rodantes son ~148.000 y
            -- desborda los 65.535. El driver no dice «desbordamiento»: dice
            -- «Unable to create Python array. This is usually caused by trying
            -- to insert None», que es el MISMO mensaje engañoso que costó la
            -- Fase 0 con `categoria_id` y `proveedor_id`. Dos veces la misma
            -- trampa: la primera con ids, la segunda con un CONTADOR — que en
            -- la Fase 1 se dio por seguro precisamente por no ser un id.
            casos_positivos_backtest  UInt32,
            evaluados_backtest        UInt32,
            precision_backtest        Decimal(6,2),
            tasa_base_backtest        Decimal(6,2),
            p_valor_backtest          Decimal(6,4),
            alerta_alpha              Decimal(5,4),
            modelo_version            String,
            fecha_calculo_ts          DateTime('{ZONA_HORARIA}'),
            fecha_carga               DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYear(fecha_calculo)
        ORDER BY (fecha_calculo, cliente_id)
        """

    def columnas(self) -> list[str]:
        return [
            "fecha_calculo", "fecha_ancla", "ventana_inicio", "meses_ventana",
            "concentracion_maxima", "cliente_id", "cliente_nombre", "email", "ciudad", "activo",
            "pedidos_ventana", "dias_observados", "tasa_diaria",
            "intervalo_medio_dias", "fecha_ultima_compra", "dias_silencio",
            "silencio_en_intervalos", "prob_silencio", "nivel_alerta",
            "facturacion_12m", "percentil_valor", "valor_en_riesgo",
            "reclamos_abiertos", "devoluciones_12m", "compras_por_mes",
            "sparkline_desde", "vendedores", "lift_backtest",
            "casos_positivos_backtest", "evaluados_backtest",
            "precision_backtest", "tasa_base_backtest", "p_valor_backtest",
            "alerta_alpha", "modelo_version", "fecha_calculo_ts", "fecha_carga",
        ]

    # ── Lectura del almacén ──────────────────────────────────────────────────

    def _compras(self, client) -> tuple[dict[int, list[date]], date]:
        """
        Fechas de compra por cliente y el ANCLA.

        **El ancla es `max(fecha_pedido)` del almacén, jamás la fecha del
        reloj** (§5.2.5). Si el ETL se detiene una semana, medir contra el reloj
        haría que los 69 clientes ganaran siete días de silencio a la vez y
        cruzaran el umbral en bloque: la pantalla se llenaría de alertas rojas
        producidas por una caída del pipeline, no por un cambio en la clientela.
        """
        filas = client.query(f"""
            SELECT cliente_id, toDate(fecha_pedido)
            FROM {CH_DATABASE}.fact_pedido
            WHERE es_cancelado = 0""").result_rows
        compras: dict[int, list[date]] = {}
        for cliente, fecha in filas:
            compras.setdefault(int(cliente), []).append(fecha)
        if not compras:
            raise ValueError("`fact_pedido` no tiene ni una compra no cancelada: "
                             "la alerta no se puede calcular.")
        ancla = max(max(v) for v in compras.values())
        return compras, ancla

    def _clientes(self, client) -> dict[int, tuple]:
        return {
            int(f[0]): (f[1], f[2], f[3], int(f[4]))
            for f in client.query(
                f"SELECT cliente_id, nombre_completo, email, ciudad, activo "
                f"FROM {CH_DATABASE}.dim_cliente FINAL").result_rows
        }

    def _contexto(self, client, ancla: date) -> dict:
        """
        Reclamos abiertos y devoluciones recientes.

        **NO son variables del modelo** y §5.2.4 lo declara para que nadie las
        añada más adelante creyendo que mejora: meter covariables sobre 5 casos
        positivos es sobreajuste por construcción. Se muestran AL LADO, para
        informar el gesto comercial — no es lo mismo llamar a quien se fue en
        silencio que a quien se fue con un reclamo abierto.
        """
        desde = ancla - timedelta(days=modelo.DIAS_FACTURACION)
        reclamos = {
            int(c): int(n) for c, n in client.query(f"""
                SELECT cliente_id, count() FROM {CH_DATABASE}.fact_ticket
                WHERE estado NOT IN ('resuelto', 'cerrado')
                GROUP BY cliente_id""").result_rows
        }
        devoluciones = {
            int(c): int(n) for c, n in client.query(f"""
                SELECT cliente_id, count() FROM {CH_DATABASE}.fact_devolucion
                WHERE toDate(fecha_solicitud) >= %(d)s
                GROUP BY cliente_id""", parameters={"d": desde}).result_rows
        }
        facturacion = {
            int(c): float(m) for c, m in client.query(f"""
                SELECT cliente_id, sum(total) FROM {CH_DATABASE}.fact_pedido
                WHERE es_cancelado = 0 AND toDate(fecha_pedido) >= %(d)s
                GROUP BY cliente_id""", parameters={"d": desde}).result_rows
        }
        return {"reclamos": reclamos, "devoluciones": devoluciones,
                "facturacion": facturacion}

    def _vendedores(self, client, inicio: date, ancla: date) -> dict[int, list[str]]:
        """Nombres de vendedor que atendieron a cada cliente DENTRO de la ventana."""
        salida: dict[int, list[str]] = {}
        for cliente, vendedor in client.query(f"""
                SELECT DISTINCT cliente_id, vendedor FROM {CH_DATABASE}.fact_pedido
                WHERE es_cancelado = 0 AND toDate(fecha_pedido) >= %(i)s
                  AND toDate(fecha_pedido) <= %(f)s AND vendedor != %(w)s
                ORDER BY cliente_id, vendedor""",
                parameters={"i": inicio, "f": ancla, "w": VENDEDOR_ONLINE}).result_rows:
            salida.setdefault(int(cliente), []).append(str(vendedor))
        return salida

    def _sparkline(self, client, malla: list[date]) -> dict[int, list[int]]:
        """Compras por mes de cada cliente sobre la malla, sin huecos."""
        posicion = {m: i for i, m in enumerate(malla)}
        salida: dict[int, list[int]] = {}
        for cliente, mes, n in client.query(f"""
                SELECT cliente_id, toStartOfMonth(fecha_pedido) AS mes, count()
                FROM {CH_DATABASE}.fact_pedido
                WHERE es_cancelado = 0 AND toStartOfMonth(fecha_pedido) >= %(d)s
                GROUP BY cliente_id, mes""",
                parameters={"d": malla[0]}).result_rows:
            if mes not in posicion:
                continue
            serie = salida.setdefault(int(cliente), [0] * len(malla))
            serie[posicion[mes]] = int(n)
        return salida

    # ── El cálculo ───────────────────────────────────────────────────────────

    def filas(self, client) -> list[tuple]:
        compras, ancla = self._compras(client)
        inicio = modelo.inicio_ventana(ancla, modelo.MESES_VENTANA)

        concentracion, mes_peor = modelo.concentracion_maxima(compras, inicio, ancla)
        estimaciones = modelo.estimar(compras, inicio, ancla)
        clientes = self._clientes(client)
        contexto = self._contexto(client, ancla)
        vendedores = self._vendedores(client, inicio, ancla)
        malla = modelo.malla_sparkline(ancla, modelo.MESES_SPARKLINE)
        spark = self._sparkline(client, malla)

        veredicto = modelo.backtest(compras, ancla, modelo.MESES_VENTANA)
        facturacion = contexto["facturacion"]
        percentil = modelo.percentiles(
            {c: facturacion.get(c, 0.0) for c in estimaciones})

        lift = veredicto.lift
        filas: list[tuple] = []
        for cliente, e in sorted(estimaciones.items()):
            nombre, email, ciudad, activo = clientes.get(
                cliente, (f"Cliente {cliente}", "", "", 1))
            fact = facturacion.get(cliente, 0.0)
            prob = e.prob_silencio
            filas.append((
                self.calculado_en.date(), ancla, inicio, modelo.MESES_VENTANA,
                _dec(concentracion), cliente, nombre, email, ciudad, activo,
                e.pedidos_ventana, e.dias_observados, _dec(e.tasa_diaria, 4),
                _dec(e.intervalo_medio), e.fecha_ultima_compra, e.dias_silencio,
                _dec(e.silencio_en_intervalos), _dec(prob, 6), e.nivel,
                _dec(fact), _dec(percentil.get(cliente, 0.0)),
                # Regla 3 de §5.2.9: valor en riesgo = facturación × (1 − P).
                # Un cliente de $500 con probabilidad 0,1 % no es la primera
                # llamada; uno de $78.000 con 2 % sí.
                _dec(fact * (1.0 - prob)),
                contexto["reclamos"].get(cliente, 0),
                contexto["devoluciones"].get(cliente, 0),
                spark.get(cliente, [0] * len(malla)), malla[0],
                vendedores.get(cliente, []),
                _dec(lift if lift is not None else 0.0),
                veredicto.positivos, veredicto.evaluados,
                _dec(veredicto.precision * 100.0), _dec(veredicto.tasa_base * 100.0),
                _dec(veredicto.p_valor, 4), _dec(modelo.ALPHA, 4),
                modelo.VERSION_MODELO, self.calculado_en, self.calculado_en,
            ))

        niveles = {n: sum(1 for e in estimaciones.values() if e.nivel == n)
                   for n in modelo.NIVELES}
        self.informe = {
            "ancla": ancla, "inicio": inicio, "meses": modelo.MESES_VENTANA,
            "concentracion": concentracion, "mes_concentracion": mes_peor,
            "clientes": len(estimaciones), "niveles": niveles,
            "veredicto": veredicto,
            "valor_en_alerta": sum(
                facturacion.get(c, 0.0) for c, e in estimaciones.items()
                if e.en_alerta),
            "sin_muestra_mayor_silencio": max(
                ((e.dias_silencio, c) for c, e in estimaciones.items()
                 if e.sin_muestra), default=(0, None)),
        }
        # Los clientes sin muestra NO son un error, pero sí lo que la bitácora
        # tiene que poder contar: son el hueco declarado del modelo.
        self.excepciones = niveles[modelo.NIVEL_SIN_MUESTRA]

        lift_txt = "n/d" if lift is None else f"{lift:.2f}"
        logger.info(
            f"[{self.nombre}] ancla {ancla} · ventana {inicio} "
            f"({modelo.MESES_VENTANA} meses) · {len(estimaciones)} clientes · "
            f"criticas {niveles[modelo.NIVEL_CRITICA]} · "
            f"atencion {niveles[modelo.NIVEL_ATENCION]} · "
            f"sin muestra {niveles[modelo.NIVEL_SIN_MUESTRA]} · "
            f"lift {lift_txt} (p={veredicto.p_valor:.3f}, "
            f"{veredicto.positivos} positivos)")
        return filas

    # ── Controles contra PostgreSQL ──────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        El UNIVERSO y el ancla salen de PostgreSQL, no del almacén.

        Una alerta no se compara al centavo —no hay en PostgreSQL ninguna fila
        que diga «este cliente está en riesgo»—, pero sí se comparan las tres
        cosas de las que depende que la alerta signifique algo:

          1. **el universo**: un cliente perdido en el almacén es un cliente que
             la pantalla nunca llama, y ninguna suma lo delataría;
          2. **el ancla**: si `max(fecha_pedido)` del almacén se quedara atrás,
             todos los silencios saldrían cortos y la lista, tranquilizadora;
          3. **los nombres de vendedor**: el recorte del VENDEDOR casa por
             NOMBRE porque el almacén no guarda `vendedor_id` (bloque 3 de la
             cabecera). Si dos usuarios compartieran nombre compartirían cartera,
             y el recorte de rol dejaría ver clientes ajenos.

        La consulta reconstruye el universo desde `pedido` con otra escritura que
        la de la tarea: si reutilizara la del almacén, ambas compartirían el
        error y el control sería una tautología.
        """
        return """
        WITH venta AS (
            SELECT p.cliente_id,
                   (p.fecha_pedido AT TIME ZONE 'America/Guayaquil')::date AS dia
            FROM pedido p
            JOIN estado_pedido ep ON ep.id = p.estado_pedido_id
            WHERE ep.codigo <> 'cancelado' AND p.cliente_id IS NOT NULL
        ),
        vendedor AS (
            -- Los usuarios que REALMENTE figuran como autor de un pedido, que es
            -- exactamente la población cuyos nombres etiquetan
            -- `fact_pedido.vendedor`. No se parte de `usuario_rol`: el rol
            -- `retailmind_etl` no tiene privilegio sobre esa tabla puente
            -- (script 85 concede 54 tablas y ésa no está), y de todos modos un
            -- vendedor dado de alta que nunca vendió no puede colisionar con
            -- nadie en una cartera que no existe.
            SELECT DISTINCT u.id,
                   trim(concat(u.nombre, ' ', COALESCE(u.apellido, ''))) AS nombre
            FROM pedido p
            JOIN usuario u ON u.id = p.vendedor_id
        )
        SELECT
            (SELECT count(DISTINCT cliente_id) FROM venta)          AS filas,
            (SELECT count(DISTINCT cliente_id) FROM venta)          AS clientes_con_compra,
            (SELECT max(dia) FROM venta)                            AS ancla,
            (SELECT count(*) FROM vendedor)                         AS vendedores,
            (SELECT count(DISTINCT nombre) FROM vendedor)           AS vendedores_distintos
        """

    # ── LA VALIDACIÓN ────────────────────────────────────────────────────────

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = self._validar_universo(client, tabla_staging, controles)
        errores += self._validar_artefacto()
        errores += self._validar_forma(client, tabla_staging)
        return errores

    def _validar_universo(self, client, tabla_staging: str, controles: dict) -> list[str]:
        ancla_ch, clientes, versiones = client.query(f"""
            SELECT max(fecha_ancla), countDistinct(cliente_id),
                   countDistinct(lift_backtest)
            FROM {tabla_staging}""").result_rows[0]

        errores = []
        esperados = controles.get("clientes_con_compra")
        if esperados is not None and int(clientes) != int(esperados):
            errores.append(
                f"Clientes con compra: PostgreSQL {esperados} vs alerta {clientes}. "
                f"Un cliente que no está en la tabla es un cliente que la pantalla "
                f"nunca va a llamar, y ninguna suma lo delataría.")

        ancla_pg = controles.get("ancla")
        if ancla_pg is not None and ancla_ch != ancla_pg:
            errores.append(
                f"ANCLA DESALINEADA: PostgreSQL {ancla_pg} vs almacén {ancla_ch}. "
                f"La recencia se mide contra el ancla; si el almacén va atrasado, "
                f"todos los silencios salen cortos y la lista sale tranquilizadora "
                f"sin dar un solo error.")

        if int(versiones) != 1:
            errores.append(
                f"Conviven {versiones} valores de `lift_backtest` en la misma foto. "
                f"El veredicto del modelo es UNO por corrida: si difiere entre filas, "
                f"la cabecera de la pantalla mostraría el de una fila cualquiera.")

        vendedores = controles.get("vendedores")
        distintos = controles.get("vendedores_distintos")
        if vendedores is not None and distintos is not None and vendedores != distintos:
            errores.append(
                f"Hay {vendedores} usuarios que venden y solo {distintos} nombres "
                f"distintos. El recorte del VENDEDOR casa por NOMBRE —el almacén no "
                f"guarda `vendedor_id`—, así que dos homónimos compartirían cartera y "
                f"un vendedor vería clientes ajenos.")
        return errores

    def _validar_artefacto(self) -> list[str]:
        """
        **La comprobación que aborta.** Ver el bloque 2 de la cabecera.

        Hoy la ventana estable da 10,9 % de concentración máxima; la historia
        completa, 100,0 %. Entre medias no hay una zona gris interesante: o la
        cartera está asentada o está arrancando.
        """
        concentracion = self.informe.get("concentracion", 0.0)
        if concentracion > modelo.CONCENTRACION_MAXIMA:
            mes = self.informe.get("mes_concentracion")
            return [
                f"ARTEFACTO DE LA RAMPA DE CARTERA: en {mes} un solo cliente hizo el "
                f"{concentracion:.1f} % de los pedidos, por encima del "
                f"{modelo.CONCENTRACION_MAXIMA:.0f} % admitido. En ese régimen λ no es "
                f"comparable entre clientes y la alerta se INVIERTE: el cliente más "
                f"grande de la historia sale como el más probable de abandonar, con "
                f"señal abrumadora y sin que falle ninguna suma. NO se publica."
            ]
        return []

    def _validar_forma(self, client, tabla_staging: str) -> list[str]:
        """
        Invariantes de la propia tabla. Ninguna rompe una suma —por eso hay que
        buscarlas a propósito— y todas producirían una pantalla con aspecto
        normal.
        """
        (nivel_malo, prob_incoherente, silencio_incoherente, sin_muestra_con_tasa,
         con_muestra_sin_tasa, silencio_futuro, anclas) = client.query(f"""
            SELECT
                countIf(nivel_alerta NOT IN {tuple(modelo.NIVELES)}),
                countIf(nivel_alerta = '{modelo.NIVEL_CRITICA}'
                            AND prob_silencio >= {modelo.ALPHA}
                     OR nivel_alerta = '{modelo.NIVEL_NORMAL}'
                            AND prob_silencio < {modelo.UMBRAL_ATENCION}),
                countIf(nivel_alerta != '{modelo.NIVEL_SIN_MUESTRA}'
                    AND abs(toFloat64(silencio_en_intervalos)
                            - toFloat64(tasa_diaria) * dias_silencio) > 0.02),
                countIf(nivel_alerta = '{modelo.NIVEL_SIN_MUESTRA}' AND tasa_diaria > 0),
                countIf(nivel_alerta != '{modelo.NIVEL_SIN_MUESTRA}'
                        AND tasa_diaria <= 0),
                countIf(fecha_ultima_compra > fecha_ancla),
                countDistinct(fecha_ancla)
            FROM {tabla_staging}""").result_rows[0]

        errores = []
        if nivel_malo:
            errores.append(f"{nivel_malo} filas con un `nivel_alerta` fuera de la "
                           f"lista blanca.")
        if prob_incoherente:
            errores.append(
                f"{prob_incoherente} filas cuyo nivel contradice su probabilidad. El "
                f"nivel es la traducción del umbral α; si dejan de coincidir, la "
                f"pantalla pinta un semáforo que no describe la cifra de al lado.")
        if silencio_incoherente:
            errores.append(
                f"{silencio_incoherente} filas donde «veces su intervalo» no es "
                f"`dias_silencio × tasa_diaria`. Es la medida PRINCIPAL de la pantalla "
                f"(regla 1 de §5.2.9): si no deriva de las dos columnas que la "
                f"acompañan, la fila se contradice a sí misma.")
        if sin_muestra_con_tasa:
            errores.append(f"{sin_muestra_con_tasa} filas `sin_muestra` con tasa "
                           f"distinta de cero.")
        if con_muestra_sin_tasa:
            errores.append(f"{con_muestra_sin_tasa} filas evaluadas con tasa cero.")
        if silencio_futuro:
            errores.append(f"{silencio_futuro} filas cuya última compra es POSTERIOR "
                           f"al ancla: la recencia saldría negativa.")
        if int(anclas) != 1:
            errores.append(f"La foto tiene {anclas} anclas distintas. Una foto se mide "
                           f"contra UN instante.")
        return errores

    # ── Informe de consola ───────────────────────────────────────────────────

    def resumen(self) -> str:
        i = self.informe
        if not i:
            return "(sin cálculo)"
        v: modelo.Backtest = i["veredicto"]
        lift = "n/d" if v.lift is None else f"{v.lift:.2f}"
        dias, cliente = i["sin_muestra_mayor_silencio"]
        lineas = [
            f"  Ancla          {i['ancla']}  (max(fecha_pedido) del almacén, "
            f"NO el reloj)",
            f"  Ventana        {i['inicio']} → {i['ancla']}  ({i['meses']} meses)",
            f"  Concentración  {i['concentracion']:.1f} % máx. mensual de un solo "
            f"cliente (límite {modelo.CONCENTRACION_MAXIMA:.0f} %"
            + (f", peor mes {i['mes_concentracion']}" if i['mes_concentracion'] else "")
            + ")",
            "",
            f"  Clientes       {i['clientes']}",
            "  Niveles        " + " · ".join(
                f"{k} {n}" for k, n in i["niveles"].items()),
            f"  En alerta      {i['niveles'][modelo.NIVEL_CRITICA] + i['niveles'][modelo.NIVEL_ATENCION]}"
            f" · facturación 12m en riesgo ${i['valor_en_alerta']:,.2f}",
            f"  Sin muestra    {i['niveles'][modelo.NIVEL_SIN_MUESTRA]}"
            + (f" · el mayor silencio entre ellos: {dias} días (cliente {cliente})"
               if cliente else ""),
            "",
            "  BACKTEST DE ORIGEN MÓVIL "
            f"({modelo.ORIGENES_BACKTEST} orígenes · prueba de "
            f"{modelo.DIAS_PRUEBA} días · top {modelo.TOP_K})",
        ]
        for o in v.origenes:
            lift_o = "n/d (0 positivos: no hay azar que batir)" \
                if o.lift is None else f"{o.lift:5.2f}"
            lineas.append(
                f"    [{o.inicio} → {o.corte}] n={o.evaluados:3d} "
                f"positivos={o.positivos:2d} ({o.tasa_base:5.1%})  "
                f"prec@{o.intentos}={o.precision:5.1%}  lift={lift_o}")
        lineas += [
            "",
            f"  VEREDICTO      lift {lift}  ·  precisión@{modelo.TOP_K} "
            f"{v.precision:.1%} ({v.aciertos}/{v.intentos})  ·  tasa base "
            f"{v.tasa_base:.1%} ({v.positivos}/{v.evaluados})",
            f"                 valor p {v.p_valor:.3f} — "
            + ("el resultado NO es distinguible del azar"
               if not v.supera_al_azar
               else "el resultado supera al azar de forma significativa"),
            "  El lift se PUBLICA sea cual sea, y va en la CABECERA de la pantalla.",
        ]
        return "\n".join(lineas)
