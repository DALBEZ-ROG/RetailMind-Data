"""
etl/dwh/tablas/fact_prevision_demanda.py — FASE E2 del nivel estratégico
(§5.1 de `docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`).

La PREVISIÓN DE DEMANDA a tres meses, en tres niveles: total, categoría y
variante. Alimenta D-10.1 (las metas del período), D-11.1 (el plan de compra) y
D-07.5 (el nivel objetivo de stock).

Es la **vigésima** tabla del almacén y la primera `TareaModelo`: se calcula
desde el DWH, no vuelve a PostgreSQL, y su transformación es un modelo en
Python (`etl/dwh/modelos/prevision_demanda.py`) en vez de un `INSERT … SELECT`.

═══════════════════════════════════════════════════════════════════════════════
1. POR QUÉ UNA TABLA NUEVA, HABIENDO 19
═══════════════════════════════════════════════════════════════════════════════

Las 19 existentes son, sin excepción, **hechos de lo que ocurrió**, y su criterio
de aceptación es la igualdad al centavo contra PostgreSQL. Una predicción es una
fila con **fecha futura**: PostgreSQL no la tiene y no puede tenerla, así que
meterla en cualquiera de las 19 haría fallar los controles de esa tabla. Además
arrastra columnas que ningún hecho tiene: su banda, su muestra y su error medido.

═══════════════════════════════════════════════════════════════════════════════
2. LA VALIDACIÓN ES EL MODELO — el punto entero de heredar el patrón atómico
═══════════════════════════════════════════════════════════════════════════════

`validar()` no comprueba solo que las filas cuadren: comprueba que **el modelo
merece publicarse**, con los tres criterios de rechazo de §5.1.6. Si alguno
falla, `carga_atomica` aborta, `EXCHANGE TABLES` no llega a ejecutarse y la
pantalla sigue mostrando la previsión de la corrida anterior.

    1. no superar el ingenuo estacional  → se publica la LÍNEA BASE
    2. MAPE > 20 % a nivel total         → se publica la LÍNEA BASE
    3. cobertura fuera de 65 % – 90 %    → ABORTA

Los dos primeros no abortan: **degradan a la línea base**, que es más simple y
más honesta, y la fila lo dice con `es_linea_base = 1`. Publicar la línea base
no es un fracaso; es el resultado cuando el modelo no aporta. El tercero sí
aborta, porque una banda mal calibrada no tiene versión degradada: un intervalo
que dice 80 % y cubre el 55 % es una afirmación falsa sobre la propia
incertidumbre.

═══════════════════════════════════════════════════════════════════════════════
3. TRES DECISIONES SOBRE EL DATO, TODAS MEDIDAS Y NINGUNA HEREDADA DE UNA
   CONSTANTE
═══════════════════════════════════════════════════════════════════════════════

**El mes truncado se DETECTA, no se escribe.** §5.1.2 verifica que el generador
coloca los pedidos en `1 + floor(random()*27)` salvo julio de 2026, que usa
`*22`. Escribir «julio de 2026» en el código funcionaría exactamente una vez:
en agosto la serie tendría un mes truncado distinto y el modelo entrenaría con
él como si estuviera completo. Se compara el día máximo del último mes contra la
mediana de los anteriores (`_detectar_truncado`), y el factor de anualización
sale de esa razón.

**El universo de series sale de la HISTORIA, no de un umbral escrito.** Una
serie se previsiona si tiene `MESES_MINIMOS` meses **con venta** — no meses de
calendario. Es la diferencia entre Abarrotes (19 con venta) y Ropa Mujer (9 con
venta sobre 19 de calendario): la segunda tiene la malla completa y aun así no
hay nada que ajustar. Por eso `meses_historia` cuenta meses con venta y es la
columna que la regla 3 de §5.1.9 exige que viaje en la fila.

**La demanda censurada se mide con un umbral declarado.** Una variante está
censurada si cerró algún mes con stock cero: es un hecho, y son 7 de 1.220. Una
CATEGORÍA no puede heredar ese sí/no de una sola variante suya, o estarían
marcadas todas; se marca cuando al menos el `UMBRAL_CENSURA` de sus unidades
viene de variantes censuradas. El umbral está aquí, con su nombre, y no
enterrado en una condición.

═══════════════════════════════════════════════════════════════════════════════
4. LO QUE ESTA TABLA NO PUEDE DECIR
═══════════════════════════════════════════════════════════════════════════════

Se prevé la **VENTA**, no la demanda. Cuando un producto estuvo sin stock la
venta fue baja porque no había qué vender, y el sistema **no registra la venta
perdida**: un quiebre se lee como demanda baja y un modelo entrenado sobre venta
lo perpetúa comprando de menos. `demanda_censurada` marca las series afectadas;
corregirlo de verdad es un cambio de sistema, no un modelo.

Y el histórico es un **seed simulado** con la curva mensual escrita a mano en
`60_seed_bloque_b_ventas.sql` línea 63 y un escalón de crecimiento fijo del
+18 % en 2026. El error medido describe la calidad del método sobre estos datos;
no es evidencia de conocimiento del mercado real de Quevedo.

Bitácora de los supuestos que no se sostuvieron:
`docs/estrategico/CORRECCIONES_DISENO_ETL.md`, entradas CE3.x.
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import numpy as np

from etl.dwh.conexiones import CH_DATABASE, ZONA_HORARIA, logger
from etl.dwh.modelos import prevision_demanda as modelo
from etl.dwh.tarea import TareaModelo

# ── Parámetros de la fase, todos declarados ──────────────────────────────────

#: Meses previstos. §5.1: «horizonte 3 meses, con la banda ensanchándose
#: visiblemente».
HORIZONTE = 3

NIVEL_TOTAL = "total"
NIVEL_CATEGORIA = "categoria"
NIVEL_VARIANTE = "variante"

#: Centinela del nivel total. §5.1.7: nunca NULL en la clave de orden.
CATEGORIA_TODAS = "(todas)"

#: Meses CON VENTA que exige una serie para tener previsión propia.
MESES_MINIMOS = modelo.MESES_MINIMOS

#: Proporción de unidades procedentes de variantes con quiebre a partir de la
#: cual una categoría (o el total) se marca como demanda censurada.
UMBRAL_CENSURA = 0.20

#: Criterios de rechazo de §5.1.6.
COBERTURA_MINIMA, COBERTURA_MAXIMA = 65.0, 90.0
MAPE_MAXIMO_TOTAL = 20.0

#: Orígenes del backtest de origen móvil.
ORIGENES_BACKTEST = 3

#: Techo de las columnas Decimal(6,2) de error. Un MAPE de variante puede
#: dispararse cuando el real de un mes es 1 unidad y el previsto 12; recortarlo
#: es preferible a que el INSERT reviente por desbordamiento, y a partir de
#: cierto punto la cifra ya solo dice «esta serie no es previsible».
MAPE_TOPE = Decimal("9999.99")


def _dec(valor: float, tope: Decimal | None = None) -> Decimal:
    """Redondea a dos decimales para las columnas Decimal, con tope opcional."""
    if valor is None or not np.isfinite(valor):
        return Decimal("0.00")
    d = Decimal(str(round(float(valor), 2)))
    return min(d, tope) if tope is not None else d


class FactPrevisionDemanda(TareaModelo):

    nombre = "fact_prevision_demanda"

    #: §5.1.5. El orden lo deriva `run_etl.py` de aquí; NO se escribe a mano.
    #: `dim_fecha` no es decorativa: de ella sale la malla mensual sin huecos,
    #: para no fabricarla contando desde el propio hecho — que es el error que
    #: C3B.4 documenta al revés.
    depende_de = ("fact_venta_linea", "dim_producto", "dim_fecha")

    def __init__(self):
        super().__init__()
        #: Parte del backtest, para el informe de consola y para `validar()`.
        self.informe: dict = {}
        #: Series publicadas con la línea base por no superar al ingenuo.
        self.con_linea_base: int = 0
        #: Momento del entrenamiento, común a todas las filas de la corrida.
        self.entrenado_en = datetime.now(ZoneInfo(ZONA_HORARIA)).replace(microsecond=0)

    # ── Definición ───────────────────────────────────────────────────────────

    def ddl(self, nombre_tabla: str) -> str:
        """
        Las columnas de §5.1.7 más CUATRO que el diseño no previó y la pantalla
        necesita (corrección CE3.4):

          * `horizonte` — §5.1.8 declara un filtro «horizonte (1-3 meses)» y con
            solo `mes` no se puede resolver: haría falta conocer por fuera cuál
            es el mes ancla, que es exactamente el dato que cambia cada corrida.
          * `mae_backtest` — §5.1.6 pide DOS métricas, «MAPE y MAE en unidades,
            interpretable para compras», y la tabla solo guardaba una. Un MAPE
            del 45 % no le dice a Compras cuántas unidades puede equivocarse.
          * `mape_linea_base` — sin la vara al lado, la regla 4 («el error por
            fila») es un número sin referencia: 24 % es bueno o malo según
            cuánto sacaba el ingenuo en ESA serie, que aquí va de 10 % a 72 %.
          * `unidades_mismo_mes_anio_previo` — el ancla que hace legible la
            previsión de un mes con UNA sola observación: es literalmente el
            único dato de ese mes del calendario que existe.
          * `horizonte_efectivo` — los meses que separan la previsión del último
            mes de ENTRENAMIENTO, que NO son los que la separan del último mes
            observado cuando ése se excluyó por estar truncado. Sin esta
            columna, el hecho de que el mes truncado se dejó fuera no sobrevive
            a la publicación: la tabla sale exactamente igual en los dos casos
            —tres meses a partir del último observado— y la pantalla no tiene
            forma de saber si el último punto del histórico es un mes completo
            o un artefacto del corte de los datos. Es además la explicación de
            por qué la banda del primer mes previsto es más ancha de lo que su
            horizonte 1 sugeriría: se calculó para una distancia de 2.
        """
        return f"""
        CREATE TABLE {nombre_tabla}
        (
            mes                             Date,
            nivel                           LowCardinality(String),
            categoria                       LowCardinality(String),
            producto_variante_id            UInt32,
            sku                             String,
            producto_nombre                 String,
            horizonte                       UInt8,
            horizonte_efectivo              UInt8,
            unidades_previstas              Decimal(12,2),
            limite_inferior                 Decimal(12,2),
            limite_superior                 Decimal(12,2),
            nivel_confianza                 Decimal(5,2),
            meses_historia                  UInt8,
            observaciones_mes               UInt8,
            metodo                          LowCardinality(String),
            mape_backtest                   Decimal(6,2),
            mae_backtest                    Decimal(12,2),
            mape_linea_base                 Decimal(6,2),
            es_linea_base                   UInt8,
            demanda_censurada               UInt8,
            unidades_mismo_mes_anio_previo  Decimal(12,2),
            modelo_version                  String,
            fecha_entrenamiento             DateTime('{ZONA_HORARIA}'),
            fecha_carga                     DateTime('{ZONA_HORARIA}')
        )
        ENGINE = MergeTree
        PARTITION BY toYear(mes)
        ORDER BY (nivel, categoria, producto_variante_id, mes)
        """

    def columnas(self) -> list[str]:
        return [
            "mes", "nivel", "categoria", "producto_variante_id", "sku",
            "producto_nombre", "horizonte", "horizonte_efectivo", "unidades_previstas",
            "limite_inferior", "limite_superior", "nivel_confianza",
            "meses_historia", "observaciones_mes", "metodo", "mape_backtest",
            "mae_backtest", "mape_linea_base", "es_linea_base",
            "demanda_censurada", "unidades_mismo_mes_anio_previo",
            "modelo_version", "fecha_entrenamiento", "fecha_carga",
        ]

    # ── Lectura del almacén ──────────────────────────────────────────────────

    def _malla(self, client) -> list[date]:
        """
        Los meses del período, SIN huecos, tomados de `dim_fecha`.

        Se recortan a los que el hecho cubre. `dim_fecha` es un calendario de
        730 días generado dentro de ClickHouse y llega más lejos que la venta:
        usarla entera fabricaría meses vacíos al final y el modelo aprendería
        una caída a cero que solo existe en el calendario.
        """
        ini, fin = client.query(
            f"SELECT min(mes), max(mes) FROM {CH_DATABASE}.fact_venta_linea "
            f"WHERE es_cancelado = 0").result_rows[0]
        meses = [f[0] for f in client.query(
            f"SELECT DISTINCT mes_inicio FROM {CH_DATABASE}.dim_fecha "
            f"WHERE mes_inicio >= %(i)s AND mes_inicio <= %(f)s ORDER BY mes_inicio",
            parameters={"i": ini, "f": fin}).result_rows]
        esperados = modelo.malla_mensual(ini, fin)
        if meses != esperados:
            # No se remienda: si `dim_fecha` no cubre el período del hecho, la
            # malla queda con un hueco y una serie con un hueco produce un
            # factor estacional de un mes que no existe.
            raise ValueError(
                f"`dim_fecha` no cubre el período del hecho sin huecos: "
                f"{len(meses)} meses frente a {len(esperados)} entre {ini} y {fin}.")
        return meses

    def _detectar_truncado(self, client, meses: list[date]) -> tuple[date | None, float]:
        """
        ¿El último mes de la serie está incompleto? — y cuánto.

        Se compara el día del mes MÁS ALTO con pedidos del último mes contra la
        mediana de los meses anteriores. El generador reparte en
        `1 + floor(random()*27)` en todos los meses menos el último de la
        siembra, que usa `*22`; el recorte de 27 días es uniforme y por tanto
        invisible en la comparación mes a mes, mientras que el de 22 no lo es.

        **No se escribe «julio de 2026».** Una constante con un mes dentro
        funciona una vez: a la corrida siguiente el último mes sería otro y el
        modelo entrenaría con un mes corto tratándolo como completo, aprendiendo
        una caída que no existe. Lo que se declara aquí es el criterio, no el mes.
        """
        filas = client.query(f"""
            SELECT mes, max(toDayOfMonth(fecha_pedido))
            FROM {CH_DATABASE}.fact_venta_linea
            WHERE es_cancelado = 0 GROUP BY mes ORDER BY mes""").result_rows
        if len(filas) < 3:
            return None, 1.0
        dias = {m: int(d) for m, d in filas}
        ultimo = meses[-1]
        referencia = float(np.median([d for m, d in dias.items() if m != ultimo]))
        propio = float(dias.get(ultimo, 0))
        if propio <= 0 or propio >= referencia:
            return None, 1.0
        return ultimo, referencia / propio

    def _series(self, client) -> dict:
        """Todo lo que el modelo necesita del almacén, en tres consultas."""
        venta = client.query(f"""
            SELECT mes, categoria, producto_variante_id, toFloat64(sum(cantidad))
            FROM {CH_DATABASE}.fact_venta_linea
            WHERE es_cancelado = 0
            GROUP BY mes, categoria, producto_variante_id""").result_rows

        catalogo = {
            int(v): (sku, nombre, categoria)
            for v, sku, nombre, categoria in client.query(
                f"SELECT producto_variante_id, sku, producto_nombre, categoria "
                f"FROM {CH_DATABASE}.dim_producto FINAL").result_rows
        }

        # Quiebre de stock: una variante que cerró algún mes con saldo total 0.
        censuradas = {
            int(f[0]) for f in client.query(f"""
                SELECT producto_variante_id FROM (
                    SELECT producto_variante_id, mes, sum(stock_cierre) AS saldo
                    FROM {CH_DATABASE}.fact_stock_mensual
                    GROUP BY producto_variante_id, mes)
                GROUP BY producto_variante_id HAVING countIf(saldo = 0) > 0""").result_rows
        }
        return {"venta": venta, "catalogo": catalogo, "censuradas": censuradas}

    # ── El cálculo ───────────────────────────────────────────────────────────

    def filas(self, client) -> list[tuple]:
        meses = self._malla(client)
        truncado, factor_truncado = self._detectar_truncado(client, meses)
        datos = self._series(client)
        posicion = {m: i for i, m in enumerate(meses)}

        def vector(pares) -> np.ndarray:
            v = np.zeros(len(meses))
            for mes, unidades in pares:
                v[posicion[mes]] += unidades
            return v

        total = np.zeros(len(meses))
        por_categoria: dict[str, list] = {}
        por_variante: dict[int, list] = {}
        categoria_de: dict[int, str] = {}
        for mes, categoria, variante, unidades in datos["venta"]:
            total[posicion[mes]] += unidades
            por_categoria.setdefault(categoria, []).append((mes, unidades))
            por_variante.setdefault(int(variante), []).append((mes, unidades))
            categoria_de[int(variante)] = categoria
        por_categoria = {c: vector(p) for c, p in por_categoria.items()}
        por_variante = {v: vector(p) for v, p in por_variante.items()}

        variantes_de: dict[str, list[int]] = {}
        for variante, categoria in categoria_de.items():
            variantes_de.setdefault(categoria, []).append(variante)

        # ── El mes truncado se EXCLUYE del entrenamiento (§5.1.2) ────────────
        # Entrenar con él enseña una caída que no existe; anualizarlo inventa
        # cinco días de ventas. Se excluye, y el coste se paga donde se ve: la
        # previsión del primer mes publicado queda a DOS meses del último dato
        # de entrenamiento, no a uno, y su banda es la de h=2.
        corte = len(meses) - 1 if truncado else len(meses)
        desfase = 1 if truncado else 0
        meses_tr = meses[:corte]

        self.informe = {
            "meses": len(meses), "primer_mes": meses[0], "ultimo_mes": meses[-1],
            "mes_truncado": truncado, "factor_truncado": factor_truncado,
            "ultimo_entrenado": meses_tr[-1], "series": {},
            "cobertura": {}, "niveles": {},
        }

        censura = self._censura(datos, por_categoria, por_variante, categoria_de)
        filas: list[tuple] = []
        dentro_global = puntos_global = 0

        # ── 1) TOTAL ────────────────────────────────────────────────────────
        parte_total = self._evaluar(meses, total, truncado, factor_truncado)
        self.informe["total"] = parte_total
        dentro_global += parte_total["dentro"]
        puntos_global += parte_total["puntos"]

        # ── El portero global (§5.1.6, criterios 1 y 2) ─────────────────────
        # Se decide UNA vez, con el nivel total, y gobierna las 510 filas: si el
        # método no sirve para la serie mejor medida del sistema, no sirve.
        global_a_base = (not parte_total["supera"]
                         or parte_total["mape"] > MAPE_MAXIMO_TOTAL)
        self.informe["modo"] = "linea_base" if global_a_base else "modelo"

        filas += self._emitir(
            meses, meses_tr, total, nivel=NIVEL_TOTAL, categoria=CATEGORIA_TODAS,
            variante=0, sku="", nombre="", parte=parte_total, desfase=desfase,
            censurada=censura["total"], forzar_base=global_a_base)

        # ── 2) CATEGORÍAS ───────────────────────────────────────────────────
        ajustes_categoria: dict[str, object] = {}
        for categoria in sorted(por_categoria, key=lambda c: -por_categoria[c].sum()):
            serie = por_categoria[categoria]
            parte = self._evaluar(meses, serie, truncado, factor_truncado)
            self.informe["series"][categoria] = parte
            dentro_global += parte["dentro"]
            puntos_global += parte["puntos"]
            filas += self._emitir(
                meses, meses_tr, serie, nivel=NIVEL_CATEGORIA, categoria=categoria,
                variante=0, sku="", nombre="", parte=parte, desfase=desfase,
                censurada=censura["categoria"].get(categoria, 0),
                forzar_base=global_a_base)
            ajuste = modelo.ajustar(meses_tr, serie[:corte])
            if ajuste is not None and parte["previsible"]:
                ajustes_categoria[categoria] = ajuste

        # ── 3) VARIANTES, top-down desde su categoría ───────────────────────
        largas = sorted(v for v, s in por_variante.items()
                        if int((s > 0).sum()) >= MESES_MINIMOS)
        for variante in largas:
            categoria = categoria_de[variante]
            ajuste = ajustes_categoria.get(categoria)
            if ajuste is None:
                continue                      # su categoría no es previsible
            sku, nombre, _ = datos["catalogo"].get(variante, ("", "", categoria))
            resultado = self._variante(
                meses, meses_tr, por_variante[variante], por_categoria[categoria],
                ajuste, len(variantes_de[categoria]), truncado, factor_truncado,
                desfase, global_a_base)
            dentro_global += resultado["dentro"]
            puntos_global += resultado["puntos"]
            for punto in resultado["filas"]:
                filas.append(self._fila(
                    nivel=NIVEL_VARIANTE, categoria=categoria, variante=variante,
                    sku=sku, nombre=nombre, **punto))

        cobertura = (dentro_global / puntos_global * 100.0) if puntos_global else 0.0
        self.informe["cobertura"] = {
            "porcentaje": cobertura, "dentro": dentro_global, "puntos": puntos_global}
        self.informe["niveles"] = {
            NIVEL_TOTAL: sum(1 for f in filas if f[1] == NIVEL_TOTAL),
            NIVEL_CATEGORIA: sum(1 for f in filas if f[1] == NIVEL_CATEGORIA),
            NIVEL_VARIANTE: sum(1 for f in filas if f[1] == NIVEL_VARIANTE),
        }
        self.excepciones = self.con_linea_base
        logger.info(
            f"[{self.nombre}] modo={self.informe['modo']} · "
            f"MAPE total {parte_total['mape']:.2f} % vs estacional "
            f"{parte_total['mape_base']:.2f} % · cobertura {cobertura:.1f} % "
            f"({dentro_global}/{puntos_global}) · {self.con_linea_base} series con "
            f"línea base")
        return filas

    # ── Evaluación de UNA serie ──────────────────────────────────────────────

    def _evaluar(self, meses, serie, truncado, factor) -> dict:
        """
        Backtest de origen móvil de una serie, con los DOS tratamientos del mes
        truncado. Devuelve todo lo que hace falta para decidir y para publicar.

        Los dos tratamientos se calculan siempre y el veredicto se toma con el
        CRUDO, que es la vara más exigente para el modelo (la línea base
        estacional saca 12,22 % en crudo y 20,07 % anualizada). Comparar un
        modelo evaluado de una manera contra una base evaluada de la otra es la
        forma más barata de ganar una comparación.
        """
        con_venta = int((np.asarray(serie) > 0).sum())
        if con_venta < MESES_MINIMOS:
            return {"previsible": False, "meses_historia": con_venta,
                    "mape": 0.0, "mae": 0.0, "mape_base": 0.0, "mae_base": 0.0,
                    "mape_ingenuo": 0.0, "supera": False,
                    "dentro": 0, "puntos": 0, "anualizado": None}

        crudo = modelo.backtest(meses, serie, ORIGENES_BACKTEST, HORIZONTE)
        anualizado = None
        if truncado is not None:
            anualizado = modelo.backtest(meses, serie, ORIGENES_BACKTEST, HORIZONTE,
                                         ajuste_real={truncado: factor})
        return {
            "previsible": True,
            "meses_historia": con_venta,
            "mape": crudo.modelo.mape,
            "mae": crudo.modelo.mae,
            "mape_base": crudo.estacional.mape,
            "mae_base": crudo.estacional.mae,
            "mape_ingenuo": crudo.ingenuo.mape,
            "supera": crudo.supera_estacional,
            "dentro": crudo.dentro_banda,
            "puntos": crudo.puntos_banda,
            "cobertura": crudo.cobertura,
            "por_origen": crudo.por_origen,
            "anualizado": anualizado,
        }

    # ── Emisión de filas ─────────────────────────────────────────────────────

    def _emitir(self, meses, meses_tr, serie, *, nivel, categoria, variante, sku,
                nombre, parte, desfase, censurada, forzar_base) -> list[tuple]:
        """Las `HORIZONTE` filas de una serie de nivel total o categoría."""
        historia = serie[:len(meses_tr)]
        reales = {m: float(v) for m, v in zip(meses, serie)}

        if not parte["previsible"]:
            # Ropa Mujer (9 meses con venta) y Ropa Hombre (1). Se publican SIN
            # número y sin banda: una previsión inventada para una serie que no
            # la admite es peor que una casilla que dice que no la hay.
            return [self._fila(
                nivel=nivel, categoria=categoria, variante=variante, sku=sku,
                nombre=nombre, mes=modelo.sumar_meses(meses[-1], h), horizonte=h,
                horizonte_efectivo=h + desfase,
                valor=0.0, inferior=0.0, superior=0.0,
                meses_historia=parte["meses_historia"], observaciones_mes=0,
                metodo=modelo.METODO_SIN_PREVISION, mape=0.0, mae=0.0,
                mape_base=0.0, es_base=0, censurada=censurada,
                anio_previo=reales.get(
                    date(modelo.sumar_meses(meses[-1], h).year - 1,
                         modelo.sumar_meses(meses[-1], h).month, 1), 0.0))
                for h in range(1, HORIZONTE + 1)]

        usar_base = forzar_base or not parte["supera"]
        if usar_base:
            self.con_linea_base += 1
            puntos = modelo.linea_base_estacional(
                meses_tr, historia, HORIZONTE + desfase)
            metodo = modelo.METODO_LINEA_BASE
            # El error que viaja en la fila es el del método que SE PUBLICA. Si
            # se publica la línea base y la fila llevara el MAPE del modelo
            # descartado, la columna estaría describiendo una previsión que no
            # está ahí — y es la columna sobre la que la regla 4 pide decidir.
            mape, mae = parte["mape_base"], parte["mae_base"]
        else:
            ajuste = modelo.ajustar(meses_tr, historia)
            puntos = modelo.predecir(ajuste, HORIZONTE + desfase)
            metodo = modelo.METODO_DESCOMPOSICION
            mape, mae = parte["mape"], parte["mae"]

        # Se descartan los `desfase` primeros: son el mes truncado, que ya
        # ocurrió. El horizonte publicado cuenta desde el último mes CERRADO.
        return [self._fila(
            nivel=nivel, categoria=categoria, variante=variante, sku=sku,
            nombre=nombre, mes=p.mes, horizonte=i + 1,
            horizonte_efectivo=p.horizonte, valor=p.valor,
            inferior=p.inferior, superior=p.superior,
            meses_historia=parte["meses_historia"],
            observaciones_mes=p.observaciones_mes, metodo=metodo, mape=mape,
            mae=mae, mape_base=parte["mape_base"], es_base=1 if usar_base else 0,
            censurada=censurada,
            anio_previo=reales.get(date(p.mes.year - 1, p.mes.month, 1), 0.0))
            for i, p in enumerate(puntos[desfase:])]

    def _variante(self, meses, meses_tr, serie_var, serie_cat, ajuste_cat,
                  variantes_en_categoria, truncado, factor, desfase,
                  forzar_base) -> dict:
        """
        Desagregación *top-down*: previsión de la categoría × cuota de la
        variante, con su propio backtest y su propia banda.

        La cuota se estima sobre `VENTANA_CUOTA` meses y NO sobre toda la
        historia: la mezcla del catálogo se mueve, y una cuota de 19 meses le
        daría a un producto retirado hace un año el peso que tenía cuando
        vendía.
        """
        n = len(meses)
        posicion = {m: i for i, m in enumerate(meses)}
        con_venta = int((serie_var > 0).sum())
        pares, pares_base = [], []
        dentro = puntos = 0

        for o in range(ORIGENES_BACKTEST, 0, -1):
            corte = n - o
            ajuste = modelo.ajustar(meses[:corte], serie_cat[:corte])
            if ajuste is None:
                continue
            cuota, unidades = self._cuota(serie_var, serie_cat, corte,
                                          variantes_en_categoria)
            pasos = min(HORIZONTE, n - corte)
            for punto in modelo.predecir(ajuste, pasos):
                real = float(serie_var[posicion[punto.mes]])
                previsto = punto.valor * cuota
                pares.append((punto.mes, real, previsto))
                inferior, superior = modelo.banda_top_down(
                    previsto, punto.sd_relativa, unidades, punto.cuantil)
                if real > 0:
                    puntos += 1
                    if inferior <= real <= superior:
                        dentro += 1
            for punto in modelo.linea_base_estacional(
                    meses[:corte], serie_var[:corte], pasos):
                pares_base.append(
                    (punto.mes, float(serie_var[posicion[punto.mes]]), punto.valor))

        error = modelo._error(pares)
        error_base = modelo._error(pares_base)
        supera = error.n > 0 and error_base.n > 0 and error.mape < error_base.mape
        usar_base = forzar_base or not supera
        if usar_base:
            self.con_linea_base += 1

        reales = {m: float(v) for m, v in zip(meses, serie_var)}
        cuota, unidades = self._cuota(serie_var, serie_cat, len(meses_tr),
                                      variantes_en_categoria)
        filas = []
        if usar_base:
            puntos_pub = modelo.linea_base_estacional(
                meses_tr, serie_var[:len(meses_tr)], HORIZONTE + desfase)
            metodo = modelo.METODO_LINEA_BASE
            mape, mae = error_base.mape, error_base.mae
        else:
            puntos_pub = modelo.predecir(ajuste_cat, HORIZONTE + desfase)
            metodo = modelo.METODO_TOP_DOWN
            mape, mae = error.mape, error.mae

        for i, punto in enumerate(puntos_pub[desfase:]):
            valor = punto.valor if usar_base else punto.valor * cuota
            if usar_base:
                inferior, superior = punto.inferior, punto.superior
            else:
                inferior, superior = modelo.banda_top_down(
                    valor, punto.sd_relativa, unidades, punto.cuantil)
            filas.append({
                "mes": punto.mes, "horizonte": i + 1,
                "horizonte_efectivo": punto.horizonte, "valor": valor,
                "inferior": inferior, "superior": superior,
                "meses_historia": con_venta,
                "observaciones_mes": punto.observaciones_mes,
                "metodo": metodo, "mape": mape, "mae": mae,
                "mape_base": error_base.mape, "es_base": 1 if usar_base else 0,
                "censurada": 0,
                "anio_previo": reales.get(
                    date(punto.mes.year - 1, punto.mes.month, 1), 0.0),
            })
        return {"filas": filas, "dentro": dentro, "puntos": puntos}

    @staticmethod
    def _cuota(serie_var, serie_cat, corte, variantes_en_categoria):
        inicio = max(corte - modelo.VENTANA_CUOTA, 0)
        unidades_var = float(serie_var[inicio:corte].sum())
        unidades_cat = float(serie_cat[inicio:corte].sum())
        return (modelo.cuota(unidades_var, unidades_cat, variantes_en_categoria),
                unidades_var)

    def _fila(self, *, nivel, categoria, variante, sku, nombre, mes, horizonte,
              horizonte_efectivo, valor, inferior, superior, meses_historia,
              observaciones_mes, metodo, mape, mae, mape_base, es_base,
              censurada, anio_previo) -> tuple:
        return (
            mes, nivel, categoria, int(variante), sku, nombre, int(horizonte),
            int(horizonte_efectivo),
            _dec(valor), _dec(inferior), _dec(superior),
            _dec(modelo.NIVEL_CONFIANZA), int(meses_historia),
            int(observaciones_mes), metodo,
            _dec(mape, MAPE_TOPE), _dec(mae), _dec(mape_base, MAPE_TOPE),
            int(es_base), int(censurada), _dec(anio_previo),
            modelo.VERSION_MODELO, self.entrenado_en, self.entrenado_en,
        )

    # ── Demanda censurada ────────────────────────────────────────────────────

    def _censura(self, datos, por_categoria, por_variante, categoria_de) -> dict:
        """
        Marca las series cuya historia contiene quiebres de stock.

        Una VARIANTE censurada es un hecho: cerró algún mes con saldo cero. Una
        CATEGORÍA no puede heredar ese sí/no de una sola variante suya —con ese
        criterio estarían marcadas las diez—, así que se marca cuando al menos
        `UMBRAL_CENSURA` de sus unidades proviene de variantes censuradas.
        """
        censuradas = datos["censuradas"]
        unidades_cat: dict[str, float] = {}
        censurado_cat: dict[str, float] = {}
        unidades_total = censurado_total = 0.0

        for variante, serie in por_variante.items():
            unidades = float(serie.sum())
            categoria = categoria_de[variante]
            unidades_cat[categoria] = unidades_cat.get(categoria, 0.0) + unidades
            unidades_total += unidades
            if variante in censuradas:
                censurado_cat[categoria] = censurado_cat.get(categoria, 0.0) + unidades
                censurado_total += unidades

        return {
            "total": int(unidades_total > 0
                         and censurado_total / unidades_total >= UMBRAL_CENSURA),
            "categoria": {
                c: int(unidades_cat.get(c, 0) > 0
                       and censurado_cat.get(c, 0.0) / unidades_cat[c] >= UMBRAL_CENSURA)
                for c in por_categoria
            },
            "variantes": censuradas,
        }

    # ── Controles contra PostgreSQL ──────────────────────────────────────────

    def sql_controles(self) -> str:
        """
        El UNIVERSO de series sale de PostgreSQL, no del almacén.

        Una previsión no se puede comparar al centavo contra PostgreSQL —no hay
        allí ninguna fila con fecha futura—, pero **el conjunto de series que
        DEBE previsionarse sí**: una categoría con venta, una variante con doce
        meses de venta. Este control es por tanto el que detecta lo que de
        verdad puede fallar aquí: que el almacén haya perdido una categoría
        entera o un puñado de variantes y el modelo publique tranquilamente una
        previsión de un catálogo más pequeño, con todas sus cifras coherentes
        entre sí y equivocadas.

        La consulta replica la definición de `fact_venta_linea` (mes en la zona
        del negocio, pedido no cancelado, categoría principal) escrita de otra
        manera: si reutilizara la de la tarea, ambas compartirían el error.
        """
        return f"""
        WITH linea AS (
            SELECT (date_trunc('month',
                        p.fecha_pedido AT TIME ZONE '{ZONA_HORARIA}'))::date AS mes,
                   COALESCE(c.nombre, 'sin_categoria')                       AS categoria,
                   pd.producto_variante_id                                   AS variante
            FROM pedido_detalle pd
            JOIN pedido p             ON p.id  = pd.pedido_id
            JOIN estado_pedido ep     ON ep.id = p.estado_pedido_id
            JOIN producto_variante pv ON pv.id = pd.producto_variante_id
            LEFT JOIN producto_categoria pc
                                      ON pc.producto_id = pv.producto_id
                                     AND pc.es_principal
            LEFT JOIN categoria c     ON c.id  = pc.categoria_id
            WHERE ep.codigo <> 'cancelado'
        ),
        cat AS (
            SELECT categoria, count(DISTINCT mes) AS meses FROM linea GROUP BY categoria
        ),
        var AS (
            SELECT variante, count(DISTINCT mes) AS meses FROM linea GROUP BY variante
        )
        SELECT
            ((SELECT count(*) FROM cat)
             + (SELECT count(*) FROM var WHERE meses >= {MESES_MINIMOS})
             + 1) * {HORIZONTE}                                   AS filas,
            (SELECT count(*) FROM cat)                            AS categorias,
            (SELECT count(*) FROM cat WHERE meses >= {MESES_MINIMOS})
                                                                  AS categorias_previsibles,
            (SELECT count(*) FROM var WHERE meses >= {MESES_MINIMOS})
                                                                  AS variantes_largas,
            (SELECT count(*) FROM var)                            AS variantes_con_venta,
            (SELECT count(DISTINCT mes) FROM linea)               AS meses,
            (SELECT max(mes) FROM linea)                          AS ultimo_mes
        """

    # ── LA VALIDACIÓN QUE DECIDE SI EL MODELO SE PUBLICA ─────────────────────

    def validar(self, client, tabla_staging: str, controles: dict) -> list[str]:
        errores = self._validar_universo(client, tabla_staging, controles)
        errores += self._validar_forma(client, tabla_staging)
        errores += self._validar_modelo()
        return errores

    def _validar_universo(self, client, tabla_staging: str, controles: dict) -> list[str]:
        """El almacén previsiona exactamente el catálogo que PostgreSQL tiene."""
        categorias, variantes, meses = client.query(f"""
            SELECT countDistinctIf(categoria, nivel = '{NIVEL_CATEGORIA}'),
                   countDistinctIf(producto_variante_id, nivel = '{NIVEL_VARIANTE}'),
                   countDistinct(mes)
            FROM {tabla_staging}""").result_rows[0]

        errores = []
        for clave, obtenido, etiqueta in (
            ("categorias", categorias, "categorías con venta"),
            ("variantes_largas", variantes, f"variantes con ≥{MESES_MINIMOS} meses"),
        ):
            esperado = controles.get(clave)
            if esperado is not None and int(obtenido) != int(esperado):
                errores.append(
                    f"{etiqueta}: PostgreSQL {esperado} vs previsión {obtenido} "
                    f"(diferencia {int(obtenido) - int(esperado)}). El modelo está "
                    f"previsionando un catálogo distinto del que existe.")
        if int(meses) != HORIZONTE:
            errores.append(f"La previsión cubre {meses} meses y el horizonte "
                           f"declarado es {HORIZONTE}.")
        return errores

    def _validar_forma(self, client, tabla_staging: str) -> list[str]:
        """
        Invariantes de la propia tabla. Ninguna de las cuatro rompe una suma
        —por eso hay que buscarlas a propósito— y las cuatro producirían una
        pantalla con aspecto normal.
        """
        (negativas, banda_rota, sin_metodo, sin_banda_con_valor,
         base_incoherente, confianza) = client.query(f"""
            SELECT countIf(unidades_previstas < 0),
                   countIf(limite_inferior > unidades_previstas
                        OR limite_superior < unidades_previstas),
                   countIf(metodo NOT IN ('{modelo.METODO_DESCOMPOSICION}',
                                          '{modelo.METODO_LINEA_BASE}',
                                          '{modelo.METODO_TOP_DOWN}',
                                          '{modelo.METODO_SIN_PREVISION}')),
                   countIf(limite_superior = limite_inferior
                       AND metodo != '{modelo.METODO_SIN_PREVISION}'),
                   countIf((es_linea_base = 1)
                        != (metodo = '{modelo.METODO_LINEA_BASE}')),
                   countDistinct(nivel_confianza)
            FROM {tabla_staging}""").result_rows[0]

        errores = []
        if negativas:
            errores.append(f"{negativas} filas con previsión NEGATIVA.")
        if banda_rota:
            errores.append(f"{banda_rota} filas cuya banda no contiene su propia "
                           f"previsión.")
        if sin_metodo:
            errores.append(f"{sin_metodo} filas con un `metodo` fuera de la lista "
                           f"blanca.")
        if sin_banda_con_valor:
            errores.append(
                f"{sin_banda_con_valor} filas con previsión y SIN banda. La regla 2 "
                f"de §5.1.9 es «ningún número sin banda»: una cifra sin intervalo "
                f"se lee como exacta, y una previsión de CERO con banda [0, 0] es "
                f"la afirmación de certeza más fuerte de la tabla sobre la serie de "
                f"la que menos se sabe.")
        if base_incoherente:
            errores.append(f"{base_incoherente} filas donde `es_linea_base` y "
                           f"`metodo` se contradicen.")
        if int(confianza) != 1:
            errores.append(f"Conviven {confianza} niveles de confianza distintos en "
                           f"la misma tabla.")

        errores += self._validar_ensanchamiento(client, tabla_staging)
        return errores

    def _validar_ensanchamiento(self, client, tabla_staging: str) -> list[str]:
        """
        Regla 5 de §5.1.9: **de octubre se sabe menos que de agosto.**

        CORRECCIÓN CE3.5 — el invariante no se puede exigir fila a fila sobre
        toda la tabla, y las dos formas obvias de escribirlo son falsas:

          * **en UNIDADES** aborta 15 series cuya previsión simplemente BAJA a lo
            largo del horizonte. Si octubre espera la mitad de unidades que
            agosto, su banda es más estrecha en unidades y más ancha en
            incertidumbre a la vez: las dos cosas son ciertas.
          * **en RELATIVO fila a fila** aborta 16, porque el ruido de conteo
            aporta `1/valor` a la varianza relativa: una variante que pasa de 2 a
            10 unidades previstas estrecha su banda relativa aunque el horizonte
            crezca. Otra vez, correcto.

        Donde el invariante SÍ es exigible es donde la regla habla: las series de
        `descomposicion` —el total y las 8 categorías, que son las que se pintan
        en el gráfico— cuyo nivel se mueve suavemente y cuyo ruido de conteo es
        despreciable. Ahí se comprueba **serie a serie**.

        Y sobre el resto se comprueba lo que sí es cierto de forma agregada: la
        banda relativa MEDIA crece con el horizonte. Es un control débil por
        fila y fuerte por tabla — si alguien quitara el `sqrt(h)` del modelo,
        esta media se quedaría plana y saltaría aquí.

        La línea base estacional queda fuera de las dos comprobaciones y se
        declara: su nivel es el del mismo mes del año pasado, un salto arbitrario
        entre un mes y el siguiente, y ninguna afirmación monótona sobre su
        anchura sería cierta.
        """
        errores = []
        estrechan = client.query(f"""
            SELECT count() FROM (
                SELECT nivel, categoria,
                       argMin((limite_superior - limite_inferior)
                              / greatest(unidades_previstas, 1), horizonte) AS primera,
                       argMax((limite_superior - limite_inferior)
                              / greatest(unidades_previstas, 1), horizonte) AS ultima
                FROM {tabla_staging}
                WHERE metodo = '{modelo.METODO_DESCOMPOSICION}'
                GROUP BY nivel, categoria
                HAVING ultima <= primera)""").result_rows[0][0]
        if estrechan:
            errores.append(
                f"{estrechan} series de `{modelo.METODO_DESCOMPOSICION}` cuya banda "
                f"relativa NO se ensancha con el horizonte. Son las que se pintan en "
                f"el gráfico, y la regla 5 de §5.1.9 lo exige.")

        for metodo in (modelo.METODO_DESCOMPOSICION, modelo.METODO_TOP_DOWN):
            medias = client.query(f"""
                SELECT horizonte, avg((limite_superior - limite_inferior)
                                      / greatest(unidades_previstas, 1))
                FROM {tabla_staging} WHERE metodo = %(m)s
                GROUP BY horizonte ORDER BY horizonte""",
                parameters={"m": metodo}).result_rows
            anchuras = [float(a) for _, a in medias]
            if len(anchuras) > 1 and anchuras[-1] <= anchuras[0]:
                errores.append(
                    f"En `{metodo}` la banda relativa MEDIA no crece con el "
                    f"horizonte ({anchuras[0]:.3f} → {anchuras[-1]:.3f}). El "
                    f"ensanchamiento por horizonte se ha perdido.")
        return errores

    def _validar_modelo(self) -> list[str]:
        """
        Los criterios de rechazo de §5.1.6, con una precisión sobre el tercero.

        La COBERTURA se mide sobre el conjunto AGRUPADO de puntos del backtest
        —total, 8 categorías y 159 variantes— y no sobre los 6 puntos del nivel
        total. Con 6 puntos, una banda perfectamente calibrada al 80 % produce
        6 aciertos de 6 el **26 %** de las veces: el criterio aplicado a esa
        muestra no distingue una banda buena de una inútilmente ancha, que es
        justo lo que pretende distinguir. Ver la corrección CE3.3.

        Los criterios 1 y 2 NO abortan aquí: ya degradaron a la línea base en
        `filas()`, y la fila lo dice. El 3 sí aborta: una banda mal calibrada no
        tiene versión degradada.
        """
        cobertura = self.informe.get("cobertura", {})
        porcentaje = cobertura.get("porcentaje", 0.0)
        puntos = cobertura.get("puntos", 0)
        errores = []
        if puntos == 0:
            errores.append("El backtest no produjo ni un punto: no hay con qué "
                           "medir la calibración de la banda.")
        elif not (COBERTURA_MINIMA <= porcentaje <= COBERTURA_MAXIMA):
            errores.append(
                f"COBERTURA DEL INTERVALO FUERA DE RANGO: {porcentaje:.1f} % "
                f"({cobertura['dentro']}/{puntos} puntos) frente al "
                f"{COBERTURA_MINIMA:.0f} %–{COBERTURA_MAXIMA:.0f} % que exige "
                f"§5.1.6. La banda dice {modelo.NIVEL_CONFIANZA:.0f} % y cumple "
                f"otra cosa. NO se publica: una banda mal calibrada es peor que no "
                f"tener banda, y a diferencia del MAPE no tiene versión degradada.")
        return errores

    # ── Informe de consola ───────────────────────────────────────────────────

    def resumen(self) -> str:
        """Parte legible del backtest, para `cargar.py` y para la fase."""
        i = self.informe
        if not i:
            return "(sin backtest)"
        total = i["total"]
        lineas = [
            f"  Serie          {i['meses']} meses · {i['primer_mes']} → {i['ultimo_mes']}",
            f"  Mes truncado   {i['mes_truncado'] or 'ninguno'}"
            + (f" (cubre 1/{i['factor_truncado']:.3f} del mes; EXCLUIDO del "
               f"entrenamiento, último mes entrenado {i['ultimo_entrenado']})"
               if i["mes_truncado"] else ""),
            f"  Modo           {i['modo']}",
            "",
            "  BACKTEST DE ORIGEN MÓVIL · nivel TOTAL",
            f"    modelo                 MAPE {total['mape']:6.2f} %   "
            f"MAE {total['mae']:8.1f} uds",
            f"    ingenuo                MAPE {total['mape_ingenuo']:6.2f} %",
            f"    ingenuo estacional     MAPE {total['mape_base']:6.2f} %   "
            f"← la vara",
        ]
        if total.get("anualizado"):
            a = total["anualizado"]
            lineas += [
                f"    (mes truncado anualizado)  modelo {a.modelo.mape:6.2f} %   "
                f"estacional {a.estacional.mape:6.2f} %",
            ]
        for origen, error in total.get("por_origen", {}).items():
            lineas.append(f"      origen {origen}: MAPE {error.mape:6.2f} %  "
                          f"MAE {error.mae:7.1f}  n={error.n}")
        c = i["cobertura"]
        lineas += [
            "",
            f"  Cobertura banda 80 %   {c['porcentaje']:.1f} % "
            f"({c['dentro']}/{c['puntos']} puntos agrupados)",
            f"  Filas por nivel        " + " · ".join(
                f"{k} {v}" for k, v in i["niveles"].items()),
            f"  Series con línea base  {self.con_linea_base}",
        ]
        return "\n".join(lineas)
