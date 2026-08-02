# Diseño del nivel estratégico de RetailMind — información para la toma de decisiones

**Universidad Técnica Estatal de Quevedo (UTEQ)** · Facultad de Ciencias de la Ingeniería
**Asignatura**: Construcción de Software — 6.º semestre
**Proyecto**: RetailMind — comercio minorista multicanal de ticket alto (Quevedo, Los Ríos, Ecuador)
**Documento**: diseño del nivel estratégico · **Versión 1** · **Fecha: 2026-08-01**

> **Qué es este documento y qué NO es.** Es el **diseño** del nivel estratégico: define las tomas
> de decisión que cada objetivo estratégico exige a la dirección, decide cuáles se resuelven con un
> tablero y cuáles con inteligencia artificial, y especifica ambos. **No se implementó nada**: ni un
> tablero, ni un modelo, ni una tabla. Todo lo que aquí se afirma sobre los datos se verificó en
> **modo lectura** contra PostgreSQL el 2026-08-01, y las consultas están citadas.
>
> **Nota sobre el vocabulario.** El nivel estratégico no produce «informes»: produce **información
> para la toma de decisiones**. La unidad de este documento no es la consulta sino la **decisión**,
> y cada decisión se nombra por la acción que se toma, no por el dato que se mira.

> **Condición de la verificación.** ClickHouse (`retailmind_dwh`) estaba **apagado** el día de
> redacción —Docker no corriendo, `localhost:8123` sin respuesta—, de modo que las 44 cifras de
> control no se volvieron a ejecutar contra el almacén. **No hace falta**: el almacén está validado
> contra PostgreSQL **al centavo** en sus 19 tablas (44 controles en verde,
> `CORRECCIONES_DISENO_ETL.md`), así que verificar contra PostgreSQL es verificar contra el mismo
> número. Todas las consultas de este documento se ejecutaron contra `retailmind` vía el MCP de
> solo lectura.

---

## 1. Resumen ejecutivo

| Magnitud | Cantidad |
|---|---|
| **Tomas de decisión derivadas** | **23** |
| Resueltas con **DASHBOARD** | **19** |
| Resueltas con **INTELIGENCIA ARTIFICIAL** | **4** (alimentadas por 2 modelos) |
| **Tableros de dirección propuestos** | **7** |
| Pantallas de predicción propuestas | 2 |
| **Tablas nuevas en el almacén para los tableros** | **0** |
| Tablas nuevas para las predicciones | 2 (`fact_prevision_demanda`, `fact_alerta_cliente`) |

### Reparto por objetivo

| Objetivo estratégico | Decisiones | Dashboard | IA |
|---|---|---|---|
| **OE-06** Consolidación de la Experiencia Omnicanal | 3 | 3 | 0 |
| **OE-07** Rentabilidad por Volumen y Rotación | 5 | 4 | 1 |
| **OE-08** Fidelización y Retención de Clientes | 4 | 3 | 1 |
| **OE-09** Eficiencia Operativa | 4 | 4 | 0 |
| **OE-10** Liderazgo en Decisiones Basadas en Datos | 3 | 2 | 1 |
| **OE-11** Excelencia en la Cadena de Abastecimiento | 4 | 3 | 1 |
| **Total** | **23** | **19** | **4** |

La asimetría es deliberada y sale del negocio: **OE-07** concentra cinco decisiones porque el margen
es la palanca que la dirección controla directamente, y **OE-10** solo tres porque es un objetivo
habilitador —sirve a los otros cinco más que a sí mismo—.

### Veredicto de viabilidad de los dos modelos

| Modelo | Veredicto | Cifra decisiva |
|---|---|---|
| **Previsión de demanda** | ✅ **VIABLE** a nivel de **total y categoría**; **NO viable** por variante individual salvo en el 13 % del catálogo | Serie total de 19 meses con **CV 0,277**; una línea base estacional ya alcanza **MAPE 9,3 %**. Frente a eso, la variante media vende **1,25 uds/mes** y solo **159 de 1.221** variantes tienen ≥12 meses de historia |
| **Predicción de abandono de cliente** | ⚠️ **NO VIABLE como modelo entrenado.** Se propone un **modelo de proceso** (alerta de silencio) en su lugar, con su debilidad declarada en pantalla | Sobre la ventana estable, el mejor predictor disponible (silencio medido en intervalos propios del cliente) tiene **correlación 0,039** con el resultado real, sobre **5 casos positivos de 53 clientes** |

Las dos verificaciones destaparon además **dos artefactos del seed** que invalidarían cualquier
métrica leída sin contexto, y que se documentan con su línea de código en §5:

1. **La estacionalidad de la demanda está escrita a mano en el generador** (`60_seed_bloque_b_ventas.sql`
   línea 63: `sales_wt := ARRAY[0.75,0.80,1.05,1.20,1.25,0.85,0.80,0.90,1.00,1.05,1.35,1.55]`). La
   correlación interanual de **0,879** que se mide no es el descubrimiento de un mercado: es la
   reproducción de un arreglo constante.
2. **Nadie abandona nunca en estos datos.** El cliente de cada pedido se sortea con
   `ORDER BY -ln(random())/pop LIMIT 1` (línea 187), un peso constante por cliente e independiente
   del tiempo y del pasado. Y en **enero y febrero de 2025 UN SOLO cliente hizo el 100 % de los
   pedidos** porque era el único registrado: el cliente más grande de la historia parece abandonado
   por un efecto de cohorte, no por un cambio de conducta.

---

## 2. Marco conceptual

### 2.1 Qué es el nivel estratégico y en qué se diferencia del táctico

| | Nivel TÁCTICO (construido) | Nivel ESTRATÉGICO (este diseño) |
|---|---|---|
| **Quién lo usa** | El jefe de área | La dirección |
| **Qué pregunta** | «¿Cómo va mi área?» | «¿Qué hago con el negocio?» |
| **Horizonte** | Hoy, esta semana, este mes | Trimestre, semestre, año |
| **Unidad** | El **informe** (69) | La **decisión** (23) |
| **Alcance** | Un departamento | Transversal: una decisión cruza varios |
| **Salida** | Una tabla con filtros | Un **tablero** o una **predicción** |
| **Reversibilidad** | Alta (se corrige mañana) | Baja (se corrige el trimestre siguiente, y con costo) |

La diferencia operativa que más pesa es la última. Un informe táctico que se lee mal se vuelve a
mirar. Una decisión estratégica que se toma mal —descontinuar el producto equivocado, concentrar la
compra en el proveedor que incumple, retirar personal del canal que crece— **se paga durante meses**.
Por eso este nivel exige algo que el táctico no exigía: **cada cifra viaja con su muestra y con su
salvedad**, y ninguna pantalla predictiva muestra un número sin su margen de error.

### 2.2 El criterio DASHBOARD vs. IA

> **DASHBOARD** cuando la respuesta **ya está en los datos** y solo hay que presentarla bien.
> **IA** cuando la respuesta **NO está** y hay que inferirla.

Aplicado con rigor, el criterio es más restrictivo de lo que parece. La prueba práctica es ésta:

- Si la pregunta se puede escribir como un `GROUP BY` sobre hechos ya ocurridos —por muy complejo
  que sea el `GROUP BY`—, es **dashboard**. «¿Qué proveedor me incumple más?» es un `GROUP BY`.
  «¿Qué categoría deja más margen?» es un `GROUP BY`. «¿Dónde está mi cuello de botella?» es un
  `GROUP BY` con cuatro promedios y sus denominadores.
- Solo es **IA** si la respuesta se refiere a **un hecho que todavía no ha ocurrido** (previsión) o a
  **un estado latente que nadie registró** (el cliente que está dejando de comprar y no lo ha dicho).

Con ese filtro, de las 23 decisiones **solo 4 son IA**, y las cuatro se apoyan en **dos modelos**:

| Modelo | Decisiones que alimenta | Objetivos a los que sirve |
|---|---|---|
| **Previsión de demanda** (serie temporal) | D-07.5 (nivel objetivo de stock), D-10.1 (metas del período), D-11.1 (plan de compra) | **OE-10** (cierra el hueco declarado «anticipar la demanda», §6.3 de la base estratégica), **OE-07**, **OE-11** |
| **Alerta de abandono de cliente** (proceso puntual) | D-08.1 (a qué cliente se recupera y con qué gesto) | **OE-08** (cierra la mitad medible del hueco «la retención no se mide», §6.2) |

**Lo que se rechazó como IA y por qué.** «Qué producto descontinuar» parece un problema de
clasificación; es un `GROUP BY` por margen y rotación sobre `fact_venta_linea` y
`fact_stock_mensual`. «Qué proveedor conviene» parece un problema de *ranking learning*; es un
`GROUP BY` por proveedor con tres columnas —costo, plazo, cumplimiento— que ya están cargadas.
Envolver cualquiera de los dos en un modelo añadiría opacidad sin añadir información: **la respuesta
ya está, y un modelo la escondería detrás de un peso**.

### 2.3 La herencia que este nivel no puede ignorar

`CORRECCIONES_DISENO_ETL.md` registra **33 supuestos del diseño que resultaron falsos**, y su patrón
dominante es uno solo:

> El JOIN no falla: devuelve una fila de más, o de menos, o con NULL. El informe se pinta, las cifras
> parecen plausibles, y nadie se entera.

Ese patrón es **peor en el nivel estratégico que en el táctico**, porque aquí la cifra plausible y
equivocada no produce una pantalla rara: produce una decisión. Tres consecuencias vinculantes para
todo lo que sigue:

1. **Ninguna cifra de un tablero se presenta sin su denominador.** Es la lección de C2.7 (cuatro
   promedios sobre poblaciones distintas hacen que el cuello de botella parezca estar donde no está)
   y de C4.2 (un ciclo medido sobre el 17,9 % de los casos, presentado como el ciclo entero).
2. **Ninguna lista blanca sale de un documento; sale de los datos o del CHECK del motor.** Es la
   lección de C3C.3 y C4.7, que reincidieron: un filtro escrito desde el diseño casaba con **cero
   filas** y vaciaba el informe sin dar un error.
3. **Ningún número predicho se muestra sin intervalo y sin muestra.** Es la extensión natural de la
   regla anterior al terreno nuevo. Un `MAPE` sin la serie sobre la que se midió, o una alerta sin
   el número de casos que la validaron, es exactamente la misma clase de cifra plausible.

---

## 3. Las tomas de decisión, objetivo por objetivo

Formato de cada ficha: **qué se decide · quién decide · periodicidad · consecuencia de equivocarse ·
clasificación**.

---

### 3.1 OE-06 · Consolidación de la Experiencia Omnicanal

> *Fortalecer la experiencia de compra integrada entre los canales digital, telefónico y presencial,
> asegurando que el cliente pueda transitar entre ellos sin fricción, y expandir la participación de
> mercado sosteniendo el alto valor por transacción.*

El objetivo tiene un hecho medido debajo: **64 de los 69 clientes con pedidos compran por la tienda
en línea Y por canal interno**, y los tres canales tienen el mismo comportamiento de compra
(dispersión del ticket 3,3 %). Las decisiones que exige no son «cuál canal es mejor» —son
intercambiables— sino **dónde se pone la capacidad y dónde está la fricción**.

#### D-06.1 · Dónde se refuerza y de dónde se retira capacidad de atención por canal

| | |
|---|---|
| **Qué se decide** | Cuánta capacidad —personal de mostrador, líneas de teléfono, inversión en la tienda en línea— se sostiene en cada canal el próximo semestre, y de cuál se retira |
| **Quién decide** | Gerente General, con el Jefe de Ventas |
| **Periodicidad** | **Semestral** (revisión mensual del indicador) |
| **Consecuencia de errar** | Sostener costo fijo de mostrador para una demanda que migró a la web, o quedarse sin capacidad en el canal que crece y perder la venta en el momento del pico. Con 53,87 % del monto en web y 25,25 % en mostrador, un error de lectura desplaza personal entre dos operaciones que no son intercambiables |
| **Clasificación** | 🟦 **DASHBOARD** — la participación por canal y su evolución están íntegras en `fact_pedido` |

#### D-06.2 · Cuál de los tres puntos de caída del recorrido se ataca este trimestre

| | |
|---|---|
| **Qué se decide** | En cuál de los tres escalones donde el cliente se cae se invierte: el carrito que no llega a pagar, el cobro que se rechaza, o el pedido confirmado que se atasca antes de entregarse |
| **Quién decide** | Gerente General con el Jefe de Ventas |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Invertir en el escalón que no es el cuello. Es exactamente el riesgo que C2.7 describe: tres tasas presentadas juntas sobre poblaciones distintas hacen que la fuga parezca estar donde no está |
| **Clasificación** | 🟦 **DASHBOARD** |

#### D-06.3 · Qué medios de cobro se ofrecen, se retiran o se renegocian

| | |
|---|---|
| **Qué se decide** | Qué formas de pago se mantienen en el checkout, cuál se retira por tasa de rechazo y con qué pasarela se renegocia |
| **Quién decide** | Gerente General con Administración |
| **Periodicidad** | **Semestral** |
| **Consecuencia de errar** | Mantener un medio que rechaza una parte de los cobros es perder ventas en el último paso, cuando el cliente ya decidió comprar; retirar el que más factura es peor |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_flujo_caja` ya trae los 176 intentos fallidos con su motivo normalizado |

---

### 3.2 OE-07 · Rentabilidad por Volumen y Rotación

> *Maximizar los márgenes de beneficio no solo a través del precio unitario, sino impulsando la alta
> rotación de inventario y el volumen masivo de transacciones.*

Es el objetivo con más decisiones porque es donde la dirección tiene las palancas más directas:
qué se vende, a qué precio, con cuánto descuento y con cuánto capital detrás. El contexto que las
condiciona: **$22,02 M de inventario valorizado** frente a **$5,72 M de venta anual** — el capital
parado es varias veces el flujo.

#### D-07.1 · Qué productos se descontinúan

| | |
|---|---|
| **Qué se decide** | Qué referencias se dejan de comprar y se liquidan, y cuáles se sostienen pese a rotar poco |
| **Quién decide** | Gerente General con el Jefe de Compras |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Liquidar un producto estacional justo antes de su temporada destruye margen que ya estaba comprado; no liquidar el que no rota deja capital enterrado en las **387 variantes sin una sola venta** |
| **Clasificación** | 🟦 **DASHBOARD** — margen, rotación y días sin venta salen de `fact_venta_linea`, `dim_producto` y `fact_movimiento_inventario` |

#### D-07.2 · Política de precio por categoría

| | |
|---|---|
| **Qué se decide** | En qué categorías se sube el precio, en cuáles se sostiene y en cuáles se defiende con volumen |
| **Quién decide** | Gerente General |
| **Periodicidad** | **Semestral** |
| **Consecuencia de errar** | El ticket de $1.400,06 lo produce el **precio unitario ($276,36)**, no la cantidad: una subida mal puesta no se compensa con volumen, porque el volumen por pedido no es la palanca de este negocio |
| **Clasificación** | 🟦 **DASHBOARD** — con una salvedad declarada: el margen se computa contra `producto_variante.costo` **vigente**, no histórico (§8.3 del diseño del ETL) |

#### D-07.3 · Cuánto descuento se autoriza y sobre qué categorías

| | |
|---|---|
| **Qué se decide** | El techo de descuento del próximo trimestre —cupón y promoción— y sobre qué categorías se concentra |
| **Quién decide** | Gerente General con Marketing |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | El descuento arrastra su IVA: **el total cae 1,15× el descuento**. Un techo mal puesto se come el margen sin traer volumen, y el efecto no se ve hasta el cierre |
| **Clasificación** | 🟦 **DASHBOARD** — las dos capas de descuento están separadas y prorrateadas por producto en `fact_venta_linea` |

#### D-07.4 · Qué capital inmovilizado se libera

| | |
|---|---|
| **Qué se decide** | Qué posiciones de sobre-stock se rebajan, se transfieren entre bodegas o se devuelven, para liberar caja |
| **Quién decide** | Gerente General con el Jefe de Bodega (que participa **en cantidades**, sin ver montos) |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Rebajar lo que sí rota destruye margen sin necesidad; no rebajar lo que no rota mantiene la bodega en ~6,8 años de rotación |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_stock_mensual` da la evolución; el sobre-stock del presente se consulta al informe simple OTD-INV-08, que ya existe en PostgreSQL |

#### D-07.5 · Nivel objetivo de stock por producto para el próximo trimestre

| | |
|---|---|
| **Qué se decide** | Los topes mínimo y máximo con los que la bodega opera el próximo trimestre, producto por producto |
| **Quién decide** | Gerente General con Bodega y Compras |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Un mínimo bajo produce quiebre en lo que rota —venta perdida que el sistema **ni siquiera registra**—; un máximo alto entierra más capital sobre los $22,02 M ya parados |
| **Clasificación** | 🟩 **IA — previsión de demanda.** El nivel objetivo de stock es la demanda esperada del período de reposición más un colchón; la demanda esperada **no está en los datos**: hay que inferirla |

---

### 3.3 OE-08 · Fidelización y Retención de Clientes

> *Construir relaciones a largo plazo mediante programas de lealtad y condiciones comerciales
> preferenciales que retengan a los clientes recurrentes, quienes concentran la mayor parte de la
> facturación del negocio.*

La premisa está verificada y es la razón económica del objetivo: **el top 10 % de clientes (7 de 69)
pone el 49,34 % del ingreso y el top 20 % (14) el 68,76 %**, y **65 de 72 clientes tienen más de un
pedido**. Perder a uno de esos catorce cuesta más que captar a varios nuevos.

#### D-08.1 · Sobre qué clientes se actúa para recuperarlos, y con qué gesto

| | |
|---|---|
| **Qué se decide** | Qué clientes entran en la lista de recuperación de la quincena y qué gesto recibe cada uno: llamada del vendedor, cupón dirigido o condición preferente |
| **Quién decide** | Gerente General con el Jefe de Ventas |
| **Periodicidad** | **Quincenal** — es la única decisión de este nivel con cadencia corta, porque una relación se recupera antes de que se enfríe, no en la revisión trimestral |
| **Consecuencia de errar** | Dos errores de signo contrario y de costo distinto. **Falso positivo**: gastar el incentivo en quien iba a volver solo — barato. **Falso negativo**: perder a un cliente que pone el 7 % de la facturación — caro, y no se recupera |
| **Clasificación** | 🟩 **IA — alerta de abandono.** «Este cliente está dejando de comprar» **no está registrado en ninguna parte**: nadie se da de baja, no hay contrato que cancelar, no hay un campo que diga «se fue». Es un estado latente que solo se puede inferir del patrón. **Ver el veredicto de viabilidad en §5.2: sobre estos datos la inferencia es débil y la pantalla debe decirlo** |

#### D-08.2 · A quién se le reconoce la condición de cliente preferente

| | |
|---|---|
| **Qué se decide** | El corte de valor a partir del cual un cliente recibe condiciones diferenciadas — el criterio del programa de lealtad que hoy **no existe como capacidad de sistema** (§6.2 de la base estratégica) |
| **Quién decide** | Gerente General |
| **Periodicidad** | **Anual**, con revisión semestral del corte |
| **Consecuencia de errar** | Dar condiciones preferentes a quien no las sostiene regala margen; no reconocer a los 14 que ponen dos tercios del ingreso los deja expuestos a la competencia sin defensa |
| **Clasificación** | 🟦 **DASHBOARD** — la curva de valor acumulado del cliente es un `GROUP BY` sobre `fact_pedido`. **Salvedad de alcance**: el tablero *informa* el corte; *ejecutarlo* exige construir el programa de lealtad, que es un bloque nuevo de base de datos, backend y pantallas, no un tablero |

#### D-08.3 · Qué causa de reclamo se ataca el próximo trimestre y con qué recurso

| | |
|---|---|
| **Qué se decide** | Dónde se pone el esfuerzo de posventa: más agentes en una cola, cambio de proveedor de una familia, o corrección de la ficha de producto que genera la expectativa equivocada |
| **Quién decide** | Gerente General con el Jefe de Soporte |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Mover agentes a la cola equivocada empeora el SLA de la que sí importaba, y el efecto sobre el cliente es inmediato |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_ticket` sirve cinco preguntas con 248 filas |

#### D-08.4 · Qué productos se retiran o se renegocian por devolución y calificación

| | |
|---|---|
| **Qué se decide** | Qué referencias salen del catálogo, se renegocian con el proveedor o se corrigen en su descripción, por su tasa de devolución y su calificación |
| **Quién decide** | Gerente General con Compras y Ventas |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Sostener un producto que devuelve dinero **y** quema reputación paga dos veces: el reembolso y la reseña |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_devolucion_linea` + `fact_resena`, con la trampa C4.4 declarada (no unir a `dim_producto`) |

---

### 3.4 OE-09 · Eficiencia Operativa

> *Reducir los costos internos y los tiempos de ejecución mediante la automatización de los procesos
> logísticos y de despacho.*

Los tiempos están medidos de punta a punta; de los costos internos **solo existe el flete**
($32.723,25 en 2.848 envíos). Las decisiones se formulan sobre lo que hay, y la ausencia se declara.

#### D-09.1 · Con qué transportistas se renueva contrato y a cuál se le retira volumen

| | |
|---|---|
| **Qué se decide** | El reparto de volumen entre los cinco transportistas para el próximo semestre, y a cuál se le retira |
| **Quién decide** | Gerente General con el Jefe de Logística |
| **Periodicidad** | **Semestral** (ciclo de renovación) |
| **Consecuencia de errar** | Pagar por un servicio que incumple la fecha prometida al cliente; o cambiar de transportista por ruido estadístico y perder una relación que funcionaba. **La corrección C3C.1 es directamente relevante**: un error de zona horaria mueve el tránsito medio de 3,98 a 3,77 días y cambia de lado a los envíos que estaban justo en el corte |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_envio`, con las tres restas ya convertidas de zona en el ETL |

#### D-09.2 · Dónde se refuerza la operación interna

| | |
|---|---|
| **Qué se decide** | Qué etapa del ciclo del pedido recibe personal o turno adicional: la cola de bodega, el picking, el despacho o la última milla |
| **Quién decide** | Gerente General con Logística y Bodega |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Reforzar la etapa que no es el cuello. **Es el riesgo que C2.7 documenta con nombre y cifra**: las cuatro etapas se miden sobre 2.868 / 2.856 / 2.727 / 3.696 pedidos, y presentarlas sin su denominador hace subir o bajar un promedio sin que nada haya cambiado en la operación |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_pedido`, con `pedidos_medidos` obligatorio en cada fila |

#### D-09.3 · Si se revisa la tarifa de envío que se cobra al cliente, por zona

| | |
|---|---|
| **Qué se decide** | Qué zonas se re-tarifan, cuáles se subsidian a propósito y cuáles dejan de subsidiarse |
| **Quién decide** | Gerente General con Administración |
| **Periodicidad** | **Semestral** |
| **Consecuencia de errar** | Subsidiar la zona cara sin saberlo, o expulsar con una tarifa alta a la zona que más pedidos aporta. **C3C.2 es el aviso**: 24 envíos sin tarifar leídos como «envíos gratis» dejan el último mes de la serie un **22 % más barato** y sugieren una bajada de tarifas que no ocurrió |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_envio` excluyendo `sin_tarifa = 1`, y **declarando cuántos excluyó** |

#### D-09.4 · Dónde se ataca la pérdida física

| | |
|---|---|
| **Qué se decide** | Qué causa de pérdida se ataca: la merma de bodega, la incidencia de entrega que termina en devolución al almacén, o el retorno del RMA |
| **Quién decide** | Gerente General con Bodega y Logística |
| **Periodicidad** | **Trimestral** |
| **Consecuencia de errar** | Atacar la pérdida pequeña y dejar la grande. **C3B.1 es el aviso más caro del proyecto**: filtrar la merma por `naturaleza='ajuste'` en vez de por `es_ajuste_real` multiplica el sobrante por **380×** y pinta un almacén descontrolado sin que falle ninguna suma |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_movimiento_inventario` con `es_ajuste_real` + `fact_novedad_envio` + `fact_devolucion` |

---

### 3.5 OE-10 · Liderazgo en Decisiones Basadas en Datos

> *Consolidar una cultura de gestión analítica que aproveche el historial operativo para anticipar la
> demanda y respaldar cada decisión gerencial con información verificable.*

La base estratégica declara este objetivo **CON HUECO** en §6.3: «respaldar con información
verificable» está cubierto, pero **«anticipar la demanda» no tiene ningún táctico predictivo**.
D-10.1 es exactamente ese hueco, y es la razón por la que el primer modelo de IA existe.

#### D-10.1 · La previsión con la que se fijan las metas del próximo período

| | |
|---|---|
| **Qué se decide** | La cifra de venta esperada del próximo mes y trimestre, por categoría, con la que se fijan las metas de los siete departamentos en `meta_venta` |
| **Quién decide** | Gerente General |
| **Periodicidad** | **Mensual** |
| **Consecuencia de errar** | Una meta inalcanzable desmotiva al equipo y contamina el indicador de OTD-VEN-15 durante todo el período; una meta baja deja dinero sobre la mesa y no se detecta hasta el cierre. Hoy la meta se fija **sin previsión**, que es literalmente lo que la visión se comprometió a superar |
| **Clasificación** | 🟩 **IA — previsión de demanda.** El próximo mes no está en los datos |

#### D-10.2 · Qué privilegios de acceso se revocan o se refuerzan

| | |
|---|---|
| **Qué se decide** | Qué cuentas se suspenden, qué rol se recorta y dónde se aprieta el control interno, a partir de quién hizo qué y de quién intentó entrar y falló |
| **Quién decide** | Administrador con el Gerente General |
| **Periodicidad** | **Mensual** |
| **Consecuencia de errar** | Dejar abierta una puerta sobre un sistema donde la seguridad vive **en el motor** (9 roles, RLS, horario, GRANT por columna); o cerrar de más y bloquear la operación de un turno entero |
| **Clasificación** | 🟦 **DASHBOARD** — **DATO SENSIBLE, solo ADMIN y GERENTE** |

#### D-10.3 · Si la información con la que se está decidiendo es confiable

| | |
|---|---|
| **Qué se decide** | Qué tablero se congela o se marca como no confiable porque su carga no cuadró, y si la decisión que dependía de él se aplaza |
| **Quién decide** | Administrador con el Gerente General |
| **Periodicidad** | **Mensual**, y tras cada corrida fallida del ETL |
| **Consecuencia de errar** | Decidir sobre una cifra plausible y equivocada. **Es el riesgo central de todo este sistema**, documentado 33 veces en `CORRECCIONES_DISENO_ETL.md`, y el único que no se puede detectar mirando el tablero: hay que mirar la bitácora de la corrida |
| **Clasificación** | 🟦 **DASHBOARD** — sobre `etl_ejecucion`, la bitácora que el pipeline ya escribe |

---

### 3.6 OE-11 · Excelencia en la Cadena de Abastecimiento

> *Fortalecer la alianza con proveedores para asegurar disponibilidad ininterrumpida, calidad y
> cumplimiento de plazos, operando con el menor costo posible.*

Es el centro de costo dominante: **$22.467.387,27 facturados en compras**, **$16.084.462,74
pagados**, **$6.382.924,53 de saldo abierto**. Cada punto porcentual de mejora aquí vale más que en
cualquier otro objetivo.

#### D-11.1 · El plan de compra del próximo trimestre

| | |
|---|---|
| **Qué se decide** | Qué se compra, cuánto y cuándo se emite cada orden, por familia de producto |
| **Quién decide** | Jefe de Compras con el Gerente General |
| **Periodicidad** | **Trimestral**, con revisión mensual |
| **Consecuencia de errar** | Por defecto, quiebre de stock y venta perdida que **el sistema no registra**; por exceso, más capital sobre un inventario que ya equivale a ~6,8 años de rotación. Con el ciclo de compra en **10,81 días de media**, el error no se corrige dentro del mismo mes |
| **Clasificación** | 🟩 **IA — previsión de demanda.** Comprar es apostar por una demanda futura; el pasado dice cuánto se vendió, no cuánto se venderá |

#### D-11.2 · A qué proveedor se concentra la compra de cada familia, y a cuál se le retira

| | |
|---|---|
| **Qué se decide** | El reparto de la compra entre los 11 proveedores por familia de producto, y con cuál se corta |
| **Quién decide** | Jefe de Compras con el Gerente General |
| **Periodicidad** | **Semestral** |
| **Consecuencia de errar** | Concentrar en el que incumple. **C5.2 muestra que el ranking se invierte por completo** según cómo se cuente lo «incompleto»: Comercial El Costeno pasa de **mejor proveedor (99,71 %) a PEOR (91,77 %)** por 4 órdenes que canceló Compras. La conclusión «dejemos de comprarle» sería exactamente la contraria a la correcta |
| **Clasificación** | 🟦 **DASHBOARD** — con el filtro `alcance` en `entregadas` por defecto |

#### D-11.3 · Qué condiciones de pago se renegocian

| | |
|---|---|
| **Qué se decide** | Con qué proveedores se renegocia el plazo de crédito y cómo se calendariza el pago de los $6,38 M de saldo abierto |
| **Quién decide** | Gerente General con Administración |
| **Periodicidad** | **Semestral** |
| **Consecuencia de errar** | Pagar antes de tiempo regala liquidez que el negocio necesita; pagar tarde cuesta el crédito comercial, que es la financiación más barata que tiene |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_orden_compra` (CxP) + `fact_flujo_caja` (egresos con su desvío de vencimiento) |

#### D-11.4 · Qué reclamo de calidad se escala al proveedor y con qué resolución

| | |
|---|---|
| **Qué se decide** | Qué lotes defectuosos se devuelven, y si se exige nota de crédito o reposición |
| **Quién decide** | Jefe de Compras |
| **Periodicidad** | **Mensual** |
| **Consecuencia de errar** | Absorber el costo de un defecto que es del proveedor. **Salvedad de muestra, obligatoria en pantalla**: hoy hay **6 resoluciones** sobre 11 proveedores y 19 meses — es un tablero de seguimiento de casos, no una base para un juicio estadístico sobre un proveedor |
| **Clasificación** | 🟦 **DASHBOARD** — `fact_devolucion_proveedor` + `fact_compra_linea`, con `origen ∈ {rma, recepcion}` tomado del CHECK (C4.7) |

---

## 4. Especificación de los tableros

**Siete tableros** cubren las 19 decisiones de dashboard. El criterio de agrupación es doble: por
objetivo estratégico (un director mira su objetivo, no una tabla) **y por corte financiero**, porque
en ClickHouse la segregación no la respalda el motor —no hay GRANT por columna— y **el único corte
posible es la RUTA**. Por eso T-4 y T-5 están separados aunque sirvan al mismo objetivo: uno lleva
dinero y el otro no.

| Tablero | Objetivo | Decisiones | Roles | ¿Lleva dinero? |
|---|---|---|---|---|
| **T-1** Omnicanal | OE-06 | D-06.1, D-06.2, D-06.3 | ADMIN, GERENTE, ANALISTA | Sí |
| **T-2** Rentabilidad y Rotación | OE-07 | D-07.1 … D-07.4 | ADMIN, GERENTE, ANALISTA | Sí |
| **T-3** Cliente y Posventa | OE-08 | D-08.2, D-08.3, D-08.4 | ADMIN, GERENTE, ANALISTA, SOPORTE (parcial) | Sí |
| **T-4** Operación y Última Milla | OE-09 | D-09.1, D-09.2, D-09.4 | ADMIN, GERENTE, ANALISTA, DESPACHO, BODEGA (su tramo) | **No** |
| **T-5** Costo de la Operación | OE-09 | D-09.3 | ADMIN, GERENTE, ANALISTA | Sí |
| **T-6** Abastecimiento | OE-11 | D-11.2, D-11.3, D-11.4 | ADMIN, GERENTE, COMPRAS, ANALISTA | Sí |
| **T-7** Gobierno del Dato | OE-10 | D-10.2, D-10.3 | **ADMIN, GERENTE únicamente** | No |

> **Tablas nuevas para los siete tableros: CERO.** Con ~64.000 filas en el almacén, ClickHouse agrega
> en milisegundos; una tabla preagregada sería una segunda verdad que mantener sin ganar nada. Los
> tres elementos que **no** salen del almacén (carritos abandonados, auditoría y accesos) se resuelven
> llamando a los informes SIMPLES que ya existen en PostgreSQL, y se declara en cada caso.

---

### T-1 · Tablero Omnicanal

**Decisiones**: D-06.1, D-06.2, D-06.3 · **Roles**: ADMIN, GERENTE, ANALISTA

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Participación de la venta por canal, mes a mes | **Serie temporal apilada al 100 %** | `fact_pedido` | Directa: `GROUP BY mes, canal WHERE es_cancelado = 0`. `mes` viene precalculado |
| Ticket promedio por canal | **Serie temporal, 3 líneas** | `fact_pedido` | Directa |
| Cliente omnicanal | **Semáforo + número** | `fact_pedido` | Cliente con pedidos en web **y** en canal interno, sobre clientes con pedido en el período. `countDistinct` con `if` |
| Embudo del recorrido | **Embudo de 5 pasos** | `fact_pedido` | `countIf(fecha_pagado IS NOT NULL)`, etc. **Cada paso declara su denominador** (C2.7) |
| Carrito abandonado | **Tarjeta + enlace** | ⚠️ **PostgreSQL**, informe OTD-VEN-08 | El almacén **no tiene grano de carrito**. 290 carritos no justifican una tabla: la pantalla hace una segunda llamada al informe simple ya construido |
| Cobros fallidos por motivo y mes | **Barras apiladas** | `fact_flujo_caja` | `sentido='ingreso' AND estado='fallido'`. ⚠️ **La fecha de estas filas es la del INTENTO** (`fecha_es_intento = 1`, C2.1): la leyenda debe decirlo |
| Mezcla de forma de pago | **Áreas apiladas** | `fact_flujo_caja` | Directa |

**KPI de cabecera**: venta del período · ticket medio · % de la venta por web · tasa de rechazo del
cobro en línea · clientes omnicanales.
**Filtros**: rango de meses · canal · categoría.

---

### T-2 · Tablero de Rentabilidad y Rotación

**Decisiones**: D-07.1, D-07.2, D-07.3, D-07.4 · **Roles**: ADMIN, GERENTE, ANALISTA

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Margen por categoría y mes | **Serie temporal + barras** | `fact_venta_linea` | Directa: `margen`, `venta_neta`, `costo_total` ya calculados. **Salvedad obligatoria**: costo **vigente**, no histórico |
| Matriz margen × rotación | **Dispersión de 4 cuadrantes** | `fact_venta_linea` + `fact_stock_mensual` | Eje X: unidades vendidas / stock medio del período. Eje Y: `margen_pct`. Los cuadrantes son literalmente la decisión D-07.1 |
| Producto hueso | **Tabla de rezagados** | `dim_producto` ⟕ `fact_venta_linea` + `fact_movimiento_inventario` | `LEFT ANTI JOIN` para las **387 sin venta**; días sin venta desde la última salida del kardex |
| Descuento entregado por mes y categoría | **Barras apiladas de 2 capas** | `fact_venta_linea` | `descuento_promocion` + `descuento_cupon_prorrateado`. ⚠️ 6 pedidos van marcados `excepcion_descuento = 1` (C1.2) y la fila los cuenta aparte |
| Descuento contra margen | **Doble eje** | `fact_venta_linea` | % de descuento sobre venta bruta contra `margen_pct`, por mes |
| Capital inmovilizado por mes | **Serie temporal** | `fact_stock_mensual` | Directa. **Salvedad obligatoria**: volumen valorizado a **moneda constante**, no valor histórico |
| Sobre-stock del presente | **Tabla + enlace** | ⚠️ **PostgreSQL**, informe OTD-INV-08 | `fact_stock_mensual` **no lleva mínimo ni máximo**; los topes son del presente y ya se consultan por pantalla |

**KPI**: venta neta · margen absoluto y % · descuento entregado y su peso sobre el margen · capital
al cierre · variación mes a mes.
**Filtros**: rango de meses · categoría · marca · bodega.

---

### T-3 · Tablero de Cliente y Posventa

**Decisiones**: D-08.2, D-08.3, D-08.4 · **Roles**: ADMIN, GERENTE, ANALISTA; SOPORTE solo en el
bloque de tickets y devoluciones

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Curva de valor del cliente (Pareto) | **Curva acumulada + corte móvil** | `fact_pedido` + `dim_cliente` | `sum(total)` por cliente, ordenado desc., acumulado con `sum() OVER`. El corte que el usuario mueve **es** la decisión D-08.2 |
| Cliente nuevo vs. recurrente por mes | **Barras apiladas + línea de %** | `fact_pedido` | Primera compra por cliente con `min(mes) OVER (PARTITION BY cliente_id)`; el mes clasifica. **Esto cierra el propuesto OTD-VEN-18 sin tabla nueva** |
| Reclamos por categoría y su tiempo de resolución | **Barras + caja** | `fact_ticket` | ⚠️ Dos salvedades ya registradas: `'sin_categoria'` para el ticket sin clasificar (C4.3) y **«resuelto» ≠ «cerrado»** — los tiempos miden sobre 76, no sobre 120 (C4.5) |
| Devolución por producto y motivo | **Ranking + composición** | `fact_devolucion` + `fact_devolucion_linea` | Directa. `reingresa_stock` distingue lo que vuelve a venderse |
| Calificación por producto | **Ranking con nota media** | `fact_resena` | ⚠️ **JAMÁS unir a `dim_producto`** (C4.4): la dimensión es por variante y multiplicaría 344 → 347 sin error |
| Producto que reclama y devuelve a la vez | **Dispersión** | `fact_ticket` + `fact_devolucion_linea` | Unión por `producto_variante_id`; es la decisión D-08.4 en una sola vista |

**KPI**: clientes activos del período · % del ingreso del top 20 % · clientes nuevos del mes · SLA
cumplido · % de venta devuelta.
**Filtros**: rango de meses · categoría de producto · categoría de ticket.

---

### T-4 · Tablero de Operación y Última Milla — **sin dinero**

**Decisiones**: D-09.1, D-09.2, D-09.4 · **Roles**: ADMIN, GERENTE, ANALISTA, **DESPACHO**, **BODEGA**
(su tramo)

Este tablero existe separado de T-5 por una razón concreta: **es el único que Despacho y Bodega
pueden abrir**, y lo único que lo garantiza es que su consulta no selecciona un solo importe y que
su ruta va enumerada por nombre en `SecurityConfig`.

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Cumplimiento de la fecha prometida por transportista | **Barras comparadas** | `fact_envio` | Directa: `entregado_a_tiempo`. Viaja **NULL, nunca 0**, cuando falta una fecha; el denominador son los 2.723 con promesa medible |
| Días de tránsito por transportista y zona | **Caja y bigotes** | `fact_envio` | Directa. Las tres restas ya vienen convertidas de zona horaria en el ETL (C3C.1) |
| Tiempo por etapa del ciclo | **Barras horizontales apiladas** | `fact_pedido` | Los 4 tramos. ⚠️ **Cada fila declara `pedidos_medidos`** (2.868 / 2.856 / 2.727 / 3.696) — sin eso el tablero miente sobre dónde está el cuello (C2.7) |
| Incidencias de entrega por tipo y desenlace | **Matriz tipo × desenlace** | `fact_novedad_envio` | ⚠️ Lista blanca `{reprogramada, devuelto_almacen, sin_resolver}` tomada de **los datos**, no del diseño (C3C.3) |
| Merma y sobrante por motivo | **Barras por motivo, en unidades** | `fact_movimiento_inventario` | ⚠️ **Filtrar por `es_ajuste_real = 1`, JAMÁS por `naturaleza='ajuste'`** (C3B.1: factor 380×) |
| Devoluciones al almacén por incidencia | **Embudo** | `fact_novedad_envio` + `fact_devolucion` | Directa |

**KPI**: % de entregas a tiempo · tránsito medio en días · ciclo total medio · incidencias abiertas ·
unidades de merma.
**Filtros**: rango de meses · transportista · zona · bodega.

---

### T-5 · Tablero de Costo de la Operación — **con dinero**

**Decisión**: D-09.3 · **Roles**: ADMIN, GERENTE, ANALISTA. **DESPACHO y BODEGA quedan fuera por
ruta**

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Serie mensual del costo de envío por zona | **Serie temporal** | `fact_envio` | ⚠️ **Excluir `sin_tarifa = 1` y DECIR cuántos excluyó** (C3C.2: incluirlos abarata el último mes un 22 %) |
| Costo por kilo por transportista y zona | **Mapa de calor** | `fact_envio` | `costo_por_kg` viaja **NULL** en los no tarifados, nunca 0 |
| Costo del retorno: reembolsos por vía y motivo | **Barras apiladas** | `fact_devolucion` | ⚠️ Usa `monto_reembolsado` de la cabecera —el único que trae la **vía**— y saca la columna «sin asiento» (C4.1: 86 vs. 85, $169,70) |
| Costo logístico total sobre la venta | **Serie temporal con % ** | `fact_envio` + `fact_pedido` | Ratio mensual |

**KPI**: costo de envío del período · costo medio por envío · costo por kilo · reembolsos pagados ·
costo logístico como % de la venta.
**Filtros**: rango de meses · zona · transportista.

---

### T-6 · Tablero de Abastecimiento

**Decisiones**: D-11.2, D-11.3, D-11.4 · **Roles**: ADMIN, GERENTE, COMPRAS, ANALISTA

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Gasto de compra por proveedor y mes | **Barras apiladas** | `fact_orden_compra` | ⚠️ **Agrupar por `toStartOfMonth(fecha_factura)`, NO por `mes`** (C5.1: el mes de la orden desplaza **$4,6 M** sin descuadrar el total) |
| Ficha comparativa del proveedor | **Tabla-radar de 4 ejes** | `fact_orden_compra` + `fact_compra_linea` | Costo, plazo (`dias_ciclo_real`), cumplimiento (`cumplio_promesa`) y calidad (`pct_rechazo`). Es literalmente D-11.2 en una vista |
| Entrega incompleta por proveedor | **Barras con filtro de alcance** | `fact_compra_linea` | ⚠️ Filtro `alcance` con valor por defecto `entregadas` (C5.2: con «todas», el mejor proveedor pasa a ser el peor) |
| Rechazo en puerta por proveedor y motivo | **Matriz** | `fact_compra_linea` | ⚠️ `pct_rechazo` sobre **lo que llegó** (`recibida + rechazada`), no sobre lo pedido (C3.2) |
| Evolución del costo de compra por producto | **Serie por producto** | `fact_compra_linea` | ⚠️ `lagInFrame` **rellena la primera fila con el DEFECTO del tipo, no con NULL**: la frontera se marca con `row_number() > 1`, jamás con `precio_previo != 0` |
| Deuda y calendario de vencimientos | **Línea de tiempo + semáforo** | `fact_orden_compra` (CxP) | Directa |
| Puntualidad de nuestro pago | **Barras + desvío medio** | `fact_flujo_caja` | `sentido='egreso'`, `dias_desvio_vencimiento` (negativo = anticipado) |
| Recuperación de defectuosos | **Tabla de casos** | `fact_devolucion_proveedor` | ⚠️ **Declarar la muestra en pantalla: 6 resoluciones.** `origen ∈ {rma, recepcion}` del CHECK, no del diseño (C4.7) |

**KPI**: gasto del período · saldo abierto en CxP · % de órdenes que cumplieron plazo · ciclo medio
en días · % de unidades rechazadas.
**Filtros**: rango de meses · proveedor · categoría · alcance.

---

### T-7 · Tablero de Gobierno del Dato — **DATO SENSIBLE**

**Decisiones**: D-10.2, D-10.3 · **Roles**: **ADMIN y GERENTE únicamente**

Es el único tablero que **no se apoya en las 19 tablas del almacén**, y la razón está declarada: la
auditoría y los accesos son informes SIMPLES ya construidos en PostgreSQL (7.073 y ~1.537 registros),
y su pregunta es de consulta filtrada, no de barrido agregado del histórico. Llevarlos al almacén
sería crear dos tablas para no ganar nada.

| Elemento | Visualización | Fuente | Transformación |
|---|---|---|---|
| Acciones por usuario, tabla y mes | **Mapa de calor** | ⚠️ **PostgreSQL**, informe OTD-GER-08 | Directa. ⚠️ El corte lo hace **la RUTA**, no el motor: `grp_analista` **sí lee `log_auditoria`** (script 19) |
| Intentos de acceso fallidos por motivo y origen | **Serie + tabla** | ⚠️ **PostgreSQL**, informe OTD-GER-09 | Aquí motor y ruta **sí coinciden**: solo `grp_administrador` y `grp_gerente` leen `log_acceso` |
| Salud de la última corrida del ETL | **Semáforo por tabla + histórico** | **`etl_ejecucion`** (bitácora, ya existe en `retailmind_dwh`) | Estado, filas, excepciones y duración por tarea. **Este panel es la decisión D-10.3 entera** |
| Antigüedad del dato que se está mirando | **Tarjeta** | `etl_ejecucion` + `fecha_carga` de cada tabla | Distancia entre la última corrida y ahora |

**KPI**: acciones auditadas del mes · intentos fallidos · tablas publicadas en la última corrida ·
tablas con excepciones · antigüedad del dato.

---

## 5. Los dos modelos de inteligencia artificial

---

### 5.1 Modelo 1 — Previsión de demanda

Alimenta **D-10.1** (metas del período), **D-11.1** (plan de compra) y **D-07.5** (nivel objetivo de
stock). Cierra el hueco de traza declarado en §6.3 de la base estratégica y realiza el propuesto
**OTD-GER-13**.

#### 5.1.1 Verificación de viabilidad contra los datos reales

**Consulta 1 — la serie total, 19 meses, unidades netas de cancelados.**

| Métrica | Valor |
|---|---|
| Meses observados | **19** (2025-01 → 2026-07) |
| Media mensual | **1.045,2 uds** |
| Desviación típica | **289,9 uds** |
| **Coeficiente de variación** | **0,277** |
| Tendencia lineal | **+23,2 uds/mes**, con **R² = 0,203** |
| Autocorrelación de orden 1 | **0,364** |

Un CV de 0,277 sobre 19 puntos es **señal suficiente**: la serie no es ruido. El R² bajo de la recta
no dice «no hay tendencia», dice que **la tendencia no es una recta** — y efectivamente no lo es (ver
5.1.2).

**Consulta 2 — ¿hay estacionalidad estimable?** Ésta es la pregunta que decide el algoritmo.

| Mes del año | Observaciones | Valores (uds) |
|---|---|---|
| Enero | 2 | 427 / 907 |
| Febrero | 2 | 767 / 877 |
| Marzo | 2 | 1.069 / 1.179 |
| Abril | 2 | 1.069 / 1.344 |
| Mayo | 2 | 1.369 / 1.567 |
| Junio | 2 | 950 / 1.000 |
| Julio | 2 | 726 / 996 |
| **Agosto** | **1** | 868 |
| **Septiembre** | **1** | 917 |
| **Octubre** | **1** | 956 |
| **Noviembre** | **1** | 1.285 |
| **Diciembre** | **1** | 1.585 |

> **Cinco de los doce factores estacionales se estimarían con UNA sola observación.** Un factor
> estimado sobre n=1 no es una estimación: es el dato. Y diciembre —el mes más alto de toda la
> serie— es uno de ellos.

Lo que **sí** se sostiene: la **forma** intraanual se repite. Sobre los 7 meses comparables entre
2025 y 2026, la correlación es **0,879** y el crecimiento medio **1,314×** (1,168× si se excluye
enero, contaminado por el arranque de la cartera).

**Consulta 3 — ¿a qué nivel de agregación hay señal?**

| Nivel | Cobertura | Variabilidad | Veredicto |
|---|---|---|---|
| **Total** | 19/19 meses | CV **0,277** | ✅ Señal clara |
| **Categoría** (las 8 reales) | **19/19 meses en las 8** | CV **0,272 – 0,332** | ✅ Señal clara y homogénea |
| Categoría residual | Ropa Mujer 9 meses / **28 uds**; Ropa Hombre 1 mes / **5 uds** | CV 1,358 / n.d. | ❌ Ruido — se excluyen o se agrupan |
| **Variante** | ver abajo | ver abajo | ⚠️ Solo el 13 % del catálogo |

**Consulta 4 — ¿cuántos productos tienen historia suficiente?**

```
variantes en el catálogo ................. 1.221
variantes con alguna venta ................  834   (68,3 %)
  con ≥12 meses de venta ..................  159   ← 13,0 % del catálogo
  con ≥6 meses ............................  446
  con ≥3 meses ............................  744
  con UN solo mes .........................   44
media de meses con venta por variante ....  7,39
media de unidades en 19 meses ............ 23,81   → 1,25 uds/mes
```

Y dentro de las 159 con historia larga, la demanda **no es intermitente**, que es lo que las hace
predecibles: **4,05 uds/mes de media**, solo **12,3 % de meses en cero**, rango 1,00 – 12,26 uds/mes.

La concentración ayuda: el **top 20 % de las variantes con venta (167) concentra el 63,15 %** de las
unidades, y el top 50 el 31,41 %. Previsión individual para 159 variantes cubre la mayor parte del
volumen que se decide comprar.

#### 5.1.2 Dos artefactos del dato que hay que declarar antes que el algoritmo

**Artefacto A — la estacionalidad está escrita en el generador.**
`retailmind/sql/postgres/60_seed_bloque_b_ventas.sql`, líneas 63 y 172-174:

```sql
sales_wt numeric[] := ARRAY[0.75,0.80,1.05,1.20,1.25,0.85,0.80,0.90,1.00,1.05,1.35,1.55];
...
wt    := sales_wt[mo];
yearf := CASE WHEN y=2026 THEN 1.18 ELSE 1.0 END;
n_ord := GREATEST(1, round(v_base * wt * yearf * (0.9+random()*0.2))::int);
```

El proceso generador es exactamente `205 · factor_mes · factor_año · U(0,9; 1,1)`. Es decir:

- La correlación interanual de **0,879** es la **reproducción de un arreglo constante**, no el
  descubrimiento de un comportamiento de mercado.
- La «tendencia» no es una tendencia: es un **escalón del +18 % en 2026**. Por eso la recta da
  R² = 0,203 mientras la forma se repite con 0,879.
- El ruido es uniforme de ±10 %, no el ruido de una demanda real.

**Consecuencia honesta, y no es que el modelo sobre**: un modelo evaluado aquí mide **el pipeline,
no el conocimiento del mercado**. Se declara en pantalla, y la métrica de aceptación se define
**relativa a una línea base** —superar al ingenuo— y no en absoluto, precisamente para que el
criterio siga siendo válido el día que entren datos reales.

**Artefacto B — ningún mes de la serie está completo, y el último menos.**
El generador coloca los pedidos en los días `1 + floor(random()*27)`, y en julio de 2026 en
`1 + floor(random()*22)`. Verificado: mayo y junio de 2026 tienen pedidos del día 1 al **27**; julio
de 2026, del 1 al **22**.

- Como el recorte de 27 días es **uniforme en los 19 meses**, no distorsiona la comparación mes a
  mes: se puede ignorar.
- El de julio de 2026 **sí** distorsiona: cubre 22 de los 27 días comparables (81,5 %). Entrenar con
  él como mes completo enseña una caída que no existe.

#### 5.1.3 Algoritmo propuesto y por qué ése

> **Descomposición explícita en tres términos, con los factores estacionales encogidos hacia 1 —
> es decir, un Holt-Winters multiplicativo REGULARIZADO—, ajustado a nivel de TOTAL y de CATEGORÍA;
> y una desagregación *top-down* por cuota histórica para las variantes.**

```
previsión(mes, categoría) = nivel · factor_mes · factor_crecimiento

  nivel            suavizado exponencial simple sobre la serie desestacionalizada
  factor_mes       ratio a la media móvil centrada, ENCOGIDO:  f̂ = (n·f + k) / (n + k)
                   con n = observaciones de ese mes (1 o 2) y k ≈ 2
  factor_crec.     razón interanual de los meses comparables

previsión(mes, variante) = previsión(mes, categoría) · cuota(variante)
  cuota            media de los últimos 6 meses, con suavizado de Laplace
```

**Por qué el encogimiento y no un Holt-Winters de libro.** Un Holt-Winters con estacionalidad de
período 12 necesita **al menos dos ciclos completos (24 meses) para inicializar sus factores**. Hay
**19**. Sin encoger, el factor de diciembre sería literalmente el único diciembre observado, y el
modelo lo proyectaría como si fuera una ley. El encogimiento hace explícito lo que la falta de
historia impone: **un mes con una observación aporta poco; uno con dos, algo más; ninguno aporta
certeza**.

**Por qué NO los alternativos** —y las tres razones son de tamaño de muestra, no de gusto:

| Alternativa | Por qué se descarta |
|---|---|
| **SARIMA(p,d,q)(P,D,Q)₁₂** | Una sola diferenciación estacional sobre 19 puntos deja **7 observaciones** para estimar los parámetros. El intervalo de confianza de cualquier coeficiente cubriría el cero |
| **Prophet** | Diseñado para series **diarias con varios años** y varias estacionalidades superpuestas. Con 19 puntos mensuales, sus *changepoints* ajustan ruido |
| **LSTM / redes recurrentes** | 19 puntos. No hay conjunto de entrenamiento |
| **Gradient boosting con variables de calendario** | Sobreajusta por construcción: hay más columnas candidatas que filas |
| **Croston / TSB (demanda intermitente)** | Sería lo correcto para las 675 variantes de venta esporádica — pero para ésas la propuesta **no es predecir individualmente**, es publicar la categoría. Anotado como oportunidad futura si el catálogo crece |

**La línea base que hay que superar, medida.** Entrenando hasta 2026-04 y prediciendo mayo, junio y
julio de 2026 sobre la serie total:

| Modelo de referencia | MAPE |
|---|---|
| **Ingenuo** (último valor observado) | **23,8 %** |
| **Ingenuo estacional con crecimiento** (mismo mes del año anterior × 1,168) | **9,3 %** |
| El mismo, con julio 2026 anualizado a sus 27 días comparables | **14,5 %** |

> **La vara está entre 9,3 % y 14,5 % según cómo se trate el mes truncado, y el tratamiento hay que
> declararlo.** El modelo propuesto solo se publica si supera la segunda línea base; si no la supera,
> **se publica la línea base**, que es más simple y más honesta.

#### 5.1.4 Variables de entrada y su origen

| Variable | Tabla del almacén | Nota |
|---|---|---|
| `cantidad`, `mes`, `categoria`, `producto_variante_id`, `es_cancelado` | **`fact_venta_linea`** | La serie. `es_cancelado = 0` |
| `categoria`, `activo`, `producto_variante_id` | **`dim_producto`** | El universo, para materializar los meses en cero de una variante que sí existía |
| `mes_inicio` | **`dim_fecha`** | Malla de meses sin huecos — el error que C3B.4 documenta al revés |
| `stock_cierre` | **`fact_stock_mensual`** | **No es una variable del modelo**: marca los meses en que la variante estuvo a cero para señalar demanda censurada (ver limitaciones) |

**Ninguna variable exógena.** No hay precio histórico, no hay tráfico web fiable en el almacén, no
hay calendario comercial local. Añadir regresores sobre 19 puntos es sobreajuste con otro nombre.

#### 5.1.5 Entrenamiento, reentrenamiento y encaje en el pipeline

- **Dónde**: una tarea nueva en `retailmind/etl/dwh/tablas/`, subclase de `TareaDerivada` — la misma
  figura que `fact_stock_mensual`, la única tabla que ya se calcula **dentro** del almacén sin volver
  a PostgreSQL.
- **Dependencia declarada**: `depende_de = {fact_venta_linea, dim_producto, dim_fecha}`. El grafo lo
  deriva `run_etl.py` de `depende_de`; **no se escribe el orden a mano**, por la razón que el propio
  orquestador declara: una segunda fuente de verdad se desincroniza en la primera tabla nueva.
- **Cadencia**: **mensual**, en la corrida siguiente al cierre de mes. Reentrenamiento **completo
  desde cero** —mismo criterio de *full refresh* que el ETL—: con 19 puntos, un ajuste incremental no
  ahorra nada y complica la reproducibilidad.
- **Herencia gratis**: publicación atómica (staging `_new` → validar → `EXCHANGE TABLES`), reintentos,
  y fila en `etl_ejecucion`. Traducido al terreno del modelo: **un modelo que no supera su backtest no
  llega a la pantalla**, porque la validación aborta y la tabla publicada no se toca.

#### 5.1.6 Validación

- **Método**: *backtest* de origen móvil (`rolling origin`) con **3 orígenes**, ventana expansiva,
  prediciendo 2026-05, 2026-06 y 2026-07.
- **Métricas**: **MAPE** (comparable entre niveles) y **MAE en unidades** (interpretable para compras).
- **Contra qué se compara**: contra las **dos** líneas base de 5.1.3, siempre las dos, siempre
  visibles.
- **Cobertura del intervalo**: aproximadamente el **80 % de los valores reales debe caer dentro de la
  banda del 80 %**. Un intervalo que acierta el 100 % es tan malo como uno que acierta el 40 %: el
  primero es inútilmente ancho.
- **Resultado inaceptable — cualquiera de los tres**:
  1. **No superar el ingenuo estacional** (MAPE > 14,5 % con el mismo tratamiento del mes truncado).
  2. MAPE **> 20 %** a nivel total, que es el umbral por debajo del cual una previsión no sirve para
     fijar una meta.
  3. **Cobertura del intervalo fuera del rango 65 % – 90 %**, que indica que la incertidumbre está
     mal calibrada — y una banda mal calibrada es peor que no tener banda.

#### 5.1.7 Dónde se guardan las predicciones

**Tabla nueva: `fact_prevision_demanda`** en `retailmind_dwh`.

**Justificación de la tabla nueva** (el encargo pide priorizar no crearlas): las 19 tablas existentes
son, sin excepción, **hechos de lo que ocurrió**. Una predicción es una fila con **fecha futura**, y
ninguna tabla del modelo admite eso sin corromper su propio criterio de aceptación —la igualdad al
centavo contra PostgreSQL—: una fila que PostgreSQL no tiene haría fallar los 44 controles. Además la
predicción arrastra columnas que ningún hecho tiene: su banda, su muestra y su error de backtest.

| Columna | Tipo | Nota |
|---|---|---|
| `mes` | `Date` | Mes **previsto** |
| `nivel` | `LowCardinality(String)` | `total` / `categoria` / `variante` |
| `categoria` | `LowCardinality(String)` | `'(todas)'` en el nivel total |
| `producto_variante_id` | `UInt32` | `0` fuera del nivel variante — **centinela, nunca NULL** en la clave |
| `sku`, `producto_nombre` | `String` | Denormalizados: la pantalla no une |
| `unidades_previstas` | `Decimal(12,2)` | |
| `limite_inferior`, `limite_superior` | `Decimal(12,2)` | Banda |
| `nivel_confianza` | `Decimal(5,2)` | 80,00 |
| `meses_historia` | `UInt8` | **La muestra viaja con el número** |
| `metodo` | `LowCardinality(String)` | `descomposicion` / `linea_base_estacional` / `top_down_categoria` |
| `mape_backtest` | `Decimal(6,2)` | El error medido para **esa** serie |
| `es_linea_base` | `UInt8` | `1` si el modelo no superó al ingenuo y se publicó la base |
| `demanda_censurada` | `UInt8` | Hubo mes con stock en cero en la historia |
| `modelo_version` | `String` | |
| `fecha_entrenamiento`, `fecha_carga` | `DateTime('America/Guayaquil')` | |

```sql
ENGINE = MergeTree
PARTITION BY toYear(mes)
ORDER BY (nivel, categoria, producto_variante_id, mes)
```

`nivel` va primero porque **toda** consulta lo filtra; `mes` va último porque el horizonte es corto
(3 meses) y no hay nada que podar.

#### 5.1.8 Cómo lo consume la aplicación

Reutiliza el patrón vigente sin excepción: `GET /api/informes/{departamento}/{informe}`, sobre único
`{items, total, page, size, resumen[], salvedad}`, método de servicio en
`@Transactional(readOnly = true)`.

| Endpoint | Roles | Sirve a |
|---|---|---|
| `GET /api/informes/gerencia/prevision-demanda` | ADMIN, GERENTE, ANALISTA | D-10.1 |
| `GET /api/informes/compras/prevision-demanda` | ADMIN, GERENTE, COMPRAS | D-11.1 y D-07.5 |

**Coste**: **0 clases Java nuevas** —los dos servicios de departamento ya existen—, 1 bloque de
definición por pantalla, 2 líneas en `SecurityConfig`. Filtros: `nivel`, `categoria`, `horizonte`
(1-3 meses), todos por lista blanca.

#### 5.1.9 Cómo se presenta el resultado

```
┌─────────────────────────────────────────────────────────────────────────┐
│ PREVISIÓN DE DEMANDA · agosto 2026            [nivel ▾] [categoría ▾]   │
├─────────────────────────────────────────────────────────────────────────┤
│  1.089 uds        ± 214           MAPE 11,4 %        19 meses           │
│  previsión        banda 80 %      del backtest       de historia        │
├─────────────────────────────────────────────────────────────────────────┤
│                                            ╭──────╮                     │
│   ────── histórico  ┄┄┄ previsión         ╱  banda ╲                    │
│  ▁▂▃▃▄▂▂▂▃▃▄▅▂▃▄▄▅▃▃┄┄┄┄┄                ╰──────╯                     │
│  2025-01 ······································· 2026-07 │ 08  09  10   │
├─────────────────────────────────────────────────────────────────────────┤
│ Categoría    Previstas    Banda 80 %      Meses hist.  MAPE   Método    │
│ Abarrotes         256     [201 – 311]          19      9,8 %  descomp.  │
│ Ropa              151     [113 – 189]          19     12,1 %  descomp.  │
│ Ropa Mujer          2     [ — ]                 9        —    sin prev. │
├─────────────────────────────────────────────────────────────────────────┤
│ ⚠ Cómo leer esto: la previsión de agosto se apoya en UNA sola           │
│   observación de agosto (2025). El factor de ese mes no es una          │
│   estimación: es el dato de un año. La banda del 80 % ya lo refleja     │
│   y por eso es más ancha que la de mayo.                                │
└─────────────────────────────────────────────────────────────────────────┘
```

Reglas de presentación, todas obligatorias:

1. **La serie histórica y la previsión van en el MISMO gráfico**, con la previsión en trazo distinto.
   Un número solo, sin la serie que lo produjo, no es interpretable.
2. **Ningún número sin banda.** Si una serie no tiene historia para una banda, la fila dice
   «sin previsión individual» y muestra la de su categoría, con su nombre.
3. **La muestra viaja en la fila**, no en una nota al pie: `meses_historia` es una columna.
4. **El error del backtest se muestra por fila**, no como cifra global: el MAPE de Abarrotes no
   describe a Electrónica.
5. **La banda se ensancha con el horizonte** y con la escasez de observaciones del mes previsto. Que
   agosto tenga la banda más ancha que mayo **es la información**, no un defecto del gráfico.

#### 5.1.10 Limitaciones que se declaran en pantalla

> **Lo que esta previsión no puede decir.**
> 1. **La historia son 19 meses.** Cada mes del calendario tiene como mucho **dos** observaciones, y
>    los de **agosto a diciembre solo una**. El factor estacional de esos cinco meses es un dato, no
>    una estimación, y la banda lo refleja siendo más ancha.
> 2. **Se prevé la VENTA, no la demanda.** Cuando un producto estuvo sin stock, la venta fue baja
>    porque no había qué vender. El sistema **no registra la venta perdida**, así que un quiebre se
>    lee como demanda baja. Las series afectadas van marcadas.
> 3. **El último mes de la serie está incompleto** y se trata aparte; el tratamiento aplicado se
>    indica junto al MAPE.
> 4. **La historia disponible es un seed simulado** con una curva mensual conocida y un escalón de
>    crecimiento fijo. El error medido describe la calidad del método sobre estos datos; **no es
>    evidencia de conocimiento del mercado real de Quevedo**.
> 5. **1.062 de 1.221 variantes no tienen previsión individual.** Se les muestra la de su categoría,
>    y la fila lo dice.

---

### 5.2 Modelo 2 — Alerta de abandono de cliente

Alimenta **D-08.1**. Realiza el propuesto **OTD-VEN-19** y cierra la mitad medible del hueco «la
retención no se mide» (§6.2 de la base estratégica).

#### 5.2.1 Verificación de viabilidad contra los datos reales

**Consulta 1 — ¿hay clientes con historia suficiente?** Sí, y es lo único que sale claramente bien.

```
clientes ................................  72
  con pedidos ...........................  69
  con ≥10 pedidos .......................  57
  con ≥3 pedidos ........................  63
  con UN solo pedido ....................   4
pedidos por cliente: media 56,9 · máximo 643
primera compra global ......... 2025-01-13
última compra global .......... 2026-07-22
```

**Consulta 2 — distribución de intervalos entre compras y su regularidad.**

```
intervalos observados ................ 3.855
  media ..............................  4,22 días
  mediana ............................  1,00 día
  percentil 90 ....................... 10 días
  percentil 99 ....................... 42 días
  máximo ............................ 158 días

por cliente (los 62 con ≥5 intervalos):
  intervalo medio ................... 10,23 días
  CV medio ..........................  1,196     ← ≈ 1
  CV mínimo / máximo ................  0,598 / 4,059
```

> **Un CV de 1,196 dice exactamente qué clase de proceso es esto: un proceso SIN MEMORIA.** Para una
> distribución exponencial el CV vale 1 exacto. Que la media de 62 clientes caiga en 1,196 significa
> que el intervalo hasta la próxima compra **no depende de cuánto lleve esperando**. Y eso tiene una
> consecuencia inmediata y dura: **no hay «regularidad propia» que romper**. Un cliente que compra
> cada 10 días de media no compra «cada 10 días»: compra con una tasa de 0,1/día, y un silencio de 30
> días le ocurre por azar con probabilidad e⁻³ ≈ 5 %.

**Consulta 3 — ¿se puede definir «abandono» con un criterio objetivo?** Aquí la respuesta es que
**no con estos datos**, y hay tres pruebas independientes.

**Prueba A — la rampa de cartera contamina toda la historia temprana.**

| Mes | Pedidos | Clientes compradores | % del mayor cliente |
|---|---|---|---|
| 2025-01 | 82 | **1** | **100,0 %** |
| 2025-02 | 146 | **1** | **100,0 %** |
| 2025-03 | 222 | 6 | 55,4 % |
| 2025-06 | 179 | 15 | 27,9 % |
| 2025-12 | 322 | 40 | 10,9 % |
| 2026-07 | 220 | 48 | 10,9 % |

**En enero y febrero de 2025 un solo cliente hizo el 100 % de los pedidos**, porque era el único
registrado (`WHERE reg <= v_ts` en el generador). Ese cliente —id 54, 297 pedidos, el segundo de la
cartera— hoy lleva **74 días de silencio, que son 43 de sus propios intervalos históricos**. Bajo una
exponencial con su tasa histórica, eso tiene probabilidad e⁻⁴³ ≈ 2·10⁻¹⁹.

> **Un modelo entrenado sobre la ventana completa aprendería que el cliente más grande de la historia
> es el que más probablemente abandona.** Es la inversión exacta de la verdad, con una señal
> aparentemente abrumadora, y sin que falle ninguna suma.

**Prueba B — el generador no permite que nadie abandone.**
`60_seed_bloque_b_ventas.sql`, líneas 185-187:

```sql
SELECT cliente_id, ... INTO v_cli, ...
FROM seed_cliente WHERE reg <= v_ts
ORDER BY -ln(random())/pop LIMIT 1;
```

Es un sorteo exponencial con peso `pop` **constante por cliente, independiente del tiempo y del
pasado**. Los clientes entran (por `reg`) pero **nunca salen**, y su tasa nunca decae. **No hay
abandono en estos datos: hay huecos de un proceso de Poisson.**

**Prueba C — el backtest, y ésta es la cifra decisiva.**
Restringiendo a la **ventana estable** (desde 2026-01, con la cartera ya asentada en 37-50
compradores/mes), entrenando 2026-01-01 → 2026-05-22 y midiendo qué pasó entre 2026-05-23 y
2026-07-22, sobre los 53 clientes con ≥3 pedidos en la ventana:

```
clientes evaluados ............................................  53
NO volvieron a comprar en los 60 días siguientes ..............   5   (9,4 %)

silencio medio al corte, medido en intervalos propios del cliente:
  de los que NO volvieron ..................................... 0,97
  de los que SÍ volvieron ..................................... 0,78

correlación entre la señal y el resultado .................... 0,039
```

> **0,039.** El mejor predictor disponible —el silencio de cada cliente medido en sus propios
> intervalos, que es exactamente lo que el encargo pide («según su propio patrón de compra»)— **no
> distingue** a quien se fue de quien volvió. Y no es un fallo de la señal: es lo que la Prueba B
> predice. Un proceso sin memoria no deja huellas del futuro en el pasado.

#### 5.2.2 Veredicto

> ⚠️ **NO VIABLE como modelo entrenado.** No existen etiquetas de abandono —nadie se da de baja, no
> hay contrato que cancelar, ninguna columna dice «se fue»—; construir la etiqueta con un corte de
> días hace que **el corte determine la respuesta** (razonamiento circular). Y aun con la etiqueta
> construida, hay **5 casos positivos**: el intervalo de confianza de la precisión de cualquier
> clasificador entrenado sobre 5 positivos cubre casi todo el rango [0, 1].

**Lo que sí se propone, y no es una retirada**: un **modelo del PROCESO** en lugar de un modelo
aprendido de los datos. Es la respuesta técnicamente correcta a un proceso de Poisson, y la única
que no finge tener información que no hay.

#### 5.2.3 Algoritmo propuesto y por qué ése

> **Alerta de supervivencia exponencial con la tasa propia de cada cliente.**

```
Para cada cliente i, sobre la ventana ESTABLE (desde 2026-01):
    λᵢ = pedidos_i / días_observados_i          (tasa diaria de compra)
    t  = días desde su última compra
    P(silencio ≥ t | λᵢ) = e^(−λᵢ·t)

    alerta si P < α          (α = 0,05 propuesto  ⇔  t > 3 · 1/λᵢ)
    nivel:  normal (P ≥ 0,10) · atención (0,05 ≤ P < 0,10) · crítica (P < 0,05)

Orden de la lista = facturación 12 meses × (1 − P)     ← valor en riesgo
```

**Por qué éste y no los candidatos obvios**:

| Alternativa | Por qué se descarta |
|---|---|
| **Regresión logística / árbol / gradient boosting** | Necesitan etiqueta, y la etiqueta no existe. Con 5 positivos, cualquier modelo con más de 2 variables memoriza |
| **BG/NBD o Pareto/NBD** (el estándar del *non-contractual churn*) | Es el modelo correcto en teoría, y su ajuste estima **4 hiperparámetros poblacionales sobre 69 clientes**. Peor: su supuesto de abandono beta-geométrico **es justamente lo que el generador no tiene** — estimaría una tasa de deserción cercana a cero y publicaría alertas vacías |
| **Modelo de supervivencia con covariables (Cox)** | Mismo problema de eventos: 5. Un Cox con 5 eventos admite ~0 covariables |
| **Regla fija «90 días sin comprar»** | Ignora el patrón propio, que es el corazón del encargo: 90 días son 9 intervalos para un cliente y medio intervalo para otro |

**Ventajas concretas del modelo de proceso, que son las que lo hacen defendible aquí**:

1. **No necesita etiquetas.** Se calcula sin conjunto de entrenamiento.
2. **Su tasa de falsa alarma se conoce de antemano**: bajo un proceso de Poisson homogéneo, la alerta
   dispara con probabilidad **≈ α** (aproximada, porque λ se estima). Es el único «modelo» de este
   documento cuyo error se puede afirmar antes de medirlo.
3. **Degrada con elegancia**: un cliente con 3 pedidos recibe una λ mala y la pantalla dice cuántos
   pedidos la sostienen.
4. **No hay que rediseñarlo** el día que los datos tengan abandono real: la misma alerta se vuelve
   progresivamente más informativa, y su lift medido sube solo.

#### 5.2.4 Variables de entrada y su origen

| Variable | Tabla | Uso |
|---|---|---|
| `cliente_id`, `fecha_pedido`, `mes`, `total`, `es_cancelado` | **`fact_pedido`** | λ, recencia y facturación |
| `nombre_completo`, `email`, `ciudad`, `activo` | **`dim_cliente`** | Identificación para la acción comercial |
| `mes_inicio` | **`dim_fecha`** | Ventana estable y malla del *sparkline* |
| `fact_ticket`, `fact_devolucion` | — | **COLUMNAS DE CONTEXTO, NO ENTRADAS DEL MODELO.** Se muestran al lado («este cliente tiene 2 reclamos abiertos») para informar el gesto comercial. **Meterlas como variables sobre 5 positivos es sobreajuste por construcción**, y se declara aquí para que nadie las añada más adelante creyendo que mejora |

#### 5.2.5 Entrenamiento, reentrenamiento y encaje en el pipeline

- **Dónde**: `TareaDerivada` en `etl/dwh/tablas/`, `depende_de = {fact_pedido, dim_cliente}`.
- **Cadencia**: **semanal** —la decisión D-08.1 es quincenal y conviene que el dato la preceda—.
  Estrictamente no hay «entrenamiento»: se reestima **un parámetro por cliente**, 69 en total. El
  cálculo completo es de milisegundos.
- **Ancla temporal, y es crítica**: la recencia se mide contra **`max(fecha_pedido)` del almacén**,
  **nunca contra la fecha de hoy del reloj**. Si el ETL se detiene una semana, medir contra el reloj
  haría que los 69 clientes cruzaran el umbral a la vez. La pantalla muestra la fecha ancla.

#### 5.2.6 Validación

- **Método**: backtest de origen móvil con **3 orígenes** dentro de la ventana estable, ventanas de
  prueba de 60 días.
- **Métricas**:
  - **Precisión@10**: de los 10 clientes con la alerta más fuerte, cuántos no compraron en los 60
    días siguientes.
  - **Lift sobre la tasa base**: precisión@10 ÷ **9,4 %**. Es la métrica que decide.
- **Resultado inaceptable**: **lift ≤ 1,0** — la alerta no supera a elegir clientes al azar.
- **Y aquí va la parte honesta**: dado que la correlación medida entre la señal y el resultado es
  **0,039**, **la expectativa razonable es que el lift salga ≈ 1,0 sobre estos datos**. El criterio de
  aceptación no se relaja por eso: **el lift medido se publica en la propia pantalla, junto al número
  de casos positivos sobre los que se midió**. Si vale 1,0, la pantalla dice que la alerta no está
  discriminando, y el gerente decide con esa información en la mano en vez de sin ella.

> Es deliberado que el modelo **traiga su propio veredicto a la pantalla**. Un modelo que oculta su
> lift es indistinguible de uno que funciona, y ése es exactamente el patrón que
> `CORRECCIONES_DISENO_ETL.md` documenta 33 veces.

#### 5.2.7 Dónde se guardan las predicciones

**Tabla nueva: `fact_alerta_cliente`** en `retailmind_dwh`.

**Justificación**: `fact_pedido` guarda las compras; la alerta es sobre la **ausencia** de compra,
que ninguna fila representa. Y tiene que ser una **foto fechada**: sin `fecha_calculo`, la alerta de
la semana pasada se pierde y el backtest del mes que viene no tiene contra qué medirse.

| Columna | Tipo | Nota |
|---|---|---|
| `fecha_calculo` | `Date` | Cuándo se calculó |
| `fecha_ancla` | `Date` | **`max(fecha_pedido)` del almacén** — contra qué se midió la recencia |
| `cliente_id` `UInt32`, `cliente_nombre` `String`, `email` `String` | | Denormalizados |
| `pedidos_ventana` | `UInt16` | **La muestra que sostiene λ** |
| `dias_observados` | `UInt16` | |
| `tasa_diaria` | `Decimal(10,4)` | λ |
| `intervalo_medio_dias` | `Decimal(8,2)` | 1/λ — la cifra interpretable |
| `dias_silencio` | `UInt16` | |
| `silencio_en_intervalos` | `Decimal(8,2)` | **La medida que se muestra**: «lleva 4,2 veces su intervalo habitual» |
| `prob_silencio` | `Decimal(8,6)` | e^(−λt) |
| `nivel_alerta` | `LowCardinality(String)` | `normal` / `atencion` / `critica` |
| `facturacion_12m` | `Decimal(14,2)` | Valor en riesgo |
| `percentil_valor` | `Decimal(5,2)` | Su lugar en la curva de Pareto |
| `reclamos_abiertos`, `devoluciones_12m` | `UInt16` | **Contexto para el gesto**, no entradas del modelo |
| `lift_backtest` | `Decimal(6,2)` | **El veredicto del modelo, en la fila** |
| `casos_positivos_backtest` | `UInt16` | La muestra del veredicto |
| `modelo_version` `String`, `fecha_carga` `DateTime('America/Guayaquil')` | | |

```sql
ENGINE = MergeTree
PARTITION BY toYear(fecha_calculo)
ORDER BY (fecha_calculo, cliente_id)
```

#### 5.2.8 Cómo lo consume la aplicación

`GET /api/informes/ventas/clientes-en-riesgo` · **ADMIN, GERENTE, VENDEDOR**.

**Con monto**: BODEGA y DESPACHO quedan fuera por ruta. VENDEDOR entra porque es quien ejecuta el
gesto comercial — y se recorta a su cartera con el mismo mecanismo de OTD-VEN-02
(`alcance: "propio"` desde el JWT). **0 clases Java nuevas**: entra en `InformesVentasService`.

#### 5.2.9 Cómo se presenta el resultado

```
┌───────────────────────────────────────────────────────────────────────────┐
│ CLIENTES EN RIESGO · datos al 22-jul-2026 (última compra registrada)      │
├───────────────────────────────────────────────────────────────────────────┤
│  9 en alerta      $412.880          lift 1,0            5 casos           │
│  de 69 clientes   facturación 12m   sobre azar          en la validación  │
│                   en riesgo         ⚠ ver abajo                          │
├───────────────────────────────────────────────────────────────────────────┤
│ Cliente        Fact. 12m   Su ritmo   Silencio   = veces    Prob.   Nivel │
│ M. Lopez        $ 78.410    c/ 9 d      67 d      7,4×      0,1 %  crítica│
│                 ▁▃▄▃▅▄▃▂▁▁___  ← compras por mes                          │
│ C. Vera         $ 61.200    c/14 d      52 d      3,7×      2,4 %  crítica│
│                 ▂▄▃▄▃▅▃▄▂▁▁__                                            │
│ J. Andrade      $ 22.900    c/21 d      44 d      2,1×      8,1 % atención│
├───────────────────────────────────────────────────────────────────────────┤
│ ⚠ Esto es una ALERTA de silencio inusual, no una predicción de abandono.  │
│   En la validación sobre esta base, la alerta NO superó al azar           │
│   (lift 1,0 sobre 5 casos). Úsela para priorizar una llamada, no para     │
│   dar por perdido a un cliente.                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

Reglas de presentación:

1. **La medida principal es «veces su intervalo propio»**, no los días. «67 días» no dice nada sin
   saber si el cliente compra cada semana o cada trimestre; «7,4 veces su ritmo» sí.
2. **El *sparkline* de compras por mes va en la fila.** Es lo que permite al gerente ver de un golpe
   si la caída es un hueco o una pendiente — y es la defensa contra el artefacto de la rampa.
3. **La lista se ordena por valor en riesgo**, no por probabilidad. Un cliente de $500 con
   probabilidad 0,1 % no es la primera llamada.
4. **El lift y su muestra van en la cabecera, no en una nota.** Si el lift es 1,0 el usuario tiene que
   verlo antes que la lista.
5. **La fecha ancla se muestra en el título.** «Datos al 22-jul-2026» impide que alguien lea la
   pantalla como si fuera de hoy.

#### 5.2.10 Limitaciones que se declaran en pantalla

> **Lo que esta alerta no puede decir.**
> 1. **No es una predicción de abandono, es una alerta de silencio estadísticamente inusual.** El
>    sistema no tiene ningún registro de un cliente que se despide: no hay baja, no hay contrato, no
>    hay campo. Un silencio largo puede ser un viaje.
> 2. **Se calcula solo sobre la ventana desde enero de 2026.** Antes, la cartera estaba creciendo
>    desde **un solo comprador**: en enero y febrero de 2025 un cliente hizo el 100 % de los pedidos.
>    Cualquier ritmo calculado con esa historia no es comparable, y usarlo señalaría a los clientes
>    más grandes como los más perdidos.
> 3. **En la validación, la alerta no superó al azar** (lift 1,0 sobre 5 casos positivos de 53
>    clientes; correlación 0,039 entre la señal y el resultado). Es una propiedad de **los datos**,
>    no del método: en el histórico disponible las compras de cada cliente ocurren a ritmo constante y
>    **nadie abandona nunca**.
> 4. **La recencia se mide contra la última compra registrada en el almacén**, no contra la fecha de
>    hoy. Si el pipeline se detiene, la pantalla no se llena de falsas alarmas — pero tampoco se
>    actualiza, y la fecha ancla del título lo dice.
> 5. **La muestra por cliente es pequeña**: la columna «pedidos» sostiene el ritmo de cada fila. Con
>    menos de 5 pedidos, el ritmo es una conjetura y la fila lo advierte.

---

### 5.3 Oportunidades de IA detectadas y NO diseñadas

Registradas para no perderlas, **sin diseñar**, conforme al alcance fijado:

| Oportunidad | Por qué podría aportar | Por qué no ahora |
|---|---|---|
| **Análisis de canasta / recomendación** | `fact_venta_linea` da 10.384 líneas con producto y pedido | Máximo **5 líneas por pedido** y 99,94 % de las líneas piden 1-4 unidades: las reglas de asociación tendrían soporte mínimo |
| **Detección de anomalías en `log_auditoria`** | 7.073 registros con autor, tabla y acción | El control interno hoy se resuelve mirando (T-7); una anomalía no supervisada sobre 10 tablas produciría más falsos positivos que revisiones |
| **Elasticidad precio-demanda** | Decidiría D-07.2 con evidencia en vez de criterio | El precio de catálogo apenas varía en el histórico sembrado: no hay variación de la que estimar una pendiente |
| **Previsión de demanda intermitente (Croston/TSB)** | Cubriría las ~675 variantes que hoy quedan sin previsión individual | Solo tiene sentido cuando el catálogo con historia crezca; hoy la respuesta correcta es publicar la categoría |

---

## 6. Arquitectura recomendada y plan de implementación

### 6.1 Dónde vive el código de los modelos

> **Recomendación: Python, dentro de `retailmind/etl/dwh/`, como dos `TareaDerivada` más — la misma
> figura que ya usa `fact_stock_mensual`.**

| Criterio | **Python en el ETL** (recomendado) | Java en el backend | Servicio aparte |
|---|---|---|---|
| Orquestación | **Gratis**: `run_etl.py` deriva el grafo de `depende_de`, con reintentos y orden reproducible | Habría que construirla | Habría que construirla |
| Publicación atómica | **Gratis**: staging `_new` → validar → `EXCHANGE TABLES`. **Un modelo que falla su backtest no llega a la pantalla** | Habría que replicarla | Habría que replicarla |
| Bitácora | **Gratis**: fila en `etl_ejecucion`, que T-7 ya consume | — | — |
| Ecosistema numérico | pandas, numpy, pyarrow **ya instalados** | Ninguno en el proyecto | Sí, pero fuera |
| Acceso a ClickHouse | `clickhouse-connect` ya configurado | Sí, pero duplicaría la conexión | Otra más |
| Piezas nuevas de despliegue | **Ninguna** | Ninguna | Un servicio, un puerto, un `docker-compose` |

**Por qué NO Java**, con precisión: el backend Spring existe para **servir** informes bajo
`@Transactional` con `SET LOCAL ROLE`, y su unidad de trabajo es la petición HTTP. Entrenar dentro de
una petición ata un proceso por lotes a un hilo web; entrenar en un `@Scheduled` reconstruiría, peor,
lo que `run_etl.py` ya hace. Y no hay ecosistema numérico en el proyecto.

**Dependencias nuevas: ninguna.** Los dos modelos caben en numpy/pandas —el de abandono es una
exponencial por cliente; el de demanda es una descomposición con encogimiento— y evitar una
dependencia nueva evita también tocar la imagen Docker del ETL. Si más adelante se prefiere
`statsmodels` para el suavizado, entra por `requirements.txt` y por la imagen, y **no antes de que el
modelo simple haya fijado su línea base**.

**Del lado de la aplicación**: los tres endpoints nuevos caben en servicios de departamento que ya
existen. **0 clases Java nuevas**, 3 bloques de definición en el frontend, 3 líneas en
`SecurityConfig`.

### 6.2 Plan por fases

El orden lo fija la Tarea 4: primero lo que ya se sostiene, después el modelo **más viable**, y al
final el débil —cuya primera entrega es, precisamente, la medición que decide si se publica—.

| Fase | Contenido | Entregable | Precede a |
|---|---|---|---|
| **E1** · Tableros de dirección | Los **7 tableros**, 19 decisiones, **0 tablas nuevas** | 7 pantallas + los endpoints; matriz rol × tablero verificada por API | Todo |
| **E2** · Previsión de demanda | `fact_prevision_demanda` + tarea + 2 endpoints | Backtest de 3 orígenes contra las 2 líneas base, **publicado en pantalla**; entrada en `CORRECCIONES_DISENO_ETL.md` | E4 |
| **E3** · Alerta de silencio | `fact_alerta_cliente` + tarea + 1 endpoint | **El backtest es el primer entregable, no el último**: si el lift sale ≤ 1,0 la pantalla se publica **con esa cifra visible**, no se oculta | E4 |
| **E4** · Cierre | Enganchar las salidas de IA a los tableros que las consumen (T-2 nivel objetivo de stock, T-6 plan de compra, T-3 recuperación) y las declaraciones en pantalla | Las 23 decisiones con su superficie | — |

**Por qué E1 va primero** aunque las fases de IA sean lo llamativo: cubre **19 de las 23 decisiones**,
no crea ni una tabla, y es la prueba de que las 19 tablas del almacén responden preguntas de
dirección y no solo de departamento. Si un tablero no se puede construir sobre ellas, es mejor
saberlo antes de entrenar nada.

**Por qué E2 antes que E3**: la verificación de la Tarea 4 es inequívoca. La demanda tiene CV 0,277,
19 meses completos y una línea base medible al 9,3 %. El abandono tiene correlación 0,039 y 5 casos
positivos.

**Regla que gobierna las cuatro fases**, heredada del ETL: cada supuesto que falle se registra en
`CORRECCIONES_DISENO_ETL.md` **con su cifra y con el tablero concreto que habría roto**, antes de dar
la fase por terminada.

---

## 7. Riesgos y decisiones abiertas

### 🔴 R-1 · El histórico es una simulación con generador conocido

Los scripts 55-84 sembraron 19 meses con una curva mensual escrita a mano y un escalón de crecimiento
fijo, y con un sorteo de cliente de peso constante. **Cualquier métrica de un modelo mide el
pipeline, no el mercado.**
**Mitigación**: los criterios de aceptación son **relativos** (superar una línea base), nunca
absolutos; las dos pantallas predictivas lo declaran en su salvedad; y este documento cita la línea
de código de cada artefacto para que nadie lo redescubra como hallazgo.

### 🔴 R-2 · La alerta de abandono probablemente no superará al azar en esta base

Correlación 0,039, 5 casos positivos. **Decisión abierta para el usuario**: (a) publicarla con su
lift visible —**recomendado**, porque el mecanismo es correcto y el veredicto queda a la vista—; o
(b) construirla y no exponerla hasta tener datos con abandono real.

### 🟠 R-3 · Demanda censurada: se prevé venta, no demanda

Cuando un producto estuvo sin stock, la venta bajó porque no había qué vender. **El sistema no
registra la venta perdida.** Un modelo entrenado sobre venta aprende el quiebre como demanda baja y
lo perpetúa comprando de menos.
**Mitigación**: marcar las series con meses a cero de stock (`demanda_censurada`). **Registrar la
venta perdida es un cambio de sistema**, no un modelo, y queda anotado como tal.

### 🟠 R-4 · El borde temporal: el seed termina y el calendario avanza

El último pedido es del **2026-07-22**. Toda pantalla predictiva debe anclar «hoy» a `max(fecha)` del
almacén **y decirlo**, o dentro de tres meses todo parecerá muerto. OTD-GER-01 ya tropezó con esto y
tuvo que emitir una fila «Día sin movimiento» a mano.

### 🟠 R-5 · La segregación financiera en ClickHouse la hace la RUTA, no el motor

ClickHouse **no tiene GRANT por columna**. Los tableros con dinero (T-1, T-2, T-3, T-5, T-6) y la
pantalla de clientes en riesgo dejan fuera a BODEGA y DESPACHO **enumerando cada ruta por nombre**,
nunca con comodín, para que un endpoint futuro no herede el permiso. Es la misma disciplina que las
Fases 3C y 4 ya aplicaron.

### 🟠 R-6 · Degradación con ClickHouse apagado

Los 7 tableros y las 2 pantallas predictivas deben responder **200 con `analiticaDisponible = false`
en ~4 s**, como los 39 compuestos vigentes, y recuperarse sin reiniciar el backend. **Solo un fallo
de CONEXIÓN degrada**: una consulta mal formada se propaga como 500 — capturar todo
`DataAccessException` disfrazaría bugs de SQL de «analítica no disponible».
*(Condición actual: ClickHouse estaba apagado durante la redacción de este documento, lo que confirma
que el escenario no es hipotético.)*

### 🟡 R-7 · Tres elementos de tablero no salen del almacén

Carritos abandonados (T-1), auditoría y accesos (T-7). Se resuelven llamando a los informes SIMPLES
ya construidos en PostgreSQL. **Decisión tomada: NO se crean tablas para ellos** — 290 carritos y una
consulta filtrada de auditoría no justifican un grano nuevo, y el criterio del encargo es priorizar
no crear tablas.

### 🟡 D-A-1 · Decisiones abiertas que corresponden al usuario

| Decisión | Opciones | Recomendación |
|---|---|---|
| **α de la alerta de abandono** | 0,05 (≈ 3 intervalos propios) / 0,10 (más alertas, más ruido) | **0,05**, con el nivel «atención» cubriendo la franja 0,05-0,10 |
| **Horizonte de la previsión** | 1 mes / 3 meses | **3 meses**, con la banda ensanchándose visiblemente. El plan de compra (D-11.1) es trimestral |
| **Tratamiento del mes truncado** | Excluirlo / anualizarlo a 27 días | **Excluirlo** del entrenamiento y mostrarlo aparte en el gráfico. Anualizar inventa 5 días |
| **Programa de lealtad** (hueco de OE-08) | Construirlo / seguir con cupón dirigido | Fuera del alcance de este diseño: D-08.2 **informa** el corte, no lo ejecuta. Construirlo es un bloque de BD + backend + pantallas |
| **Airflow** | Envolver `run_etl.py` en un DAG / seguir con el orquestador propio | Seguir como está. Las dos tareas de IA entran en el grafo existente sin tocar `run_etl.py`; un `BashOperator` por tarea lo reproduciría el día que se decida |

---

## 8. Anexo — verificaciones ejecutadas para este documento

Todas de **solo lectura**, el **2026-08-01**, contra la base `retailmind` (PostgreSQL) vía el MCP de
consulta, más lectura del repositorio. **Ninguna escritura, ningún DDL, ningún modelo entrenado,
ninguna tabla creada.**

| # | Afirmación verificada | Consulta / fuente | Resultado |
|---|---|---|---|
| 1 | Serie mensual de la demanda | `sum(pedido_detalle.cantidad)` por mes, netos de cancelados | 19 meses; media **1.045,2** uds; σ **289,9**; **CV 0,277** |
| 2 | Tendencia y memoria de la serie | `regr_slope`, `regr_r2`, `corr` con `lag` | **+23,2** uds/mes · **R² 0,203** · autocorrelación **0,364** |
| 3 | Observaciones por mes del calendario | `GROUP BY extract(month …)` | meses 1-7 con **2** obs.; **meses 8-12 con UNA** |
| 4 | Repetición de la forma intraanual | `corr` entre 2025 y 2026 sobre los 7 meses comparables | **0,879**; razón media **1,314** (**1,168** sin enero) |
| 5 | Señal por categoría | `GROUP BY categoría, mes` | 8 categorías con **19/19** meses y **CV 0,272-0,332**; 2 residuales (28 y 5 uds) |
| 6 | Historia por variante | meses distintos con venta por variante | 1.221 catálogo · **834** con venta · **159** con ≥12 meses · 446 ≥6 · 744 ≥3 · media **23,81** uds |
| 7 | Intermitencia de las variantes con historia | sobre las 159 | **4,05** uds/mes · **12,3 %** de meses en cero · rango 1,00-12,26 |
| 8 | Concentración de la demanda | acumulado por ranking de variante | top 20 % (167) = **63,15 %**; top 50 = 31,41 % |
| 9 | **La estacionalidad está en el generador** | `60_seed_bloque_b_ventas.sql` líneas 63, 172-174 | `sales_wt` de 12 valores · `yearf = 1,18` en 2026 · ruido `U(0,9;1,1)` |
| 10 | **Ningún mes está completo** | `min/max` del día del pedido por mes | días 1-**27** en todos los meses; **1-22** en julio de 2026 |
| 11 | Línea base de previsión (cálculo aritmético sobre la serie de la fila 1) | ingenuo vs. ingenuo estacional, prediciendo may-jul 2026 | MAPE **23,8 %** vs. **9,3 %** (**14,5 %** con julio anualizado) |
| 12 | Historia de compra por cliente | `count`, `min`, `max` por `cliente_id` | 72 clientes · **69** con pedidos · **57** con ≥10 · 4 con uno · media **56,9** · máx. **643** |
| 13 | Intervalos entre compras | `lag` sobre la fecha del pedido | 3.855 intervalos · media **4,22** d · mediana **1** · p90 10 · p99 42 · máx. 158 |
| 14 | Regularidad por cliente | CV de los intervalos, 62 clientes con ≥5 | intervalo medio **10,23** d · **CV medio 1,196** (0,598-4,059) |
| 15 | Recencia al 2026-08-01 | `max(fecha_pedido)` por cliente | media **32,4** d · máx. **189** · **22** de 69 >30 d · **9** >60 d |
| 16 | **La rampa de cartera** | compradores distintos y % del mayor, por mes | **1 solo comprador con el 100 %** en 2025-01 y 2025-02; 6 en marzo (55,4 %); 48-50 desde 2026 |
| 17 | Un cliente grande «abandonado» por artefacto | cliente 54: 297 pedidos, mensualizado | 82 y 146 pedidos en ene-feb 2025 (todo el mes); **74 d de silencio = 43 intervalos propios** |
| 18 | **El generador no permite abandonar** | `60_seed_bloque_b_ventas.sql` líneas 185-187 | `ORDER BY -ln(random())/pop LIMIT 1` — peso **constante**, sin dependencia del pasado |
| 19 | **Backtest de abandono, ventana estable** | entrenar 2026-01→05-22, medir 05-23→07-22, 53 clientes con ≥3 pedidos | **5 no volvieron (9,4 %)** · silencio propio **0,97** (no volvieron) vs **0,78** (volvieron) · **correlación 0,039** |
| 20 | Backtest sobre la ventana completa (contraste) | mismo corte, sin restringir la ventana | 60 clientes · 9 no volvieron (15,0 %) · 5 de los 48 recurrentes |
| 21 | Las 19 tablas del almacén existen y están registradas | `retailmind/etl/dwh/registro.py` + `tablas/` | **19** módulos, `TAREAS` completa, modelo declarado COMPLETO |
| 22 | Pila numérica disponible | `retailmind/requirements.txt` | pandas 2.2.2 · pyarrow 16.1.0 · clickhouse-connect 0.7.16 · psycopg2. **Sin statsmodels ni scikit-learn** |
| 23 | ClickHouse no disponible el día de la redacción | `curl localhost:8123/ping` · `docker ps` | Sin respuesta; demonio Docker detenido. Verificación desviada a PostgreSQL |
