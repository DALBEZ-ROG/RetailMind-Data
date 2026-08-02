"""
etl/dwh/modelos/alerta_abandono.py — FASE E3 del nivel estratégico
(§5.2 de `docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md`).

El modelo de la **alerta de abandono de cliente**, aislado de toda conexión:
entran fechas de compra, sale una probabilidad con su veredicto. Mismo criterio
que `prevision_demanda.py` — el modelo se puede ejecutar y discutir sin base de
datos delante, y la tarea de carga se ocupa del almacén.

═══════════════════════════════════════════════════════════════════════════════
1. QUÉ ES ESTO, Y QUÉ NO ES
═══════════════════════════════════════════════════════════════════════════════

**No es un modelo entrenado, y no puede serlo.** §5.2.1 lo verificó contra los
datos y el veredicto es negativo por tres pruebas independientes:

  * no existe etiqueta de abandono —nadie se da de baja, no hay contrato,
    ninguna columna dice «se fue»—, así que construirla con un corte de días
    hace que **el corte determine la respuesta**;
  * el generador del seed sortea el cliente de cada pedido con peso CONSTANTE e
    independiente del pasado (`ORDER BY -ln(random())/pop`, script 60 línea
    187): los clientes entran y **nunca salen**;
  * el CV medio de los intervalos entre compras es **1,196** — para una
    exponencial vale 1 exacto. Es un proceso SIN MEMORIA: el tiempo hasta la
    próxima compra no depende de cuánto lleve esperando.

Lo que se implementa es por tanto un **modelo del PROCESO**: supervivencia
exponencial con la tasa propia de cada cliente. No necesita etiquetas, su tasa
de falsa alarma se conoce de antemano (≈ α) y degrada con elegancia — un cliente
con 3 pedidos recibe una λ mala y la fila dice cuántos pedidos la sostienen.

    λᵢ = pedidos_i / días_observados_i
    P(silencio ≥ t | λᵢ) = e^(−λᵢ·t)
    alerta si P < α   ⇔   t > 3·(1/λᵢ) para α = 0,05

═══════════════════════════════════════════════════════════════════════════════
2. TRES DECISIONES QUE EL DISEÑO DEJÓ COMO CONSTANTES Y AQUÍ SON REGLAS
═══════════════════════════════════════════════════════════════════════════════

**La ventana estable NO se escribe como fecha.** §5.2.10 dice «se calcula solo
sobre la ventana desde enero de 2026». Un mes escrito en el código funciona
exactamente una vez: en la corrida siguiente la ventana seguiría anclada a un
enero que ya no es el borde de nada, y en cuanto entren datos nuevos el modelo
estaría estimando λ sobre un período cada vez más largo — que es justo el error
que la ventana existe para evitar. Aquí la ventana son los últimos
`MESES_VENTANA` meses **contados desde el ancla**, y el ancla es
`max(fecha_pedido)` del almacén. Con los datos de hoy eso da exactamente la
ventana que el diseño declara (2026-01 → 2026-07), y mañana se desplaza sola.

**El artefacto de la rampa se comprueba, no se supone.** La razón entera de la
ventana es que en enero y febrero de 2025 **un solo cliente hizo el 100 % de los
pedidos** (era el único registrado), y con la historia completa ese cliente —el
segundo de la cartera, $399.425 facturados— sale como la alerta MÁS fuerte del
sistema con P = 4·10⁻¹⁷, que es la inversión exacta de la verdad. Verificado:
con la ventana completa acumula 283 pedidos, 1/λ = 1,96 días y 74 de silencio,
o sea 37,7 intervalos propios; con la ventana estable son 4 pedidos,
1/λ = 31,5 días y 2,35 intervalos — normal. Por eso `concentracion_maxima()` es
una comprobación que la carga ejecuta y que **aborta la publicación**: si alguien
alarga la ventana hasta tragarse la rampa, la tabla no se publica.

**El lift se mide contra la tasa base de SU origen, no contra una constante.**
§5.2.6 propone «precisión@10 ÷ 9,4 %». El 9,4 % es la tasa base de UN origen
—medida—, y los otros dos dan 5,8 % y 7,0 %; en la ventana corta, el origen más
antiguo llega a dar **0 positivos**, y ahí el lift no vale 0: **no existe**.
Dividir siempre por 9,4 % publicaría un lift falso en dos de los tres orígenes.
Cada origen se divide por su propia base, los orígenes sin positivos se declaran
no medibles, y el veredicto que viaja en la fila es el AGRUPADO.

═══════════════════════════════════════════════════════════════════════════════
3. EL LIFT SE PUBLICA, SEA CUAL SEA — Y CON SU INCERTIDUMBRE
═══════════════════════════════════════════════════════════════════════════════

Decisión de negocio ya tomada (§5.2.6): la alerta se publica aunque el lift
salga 1,0, y **el lift medido y su número de casos positivos van en la CABECERA
de la pantalla**, no en una nota al pie. «Un modelo que oculta su lift es
indistinguible de uno que funciona.»

A eso se le añade una pieza que el diseño no pidió y sin la cual el lift engaña
igual: **su valor p**. Sobre esta base el lift agrupado sale ≈ 2,0, que leído
solo parece un éxito; medido contra el azar sobre 14 positivos, la probabilidad
de obtener ese resultado por casualidad ronda el 11 %. Publicar «lift 1,99» sin
esa cifra es exactamente el patrón que `CORRECCIONES_DISENO_ETL.md` documenta.
"""

from __future__ import annotations

import math
from bisect import bisect_left, bisect_right
from dataclasses import dataclass, field
from datetime import date, timedelta

# ── Parámetros del modelo, todos declarados ─────────────────────────────────

VERSION_MODELO = "alerta-abandono-exponencial-1.0"

#: Umbral de alerta. §5.2.3: α = 0,05  ⇔  t > 3·(1/λ).
ALPHA = 0.05

#: Frontera del nivel intermedio. P ≥ este valor es `normal`.
UMBRAL_ATENCION = 0.10

#: Pedidos MÍNIMOS dentro de la ventana para que λ signifique algo. Con 2
#: pedidos, λ es el inverso de un único intervalo observado.
MIN_PEDIDOS = 3

#: Longitud de la ventana estable, en meses, contados hacia atrás desde el mes
#: del ancla. NO es una fecha: ver el bloque 2 de la cabecera.
MESES_VENTANA = 7

#: Cuota máxima de pedidos de un solo cliente en un mes de la ventana. Por
#: encima de esto la cartera de ese mes no es una cartera, y λ deja de ser
#: comparable entre clientes. Es la comprobación que aborta la publicación.
CONCENTRACION_MAXIMA = 25.0

#: Backtest de origen móvil (§5.2.6).
ORIGENES_BACKTEST = 3
DIAS_PRUEBA = 60
DIAS_PASO = 30
TOP_K = 10

#: Meses del *sparkline* de compras que va en cada fila (regla 2 de §5.2.9).
MESES_SPARKLINE = 12

#: Ventana de facturación para el valor en riesgo.
DIAS_FACTURACION = 365

NIVEL_CRITICA = "critica"
NIVEL_ATENCION = "atencion"
NIVEL_NORMAL = "normal"

#: Cliente sin pedidos suficientes en la ventana. NO es «normal»: es «el modelo
#: no puede opinar». Ver `Estimacion.sin_muestra`.
NIVEL_SIN_MUESTRA = "sin_muestra"

NIVELES = (NIVEL_CRITICA, NIVEL_ATENCION, NIVEL_NORMAL, NIVEL_SIN_MUESTRA)


# ── La estimación de un cliente ─────────────────────────────────────────────

@dataclass
class Estimacion:
    """
    Lo que el modelo sabe de un cliente en un instante de corte.

    `sin_muestra` marca al cliente que no llega a `MIN_PEDIDOS` en la ventana.
    Se publica igualmente, y es deliberado: **son precisamente los candidatos
    más fuertes al abandono los que se quedan sin muestra**, porque su silencio
    es lo que los ha dejado sin pedidos en la ventana. Excluirlos de la tabla
    haría que la pantalla no los mostrara jamás — el modelo se comería su propio
    caso de uso sin dar ningún error. Se publican con su silencio REAL, que es un
    hecho medible sin λ, y con el nivel que dice que no hay ritmo que comparar.
    """
    cliente_id: int
    pedidos_ventana: int
    dias_observados: int
    tasa_diaria: float
    dias_silencio: int
    fecha_ultima_compra: date | None
    sin_muestra: bool = False

    @property
    def intervalo_medio(self) -> float:
        """1/λ — la cifra interpretable: «este cliente compra cada N días»."""
        return 1.0 / self.tasa_diaria if self.tasa_diaria > 0 else 0.0

    @property
    def silencio_en_intervalos(self) -> float:
        """
        La medida que se MUESTRA (regla 1 de §5.2.9). «67 días» no dice nada sin
        saber si el cliente compra cada semana o cada trimestre.
        """
        return self.dias_silencio * self.tasa_diaria

    @property
    def prob_silencio(self) -> float:
        """
        P(silencio ≥ t | λ) = e^(−λ·t).

        Un cliente sin muestra devuelve **1,0** y no 0: «no hay evidencia de
        silencio inusual» es lo que corresponde cuando no hay con qué medirlo.
        Devolver 0 lo pondría en cabeza de la lista de alertas con la certeza
        más alta del sistema, que es la afirmación más falsa que esta tabla
        podría hacer.
        """
        if self.sin_muestra:
            return 1.0
        return math.exp(-self.tasa_diaria * self.dias_silencio)

    @property
    def nivel(self) -> str:
        if self.sin_muestra:
            return NIVEL_SIN_MUESTRA
        p = self.prob_silencio
        if p < ALPHA:
            return NIVEL_CRITICA
        return NIVEL_ATENCION if p < UMBRAL_ATENCION else NIVEL_NORMAL

    @property
    def en_alerta(self) -> bool:
        return self.nivel in (NIVEL_CRITICA, NIVEL_ATENCION)


# ── La ventana estable ──────────────────────────────────────────────────────

def inicio_ventana(ancla: date, meses: int = MESES_VENTANA) -> date:
    """
    Primer día del mes que abre la ventana estable, contando `meses` meses hacia
    atrás **desde el mes del ancla, incluido**.

    Con ancla 2026-07-22 y 7 meses da 2026-01-01, que es exactamente la ventana
    que §5.2.10 declara — pero como regla y no como fecha escrita.
    """
    mes = ancla.month - (meses - 1)
    anio = ancla.year
    while mes <= 0:
        mes += 12
        anio -= 1
    return date(anio, mes, 1)


def concentracion_maxima(compras: dict[int, list[date]], inicio: date,
                         fin: date) -> tuple[float, date | None]:
    """
    Mayor cuota mensual de un solo cliente dentro de la ventana, en por ciento.

    Es la prueba del artefacto de la rampa, y devuelve además EN QUÉ MES ocurre
    para que el mensaje de aborto sea accionable. En la ventana estable de hoy
    el máximo es 10,9 %; en la historia completa, 100,0 % (enero de 2025).
    """
    por_mes: dict[date, dict[int, int]] = {}
    for cliente, fechas in compras.items():
        for f in fechas:
            if inicio <= f <= fin:
                conteo = por_mes.setdefault(date(f.year, f.month, 1), {})
                conteo[cliente] = conteo.get(cliente, 0) + 1
    peor, mes_peor = 0.0, None
    for mes, conteo in por_mes.items():
        total = sum(conteo.values())
        if not total:
            continue
        cuota = 100.0 * max(conteo.values()) / total
        if cuota > peor:
            peor, mes_peor = cuota, mes
    return peor, mes_peor


# ── Estimación ──────────────────────────────────────────────────────────────

def estimar(compras: dict[int, list[date]], inicio: date, corte: date,
            min_pedidos: int = MIN_PEDIDOS) -> dict[int, Estimacion]:
    """
    λ, recencia y nivel de cada cliente, medidos sobre `[inicio, corte]`.

    `dias_observados` arranca en la PRIMERA compra del cliente dentro de la
    ventana y no en el inicio de la ventana: un cliente que se dio de alta en
    mayo no lleva siete meses comprando, y contarle los cuatro meses en que
    todavía no era cliente le divide λ por tres y le regala una probabilidad
    tranquilizadora justo cuando su silencio empieza a significar algo.

    Se incluye a TODO cliente con alguna compra en la historia, tenga o no
    muestra en la ventana — ver la nota de `Estimacion.sin_muestra`.
    """
    salida: dict[int, Estimacion] = {}
    for cliente, fechas in compras.items():
        if not fechas:
            continue
        orden = sorted(fechas)
        previas = [f for f in orden if f <= corte]
        if not previas:
            continue
        ultima = previas[-1]
        izq = bisect_left(previas, inicio)
        en_ventana = previas[izq:]

        if len(en_ventana) < min_pedidos:
            salida[cliente] = Estimacion(
                cliente_id=cliente, pedidos_ventana=len(en_ventana),
                dias_observados=0, tasa_diaria=0.0,
                dias_silencio=(corte - ultima).days,
                fecha_ultima_compra=ultima, sin_muestra=True)
            continue

        dias = (corte - en_ventana[0]).days + 1
        salida[cliente] = Estimacion(
            cliente_id=cliente, pedidos_ventana=len(en_ventana),
            dias_observados=dias, tasa_diaria=len(en_ventana) / dias,
            dias_silencio=(corte - ultima).days,
            fecha_ultima_compra=ultima)
    return salida


# ── Backtest de origen móvil ────────────────────────────────────────────────

@dataclass
class Origen:
    """Un corte del backtest, con su propia tasa base."""
    inicio: date
    corte: date
    fin_prueba: date
    evaluados: int
    positivos: int
    aciertos: int
    intentos: int

    @property
    def tasa_base(self) -> float:
        return self.positivos / self.evaluados if self.evaluados else 0.0

    @property
    def precision(self) -> float:
        return self.aciertos / self.intentos if self.intentos else 0.0

    @property
    def lift(self) -> float | None:
        """
        `None` cuando NO existe: un origen en el que nadie dejó de comprar no
        tiene tasa base sobre la que levantar nada. Publicarlo como 0,0 diría
        «la alerta fue peor que el azar» cuando lo cierto es «no hubo azar que
        batir». Ver el bloque 2 de la cabecera.
        """
        base = self.tasa_base
        return self.precision / base if base > 0 else None


@dataclass
class Backtest:
    """El veredicto del modelo. Viaja ENTERO a la fila y a la pantalla."""
    origenes: list[Origen] = field(default_factory=list)
    evaluados: int = 0
    positivos: int = 0
    aciertos: int = 0
    intentos: int = 0

    @property
    def tasa_base(self) -> float:
        return self.positivos / self.evaluados if self.evaluados else 0.0

    @property
    def precision(self) -> float:
        return self.aciertos / self.intentos if self.intentos else 0.0

    @property
    def lift(self) -> float | None:
        base = self.tasa_base
        return self.precision / base if base > 0 else None

    @property
    def p_valor(self) -> float:
        """
        Probabilidad de acertar AL MENOS lo que se acertó eligiendo al azar.

        Es la cifra que decide si un lift de 2,0 significa algo. Con 14
        positivos sobre 167 evaluaciones y 5 aciertos en 30 intentos, sale
        ≈ 0,11: el resultado NO es distinguible del azar, y la pantalla lo dice
        junto al lift en vez de dejar que el 2,0 hable solo.
        """
        if self.intentos == 0 or self.tasa_base <= 0:
            return 1.0
        return cola_superior_binomial(self.aciertos, self.intentos, self.tasa_base)

    @property
    def supera_al_azar(self) -> bool:
        """Criterio declarado: lift > 1 Y el resultado no atribuible al azar."""
        return self.lift is not None and self.lift > 1.0 and self.p_valor < 0.05


def cola_superior_binomial(k: int, n: int, p: float) -> float:
    """
    P(X ≥ k) con X ~ Binomial(n, p), exacta y sin dependencias nuevas.

    Se suma desde k hacia arriba —y no 1 − P(X < k)— porque la cola que interesa
    es la pequeña y restarla de 1 pierde justo los dígitos que deciden.
    """
    if k <= 0:
        return 1.0
    if p <= 0.0:
        return 0.0
    if p >= 1.0:
        return 1.0
    total = 0.0
    for i in range(k, n + 1):
        total += math.comb(n, i) * (p ** i) * ((1.0 - p) ** (n - i))
    return min(total, 1.0)


def backtest(compras: dict[int, list[date]], ancla: date,
             meses_ventana: int = MESES_VENTANA,
             origenes: int = ORIGENES_BACKTEST,
             dias_prueba: int = DIAS_PRUEBA, paso: int = DIAS_PASO,
             top_k: int = TOP_K) -> Backtest:
    """
    Backtest de origen móvil: en cada corte se calcula la alerta con SOLO lo
    conocido hasta ese día y se mira quién no volvió a comprar en los
    `dias_prueba` siguientes.

    **La ventana de entrenamiento RUEDA con el origen.** Fijarla en la misma
    fecha de inicio para los tres cortes mediría un modelo que no es el de
    producción: en el origen más antiguo λ saldría de 83 días de historia frente
    a los 203 de la corrida real, y el error medido describiría un estimador que
    nunca se publica. Cada origen entrena con `meses_ventana` meses acabados en
    su propio corte, igual que la corrida de producción acaba en el ancla.

    Los clientes SIN MUESTRA quedan fuera de la evaluación: el modelo no emite
    juicio sobre ellos, así que contarlos como acierto o como fallo mediría otra
    cosa. Su exclusión es una limitación declarada, no un descarte silencioso.
    """
    resultado = Backtest()
    for k in range(origenes, 0, -1):
        corte = ancla - timedelta(days=dias_prueba + paso * (k - 1))
        inicio = inicio_ventana(corte, meses_ventana)
        estimaciones = {c: e for c, e in estimar(compras, inicio, corte).items()
                        if not e.sin_muestra}
        if len(estimaciones) < top_k:
            continue

        fin = corte + timedelta(days=dias_prueba)
        volvio = {c: _compro_entre(compras[c], corte, fin) for c in estimaciones}
        positivos = [c for c in estimaciones if not volvio[c]]

        ranking = sorted(estimaciones,
                         key=lambda c: (estimaciones[c].prob_silencio, c))[:top_k]
        aciertos = sum(1 for c in ranking if not volvio[c])

        resultado.origenes.append(Origen(
            inicio=inicio, corte=corte, fin_prueba=fin,
            evaluados=len(estimaciones), positivos=len(positivos),
            aciertos=aciertos, intentos=len(ranking)))
        resultado.evaluados += len(estimaciones)
        resultado.positivos += len(positivos)
        resultado.aciertos += aciertos
        resultado.intentos += len(ranking)
    return resultado


def _compro_entre(fechas: list[date], desde: date, hasta: date) -> bool:
    """¿Hay alguna compra en (desde, hasta]? Sobre la lista ya ordenada."""
    orden = sorted(fechas)
    i = bisect_right(orden, desde)
    return i < len(orden) and orden[i] <= hasta


# ── Utilidades de presentación que el modelo produce ────────────────────────

def malla_sparkline(ancla: date, meses: int = MESES_SPARKLINE) -> list[date]:
    """Los `meses` primeros-de-mes que acaban en el mes del ancla."""
    salida = []
    anio, mes = ancla.year, ancla.month
    for _ in range(meses):
        salida.append(date(anio, mes, 1))
        mes -= 1
        if mes == 0:
            mes, anio = 12, anio - 1
    return list(reversed(salida))


def percentiles(valores: dict[int, float]) -> dict[int, float]:
    """
    Lugar de cada cliente en la curva de Pareto, en por ciento (100 = el mayor).

    Se calcula por RANGO y no por cuota acumulada porque es lo que la columna
    promete: «su lugar en la curva», comparable entre clientes aunque la
    facturación esté muy concentrada.
    """
    if not valores:
        return {}
    orden = sorted(valores.items(), key=lambda kv: kv[1])
    n = len(orden)
    salida: dict[int, float] = {}
    for i, (cliente, _) in enumerate(orden):
        salida[cliente] = 100.0 * (i + 1) / n
    return salida
