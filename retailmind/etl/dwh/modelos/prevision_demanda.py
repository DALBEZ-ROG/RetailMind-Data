"""
etl/dwh/modelos/prevision_demanda.py — el MODELO de la fase E2 (§5.1 del diseño
del nivel estratégico).

Descomposición multiplicativa explícita en tres términos, con los factores
estacionales ENCOGIDOS hacia 1:

    previsión(mes) = nivel · factor_mes · factor_crecimiento

Es un Holt-Winters multiplicativo REGULARIZADO, y el encogimiento no es un
adorno: un Holt-Winters de libro necesita **dos ciclos completos (24 meses)**
para inicializar sus doce factores estacionales, y aquí hay **19**. Cinco meses
del calendario —agosto a diciembre— se estimarían con UNA sola observación. Sin
encoger, el factor de diciembre sería literalmente el único diciembre observado
proyectado como si fuera una ley.

═══════════════════════════════════════════════════════════════════════════════
1. AQUÍ NO SE ABRE NINGUNA CONEXIÓN
═══════════════════════════════════════════════════════════════════════════════

Entran dos vectores (meses consecutivos y valores) y sale una previsión con su
banda y su error de backtest. Quien lee de ClickHouse y quien escribe es
`tablas/fact_prevision_demanda.py`. La consecuencia práctica es que el backtest
de §5.1.6 se puede ejecutar y reproducir sin levantar el almacén.

═══════════════════════════════════════════════════════════════════════════════
2. LOS TRES TÉRMINOS, UNO A UNO
═══════════════════════════════════════════════════════════════════════════════

**factor_crecimiento.** MEDIANA de las razones interanuales de los meses
comparables, no la media. §5.1.1 mide 1,314× de media y 1,168× «excluyendo
enero, contaminado por el arranque de la cartera» — es decir, el propio diseño
tuvo que quitar a mano un mes atípico para que la media sirviera. La mediana
hace eso mismo sin nombrar el mes: enero da 2,124× frente a un entorno de
1,05–1,26× y la mediana lo ignora por construcción. Nombrar «enero» en el código
funcionaría en este seed y fallaría con el primer dato real que tuviera su
atípico en otro mes.

**factor_mes.** Razón de cada observación a su nivel local, promediada por mes
del calendario y encogida hacia 1:

    f̂(m) = (n(m) · f(m) + k) / (n(m) + k)      con k = 2

n(m) = 1 (agosto…diciembre) deja el factor a medio camino entre el dato y 1;
n(m) = 2 lo acerca al dato. Ningún mes llega a ser el dato crudo. Después se
renormalizan los doce factores a media 1: sin eso, el encogimiento arrastraría
el nivel hacia arriba o hacia abajo según cuántos meses queden por debajo de 1.

**nivel.** Suavizado exponencial simple sobre la serie desestacionalizada. Como
factores y nivel se necesitan mutuamente, se ITERA (`_ITERACIONES` pasadas):
factores ≡ 1 → nivel → factores → nivel… Tres pasadas bastan porque el cambio
entre la segunda y la tercera está por debajo del 0,1 %.

═══════════════════════════════════════════════════════════════════════════════
3. LA BANDA — y por qué se ensancha donde se ensancha
═══════════════════════════════════════════════════════════════════════════════

    semiancho = z(80 %) · σ_rel · ŷ · sqrt(h) · sqrt(1 + 1/n(m) + 1/n)

Cada factor responde a una fuente de incertidumbre distinta, y los tres se
declaran porque la regla 5 de §5.1.9 exige que la banda ensanche **con el
horizonte y con la escasez de observaciones del mes previsto**:

  * `σ_rel` — dispersión relativa de los residuos del ajuste, con los grados de
    libertad descontados (`n - _PARAMETROS_EFECTIVOS`). Es el ruido del proceso.
  * `sqrt(h)` — el nivel es un paseo aleatorio: a tres meses vista la
    incertidumbre del nivel es √3 veces la de un mes.
  * `sqrt(1 + 1/n(m))` — la del FACTOR del mes previsto. Un mes visto una vez
    (n=1) ensancha ×1,414; visto dos veces (n=2), ×1,225. **Que agosto tenga la
    banda más ancha que mayo es la información, no un defecto del gráfico.**
  * `1/n` — la del nivel medio estimado sobre n observaciones.

`z(80 %)` es el cuantil de la **t de Student** con `n - _PARAMETROS_EFECTIVOS`
grados de libertad y no el de la normal (1,2816): con 15 grados de libertad la t
da 1,341, y usar la normal con una σ estimada sobre 18 puntos produce una banda
sistemáticamente estrecha. La tabla de cuantiles va escrita a mano —cinco
valores— antes que añadir SciPy por un número.

═══════════════════════════════════════════════════════════════════════════════
4. LAS DOS LÍNEAS BASE — se calculan SIEMPRE, no solo cuando se sospecha
═══════════════════════════════════════════════════════════════════════════════

  * **ingenuo**: el último valor observado, repetido.
  * **ingenuo estacional con crecimiento**: el mismo mes del año anterior por el
    factor de crecimiento. Es la vara real: §5.1.3 la mide en **9,3 %** de MAPE.

Un modelo que no supera a la segunda no aporta nada, y entonces **se publica la
línea base**: es más simple y más honesta. Eso no es un fracaso del proyecto; es
el resultado del proyecto cuando el resultado es ése.

═══════════════════════════════════════════════════════════════════════════════
5. EL MES TRUNCADO
═══════════════════════════════════════════════════════════════════════════════

El generador coloca los pedidos en `1 + floor(random()*27)` salvo en julio de
2026, donde usa `*22`. El recorte de 27 días es uniforme en los 19 meses y no
distorsiona nada; el de julio SÍ: cubre 22 de 27 días comparables (81,5 %).

Este módulo **no decide** qué mes está truncado —es un hecho del origen y lo
declara la tarea—, pero sí ofrece las dos lecturas del error para que la
comparación con la línea base use el MISMO tratamiento en ambos lados
(`MAPE.crudo` y `MAPE.anualizado`). Comparar un modelo evaluado de una manera
contra una base evaluada de la otra es la forma más barata de ganar una vara.

═══════════════════════════════════════════════════════════════════════════════
6. LO QUE ESTE MODELO MIDE
═══════════════════════════════════════════════════════════════════════════════

El histórico es un seed cuya curva mensual está escrita a mano en
`60_seed_bloque_b_ventas.sql` línea 63 y cuyo crecimiento es un escalón fijo del
+18 % en 2026. **Cualquier métrica de aquí mide el pipeline, no el mercado.** Por
eso el criterio de aceptación es RELATIVO a una línea base y nunca absoluto: el
día que entren datos reales, el criterio sigue valiendo.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

import numpy as np

# ── Hiperparámetros, todos declarados ────────────────────────────────────────

#: Fuerza del encogimiento de los factores estacionales, cuando NO se estima.
#: k=2 equivale a «dos observaciones ficticias que dicen factor 1»: con n(m)=1
#: el factor queda a un tercio del dato y con n(m)=2, a la mitad. Es el valor
#: que §5.1.3 propone, y se conserva como respaldo de `_k_empirico` cuando la
#: serie no tiene ni un mes repetido con que estimar el ruido.
#:
#: CORRECCIÓN CE3.2: k = 2 fijo es DEMASIADO encogimiento para esta serie. Ver
#: `_k_empirico`.
K_ENCOGIMIENTO = 2.0

#: Límites del k estimado. El suelo impide que una varianza de ruido subestimada
#: deje pasar el factor crudo (que es el dato, no una estimación: exactamente lo
#: que §5.1.3 quiere evitar); el techo hace que una serie sin estacionalidad real
#: acabe con todos los factores en 1, que es la respuesta correcta cuando no hay
#: estacionalidad que estimar.
K_MINIMO, K_MAXIMO = 0.25, 20.0

#: Suavizado del nivel. 0,3 sobre 18-19 puntos: memoria efectiva ~6 meses. Más
#: alto perseguiría el ruido de ±10 % del generador; más bajo ignoraría el
#: escalón de 2026 que el factor de crecimiento ya recoge por separado.
ALFA_NIVEL = 0.3

#: Pasadas del ajuste alternado factores ↔ nivel.
_ITERACIONES = 3

#: Meses mínimos para ajustar el modelo. Por debajo se publica `sin_prevision`:
#: con menos de un ciclo no hay ni un factor estacional que estimar.
MESES_MINIMOS = 12

#: Grados de libertad que consume el ajuste: nivel + crecimiento + el conjunto
#: encogido de factores, que por estar encogido cuenta como uno y no como once.
_PARAMETROS_EFECTIVOS = 3

#: ÍNDICE DE DISPERSIÓN de la demanda mensual (varianza / media).
#:
#: El ruido de conteo se venía tratando como POISSON PURO —varianza = media—, y
#: eso es cierto si cada venta mueve UNA unidad. Aquí no: una línea de pedido
#: lleva `cantidad ∈ {1,2,3,4}` con frecuencias 43,18 / 28,86 / 13,89 / 14,01 %,
#: así que la demanda es un POISSON COMPUESTO y su índice vale
#:
#:     E[c²] / E[c] = 5,078 / 1,987 = 2,56
#:
#: Medido sobre el almacén con la década cargada —residuo de cada variante
#: frente a su valor esperado (cuota × total del mes), 6.220 variantes— sale
#: **2,53**. La teoría y el dato coinciden, así que no es un parche: es el
#: parámetro que faltaba.
#:
#: Lo que costaba omitirlo: la banda construía una desviación de √25,65 = 5,06
#: unidades donde la real es √(2,53 × 25,65) = 8,06 — un factor 1,59 de más
#: estrechez. Una banda del 80 % encogida 1,59 veces cubre P(|Z| < 0,806) =
#: 58,0 %, y la cobertura MEDIDA era 59,7 %. El criterio §5.1.6 no se toca ni
#: se relaja: se corrige el estimador para que la banda que dice 80 % cumpla
#: el 80 %, que es exactamente lo que ese criterio existe para vigilar.
#:
#: OJO si cambia la cesta: este valor depende de la distribución de `cantidad`
#: por línea. Si un día se venden cajas de 12, hay que volver a medirlo.
INDICE_DISPERSION = 2.53

#: Nivel de confianza de la banda publicada.
NIVEL_CONFIANZA = 80.0

#: Cuantil 0,90 de la t de Student (banda bilateral del 80 %) por grados de
#: libertad. Escrito a mano —cinco entradas— antes que arrastrar SciPy al
#: `requirements.txt` y a la imagen Docker del ETL por un solo número.
_T_090 = {1: 3.078, 2: 1.886, 3: 1.638, 4: 1.533, 5: 1.476, 6: 1.440,
          7: 1.415, 8: 1.397, 9: 1.383, 10: 1.372, 12: 1.356, 15: 1.341,
          20: 1.325, 30: 1.310, 60: 1.296}

#: Suavizado de Laplace de la cuota de una variante dentro de su categoría
#: (§5.1.3). Evita que una variante sin ventas en los últimos 6 meses reciba
#: cuota exactamente 0 y desaparezca del plan de compra.
LAPLACE_CUOTA = 0.5

#: Meses de la ventana con que se estima la cuota de la variante.
VENTANA_CUOTA = 6

# ── Nombres de método que viajan a la tabla ──────────────────────────────────

METODO_DESCOMPOSICION = "descomposicion"
METODO_LINEA_BASE = "linea_base_estacional"
METODO_TOP_DOWN = "top_down_categoria"
#: CORRECCIÓN CE3.1: §5.1.7 enumera TRES métodos y la base necesita un cuarto.
#: Ropa Mujer (9 meses / 28 uds) y Ropa Hombre (1 mes / 5 uds) existen como
#: categoría y no se pueden previsionar; publicarlas con `descomposicion` y una
#: banda inventada sería peor que declararlas sin previsión.
METODO_SIN_PREVISION = "sin_prevision"

VERSION_MODELO = "descomposicion-encogida-v1"


def semiancho(valor: float, sd_relativa: float, cuantil: float,
              varianza_relativa_extra: float = 0.0) -> float:
    """
    Semiancho de la banda, en UNIDADES, componiendo en escala de varianza.

    Tres sumandos, y el segundo es el que no estaba y hacía falta:

        varianza = (valor · sd_relativa)²          incertidumbre del modelo
                 + max(valor, 1)                   RUIDO DE CONTEO
                 + valor² · varianza_extra         lo que aporte quien llame

    **El ruido de conteo.** La demanda se mide en unidades enteras y se comporta
    como un conteo: su varianza es del orden de su media. En el total, con ~1.100
    unidades al mes, aporta ±34 sobre una banda de ±250 y es casi invisible. En
    una variante que espera 4 unidades, es el fenómeno entero.

    **El suelo `max(valor, 1)`.** Sin él, una previsión de CERO sale con banda
    [0, 0] — una afirmación de certeza absoluta sobre el mes que viene, y
    precisamente sobre la serie de la que menos se sabe. Ocurría de verdad: la
    línea base estacional de una variante que no vendió nada ese mes del año
    pasado publicaba `0 uds [0 – 0]`. Con el suelo, esa fila dice «0, y podría
    llegar a 1 ó 2», que es lo que en realidad se sabe.
    """
    varianza = ((valor * sd_relativa) ** 2
                + INDICE_DISPERSION * max(valor, 1.0)
                + (valor ** 2) * max(varianza_relativa_extra, 0.0))
    return cuantil * math.sqrt(varianza)


def _t090(gl: int) -> float:
    """Cuantil 0,90 de la t por interpolación sobre la tabla, con suelo en 1."""
    gl = max(int(gl), 1)
    claves = sorted(_T_090)
    if gl >= claves[-1]:
        return 1.2816            # el límite normal
    if gl in _T_090:
        return _T_090[gl]
    bajo = max(c for c in claves if c < gl)
    alto = min(c for c in claves if c > gl)
    peso = (gl - bajo) / (alto - bajo)
    return _T_090[bajo] + peso * (_T_090[alto] - _T_090[bajo])


# ── Estructuras ──────────────────────────────────────────────────────────────

@dataclass
class Ajuste:
    """Un modelo ya entrenado sobre UNA serie."""
    nivel: float
    crecimiento: float
    #: mes del calendario (1-12) → factor encogido y renormalizado.
    factores: dict[int, float]
    #: mes del calendario → observaciones con que se estimó su factor.
    observaciones: dict[int, int]
    #: Dispersión relativa de los residuos del ajuste.
    sigma_rel: float
    #: Meses de historia efectivamente usados.
    n: int
    #: Último mes de entrenamiento y su año, para proyectar el crecimiento.
    ultimo_mes: date
    #: Años presentes en el entrenamiento; el crecimiento se aplica a los
    #: posteriores al primero.
    anio_base: int
    #: Fuerza del encogimiento realmente aplicada (estimada salvo imposición).
    k: float = K_ENCOGIMIENTO


@dataclass
class Punto:
    """Una previsión con su banda."""
    mes: date
    valor: float
    inferior: float
    superior: float
    horizonte: int
    #: Observaciones del mes del calendario previsto (1 o 2 en esta base).
    observaciones_mes: int
    #: Desviación típica RELATIVA de la previsión, SIN el multiplicador del
    #: cuantil. Es lo que hay que componer para derivar una banda a partir de
    #: ésta (la desagregación a la variante): sumar en cuadratura un semiancho
    #: ya multiplicado por t con una desviación típica cruda mezcla dos escalas
    #: distintas y produce una banda que parece razonable y está mal.
    sd_relativa: float = 0.0
    #: Cuantil aplicado, para que quien componga la banda use el mismo.
    cuantil: float = 1.2816


@dataclass
class ErrorBacktest:
    """MAPE y MAE de una serie de previsiones contra sus valores reales."""
    mape: float = 0.0
    mae: float = 0.0
    n: int = 0
    #: (mes, real, previsto) de cada punto, para poder auditar el número.
    detalle: list[tuple[date, float, float]] = field(default_factory=list)


@dataclass
class ResultadoBacktest:
    """El backtest completo de UNA serie: modelo contra las DOS líneas base."""
    modelo: ErrorBacktest
    ingenuo: ErrorBacktest
    estacional: ErrorBacktest
    #: Cobertura observada de la banda del 80 %, en tanto por ciento.
    cobertura: float
    puntos_banda: int
    dentro_banda: int
    #: Puntos por (origen, horizonte) para el informe.
    por_origen: dict[str, ErrorBacktest] = field(default_factory=dict)

    @property
    def supera_estacional(self) -> bool:
        """¿El modelo bate a la línea base que hay que batir?"""
        return self.estacional.n > 0 and self.modelo.mape < self.estacional.mape


# ── Utilidades de calendario ─────────────────────────────────────────────────

def sumar_meses(m: date, k: int) -> date:
    """Primer día del mes que está k meses después de `m`."""
    total = (m.year * 12 + (m.month - 1)) + k
    return date(total // 12, total % 12 + 1, 1)


def malla_mensual(inicio: date, fin: date) -> list[date]:
    """Meses consecutivos de `inicio` a `fin`, ambos incluidos."""
    meses, actual = [], date(inicio.year, inicio.month, 1)
    tope = date(fin.year, fin.month, 1)
    while actual <= tope:
        meses.append(actual)
        actual = sumar_meses(actual, 1)
    return meses


# ── Ajuste ───────────────────────────────────────────────────────────────────

def _crecimiento(meses: list[date], valores: np.ndarray) -> float:
    """
    MEDIANA de las razones interanuales de los meses comparables.

    Se exige que AMBOS términos sean positivos: una razón contra un mes en cero
    es infinita, y una mediana con un infinito dentro deja de ser robusta justo
    cuando más se la necesita.
    """
    indice = {m: i for i, m in enumerate(meses)}
    razones = []
    for i, m in enumerate(meses):
        previo = date(m.year - 1, m.month, 1)
        j = indice.get(previo)
        if j is not None and valores[j] > 0 and valores[i] > 0:
            razones.append(valores[i] / valores[j])
    if len(razones) < 2:
        return 1.0
    return float(np.median(razones))


def _normalizar_al_anio_base(meses: list[date], valores: np.ndarray,
                             crecimiento: float, anio_base: int) -> np.ndarray:
    """
    Lleva toda la serie a la escala del PRIMER año dividiendo por el
    crecimiento acumulado. Después de esto el nivel es comparable entre años y
    el suavizado exponencial no tiene que perseguir un escalón.
    """
    factores = np.array([crecimiento ** max(m.year - anio_base, 0) for m in meses])
    return valores / factores


def _ses(serie: np.ndarray, alfa: float) -> np.ndarray:
    """
    Suavizado exponencial simple. Devuelve el nivel FILTRADO en cada instante
    (el nivel después de ver la observación t), que es lo que hace falta para
    calcular la razón de cada observación a su nivel local.
    """
    nivel = np.empty_like(serie, dtype=float)
    actual = float(serie[0])
    for i, valor in enumerate(serie):
        actual = alfa * float(valor) + (1 - alfa) * actual
        nivel[i] = actual
    return nivel


def _k_empirico(razones: dict[int, list[float]]) -> float:
    """
    Estima la fuerza del encogimiento en vez de fijarla — CORRECCIÓN CE3.2.

    §5.1.3 propone «k ≈ 2», que sobre esta serie encoge DEMASIADO: el factor de
    diciembre queda en 1,075 cuando el generador del seed lo escribió en 1,48
    (línea 63 de `60_seed_bloque_b_ventas.sql`, normalizado a media 1). Con k=2 y
    n(m)=1 el dato pesa un tercio, y el modelo devuelve una serie casi plana cuya
    banda hay que ensanchar al ±41 % para que cubra la realidad. Esa banda cubre
    el 100 % de los puntos del backtest y **suspende el criterio de aceptación de
    §5.1.6**, que exige entre 65 % y 90 %: un intervalo que nunca falla es tan
    inútil como uno que siempre falla.

    La cuestión no es de gusto: el peso óptimo de un encogimiento hacia la media
    es conocido y **se estima de los propios datos** (Stein / Bayes empírico):

        peso(m) = n(m)·τ² / (n(m)·τ² + σ²)        ⇔        k = σ² / τ²

      σ² — varianza del RUIDO de una razón, estimada DENTRO de cada mes del
           calendario con los meses que se repiten (aquí, los seis de enero a
           junio, con 2 observaciones cada uno).
      τ² — varianza REAL entre los doce factores, estimada como la varianza de
           las medias por mes MENOS la parte que ya explica el ruido.

    Cuando la estacionalidad es fuerte frente al ruido —que es este caso—, k sale
    pequeño y el dato manda. Cuando no hay estacionalidad por encima del ruido,
    τ² tiende a cero, k se dispara y todos los factores acaban en 1. Las dos
    respuestas son las correctas, y ninguna se ha elegido a mano.

    Si ningún mes se repite no hay con qué estimar σ² y se devuelve el `k = 2` de
    §5.1.3: sin observaciones repetidas, ser conservador es lo único disponible.
    """
    repetidos = {m: v for m, v in razones.items() if len(v) >= 2}
    if not repetidos:
        return K_ENCOGIMIENTO

    # σ²: varianza dentro del mes, agrupada sobre los meses que se repiten.
    suma, grados = 0.0, 0
    for valores_mes in repetidos.values():
        arreglo = np.asarray(valores_mes, dtype=float)
        suma += float(np.sum((arreglo - arreglo.mean()) ** 2))
        grados += len(arreglo) - 1
    sigma2 = suma / grados if grados else 0.0
    if sigma2 <= 0:
        return K_MINIMO

    # τ²: varianza entre meses, descontando la parte que aporta el propio ruido.
    medias = np.array([float(np.mean(v)) for v in razones.values() if v])
    n_medio = float(np.mean([len(v) for v in razones.values() if v]))
    tau2 = float(np.var(medias, ddof=1)) - sigma2 / n_medio if len(medias) > 1 else 0.0
    if tau2 <= 0:
        return K_MAXIMO

    return float(min(max(sigma2 / tau2, K_MINIMO), K_MAXIMO))


def ajustar(meses: list[date], valores, k: float | None = None,
            alfa: float = ALFA_NIVEL) -> Ajuste | None:
    """
    Entrena la descomposición sobre una serie mensual SIN huecos.

    Devuelve `None` si la serie no llega a `MESES_MINIMOS`: por debajo de un
    ciclo completo no hay factor estacional que estimar, y devolver un modelo
    con factores todos a 1 sería devolver una media disfrazada de modelo.

    `k` se ESTIMA de los datos (`_k_empirico`) salvo que se imponga desde fuera,
    que es lo que hace la comparación de la corrección CE3.2.
    """
    valores = np.asarray(valores, dtype=float)
    n = len(meses)
    if n < MESES_MINIMOS or valores.sum() <= 0:
        return None

    anio_base = meses[0].year
    crecimiento = _crecimiento(meses, valores)
    z = _normalizar_al_anio_base(meses, valores, crecimiento, anio_base)

    mes_del_anio = np.array([m.month for m in meses])
    observaciones = {m: int((mes_del_anio == m).sum()) for m in range(1, 13)}

    factores = {m: 1.0 for m in range(1, 13)}
    nivel_serie = np.ones(n)
    k_usado = k if k is not None else K_ENCOGIMIENTO

    for _ in range(_ITERACIONES):
        # (a) desestacionalizar con los factores vigentes y re-nivelar
        desest = z / np.array([factores[m] for m in mes_del_anio])
        nivel_serie = _ses(desest, alfa)

        # (b) razón de cada observación a su nivel, agrupada por mes.
        #
        #     EL NIVEL DE ESTA DIVISIÓN ES EL GLOBAL, NO EL FILTRADO — y no es un
        #     atajo. Un nivel local tiene que ser ESTACIONALMENTE NEUTRO, que es
        #     justo la propiedad por la que el método clásico usa una media móvil
        #     CENTRADA de 12 meses: promedia un ciclo entero y por eso no
        #     contiene estacionalidad. El nivel filtrado por suavizado
        #     exponencial no la tiene: persigue la subida de mayo y la bajada de
        #     junio, de modo que al dividir por él la estacionalidad ya se ha
        #     comido a sí misma. Medido: con el nivel filtrado, la varianza
        #     DENTRO de cada mes sale mayor que la varianza ENTRE meses, el
        #     encogimiento empírico se dispara a su tope y los doce factores
        #     acaban entre 0,98 y 1,02 — un modelo sin estacionalidad ninguna
        #     sobre una serie que la tiene escrita en el generador.
        #
        #     La media móvil centrada tampoco sirve AQUÍ: con 18 puntos solo
        #     produce ratio para los 6 meses centrales, y los seis primeros
        #     —enero a junio, donde vive la mayor amplitud— se quedarían sin
        #     factor. El nivel global sí es neutro porque la serie `z` ya está
        #     normalizada al año base: quitado el escalón del crecimiento, lo que
        #     queda es nivel constante + estacionalidad + ruido.
        nivel_global = float(np.mean(desest))
        with np.errstate(divide="ignore", invalid="ignore"):
            razon = np.where(nivel_global > 0, z / nivel_global, np.nan)

        por_mes: dict[int, list[float]] = {}
        crudos = {}
        for m in range(1, 13):
            propias = razon[mes_del_anio == m]
            propias = propias[np.isfinite(propias)]
            if len(propias):
                por_mes[m] = [float(x) for x in propias]
            crudos[m] = float(propias.mean()) if len(propias) else 1.0

        # (c) ENCOGER hacia 1 con el peso de la muestra de cada mes. La FUERZA
        #     del encogimiento se estima de los propios datos salvo imposición.
        if k is None:
            k_usado = _k_empirico(por_mes)
        encogidos = {}
        for m in range(1, 13):
            n_m = observaciones[m]
            encogidos[m] = (n_m * crudos[m] + k_usado) / (n_m + k_usado) if n_m else 1.0

        # (d) renormalizar a media 1. Sin esto el encogimiento desplaza el
        #     nivel: si nueve meses quedan por debajo de 1, la previsión entera
        #     se va hacia abajo sin que ningún factor parezca sospechoso.
        media = float(np.mean(list(encogidos.values())))
        factores = {m: (f / media if media > 0 else 1.0) for m, f in encogidos.items()}

    # Residuos del ajuste: en la escala ORIGINAL, no en la normalizada.
    ajustado = np.array([
        nivel_serie[i] * factores[mes_del_anio[i]]
        * (crecimiento ** max(meses[i].year - anio_base, 0))
        for i in range(n)
    ])
    with np.errstate(divide="ignore", invalid="ignore"):
        relativos = np.where(ajustado > 0, (valores - ajustado) / ajustado, np.nan)
    relativos = relativos[np.isfinite(relativos)]

    gl = max(len(relativos) - _PARAMETROS_EFECTIVOS, 1)
    sigma = float(math.sqrt(float(np.sum(relativos ** 2)) / gl)) if len(relativos) else 0.0

    return Ajuste(
        nivel=float(nivel_serie[-1]),
        crecimiento=crecimiento,
        factores=factores,
        observaciones=observaciones,
        sigma_rel=sigma,
        n=n,
        ultimo_mes=meses[-1],
        anio_base=anio_base,
        k=float(k_usado),
    )


# ── Predicción ───────────────────────────────────────────────────────────────

def predecir(ajuste: Ajuste, horizonte: int = 3, con_banda: bool = True) -> list[Punto]:
    """Los `horizonte` meses siguientes al último de entrenamiento, con banda."""
    gl = max(ajuste.n - _PARAMETROS_EFECTIVOS, 1)
    t = _t090(gl)
    puntos = []
    for h in range(1, horizonte + 1):
        mes = sumar_meses(ajuste.ultimo_mes, h)
        factor = ajuste.factores.get(mes.month, 1.0)
        crecimiento = ajuste.crecimiento ** max(mes.year - ajuste.anio_base, 0)
        valor = max(ajuste.nivel * factor * crecimiento, 0.0)

        n_m = ajuste.observaciones.get(mes.month, 0)
        inflacion = math.sqrt(1.0 + (1.0 / n_m if n_m else 1.0) + 1.0 / ajuste.n)
        sd_rel = ajuste.sigma_rel * math.sqrt(h) * inflacion
        semi = semiancho(valor, sd_rel, t) if con_banda else 0.0
        puntos.append(Punto(
            mes=mes, valor=valor,
            inferior=max(valor - semi, 0.0), superior=valor + semi,
            horizonte=h, observaciones_mes=n_m,
            sd_relativa=sd_rel, cuantil=t,
        ))
    return puntos


# ── Líneas base ──────────────────────────────────────────────────────────────

def linea_base_ingenua(meses: list[date], valores, horizonte: int = 3) -> list[Punto]:
    """El último valor observado, repetido. La referencia más tonta que existe."""
    valores = np.asarray(valores, dtype=float)
    ultimo = float(valores[-1])
    return [
        Punto(mes=sumar_meses(meses[-1], h), valor=ultimo,
              inferior=ultimo, superior=ultimo, horizonte=h, observaciones_mes=0)
        for h in range(1, horizonte + 1)
    ]


def linea_base_estacional(meses: list[date], valores, horizonte: int = 3,
                          sigma_rel: float | None = None) -> list[Punto]:
    """
    Mismo mes del año anterior × factor de crecimiento. **Ésta es la vara.**

    Si el mes previsto no tiene homólogo en el año anterior, cae al último valor
    observado × crecimiento: es lo más parecido que hay, y devolver 0 sería
    fabricar una caída.
    """
    valores = np.asarray(valores, dtype=float)
    indice = {m: i for i, m in enumerate(meses)}
    crecimiento = _crecimiento(meses, valores)

    # Dispersión para la banda de la línea base, cuando se publica como
    # resultado y no solo como referencia: la del propio estimador estacional
    # sobre la historia.
    if sigma_rel is None:
        errores = []
        for i, m in enumerate(meses):
            j = indice.get(date(m.year - 1, m.month, 1))
            if j is not None and valores[j] > 0:
                estimado = valores[j] * crecimiento
                if estimado > 0:
                    errores.append((valores[i] - estimado) / estimado)
        sigma_rel = float(np.sqrt(np.mean(np.square(errores)))) if errores else 0.0

    n = len(meses)
    t = _t090(max(n - 2, 1))
    puntos = []
    for h in range(1, horizonte + 1):
        mes = sumar_meses(meses[-1], h)
        j = indice.get(date(mes.year - 1, mes.month, 1))
        base = float(valores[j]) if j is not None else float(valores[-1])
        valor = max(base * crecimiento, 0.0)
        sd_rel = sigma_rel * math.sqrt(h)
        semi = semiancho(valor, sd_rel, t)
        puntos.append(Punto(mes=mes, valor=valor,
                            inferior=max(valor - semi, 0.0), superior=valor + semi,
                            horizonte=h, sd_relativa=sd_rel, cuantil=t,
                            observaciones_mes=sum(1 for x in meses if x.month == mes.month)))
    return puntos


# ── Backtest de origen móvil ─────────────────────────────────────────────────

def _error(pares: list[tuple[date, float, float]]) -> ErrorBacktest:
    """MAPE y MAE sobre (mes, real, previsto). Los reales en cero se descartan."""
    utiles = [(m, r, p) for m, r, p in pares if r > 0]
    if not utiles:
        return ErrorBacktest(detalle=list(pares))
    mape = float(np.mean([abs(r - p) / r for _, r, p in utiles]) * 100.0)
    mae = float(np.mean([abs(r - p) for _, r, p in utiles]))
    return ErrorBacktest(mape=mape, mae=mae, n=len(utiles), detalle=list(pares))


def backtest(meses: list[date], valores, origenes: int = 3, horizonte: int = 3,
             ajuste_real: dict[date, float] | None = None,
             k: float | None = None) -> ResultadoBacktest:
    """
    Origen móvil con ventana EXPANSIVA (§5.1.6).

    Con `origenes = 3` y una serie que acaba en 2026-07 se entrena hasta
    2026-04, 2026-05 y 2026-06, y cada origen predice hasta `horizonte` meses
    por delante mientras existan valores reales con que comparar:

        origen 2026-04 → mayo (h=1), junio (h=2), julio (h=3)
        origen 2026-05 → junio (h=1), julio (h=2)
        origen 2026-06 → julio (h=1)

    Seis puntos por serie, que es lo que permite medir la COBERTURA de la banda
    sobre algo más que tres observaciones.

    `ajuste_real` reescala el valor REAL de meses concretos —el truncado— para
    poder medir el error con los dos tratamientos. Se aplica al real y no a la
    previsión: el modelo predice un mes completo, es el dato lo que está corto.
    """
    valores = np.asarray(valores, dtype=float)
    n = len(meses)
    reales = {m: float(v) for m, v in zip(meses, valores)}
    if ajuste_real:
        for m, factor in ajuste_real.items():
            if m in reales:
                reales[m] = reales[m] * factor

    pares_modelo: list[tuple[date, float, float]] = []
    pares_ingenuo: list[tuple[date, float, float]] = []
    pares_estacional: list[tuple[date, float, float]] = []
    por_origen: dict[str, ErrorBacktest] = {}
    dentro = total_banda = 0

    for o in range(origenes, 0, -1):
        corte = n - o                       # nº de meses de entrenamiento
        if corte < MESES_MINIMOS:
            continue
        m_tr, v_tr = meses[:corte], valores[:corte]
        pasos = min(horizonte, n - corte)

        ajuste = ajustar(m_tr, v_tr, k=k)
        propios: list[tuple[date, float, float]] = []

        if ajuste is not None:
            for punto in predecir(ajuste, pasos):
                real = reales[punto.mes]
                propios.append((punto.mes, real, punto.valor))
                pares_modelo.append((punto.mes, real, punto.valor))
                if real > 0:
                    total_banda += 1
                    if punto.inferior <= real <= punto.superior:
                        dentro += 1

        for punto in linea_base_ingenua(m_tr, v_tr, pasos):
            pares_ingenuo.append((punto.mes, reales[punto.mes], punto.valor))
        for punto in linea_base_estacional(m_tr, v_tr, pasos):
            pares_estacional.append((punto.mes, reales[punto.mes], punto.valor))

        por_origen[m_tr[-1].strftime("%Y-%m")] = _error(propios)

    cobertura = (dentro / total_banda * 100.0) if total_banda else 0.0
    return ResultadoBacktest(
        modelo=_error(pares_modelo),
        ingenuo=_error(pares_ingenuo),
        estacional=_error(pares_estacional),
        cobertura=cobertura, puntos_banda=total_banda, dentro_banda=dentro,
        por_origen=por_origen,
    )


# ── Desagregación top-down a la variante (§5.1.3) ────────────────────────────

def cuota(unidades_variante: float, unidades_categoria: float,
          variantes_en_categoria: int) -> float:
    """
    Cuota de una variante dentro de su categoría, con suavizado de Laplace.

    Sin el suavizado, una variante que no vendió nada en la ventana recibiría
    cuota 0 exacta y desaparecería del plan de compra — que es justo la variante
    sobre la que hay que decidir si se descontinúa o se repone.
    """
    numerador = unidades_variante + LAPLACE_CUOTA
    denominador = unidades_categoria + LAPLACE_CUOTA * max(variantes_en_categoria, 1)
    return numerador / denominador if denominador > 0 else 0.0


def banda_top_down(valor: float, sd_relativa_categoria: float,
                   unidades_ventana: float, cuantil: float) -> tuple[float, float]:
    """
    Banda de una variante: la de su categoría MÁS **dos** ruidos propios.

    Se compone en escala de desviación típica y el cuantil se aplica UNA vez al
    final. Sumar en cuadratura un semiancho ya multiplicado por t con una
    desviación cruda mezcla dos escalas, y el resultado no es la banda de nadie.

    Lo propio de la variante son DOS ruidos, y ninguno es prescindible:

      * **1/sqrt(unidades de la ventana)**, que entra por aquí — la cuota se
        estima CONTANDO, y un conteo trae ruido de Poisson. Es la incertidumbre
        de *qué parte* de la categoría le toca a esta variante.

      * **el ruido de conteo de la propia venta**, que lo pone `semiancho`. Una
        variante que espera 4 unidades el mes que viene tiene un ±50 %
        irreducible aunque su cuota se conociera exactamente. **Éste es el
        término dominante y es el que faltaba**: sin él la cobertura medida de
        las 159 variantes cae al 54,8 %, muy por debajo del suelo del 65 % que
        §5.1.6 fija — una banda que falla una de cada dos veces mientras dice
        «80 %».

    La media de las 159 variantes con historia larga es de 4,05 uds/mes: en ese
    régimen el ruido de conteo no es una corrección de segundo orden, es el
    fenómeno.
    """
    semi = semiancho(valor, sd_relativa_categoria, cuantil,
                     varianza_relativa_extra=1.0 / max(unidades_ventana, 1.0))
    return max(valor - semi, 0.0), valor + semi
