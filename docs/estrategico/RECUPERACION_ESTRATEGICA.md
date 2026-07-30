# Recuperación estratégica de RetailMind — arqueología de misión, visión, negocio y objetivos

Diagnóstico **READ-ONLY** ejecutado el **2026-07-29**. Objetivo: localizar los documentos
fundacionales donde se escribieron la misión, la visión, las características del negocio y los
objetivos estratégicos originales; extraerlos **verbatim**; y medir su desalineamiento contra el
sistema actual. **No se redactó ninguna misión, visión u objetivo nuevo** — eso es una tarea
posterior con criterio humano. Ningún archivo del proyecto fue modificado; en la base solo se
ejecutaron `SELECT`.

---

## 1. Resumen ejecutivo

| Artefacto | ¿Existe? | ¿Cuántas versiones? | Fuentes |
|---|---|---|---|
| **Misión** | **SÍ** | **3 versiones distintas** | Tarea02, EVF04, TA06 |
| **Visión** | **SÍ** | **3 versiones distintas** | Tarea02, EVF04, TA06 |
| **Características / descripción del negocio** | **SÍ** | **3 versiones originales** (+1 caracterización actual, de nivel táctico) | Tarea02, EVF04, TA06 (+ `CATALOGO_OBJETIVOS_TACTICOS.md` §1.1) |
| **Objetivos estratégicos** | **SÍ** | **15 declaraciones** (14 con ID + 1 «objetivo estratégico general» sin ID) en **3 documentos** | Tarea02 (OE-01…05), EVF04 (OE-01…05), TA06 (OEG + OE1…OE4) |
| **Valores corporativos** | **NO EXISTEN** | — | ningún documento del repo declara valores |
| **Mapa estratégico / BSC** | SÍ, uno solo | 1 | TA06 (Balanced Scorecard de 4 perspectivas) |

**Hallazgo central**: se recuperaron los tres documentos fundacionales, y son **tres relatos de
empresa incompatibles entre sí**, escritos en un lapso de **28 días** (16 mayo → 13 junio 2026):

1. **Tarea02 (2026-05-16)** — RetailMind es una **casa de software** ecuatoriana que *vende
   analítica a clientes retail* («RetailMind cobra por los insights generados»).
2. **EVF04 (2026-06-04)** — RetailMind es una **plataforma de retail analytics con tienda online**
   sobre ClickHouse; el negocio es la plataforma misma.
3. **TA06 (2026-06-13)** — RetailMind es una **plataforma en expansión internacional** vía APIs,
   marketplaces, cloud/Kubernetes y Machine Learning.

Ninguno de los tres describe lo que el sistema es hoy. La pieza estratégica **más reciente es del
2026-06-13**, es decir **anterior en casi un mes** a la migración de la tienda a PostgreSQL
(2026-07-11) y anterior a **todo** el back-office (compras, inventario, ventas con compuertas, RMA,
devolución a proveedor, soporte con SLA, marketing con descuentos reales). La base estratégica del
proyecto quedó congelada en la etapa «renacuajo» (plataforma analítica ClickHouse + PocketBase).

**Veredictos de los 15 objetivos estratégicos**: **VIGENTE 2 · DESACTUALIZADO 8 · OBSOLETO 5.**
Los 2 vigentes son EVF04/OE-04 (seguridad RBAC — ampliamente superado en profundidad) y
Tarea02/OE-05 (automatización ETL con Airflow — sigue **pendiente**, no cumplido).

**Corrección a un diagnóstico previo**: `docs/tactico/SINCRONIZACION_OBJETIVOS.md` (2026-07-21)
afirma en su §2 que *«No se encontró ningún archivo llamado `EVF04` ni `TA06` en el repositorio:
ambos son documentos externos previos cuyo contenido sobrevive solo por citación verbatim dentro de
`specs/001-007`»*. Eso era cierto entonces. **Hoy ya no**: los PDFs `RetailMind_EVF04_Objetivos.pdf`
y `RetailMind_TA06_Desarrollo_Empresarial.pdf` están en `docs/` (agregados el 2026-07-29, todavía
**sin commitear** — `git status` los reporta como `??`), y con ellos apareció además un tercer
documento que ninguna auditoría anterior había visto: `RetailMind_Tarea02_Documentacion.pdf`, que
es **el más antiguo y el que contiene la primera misión y visión escritas del proyecto**.

---

## 2. FUENTES ENCONTRADAS

Fechas tomadas del campo `/CreationDate` de los PDFs (metadato del generador Word/ReportLab), que
es más confiable que la portada; se anota la discrepancia cuando existe.

| Archivo | Tipo de contenido estratégico | Fecha | ¿Legible? |
|---|---|---|---|
| `docs/RetailMind_Tarea02_Documentacion.pdf` | **Descripción de la empresa, MISIÓN, VISIÓN, 5 objetivos estratégicos (OE-01…OE-05) con necesidades de información táctica/operativa y tabla de alineación estratégica** | **2026-05-16** (cuerpo: «Mayo 2026») | SÍ (5 pág.; extraído con `pypdf` — la lectura directa falla por falta de `poppler`) |
| `docs/RetailMind_EVF04_Objetivos.pdf` | **DESCRIPCIÓN DE LA EMPRESA, VISIÓN, MISIÓN, «Niveles de objetivo», 5 OE + 10 OT + 19 OO con proceso, funcionalidad, KPI y CU** | **2026-06-04** (portada dice «2025» — inconsistente con el metadato) | SÍ (8 pág.) |
| `docs/RetailMind_TA06_Desarrollo_Empresarial.pdf` | **Desarrollo empresarial, niveles organizacionales, «LA EMPRESA / Actividad», MISIÓN, VISIÓN, «Objetivo estratégico general», mapa estratégico BSC (4 perspectivas + plan de acción), 4 OE + 8 OT + 12 OP, catálogo CU-E/CU-T/CU-O, visión arquitectónica y técnicas IA/ML** | **2026-06-13** | SÍ (16 pág.) |
| `specs/001-007/spec.md` (§15 Trazabilidad + «Leyenda de objetivos») y `specs/README.md` | Citas **verbatim** de los OE/OT/OO de EVF04, módulo por módulo. **Única fuente donde aparece la 3.ª redacción de `OE-04`** | 2026-06-18 | SÍ |
| `docs/RetailMind_GA07_Especificaciones_Condensado.pdf` (y `_Operativo.docx`) | Tabla de trazabilidad módulo → OE/OT/OO/CU + leyenda de objetivos. Contiene una **redacción reconciliada de `OE-04`** que no existe en EVF04 | 2026-06-21 | SÍ (37 pág.) |
| `.specify/memory/constitution.md` | 7 principios de gobernanza + descripción del producto en una línea. **Ya enmendada a v2.0.0** (2026-07-21): describe el sistema ACTUAL, no el original. **No contiene misión ni visión** | v1.0.0 = 2026-06-18 · v2.0.0 = 2026-07-21 | SÍ |
| `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §1.1 | **Única caracterización del negocio actual**: «distribuidora mayorista B2B». Es un documento de nivel **TÁCTICO**, no estratégico | 2026-07-26 | SÍ |
| `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` (§ sobre perfil de cliente) | Deriva la misma caracterización mayorista desde los datos sembrados | 2026-07-25 | SÍ |
| `docs/tactico/SINCRONIZACION_OBJETIVOS.md` | Auditoría previa de objetivos de nivel superior (14 entradas: OE/OT de EVF04 + principios de la constitución) | 2026-07-21 | SÍ |
| `docs/RetailMind_T11_Analisis_Tactico.docx` / `.pdf` / `docs/build_t11.py` | Nivel **táctico**. El `.docx` (2026-07-26) ya incorpora §1.1 mayorista; sin misión/visión/OE propios | PDF 2026-07-17 · DOCX 2026-07-26 | SÍ |
| `README.md` (raíz) | «Descripción» del producto («plataforma integral de retail analytics con tienda online»). **Sin misión, sin visión, sin OE** | 2026-06-06 | SÍ |
| `.kiro/steering/product.md` | Descripción del sistema actual («tienda PyME con back-office completo»). **Sin misión, sin visión, sin OE** | 2026-07-18 | SÍ |
| `GA 03 PARA TA07 SIN IMAGENES SOLAMENTE.docx` (raíz) | Casos de uso por paquetes con historias de usuario. **Sin contenido estratégico** | 2026-06-19 | SÍ (revisado; descartado) |
| `docs/RetailMind_EV09_*.pdf/.docx`, `docs/RetailMind_GA07_Documento_UML.pdf` | Especificación operativa y UML. **Sin misión/visión/OE propios** | 2026-07-05 / 2026-06-21 | SÍ (revisados; descartados) |
| `.kiro/specs/retailmind-etl-pipeline/`, `.kiro/specs/retailmind-package-reorganization/` | Requisitos técnicos de ETL y de reorganización de paquetes. **Sin contenido estratégico** | 2026-05-09 / 2026-05-21 | SÍ (revisados; descartados) |

**Barrido negativo (para que conste qué se buscó y no existe)**: se recorrió el repositorio
completo buscando por nombre `EVF01/02/03/05`, `GA01`…`GA06`, `TA01`…`TA05`, `TA07`…`TA12`,
`anteproyecto`, `propuesta`, `vision`, `mision`, `estrategico`, `business`, `negocio`, y por
contenido las mismas palabras en `.md`, `.py`, `.docx`, `.pdf` y `.sql`. **Ninguno existe como
archivo.** También se revisó el historial de git con `--diff-filter=D`: **no hay ningún documento
estratégico borrado** (los únicos archivos eliminados son datos internos de ClickHouse).

---

## 3. MISIÓN

Existen **tres misiones distintas**. Se presentan en orden cronológico; ninguna reemplaza
formalmente a la anterior (no hay nota de derogación en ningún documento).

### M1 — Tarea02, §2 «MISIÓN» (2026-05-16)

> «Construir sistemas de software de alta calidad que conviertan los datos de comportamiento del
> usuario en retail en insights accionables y generadores de valor económico, aplicando buenas
> prácticas de ingeniería de software, arquitecturas limpias y estándares internacionales de calidad
> como la norma ISO/IEC 25010, garantizando sistemas confiables, seguros y mantenibles que maximicen
> la productividad y el margen de ganancia de nuestros clientes.»

**Veredicto: OBSOLETO.** La misión de una **fábrica de software que vende sistemas a terceros**
(«nuestros clientes», «construir sistemas») no describe al sistema actual, que **es** la operación
de una sola empresa distribuidora, no un producto vendido a clientes retail. Frases a revisar:
*«Construir sistemas de software»*, *«el margen de ganancia de nuestros clientes»*.

### M2 — EVF04, §«MISIÓN» (2026-06-04)

> «Proveer una plataforma tecnológica robusta que combine una tienda online intuitiva para el
> cliente con un sistema de análisis de datos de alto rendimiento para el administrador, procesando
> millones de eventos de comportamiento de usuarios mediante ClickHouse para generar métricas
> accionables que optimicen el Funnel de conversión, reduzcan el abandono de carrito e incrementen
> las ventas del negocio retail.»

**Veredicto: DESACTUALIZADO.** El propósito final («incrementar las ventas del negocio retail»)
sigue siendo válido, pero el resto describe la etapa temprana: la analítica de comportamiento sobre
ClickHouse es hoy **opcional y degradable** (con ClickHouse apagado el sistema entero funciona), y
el sistema se compone de **dos usuarios** (cliente / administrador) cuando hoy hay **9 roles de
grupo** y un back-office de seis jefaturas. Frases exactas a revisar: *«procesando millones de
eventos de comportamiento de usuarios mediante ClickHouse»*, *«optimicen el Funnel de conversión,
reduzcan el abandono de carrito»*, *«una tienda online intuitiva para el cliente… y… para el
administrador»* (el sistema ya no se agota en esos dos actores).

### M3 — TA06, §«RETAILMIND SHOP — LA EMPRESA / Misión» (2026-06-13)

> «Proveer una plataforma tecnológica robusta que combine una tienda online intuitiva con un sistema
> de análisis de datos de alto rendimiento, procesando millones de eventos de comportamiento mediante
> ClickHouse para generar métricas accionables que optimicen el Funnel de conversión, reduzcan el
> abandono e incrementen las ventas del negocio retail **en cualquier mercado del mundo**.»

**Veredicto: DESACTUALIZADO** (y su cláusula final, **OBSOLETA**). Es M2 con dos cambios: se quita
la mención al administrador y se agrega *«en cualquier mercado del mundo»*. Esa cláusula no tiene
ningún sustento: la base tiene **1 país (Ecuador)** y **3 zonas de envío**; el negocio está
localizado en Quevedo, y la propia constitución v2.0.0 (Principio I) declara la
internacionalización como *«PENDIENTE: la internacionalización efectiva aún no está implementada y
se trata como objetivo, no como requisito vigente»*.

---

## 4. VISIÓN

También **tres versiones**, con **tres ámbitos geográficos distintos** (Latinoamérica → Ecuador →
Latinoamérica + internacional).

### V1 — Tarea02, §3 «VISIÓN» (2026-05-16)

> «Ser reconocida para el año 2030 como la empresa referente en desarrollo de software analítico
> para el sector retail en América Latina, destacando por la calidad técnica de sus sistemas, la
> capacidad de procesar millones de registros en tiempo real y la generación de los mayores márgenes
> de ganancia para sus clientes mediante decisiones basadas en datos.»

**Veredicto: OBSOLETO.** Visión de empresa **proveedora de software** («referente en desarrollo de
software analítico», «para sus clientes»), modelo que el sistema abandonó. Es, sin embargo, la
**única visión con horizonte temporal explícito** (2030) de las tres.

### V2 — EVF04, §«VISIÓN» (2026-06-04)

> «Ser la plataforma de retail analytics de referencia en Ecuador, que integre de forma nativa la
> experiencia de compra del cliente con la inteligencia de datos en tiempo real, permitiendo a las
> empresas del sector retail tomar decisiones estratégicas basadas en el comportamiento real de sus
> usuarios para maximizar sus márgenes de ganancia y fidelizar a sus clientes.»

**Veredicto: DESACTUALIZADO.** «Ecuador» sí coincide con el sistema real (único país sembrado) y
«maximizar márgenes» sigue vigente, pero el sujeto beneficiario está invertido: la visión habla de
*«permitiendo a las empresas del sector retail»* (RetailMind como proveedor de otras empresas),
mientras el sistema construido sirve a **sus propias jefaturas internas**. Frases a revisar:
*«plataforma de retail analytics»*, *«permitiendo a las empresas del sector retail»*, *«basadas en
el comportamiento real de sus usuarios»* (hoy las decisiones se toman sobre datos
**transaccionales**, no sobre eventos de comportamiento).

### V3 — TA06, §«RETAILMIND SHOP — LA EMPRESA / Visión» (2026-06-13)

> «Ser la plataforma de retail analytics de referencia en Latinoamérica con presencia internacional,
> que integre de forma nativa la experiencia de compra del cliente con la inteligencia de datos en
> tiempo real, creciendo en mercados extranjeros mediante canales 100% digitales, integraciones por
> API y una infraestructura cloud escalable.»

**Veredicto: OBSOLETO** en sus tres mecanismos. Verificado en el repositorio: **no existe ninguna
API pública documentada** (`pom.xml` sin `springdoc`/`openapi`/`swagger`), **no existe CI/CD**
(sin `.github/workflows`), **no existe orquestación cloud** (sin manifiestos Kubernetes; el
`docker-compose.yml` tiene 5 servicios y **PostgreSQL, la base operativa principal, corre fuera de
él, en local**). Frases exactas a revisar: *«con presencia internacional»*, *«creciendo en mercados
extranjeros»*, *«integraciones por API»*, *«infraestructura cloud escalable»*.

---

## 5. CARACTERÍSTICAS DEL NEGOCIO

### C1 — Tarea02, §1 «DESCRIPCIÓN DE LA EMPRESA» (2026-05-16)

> «RetailMind Analytics S.A. es una empresa ecuatoriana de desarrollo de software e inteligencia
> artificial aplicada al comercio electrónico y retail. Fundada en 2024, se especializa en la
> construcción de sistemas de análisis de recorridos de usuario (user journeys), aplicando
> metodologías modernas de ingeniería de software, estándares de calidad internacionales y
> arquitecturas escalables.
>
> La empresa trata los datos como su activo más preciado. Cada registro de sesión, evento de usuario
> y conversión representa una oportunidad de generar valor económico para sus clientes del sector
> retail, convirtiendo el comportamiento digital en decisiones de negocio rentables y medibles.
>
> El sistema desarrollado procesa actualmente 108,584 registros semanales de recorridos de usuario,
> creciendo hasta 1,600,000 registros al finalizar las 16 semanas operativas del sistema, utilizando
> tecnologías como Python, ClickHouse, Spring Boot y Angular.»

**Veredicto: OBSOLETO.** Define una **razón social y un giro de negocio distintos** (software
factory, «RetailMind Analytics S.A.», fundada 2024). El único elemento que sobrevive es la
nacionalidad ecuatoriana. Nota: es la **única fuente que declara una fecha de fundación y una razón
social**; ambos datos no se repiten en ningún otro documento.

### C2 — EVF04, §«DESCRIPCION DE LA EMPRESA» (2026-06-04)

> «RetailMind Shop es una plataforma digital de retail analytics que combina una tienda en línea
> funcional con un sistema avanzado de inteligencia de negocios. La empresa opera en el sector de
> comercio electrónico y análisis de datos, orientada a maximizar las conversiones de ventas y
> generar información estratégica a partir del comportamiento de los usuarios.
>
> El sistema gestiona un catálogo de 1,200 productos distribuidos en 8 categorías (Electronics,
> Groceries, Sports, Accessories, Beauty, Home, Shoes, Apparel) y analiza el comportamiento de más de
> 6,800 usuarios registrados a través de 1.6 millones de eventos de interacción almacenados en
> ClickHouse, una base de datos columnar de alto rendimiento.
>
> La plataforma atiende dos tipos de usuarios: clientes que navegan el catálogo, agregan productos al
> carrito, gestionan su wishlist y realizan compras; y administradores que monitorean métricas
> analíticas, gestionan el pipeline ETL, supervisan pedidos y controlan el acceso al sistema.»

**Veredicto: DESACTUALIZADO.** El catálogo sí sobrevive (1.221 variantes reales en PostgreSQL, hoy
en **11 categorías**, no 8), pero el resto describe la etapa «renacuajo». Frases exactas a revisar:
*«plataforma digital de retail analytics»* (hoy es una operación comercial con analítica accesoria);
*«8 categorías»* (son 11); *«6,800 usuarios registrados»* (la BD real tiene **72 clientes** y **88
usuarios de sistema**); *«1.6 millones de eventos… almacenados en ClickHouse»* (ClickHouse es
opcional); y sobre todo *«atiende dos tipos de usuarios: clientes… y administradores»* — hoy son
**9 roles de grupo** con RLS, restricción horaria y segregación financiera por columna.

### C3 — TA06, §«RETAILMIND SHOP — LA EMPRESA / Actividad» (2026-06-13)

> «Plataforma digital de comercio electrónico y retail analytics que combina una tienda online
> (catálogo de 1,200 productos en 8 categorías, carrito, wishlist, pedidos y recomendaciones
> personalizadas) con un sistema de inteligencia de negocio de alto rendimiento que procesa más de
> 2.3 millones de eventos de comportamiento de usuarios en ClickHouse, ofreciendo analítica de
> Funnel, región, dispositivo y fuente de tráfico en tiempo real.»

Y su declaración de propósito del sistema (§«CASOS DE USO DEL SISTEMA», punto 1):

> «Propósito: controlar el catálogo de productos, clientes, carrito, wishlist, pedidos, eventos de
> comportamiento, pipeline ETL, indicadores analíticos, recomendaciones, reportes ejecutivos y
> seguimiento estratégico del negocio retail digital.»

TA06 aporta además la **única definición de niveles organizacionales de la empresa** (no del
software), verbatim:

> «1. Nivel estratégico — Es el nivel más alto de la organización, formado por la dirección general
> de RetailMind Shop. Su función principal es definir el rumbo de la empresa en el mercado digital
> global. […] Horizonte de tiempo: largo plazo.»
> «2. Nivel táctico — Es el nivel intermedio, compuesto por los responsables de áreas: marketing
> digital, plataforma e integraciones, infraestructura cloud y analítica de datos. […] Horizonte de
> tiempo: mediano plazo.»
> «3. Nivel operativo — Es el nivel encargado de ejecutar las actividades diarias dentro del sistema
> RetailMind Shop: clientes navegando la tienda, administradores gestionando datos, el ETL cargando
> registros y el motor de recomendaciones funcionando en cada sesión. […] Horizonte de tiempo: corto
> plazo.»

**Veredicto: DESACTUALIZADO, con la definición de áreas OBSOLETA.** La actividad descrita
(«comercio electrónico y retail analytics») ignora los seis departamentos reales. La lista de áreas
del nivel táctico — *«marketing digital, plataforma e integraciones, infraestructura cloud y
analítica de datos»* — es **áreas de una empresa de tecnología**, no las del negocio real
(**Ventas, Compras, Inventario/Bodega, Logística/Despacho, Soporte, Gerencia**, con 68 objetivos
tácticos ya catalogados). Frases a revisar: la lista de áreas completa, *«mercado digital global»*,
*«2.3 millones de eventos… en ClickHouse»* como núcleo del negocio.

### C4 — La caracterización ACTUAL, y dónde vive

El único texto del repositorio que describe el negocio tal como es hoy **no es un documento
estratégico**: está en `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` §1.1 (2026-07-26), es decir en
el nivel **táctico**:

> «RetailMind opera como **distribuidora mayorista ecuatoriana con sede en Quevedo**: compra volumen
> a proveedores, almacena en dos bodegas y revende a clientes corporativos y minoristas recurrentes
> por tres canales (mostrador, teléfono y tienda en línea). Esa caracterización no es decorativa —
> condiciona qué informe táctico tiene sentido:
> - **El abastecimiento pesa más que la venta.** […] $22,47 M facturados en compras contra $5,72 M
>   vendidos […] en una distribuidora, el margen se gana comprando.
> - **El margen es de distribución, no de detalle.** […]
> - **El cliente es recurrente y de volumen**, no un comprador ocasional […]
> - **El capital vive en la bodega.** El inventario valorizado asciende a $22,02 M […]»

Ese mismo documento declara el cambio como decisión consciente (§«Qué cambió», punto 3):

> «**Reencuadre del negocio**: RetailMind se caracteriza como **distribuidora mayorista B2B**, no
> como comercio minorista. La narrativa de cada jefatura se ajustó a ese marco […]. Los datos no
> cambian; cambia la lectura de negocio que los enmarca.»

**Este reencuadre nunca subió al nivel estratégico.** Verificado: los documentos que sí describen
el sistema actual —`CLAUDE.md` («Tienda PyME con back-office completo»),
`.kiro/steering/product.md` («tienda PyME con back-office completo y analítica integrada») y
`.specify/memory/constitution.md` v2.0.0 («plataforma web de retail para PyME»)— **siguen diciendo
“PyME/retail”, no “distribuidora mayorista B2B”**. Las palabras «mayorista», «B2B» y «distribuidora»
aparecen **solo** en `docs/tactico/` (catálogo táctico y auditoría de seed) y, de pasada, en la línea
de `CLAUDE.md` que describe las bandas de costo del script 67.

---

## 6. OBJETIVOS ESTRATÉGICOS

15 declaraciones en 3 documentos. Los IDs **colisionan entre documentos** (ver §7): un mismo
`OE-01` significa tres cosas distintas según de dónde se lea. Se prefija cada fila con su documento
para poder citarlas sin ambigüedad.

| ID original | Texto verbatim | Fuente | Fecha | Veredicto | Frase exacta a revisar |
|---|---|---|---|---|---|
| **OE-01** (Tarea02) | «Maximizar la tasa de conversión del usuario en retail — Desarrollar un sistema que identifique los patrones de comportamiento que llevan a una compra exitosa, permitiendo a los clientes retail incrementar su tasa de conversión y por ende sus ingresos.» | `docs/RetailMind_Tarea02_Documentacion.pdf` §4 | 2026-05-16 | **DESACTUALIZADO** | «permitiendo a **los clientes retail** incrementar su tasa de conversión» (modelo proveedor-cliente) y, en su bloque de alineación, «**RetailMind cobra por los insights generados**» |
| **OE-02** (Tarea02) | «Escalar el procesamiento de datos de 100K a 1.6M registros — Garantizar que la arquitectura del sistema soporte el crecimiento semanal de datos sin degradación del rendimiento, manteniendo tiempos de respuesta óptimos para los clientes y el equipo de análisis.» | ídem | 2026-05-16 | **DESACTUALIZADO** | «de **100K a 1.6M registros**» y «**crecimiento semanal**» — la cifra quedó atrás (2,3 M de eventos) y el crecimiento real del sistema fue **transaccional** (110 tablas), no de eventos semanales |
| **OE-03** (Tarea02) | «Reducir el tiempo de toma de decisiones basadas en datos — Proveer dashboards e informes en tiempo real que permitan a los clientes retail tomar decisiones de negocio en minutos en lugar de días, eliminando la dependencia de reportes manuales en hojas de cálculo.» | ídem | 2026-05-16 | **DESACTUALIZADO** (el más cercano a cumplido de los 15) | «que permitan a **los clientes retail** tomar decisiones» — el beneficiario real son las jefaturas internas. Quitando esas tres palabras, el resto describe literalmente los **29 informes tácticos** (28 ya en pantalla) |
| **OE-04** (Tarea02) | «Garantizar la portabilidad e intercambiabilidad del sistema entre motores de BD — Construir el sistema con una capa de abstracción que permita migrar entre diferentes motores de base de datos (ClickHouse, PostgreSQL, MongoDB) sin reescribir el código, respondiendo a los cambios tecnológicos del mercado.» | ídem | 2026-05-16 | **OBSOLETO** | «una **capa de abstracción** que permita migrar entre diferentes motores… **sin reescribir el código**». El sistema actual apuesta lo contrario: su seguridad **depende del motor** (`SET LOCAL ROLE`, RLS, `SECURITY DEFINER`, triggers de totales, columnas `GENERATED`). La portabilidad ya no es alcanzable ni deseada |
| **OE-05** (Tarea02) | «Automatizar el pipeline ETL para eliminar intervención manual — Implementar un sistema completamente automatizado que ejecute la carga semanal de datos sin intervención humana, desde la extracción en Pocketbase hasta la visualización en el dashboard, usando Airflow como orquestador.» | ídem | 2026-05-16 | **VIGENTE** (no cumplido) | — Sigue declarado como pendiente vigente en `CLAUDE.md` («orquestación ETL con Airflow») y en la constitución v2.0.0 («Orquestación con Apache Airflow…: NO implementada»). Es el único OE original que el proyecto todavía reclama palabra por palabra |
| **OE-01** (EVF04) | «Maximizar conversiones y ventas en la tienda online RetailMind Shop» | `docs/RetailMind_EVF04_Objetivos.pdf` p.3 · citado en `specs/002,003,004,005,007/spec.md` §15 | 2026-06-04 | **DESACTUALIZADO** | «en la **tienda online**» — hoy es **1 de 3 canales**: verificado en la BD, `web` 2.213 pedidos (54,2 %), `tienda` 1.030 (25,2 %), `telefono` 840 (20,6 %). La intención (maximizar ventas) es vigente; el alcance de canal único, no |
| **OE-02** (EVF04) | «Generar inteligencia de negocio a partir de datos de comportamiento de usuarios» | `docs/RetailMind_EVF04_Objetivos.pdf` p.4 | 2026-06-04 | **DESACTUALIZADO** | «a partir de datos **de comportamiento de usuarios**» — la inteligencia de negocio que efectivamente se construyó (68 objetivos tácticos, 29 informes) se alimenta de datos **transaccionales de PostgreSQL**; la analítica de comportamiento es la capa opcional |
| **OE-03** (EVF04) | «Automatizar el pipeline ETL para garantizar disponibilidad continua de datos en ClickHouse» | `docs/RetailMind_EVF04_Objetivos.pdf` p.5 | 2026-06-04 | **DESACTUALIZADO** | «garantizar **disponibilidad continua** de datos **en ClickHouse**» — contradice el principio operativo vigente: «con ClickHouse apagado TODO el sistema funciona; solo analytics/recomendaciones se degradan con aviso» (`CLAUDE.md`). La automatización sigue pendiente; la *continuidad* dejó de ser un requisito |
| **OE-04** (EVF04) | «Garantizar la seguridad del sistema mediante control de acceso basado en roles (RBAC)» | `docs/RetailMind_EVF04_Objetivos.pdf` p.6 · citado en `specs/001/spec.md:348` y `specs/006/spec.md:279` | 2026-06-04 | **VIGENTE** (superado en profundidad) | — El objetivo se cumple y se excede: de 2 roles de aplicación a **9 roles de grupo** en PostgreSQL con RLS, restricción por horario y GRANT por columna. Lo que sí está obsoleto son sus objetivos operativos: **OO-12** «Autenticar con JWT y credenciales hasheadas **en ClickHouse**» y **OO-13** «roles diferenciados (**ADMIN/CLIENTE**)» |
| **OE-05** (EVF04) | «Garantizar la escalabilidad y portabilidad del sistema para soportar el crecimiento de datos y usuarios» | `docs/RetailMind_EVF04_Objetivos.pdf` p.7 | 2026-06-04 | **DESACTUALIZADO** | Su sustento operativo **OO-18**: «Contenerizar **todos** los servicios del sistema con Docker para garantizar portabilidad» — hoy **PostgreSQL, la base operativa principal, corre local y fuera de `docker-compose`**, y eso está declarado como fase pendiente en la constitución (Principio I) y en `CLAUDE.md` |
| **(sin ID)** «Objetivo estratégico general» (TA06) | «Incrementar la rentabilidad y competitividad internacional de RetailMind Shop mediante la adquisición digital automatizada de clientes, la escalabilidad comercial por APIs y marketplaces, la infraestructura cloud de alta disponibilidad y la inteligencia de negocio centralizada para la toma de decisiones ultra-rapida.» | `docs/RetailMind_TA06_Desarrollo_Empresarial.pdf` §«LA EMPRESA» | 2026-06-13 | **OBSOLETO** | Los cuatro mecanismos son inexistentes: «**adquisición digital automatizada**», «**APIs y marketplaces**», «**infraestructura cloud de alta disponibilidad**» y «competitividad **internacional**». Solo «incrementar la rentabilidad» sobrevive |
| **OE1** (TA06) | «Penetración de Mercado Digital y Adquisición Automatizada de Clientes (Growth Hacking) — Capturar rápidamente una masa crítica de clientes en el extranjero reduciendo al mínimo la necesidad de desplegar infraestructura física o equipos de ventas tradicionales en cada país, utilizando flujos de captación 100% digitales.» | ídem §«OBJETIVOS ESTRATEGICOS DE LA EMPRESA» y §3.A | 2026-06-13 | **OBSOLETO** | «clientes **en el extranjero**», «**en cada país**», «captación **100% digitales**», y su apoyo TIC «**pasarelas de pago globales multi-divisa**». La BD tiene **1 país (Ecuador)** y **3 zonas de envío**; el pago es simulado y en una sola moneda; el negocio tiene **72 clientes recurrentes**, y 45,8 % de los pedidos entran por mostrador o teléfono — exactamente los «equipos de ventas tradicionales» que el objetivo quería eliminar |
| **OE2** (TA06) | «Escalabilidad Comercial Exponencial a través de Plataformas de Ecosistemas (Marketplaces y APIs) — Multiplicar los ingresos a gran velocidad sin contratar ejércitos de vendedores, integrando la oferta comercial de RetailMind Shop dentro de las infraestructuras digitales que ya utilizan los clientes en los países destino.» | ídem | 2026-06-13 | **OBSOLETO** | «**Marketplaces y APIs**», «los **países destino**» y su apoyo TIC «exponer las APIs REST… como **APIs publicas estables y estandarizadas con OpenAPI**». Verificado: `retailmind-backend/pom.xml` **no tiene `springdoc`, `openapi` ni `swagger`**; no hay API pública ni partner integrado. Su KPI («% ARR/MRR por integraciones») no es medible: no existe el dato |
| **OE3** (TA06) | «Expansion Continua Basada en Infraestructura en la Nube de Alta Disponibilidad — Garantizar que la entrega del servicio mantenga el mismo rendimiento y velocidad en cualquier parte del mundo, permitiendo que la operación técnica crezca bajo demanda sin restricciones geográficas.» | ídem | 2026-06-13 | **OBSOLETO** | «en **cualquier parte del mundo**» y su apoyo TIC: «hacia **nubes publicas globales con orquestación Kubernetes**; implementación de **CDNs globales**; y… **DevOps/CI-CD**». Verificado: sin manifiestos Kubernetes, sin `.github/workflows`, sin cloud; y la contenerización está **incompleta** (PostgreSQL fuera de compose) |
| **OE4** (TA06) | «Inteligencia de Negocio Centralizada para la Ventaja Competitiva Global — Recolectar y procesar datos masivos del comportamiento de los distintos mercados internacionales en tiempo real para tomar decisiones de precios, producto y operaciones de forma ultra-rapida y superar a los competidores consolidados.» | ídem | 2026-06-13 | **DESACTUALIZADO** | «de los distintos **mercados internacionales**» y su apoyo TIC «**modelos de Machine Learning** sobre los 2.3 millones de eventos… predecir la fuga de clientes (**churn**) y aplicar estrategias de **precios dinámicos**». La centralización de BI sí existe en parte (dashboard de 8 KPIs + 29 informes tácticos); **ML, churn y precios dinámicos no existen** en el sistema, y «mercados internacionales» tampoco |

**Conteo de veredictos (15 declaraciones): VIGENTE 2 · DESACTUALIZADO 8 · OBSOLETO 5.**

> **Nota de alcance**: los **10 OT + 19 OO de EVF04**, los **8 OT + 12 OP de TA06** y los **68 OTD-
> del catálogo táctico** NO se diagnostican aquí (no son objetivos estratégicos). Los OT/OO de EVF04
> ya fueron auditados en `docs/tactico/SINCRONIZACION_OBJETIVOS.md` §3, y los OTD- en
> `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md`. Sí se citan arriba los OO-12, OO-13 y OO-18 porque
> son el sustento operativo directo de un OE y su obsolescencia condiciona el veredicto del OE.

---

## 7. VERSIONES EN CONFLICTO

Diez conflictos abiertos. Ninguno está resuelto por una nota de derogación en ningún documento.

1. **Tres misiones** (M1/M2/M3, §3). M1 es de una fábrica de software; M2 y M3 son de la
   plataforma. M3 = M2 + «en cualquier mercado del mundo» − «para el administrador».
2. **Tres visiones con tres geografías** (V1/V2/V3, §4): «América Latina» (2026-05-16) →
   «Ecuador» (2026-06-04) → «Latinoamérica con presencia internacional» (2026-06-13). El ámbito se
   contrajo y volvió a expandirse en 28 días. Solo V1 tiene horizonte temporal (2030).
3. **Tres identidades de empresa** (§5): «RetailMind Analytics S.A.», empresa de desarrollo de
   software fundada en 2024 (Tarea02) / «RetailMind Shop», plataforma digital de retail analytics
   (EVF04, TA06) / «distribuidora mayorista B2B con sede en Quevedo» (catálogo táctico, 2026-07-26).
   La razón social y el año de fundación **solo existen en Tarea02** y no se repiten en ninguna otra
   parte.
4. **Colisión del ID `OE-01`** (3 textos): «Maximizar **la tasa de conversión del usuario en
   retail**» (Tarea02) vs. «Maximizar conversiones y ventas **en la tienda online RetailMind Shop**»
   (EVF04) vs. «**Penetración de mercado digital** y adquisición automatizada de clientes» (TA06,
   como `OE1`).
5. **Colisión del ID `OE-02`** (2 textos): «**Escalar el procesamiento de datos** de 100K a 1.6M
   registros» (Tarea02) vs. «**Generar inteligencia de negocio** a partir de datos de comportamiento»
   (EVF04) vs. «**Escalabilidad comercial** vía marketplaces y APIs» (TA06, `OE2`).
6. **Colisión del ID `OE-03`**: «**Reducir el tiempo de toma de decisiones**» (Tarea02) vs.
   «**Automatizar el pipeline ETL**» (EVF04) vs. «**Expansión cloud**» (TA06, `OE3`).
7. **Colisión del ID `OE-04`, con CUATRO redacciones** — el conflicto más documentado del repo:
   - «Garantizar la **portabilidad e intercambiabilidad entre motores de BD**» (Tarea02).
   - «Garantizar la seguridad del sistema mediante **control de acceso basado en roles (RBAC)**»
     (EVF04 p.6, y así citado en `specs/001/spec.md:348` y `specs/006/spec.md:279`).
   - «**Inteligencia de negocio / personalización** (y control de acceso)» (`specs/007-operativo-
     recomendaciones/spec.md:293`) — incompatible con la anterior, mismo ID.
   - «Garantizar la seguridad (RBAC) **e inteligencia de negocio / personalización**» — redacción
     **fusionada** que aparece solo en `docs/RetailMind_GA07_Especificaciones_Condensado.pdf`
     (leyenda de objetivos) y que **no existe en EVF04**: es una reconciliación introducida a
     posteriori, sin declararse como tal.
8. **Colisión del ID `OE-05`**: «**Automatizar el pipeline ETL** con Airflow» (Tarea02) vs.
   «Garantizar la **escalabilidad y portabilidad**» (EVF04). Nótese el cruce: la automatización del
   ETL es `OE-05` en Tarea02 y `OE-03` en EVF04; la escalabilidad/portabilidad es `OE-05` en EVF04
   y `OE-02`+`OE-04` en Tarea02.
9. **Dos esquemas de numeración incompatibles**: `OE-01…OE-05` con guion, cinco objetivos
   (Tarea02 y EVF04) vs. `OE1…OE4` sin guion, cuatro objetivos (TA06). Lo mismo en el nivel táctico:
   `OT-01…OT-10` (EVF04) vs. `OT1…OT8` (TA06) vs. `OTD-*` (catálogo táctico, prefijo elegido
   explícitamente para no colisionar — ver `CATALOGO_OBJETIVOS_TACTICOS.md` §2).
10. **Dos catálogos de nivel operativo**: `OO-01…OO-19` (EVF04, 19 objetivos operativos) vs.
    `OP1…OP12` (TA06, 12 objetivos operativos), sin tabla de correspondencia entre ellos. La
    consecuencia práctica ya está documentada como deuda en `specs/006` y `specs/007`: EVF04 **no
    asigna OO ni CU** a perfil ni a recomendaciones, y las specs tuvieron que tomar prestados
    `CU-O08` y `CU-O09` **de TA06** para cerrar la trazabilidad.

---

## 8. ELEMENTOS FALTANTES

Lo que **no existe** en el repositorio. Se declara explícitamente, sin proponer reemplazos.

1. **VALORES corporativos: NO EXISTEN.** Ningún documento del repo declara valores, principios
   éticos o cultura organizacional en el sentido estratégico. Lo más cercano son (a) la frase de
   Tarea02 «La empresa trata los datos como su activo más preciado» y (b) los siete principios de
   la constitución, que son **gobernanza técnica** (arquitectura, seguridad, calidad de código), no
   valores de empresa.
2. **NO existe misión ni visión del negocio mayorista.** Las tres misiones y las tres visiones son
   de 2026-05/06 y describen una plataforma de analítica. El reencuadre a «distribuidora mayorista
   B2B» (2026-07-26) vive **solo** en `docs/tactico/`, un nivel por debajo. No hay ningún documento
   estratégico que lo recoja.
3. **NO existe ningún objetivo estratégico que sustente el back-office**, que es el grueso de lo
   construido. Ninguna de las 15 declaraciones menciona compras, abastecimiento, proveedores,
   cuentas por pagar, inventario/kardex, bodegas, logística de salida, logística inversa (RMA),
   devolución a proveedor, soporte con SLA ni facturación. Los 68 objetivos tácticos departamentales
   **no cuelgan de ningún OE**: son huérfanos de nivel superior. Es la brecha más grande del
   diagnóstico — y es simétrica a la de §5: los objetivos estratégicos no cubren el negocio real
   porque **las características del negocio real tampoco están escritas a nivel estratégico**.
4. **NO existe ningún documento estratégico posterior al 2026-06-13.** La última pieza (TA06) es
   anterior a la migración de la tienda a PostgreSQL (2026-07-11) y a todos los scripts 32-84. Todo
   lo que se escribió después es táctico, operativo o de deuda técnica.
5. **NO existe mapa estratégico vigente.** El único Balanced Scorecard del repo es el de TA06, con
   4 perspectivas, 16 objetivos-indicador y un plan de acción de 6 acciones, **enteramente
   construido sobre la expansión internacional** (CAC internacional, % ARR/MRR por APIs, uptime
   99.9 %, certificaciones cloud). Ninguno de sus indicadores es medible con la base actual.
6. **NO existe indicador estratégico alguno referido al modelo mayorista.** Los KPI declarados en
   los tres documentos son de conversión, funnel, abandono de carrito, latencia OLAP, CAC, ARR/MRR
   y uptime. Ninguno mide abastecimiento, deuda con proveedores, capital inmovilizado en bodega ni
   margen de distribución — que es lo que las cifras reales del negocio muestran como dominante
   ($22,47 M en compras vs. $5,72 M en ventas; $22,02 M de inventario valorizado).
7. **NO existen como archivos** los entregables `EVF01`, `EVF02`, `EVF03`, `EVF05`, `GA01`–`GA06`,
   `TA01`–`TA05`, `TA07`–`TA12`, ni ningún `anteproyecto` o `propuesta`. Verificado por nombre y por
   contenido, y verificado también que **no fueron borrados del historial de git**. Si contenían
   material estratégico, ese material está fuera del repositorio.
8. **`README.md`, `.kiro/steering/*` y `CLAUDE.md` no contienen misión ni visión.** Son descripciones
   de producto y de arquitectura. La constitución v2.0.0 aporta una sola línea de identidad
   («RetailMind es una plataforma web de retail para PyME con back-office operativo completo, tienda
   online integrada y analítica»), que no es una misión y que además **no dice «mayorista»**.

---

## 9. DIAGNÓSTICO DE ALINEAMIENTO

**Cuánto de la base estratégica original sigue sirviendo: poco, y de forma dispersa.** De 15
declaraciones estratégicas, **2 vigentes (13 %)**, y una de las dos (`OE-05` de Tarea02, Airflow)
está vigente **porque nunca se ejecutó**, no porque describa un logro. La única declaración que a la
vez sigue vigente y se cumplió con creces es `OE-04` de EVF04 (seguridad RBAC): el sistema pasó de
2 roles de aplicación a 9 roles de motor con RLS, horario y segregación financiera por columna. Es
el único hilo de continuidad real entre la etapa fundacional y el sistema actual.

**El desalineamiento no es de redacción: es de sujeto.** Los tres documentos discuten *qué clase de
empresa es RetailMind* y dan tres respuestas distintas — proveedor de software, plataforma de
analítica, plataforma global. El sistema construido dio una cuarta, que ninguno de los tres
contempla: **una distribuidora mayorista que se administra a sí misma con su propio software**. Por
eso el desfase no se arregla actualizando tecnología en las frases (cambiar «ClickHouse» por
«PostgreSQL» en la misión no la vuelve correcta): el objeto del enunciado cambió.

**Tres desalineamientos estructurales, en orden de gravedad:**

1. **El 100 % del back-office no tiene respaldo estratégico.** Compras, inventario, logística de
   salida, RMA, devolución a proveedor, soporte y facturación — el trabajo de los scripts 01-84 y de
   los seis departamentos — no aparece en ninguno de los 15 objetivos. Y a la inversa: los 68
   objetivos tácticos que sí dirigen ese back-office no cuelgan de ningún OE. La pirámide
   estratégico → táctico → operativo está **partida en su nivel más alto**: el táctico se reconstruyó
   entero contra la base real, el estratégico no se tocó desde junio.
2. **El reencuadre mayorista está atrapado en el nivel táctico.** La caracterización del negocio
   actual (mayorista B2B, Quevedo, tres canales, margen de compra, capital en bodega) existe, está
   verificada contra la base y es coherente — pero vive en `docs/tactico/`. Los documentos de más
   autoridad del repo (constitución, steering, `CLAUDE.md`) siguen diciendo «PyME/retail». Hay, en
   la práctica, **dos verdades de negocio simultáneas** en el repositorio, y la más precisa es la que
   está en el nivel más bajo.
3. **La mitad obsoleta es toda una misma apuesta que nunca ocurrió.** Los 5 OBSOLETOS no están
   dispersos: 4 de los 5 son TA06 (el general + OE1 + OE2 + OE3), es decir **el pivote internacional
   completo** — APIs públicas, marketplaces, cloud/Kubernetes, CI/CD, multi-divisa, ML de churn y
   precios dinámicos. Verificado en el repositorio: **ninguna de esas piezas existe**, y la
   constitución v2.0.0 ya trata la internacionalización como aspiración explícitamente no
   implementada. El quinto obsoleto (`OE-04` de Tarea02, portabilidad entre motores) es interesante
   por otra razón: el sistema no solo no lo cumplió, sino que **eligió deliberadamente lo contrario**
   —apostar la seguridad al motor PostgreSQL— y esa decisión es hoy el activo arquitectónico más
   valioso del proyecto.

**Lo que sí es reutilizable, sin reescribirlo:**

- **La intención de EVF04/`OE-01`** (maximizar ventas): vigente; solo su alcance de canal es
  estrecho («tienda online» = 54,2 % de los pedidos, no el 100 %).
- **La intención de Tarea02/`OE-03`** (decisiones en minutos, no en días, sin hojas de cálculo): es
  la que mejor predijo lo que se construyó — 29 informes tácticos, 28 en pantalla. Le sobran tres
  palabras («a los clientes retail»).
- **EVF04/`OE-04`** (seguridad por roles): vigente y superado.
- **Tarea02/`OE-05`** (Airflow): vigente y pendiente, con el mismo texto con el que nació.
- **La estructura de tres niveles** (estratégico/táctico/operativo) de TA06 y EVF04: el andamiaje
  conceptual sigue siendo el que usa el sistema (constitución, Principio III). Lo que caducó es el
  **contenido** de cada nivel, no la división.

**Consecuencia para el trabajo posterior** (dicho como diagnóstico, no como propuesta): reencuadrar
la misión y la visión al modelo mayorista no es un ajuste de vocabulario sino una reescritura, porque
el sujeto del enunciado cambió; y **antes** de redactar objetivos estratégicos nuevos hay que decidir
qué se hace con los IDs, porque hoy `OE-01`…`OE-05` están tomados por dos juegos distintos y citados
verbatim en `specs/001-007`, en `GA07` y en `EV09`. Cualquier redefinición de esos IDs sin declarar
la derogación agregaría un conflicto más sobre los diez ya abiertos en §7.

---

### Anexo — Verificaciones ejecutadas para este diagnóstico (todas de solo lectura)

| Afirmación verificada | Método | Resultado |
|---|---|---|
| Tamaño real del esquema | `SELECT count(*) FROM information_schema.tables` | **110 tablas** en `public` |
| Universo de clientes / usuarios / proveedores | `SELECT count(*)` | 72 clientes · 88 usuarios · 11 proveedores |
| «Tienda online» como canal único | `GROUP BY pedido.canal` | web 2.213 (54,2 %) · tienda 1.030 (25,2 %) · telefono 840 (20,6 %); total 4.083 |
| Alcance internacional | `SELECT count(*) FROM pais`, `zona_envio` | **1 país (Ecuador)** · 3 zonas |
| APIs públicas OpenAPI (TA06/OE2) | `grep -i "springdoc\|openapi\|swagger" retailmind-backend/pom.xml` | **ninguna dependencia** |
| CI/CD y Kubernetes (TA06/OE3) | `ls .github/workflows`, búsqueda de manifiestos k8s | **no existen** |
| Contenerización completa (EVF04/OO-18) | servicios de `docker-compose.yml` | 5 servicios (pocketbase, clickhouse, backend, frontend, etl); **PostgreSQL no está** |
| Fechas de los documentos | `/CreationDate` de cada PDF vía `pypdf` | Tarea02 2026-05-16 · EVF04 2026-06-04 · TA06 2026-06-13 · GA07 2026-06-21 · EV09 2026-07-05 · T11 2026-07-17 |
| Estado de los PDFs en el repo | `git status --porcelain docs/` | los 3 documentos fundacionales están **sin commitear** (`??`) |
| Documentos estratégicos borrados | `git log --diff-filter=D --name-only` | **ninguno** (solo datos internos de ClickHouse) |
