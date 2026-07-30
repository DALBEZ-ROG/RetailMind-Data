# Base estratégica de RetailMind — misión, visión y objetivos estratégicos

**Universidad Técnica Estatal de Quevedo (UTEQ)** · Facultad de Ciencias de la Ingeniería
**Asignatura**: Construcción de Software — 6.º semestre
**Proyecto**: RetailMind — red de distribución comercial híbrida B2B + B2C (Quevedo, Los Ríos, Ecuador)
**Documento**: base estratégica vigente · **Versión 2** · **Fecha: 2026-07-29**

> **Qué cambió en la versión 2** (mismo día): se **reencuadró OE-06** —nombre y texto— para que
> refleje la composición real del negocio en vez de presuponer un equilibrio B2B/B2C que los datos
> no respaldan (§3.2, §4, §7), y se **cerró la mitad de canales de su hueco de traza** con la
> construcción del informe táctico **OTD-VEN-16** «Participación de la venta por canal», ya
> consultable en pantalla (§6.1.a). El corte B2B/B2C sigue abierto y ahora está declarado con más
> evidencia (§6.1.b), incluida la prueba de comportamiento de canasta.

**Qué es este documento.** Es la base estratégica **vigente** de RetailMind: fija la misión, la
visión y los seis objetivos estratégicos, y —lo más importante— **traza cada objetivo estratégico
contra los objetivos tácticos departamentales (OTD-) que efectivamente lo sostienen**, verificados
uno a uno contra `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` y contra la base real `retailmind`.

**Qué reemplaza.** Sustituye la base estratégica original recuperada en
`docs/estrategico/RECUPERACION_ESTRATEGICA.md` (documentos Tarea02 · 2026-05-16, EVF04 ·
2026-06-04 y TA06 · 2026-06-13), que quedó desalineada por cuatro razones documentadas allí: la
misión era B2C y mencionaba tecnología concreta (ClickHouse), la visión era B2B y con horizonte
2027 ya vencido, ninguna de las dos recogía el reposicionamiento mayorista ni el modelo híbrido, y
**el 100 % del back-office construido no colgaba de ningún objetivo estratégico** (§8.3 y §9 de la
recuperación).

**Qué NO hace.** No modifica la base de datos ni la analítica; todo lo verificado aquí se hizo en
modo lectura (solo `SELECT`). La versión 2 sí tiene dos consecuencias fuera de este archivo, ambas
declaradas: el catálogo táctico incorporó **OTD-VEN-16** y la aplicación ganó su informe (un método
de servicio, un endpoint, una línea de autorización y una definición de pantalla, sin componentes ni
esquema nuevos).

---

## 1. MISIÓN

> «Proveer a negocios y consumidores acceso a un catálogo diverso de productos a precios altamente
> competitivos, eliminando intermediarios innecesarios. Operamos mediante procesos ágiles y
> confiables de abastecimiento, control de inventario y logística de entrega, garantizando la máxima
> disponibilidad y el cumplimiento exacto en cada pedido.»

## 2. VISIÓN

> «Para el año 2030, ser la red de distribución comercial referente en la Costa ecuatoriana,
> reconocida por conectar eficientemente a proveedores, negocios y consumidores. Lideraremos el
> mercado mediante una operación confiable y la toma de decisiones estratégicas basadas en datos,
> anticipando la demanda y optimizando cada eslabón de nuestra cadena de abastecimiento.»

---

## 3. OBJETIVOS ESTRATÉGICOS

### 3.1 Convención de identificadores (y por qué no se reutiliza `OE-01`)

La recuperación estratégica dejó diez conflictos abiertos (§7), y dos de ellos son de numeración:
`OE-01`…`OE-05` **están tomados por dos juegos distintos** (Tarea02 y EVF04, con textos
incompatibles bajo el mismo ID) y además están **citados verbatim** en `specs/001-007`, en
`RetailMind_GA07_Especificaciones_Condensado.pdf` y en `EV09`. Su §9 advierte que redefinir esos
IDs sin declarar la derogación agregaría un conflicto más sobre los diez ya existentes.

Por eso este documento **conserva el formato `OE-NN` (con guion, el de Tarea02 y EVF04) y continúa
la serie desde el primer número libre: `OE-06`…`OE-11`.** Es la misma solución que el nivel táctico
aplicó con el prefijo `OTD-` para no colisionar con los `OT-` de EVF04
(`CATALOGO_OBJETIVOS_TACTICOS.md` §2): se evita la colisión sin romper la convención heredada.

**Declaración de derogación**: los quince objetivos estratégicos originales (Tarea02 `OE-01`…`OE-05`,
EVF04 `OE-01`…`OE-05`, TA06 `OEG` + `OE1`…`OE4`) quedan **derogados como base estratégica vigente**
y pasan a ser material histórico. Sus IDs **no se reasignan**: siguen significando lo que
significaban en su documento de origen, para que las citas de `specs/001-007`, `GA07` y `EV09` no
cambien de sentido. Dos salvedades, tomadas de los veredictos de la recuperación (§6):

- **EVF04 `OE-04`** (seguridad mediante RBAC, único VIGENTE y cumplido) no reaparece como objetivo
  estratégico porque hoy es una **capacidad transversal ya construida y gobernada** por el
  Principio de seguridad de `.specify/memory/constitution.md` (9 roles de grupo en PostgreSQL, RLS,
  restricción horaria y GRANT por columna). Deja de ser una meta y pasa a ser un supuesto de
  operación de los seis objetivos.
- **Tarea02 `OE-05`** (automatizar el ETL con Airflow, VIGENTE y **pendiente**) no se deroga ni se
  reencuadra: continúa vivo como **medio técnico** de `OE-10`, y es su principal habilitador
  pendiente.

### 3.2 Los seis objetivos

| ID | Nombre corto | Objetivo estratégico (texto vigente) |
|---|---|---|
| **OE-06** | **Desarrollo Omnicanal con foco en expansión mayorista** | Consolidar el liderazgo en la venta directa al consumidor final (B2C), que constituye la base actual del negocio, mientras se desarrolla estratégicamente el canal mayorista (B2B) como principal vía de crecimiento, ampliando la participación de tiendas y minoristas que compran para revender. |
| **OE-07** | **Rentabilidad por Volumen y Rotación** | Maximizar los márgenes de beneficio no solo a través del precio unitario, sino impulsando la alta rotación de inventario y el volumen masivo de transacciones. |
| **OE-08** | **Fidelización y Retención de Clientes** | Construir relaciones a largo plazo mediante programas de lealtad para consumidores finales y condiciones comerciales preferenciales para retener a los negocios recurrentes. |
| **OE-09** | **Eficiencia Operativa** | Reducir los costos internos y los tiempos de ejecución mediante la automatización de los procesos logísticos y de despacho. |
| **OE-10** | **Liderazgo en Decisiones Basadas en Datos** | Consolidar una cultura de gestión analítica que aproveche el historial operativo para anticipar la demanda y respaldar cada decisión gerencial con información verificable. |
| **OE-11** | **Excelencia en la Cadena de Abastecimiento** | Fortalecer la alianza con proveedores para asegurar disponibilidad ininterrumpida, calidad y cumplimiento de plazos, operando con el menor costo posible. |

### 3.3 Origen de cada objetivo: cuatro reencuadrados, dos nuevos

| ID | Nombre | Origen | Antecedente en la base original |
|---|---|---|---|
| OE-06 | Desarrollo Omnicanal con foco en expansión mayorista | **Reencuadrado** | EVF04 `OE-01` (maximizar conversiones y ventas en la tienda online) |
| OE-07 | Rentabilidad por Volumen y Rotación | **Reencuadrado** | Tarea02 `OE-01` (maximizar la conversión → ingresos y márgenes) + TA06 «Objetivo estratégico general» (incrementar la rentabilidad) |
| OE-08 | Fidelización y Retención de Clientes | **NUEVO** | — sin antecedente estratégico (ver nota) |
| OE-09 | Eficiencia Operativa | **Reencuadrado** | EVF04 `OE-03` (automatizar el pipeline ETL para eliminar la intervención manual) |
| OE-10 | Liderazgo en Decisiones Basadas en Datos | **Reencuadrado** | Tarea02 `OE-03` (reducir el tiempo de toma de decisiones) + EVF04 `OE-02` (generar inteligencia de negocio) |
| OE-11 | Excelencia en la Cadena de Abastecimiento | **NUEVO** | — sin antecedente estratégico (ver nota) |

**Nota sobre cuáles son los dos nuevos.** El reparto pedido (4 reencuadrados + 2 nuevos) se
conserva, pero al contrastarlo con las quince declaraciones originales los dos **sin antecedente**
resultan ser **OE-08 y OE-11**, no OE-10 y OE-11:

- **OE-10 SÍ tiene antecedente, y doble.** Tarea02 `OE-03` («reducir el tiempo de toma de decisiones
  basadas en datos… en minutos en lugar de días, eliminando la dependencia de reportes manuales en
  hojas de cálculo») es, según el propio diagnóstico (§9, «lo que sí es reutilizable»), *la
  declaración que mejor predijo lo que se construyó*; y EVF04 `OE-02` («generar inteligencia de
  negocio») aporta el otro medio enunciado. Presentarlo como nuevo borraría el único hilo de
  continuidad analítica del proyecto.
- **OE-08 NO tiene antecedente estratégico.** Ninguno de los quince objetivos originales menciona
  fidelización, lealtad ni retención. La palabra «fidelizar» aparece **una sola vez en todo el
  material fundacional, y dentro de la VISIÓN de EVF04** («maximizar sus márgenes de ganancia y
  fidelizar a sus clientes»), no en un objetivo; la retención existía únicamente en el nivel
  **táctico** de EVF04 (`OT-02`, alcance «tienda del cliente»). Un objetivo estratégico de
  fidelización es, por tanto, nuevo.
- **OE-11 tampoco tiene antecedente**, y es la brecha más grande que la recuperación declaró (§8.3):
  ningún objetivo original menciona compras, abastecimiento, proveedores, cuentas por pagar,
  inventario, bodegas ni logística inversa.

---

## 4. NOTA DE ALINEACIÓN MISIÓN → VISIÓN → OBJETIVOS

La misión declara **qué hacemos hoy**: dar a negocios y consumidores acceso a un catálogo diverso a
precio competitivo, sin intermediarios, con abastecimiento, inventario y entrega confiables. La
visión declara **dónde queremos estar en 2030**: ser la red de distribución referente de la Costa
ecuatoriana, conectando proveedores, negocios y consumidores con decisiones basadas en datos. Los
seis objetivos son el puente entre ambas y se reparten sin solaparse.

Del lado del mercado, **OE-06** persigue el crecimiento en los dos segmentos que la misión nombra
—negocios y consumidores—, pero **no los trata como iguales**: reconoce que el B2C es la base
instalada que hoy sostiene el ingreso y sitúa el B2B como la vía de crecimiento por desarrollar.
Ese orden no es una concesión, es el diagnóstico: la venta verificada entra por tres canales de
atención al consumidor final y **no hay un solo cliente registrado como negocio** (§6.1), de modo
que «ampliar la participación de tiendas y minoristas que compran para revender» es literalmente
partir de cero en el segmento que la visión pone en el centro. **OE-08** convierte esa venta en
relación duradera, que es lo que sostiene el «referente» de la visión. Del lado del dinero, **OE-07** traduce «precios altamente
competitivos» en su única forma viable para un distribuidor: margen por volumen y rotación, no por
precio unitario. Del lado de la operación, **OE-11** asegura el eslabón de entrada (proveedores,
disponibilidad, calidad, plazo) y **OE-09** el de salida (logística y despacho al menor costo y
tiempo); juntos son el «cumplimiento exacto en cada pedido» de la misión y el «optimizando cada
eslabón» de la visión. Y **OE-10** es el objetivo que hace posibles a los otros cinco: la visión
compromete «anticipar la demanda» y «decisiones basadas en datos», y sin gestión analítica del
historial operativo los demás objetivos se dirigirían por intuición.

---

## 5. TRAZABILIDAD ESTRATÉGICO → TÁCTICO

Regla aplicada: solo se cita un `OTD-` cuando la pregunta de dirección que resuelve **sirve
directamente** al objetivo estratégico. Un mismo táctico puede sostener a dos objetivos (se indica);
donde no hay soporte suficiente **se declara el hueco** en la §6 en lugar de rellenar la traza.
Todos los IDs y nombres citados existen en `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md`
(**69 objetivos**, versión 4 del 2026-07-29, que incorporó OTD-VEN-16 para sostener este OE-06).
**Cobertura: los 69 objetivos tácticos quedan reclamados por al menos un objetivo estratégico**;
ninguno queda huérfano y ninguno se citó dos veces sin motivo.

| OE (ID + nombre) | Objetivos tácticos que lo sostienen | ¿Traza completa o con hueco? |
|---|---|---|
| **OE-06 · Desarrollo Omnicanal con foco en expansión mayorista** (11 tácticos) | **OTD-VEN-16** participación de cada canal en la venta del período, con monto, ticket promedio y % *(SIMPLE, ✔ en pantalla)* · **OTD-VEN-13** la misma participación mes a mes *(COMPUESTO, ETL)* · **OTD-VEN-07** valor promedio del pedido por período y canal · **OTD-VEN-01** cartera de pedidos por estado y canal · **OTD-VEN-06** evolución de ventas mes a mes y por categoría · **OTD-VEN-05** cuánto compra cada cliente (total, n.º de pedidos, última compra) · **OTD-VEN-02** pedidos y monto por vendedor · **OTD-VEN-15** venta acumulada contra la meta del período · **OTD-VEN-08** carritos abandonados sin llegar a pagar · **OTD-VEN-12** cobros en línea que fallan y por qué · **OTD-VEN-09** mezcla de formas de pago y su evolución | **CON HUECO, uno menos** — el **análisis de canales quedó CERRADO** en su versión foto por OTD-VEN-16, ya consultable en pantalla (la evolución mensual sigue pendiente del ETL, OTD-VEN-13). Persiste el hueco de fondo: **ningún táctico separa B2B de B2C**, y no por falta de informe sino porque el segmento del comprador **no se captura**. Ver §6.1 |
| **OE-07 · Rentabilidad por Volumen y Rotación** (13 tácticos) | **OTD-GER-10** margen por producto · **OTD-GER-03** ganancia por categoría y período · **OTD-GER-11** descuento total entregado por mes y producto · **OTD-GER-05** descuento efectivamente otorgado por cupón · **OTD-GER-07** efecto de las promociones sobre la venta *(REQUIERE VOLUMEN)* · **OTD-GER-02** balanza ventas vs. compras · **OTD-VEN-03** los 10 productos más vendidos («producto estrella») · **OTD-VEN-04** productos sin rotación («producto hueso») · **OTD-VEN-14** dinero devuelto por los clientes y su peso sobre la venta · **OTD-INV-04** rotación por categoría y período · **OTD-INV-07** dinero inmovilizado en mercancía, por categoría y bodega · **OTD-INV-08** sobre-stock por encima del tope máximo · **OTD-INV-09** evolución mensual del capital almacenado | **COMPLETA** — cubre las tres palancas del objetivo: margen (GER-03/10), rotación (VEN-03/04, INV-04/08/09) y volumen (GER-02, VEN-14). Salvedad declarada: GER-07 espera volumen y el margen usa costo vigente, sin histórico de costos |
| **OE-08 · Fidelización y Retención de Clientes** (18 tácticos) | **OTD-VEN-05** comportamiento de compra por cliente, con fecha de última compra *(también en OE-06)* · **OTD-VEN-10** cola de moderación: reseñas y preguntas sin atender · **OTD-VEN-11** calificación de cada producto y su evolución · **OTD-SOP-01** bandeja de reclamos abiertos · **OTD-SOP-02** cumplimiento del tiempo prometido (SLA) · **OTD-SOP-03** tiempo de resolución por tipo de problema · **OTD-SOP-04** qué tipos de problema generan más reclamos · **OTD-SOP-05** carga y cierre por agente · **OTD-SOP-06** tiempo hasta la primera respuesta · **OTD-SOP-07** tiempo de resolución por agente · **OTD-SOP-08** productos que más reclamos y devoluciones generan · **OTD-LOG-06** devoluciones de cliente en curso · **OTD-LOG-07** días de ciclo de la devolución · **OTD-LOG-08** motivos de devolución y destino de la mercancía · **OTD-LOG-10** reembolsos pagados al cliente · **OTD-GER-04** cupones vigentes, usos restantes y vencimiento · **OTD-GER-05** uso real y costo de cada cupón *(también en OE-07)* · **OTD-GER-06** promociones, campañas y banners vigentes | **CON HUECO** — abundante en **posventa y servicio** (Soporte completo, RMA completo) y en **incentivos comerciales** (cupones, promociones), pero **no existe programa de lealtad** ni medición alguna de **retención**. Ver §6.2 |
| **OE-09 · Eficiencia Operativa** (15 tácticos) | **OTD-LOG-12** tiempo por etapa del ciclo (dónde está el cuello de botella) · **OTD-LOG-01** cola de pedidos en espera de despacho · **OTD-LOG-02** seguimiento de envíos por estado y transportista · **OTD-LOG-03** cumplimiento de la fecha prometida, por transportista · **OTD-LOG-04** días reales de tránsito por transportista · **OTD-LOG-05** problemas de entrega, intentos y desenlace · **OTD-LOG-09** proporción de envíos que terminan en devolución · **OTD-LOG-11** costo de envío por zona y transportista · **OTD-INV-02** existencias por bodega, incluido lo reservado · **OTD-INV-03** kardex completo de entradas y salidas · **OTD-INV-05** ajustes de inventario y sus motivos · **OTD-INV-06** transferencias entre bodegas · **OTD-INV-10** mermas y sobrantes acumulados por motivo · **OTD-SOP-05** carga de trabajo por agente *(también en OE-08)* · **OTD-SOP-07** productividad por agente *(también en OE-08)* | **COMPLETA en tiempos, PARCIAL en costos** — los tiempos de ejecución están medidos de punta a punta (LOG-03/04/12) y la pérdida operativa también (INV-05/10), pero el **único costo interno medido es el flete** (LOG-11). Ver §6.4 |
| **OE-10 · Liderazgo en Decisiones Basadas en Datos** (7 tácticos + soporte transversal) | **OTD-GER-01** foto del día del negocio · **OTD-GER-08** registro de auditoría: quién hizo qué, con autor, fecha y detalle *(información verificable)* · **OTD-GER-09** intentos de acceso al sistema y su motivo de fallo · **OTD-VEN-15** avance de la venta contra la meta fijada *(también en OE-06)* · **OTD-VEN-06** evolución de ventas mes a mes y por categoría *(también en OE-06)* · **OTD-INV-04** rotación por categoría y período *(también en OE-07)* · **OTD-COM-12** evolución del costo de compra por producto y proveedor *(también en OE-11)*. **Soporte transversal**: los 39 objetivos COMPUESTOS del catálogo —los que recorren el histórico y comparan períodos— son por definición el sustrato de este objetivo, y los 29 informes SIMPLES ya implementados son su realización presente | **CON HUECO** — «respaldar cada decisión gerencial con información verificable» está cubierto (GER-01/08/09 + 29 informes en pantalla); **«anticipar la demanda» no tiene ningún táctico predictivo**. Ver §6.3 |
| **OE-11 · Excelencia en la Cadena de Abastecimiento** (15 tácticos) | **OTD-COM-01** órdenes de compra y su aprobación · **OTD-COM-02** deuda por proveedor y cuotas por vencer · **OTD-COM-03** puntualidad de nuestro pago al proveedor · **OTD-COM-04** gasto de compra por proveedor y mes · **OTD-COM-05** cumplimiento del plazo prometido por el proveedor · **OTD-COM-06** días reales del ciclo de compra · **OTD-COM-07** mercancía rechazada en puerta, por proveedor y motivo · **OTD-COM-08** defectuosos pendientes de devolver al proveedor · **OTD-COM-09** monto recuperado por mercancía defectuosa *(REQUIERE VOLUMEN)* · **OTD-COM-10** a qué proveedor conviene comprar cada producto (costo, plazo, preferido) · **OTD-COM-11** proveedores que entregan incompleto *(único informe simple aún no construido)* · **OTD-COM-12** evolución del costo de compra *(también en OE-10)* · **OTD-INV-01** productos bajo el mínimo, a reponer · **OTD-INV-08** sobre-stock: dejar de comprar *(también en OE-07)* · **OTD-GER-02** balanza compras vs. ventas *(también en OE-07)* | **COMPLETA** — es la traza más fuerte de las seis: los cuatro compromisos del objetivo tienen táctico propio — disponibilidad (INV-01, COM-01), calidad (COM-07, COM-08, COM-09), plazo (COM-05, COM-06, COM-11) y menor costo (COM-04, COM-10, COM-12, COM-02/03). Dos salvedades de ejecución: COM-09 espera volumen y COM-11 aún no está en pantalla |

**Resumen de la traza**

| OE | Tácticos que lo sostienen | Estado |
|---|---|---|
| OE-06 Desarrollo Omnicanal con foco en expansión mayorista | 11 | CON HUECO (B2B vs. B2C; el análisis de canales ya está cerrado) |
| OE-07 Rentabilidad por Volumen y Rotación | 13 | COMPLETA |
| OE-08 Fidelización y Retención | 18 | CON HUECO (programa de lealtad y medición de retención) |
| OE-09 Eficiencia Operativa | 15 | COMPLETA en tiempos, PARCIAL en costos |
| OE-10 Decisiones Basadas en Datos | 7 + los 39 compuestos | CON HUECO (anticipación de la demanda) |
| OE-11 Excelencia en Abastecimiento | 15 | COMPLETA |

---

## 6. HUECOS DE TRAZA

Cada hueco se declara con la verificación que lo prueba. **Nada de lo que sigue está insinuado como
existente**: son capacidades que hoy no están y que, si el negocio las quiere medir, exigen el mismo
método de tres capas del catálogo táctico (base de datos → backend → formulario) antes de poder
construir informe alguno.

### 6.1 OE-06 — El análisis de canales queda CERRADO; el corte B2B/B2C sigue sin ser medible

Este hueco se **partió en dos** al reencuadrar OE-06, y solo una mitad sigue abierta.

#### 6.1.a CERRADO — participación de la venta por canal (versión foto)

**OTD-VEN-16** existe, está implementado y se consulta en
`/operativo/informes/ventas` → «Participación de la venta por canal»
(`GET /api/informes/ventas/participacion-canal`, ADMIN/GERENTE/ANALISTA). Da por canal los
pedidos, el monto, el ticket promedio y el porcentaje de participación del período filtrado, y
es la evidencia directa de la primera mitad del objetivo. Cifras verificadas el 2026-07-29 sobre
los 19 meses completos (venta neta de cancelados, $5.498.570,35):

| Canal | Pedidos | Monto vendido | Ticket promedio | % de pedidos | % del monto |
|---|---|---|---|---|---|
| Tienda en línea (`web`) | 2.132 | $2.962.187,16 | $1.389,39 | 54,33 % | 53,87 % |
| Mostrador (`tienda`) | 990 | $1.388.194,13 | $1.402,22 | 25,23 % | 25,25 % |
| Teléfono (`telefono`) | 802 | $1.148.189,06 | $1.431,66 | 20,44 % | 20,88 % |

**Queda pendiente su versión de EVOLUCIÓN**, que es **OTD-VEN-13** (la misma participación mes a
mes): es **COMPUESTA** por definición —compara períodos— y por tanto se procesa en ClickHouse vía
el ETL orquestado con Airflow, junto con los otros 39 compuestos. No es un hueco de dato, es la
fase pendiente del pipeline.

#### 6.1.b ABIERTO — el segmento del comprador no se captura

**Verificado el 2026-07-29 contra la base real, en modo lectura:**

| Comprobación | Resultado |
|---|---|
| `pedido_canal_check` (CHECK del motor) | `canal ∈ ('web','tienda','telefono')` — **el medio, no el tipo de cliente** |
| `grupo_cliente` / `segmento_cliente` / `cliente_segmento` | **0, 0 y 0 filas** |
| `cliente.grupo_cliente_id` poblado | **0 de 72 clientes** |
| `cliente.tipo_identificacion` | **'cedula' en los 72 clientes** (ningún RUC que permita inferir «negocio») |
| `length(factura_venta.identificacion)` | **10 dígitos en las 3.887 facturas** — cédula de persona natural; un negocio ecuatoriano llevaría RUC de 13 |
| Columnas o tablas con `tipo_cliente`, `b2b`, `mayorista`, `segmento` | **ninguna en las 110 tablas** |
| Comportamiento de canasta por canal (¿alguien compra como mayorista?) | mediana **4-5 unidades** por pedido y máximo **24** en los tres canales; **cero pedidos de 50 unidades o más** |

**Precisión importante sobre el dato.** `pedido.canal` **sí existe y sí está explotado** por
OTD-VEN-16, OTD-VEN-13, OTD-VEN-07 y OTD-VEN-01 — pero `canal` es **la vía por la que entró el
pedido** (mostrador, teléfono, tienda en línea), **no el segmento del comprador**. Un negocio puede
comprar por la tienda en línea y un consumidor final por mostrador: agrupar por canal y llamarlo
«B2B vs. B2C» sería una lectura falsa del dato, y por eso **OTD-VEN-16 no lo hace**. El segmento
**no está en ninguna parte de la base**: las tres tablas previstas para ello están vacías y el
catálogo táctico ya lo registró como decisión de alcance abierta («Segmentación de clientes… la
brecha es de captura, no de volumen: ninguna pantalla asigna cliente a segmento», §12).

**Cómo el informe mide la ausencia en vez de disimularla.** OTD-VEN-16 incluye la columna
**«Clientes negocio (B2B)»** —clientes distintos con `grupo_cliente_id` asignado, la FK prevista
para el segmento mayorista— y un KPI del mismo nombre. Hoy valen **0 en los tres canales**. Es
deliberado: el informe no inventa una clasificación que el dato no soporta, y a la vez deja el
hueco a la vista cada vez que alguien lo abre.

**Consecuencia, y por qué justifica el objetivo tal como está redactado.** La mitad B2B de OE-06
**no está medida ni es medible hoy**, y lo que sí se puede afirmar con el dato es más fuerte que un
hueco de informe: **no hay evidencia de un solo cliente mayorista en 19 meses de operación** —ni por
identificación fiscal, ni por segmento asignado, ni por comportamiento de compra. Eso es
exactamente el diagnóstico que el texto vigente del objetivo declara: el B2C es la base real del
negocio y el canal mayorista es una **vía de crecimiento por construir**, no un segmento a
optimizar. La otra mitad (consolidar el B2C, participación por canal y por cliente) está medida
con 11 tácticos, uno de ellos ya en pantalla.

**Táctico futuro sugerido** — *no existe, no darlo por existente*:

> **OTD-VEN-17 (propuesto)** · «Conocer cuánto vende y cuánto crece cada segmento de cliente —negocio
> (B2B) frente a consumidor final (B2C)— y qué participación tiene cada uno en la venta total, por
> período.» · Clasificación: **COMPUESTO** (compara períodos). · **Prerrequisito de sistema, en tres
> capas**: (1) **BD** — poblar `grupo_cliente` con los segmentos del negocio y llenar
> `cliente.grupo_cliente_id` (la FK ya existe; no hace falta columna nueva); (2) **backend** —
> exponer el segmento en el alta y edición de cliente y arrastrarlo al informe; (3) **formulario** —
> selector de segmento en la ficha del cliente, sin el cual la columna seguirá vacía. Mientras las
> tres capas no estén, este objetivo permanece en **REQUIERE CAMBIO EN EL SISTEMA**. Cuando existan,
> OTD-VEN-16 gana la dimensión de segmento **sin cambiar de forma**: la columna
> «Clientes negocio (B2B)» dejará de ser 0 y bastará agrupar además por segmento.
>
> *(Nota de numeración: este propuesto era «OTD-VEN-16» en la versión 1 de este documento. El
> número 16 lo tomó el informe real de participación por canal al incorporarse al catálogo el
> 2026-07-29, y los propuestos de §6.2 corrieron un lugar por la misma razón.)*

### 6.2 OE-08 — No existe programa de lealtad; la retención no se mide

**Verificado el 2026-07-29 contra la base real:**

| Comprobación | Resultado |
|---|---|
| Tablas con `lealtad`, `punto`, `membresia`, `fideliz`, `recompensa` | **ninguna en las 110 tablas** |
| Columnas con `puntos`, `nivel`, `lealtad`, `membresia` | **ninguna** (el único `nivel` es `ubicacion_bodega.nivel`, ubicación física) |
| Tablas de lista de precios o de crédito por cliente | **ninguna** — no hay precio diferenciado ni condiciones de pago pactadas por cliente |
| Mecanismos de incentivo que **sí** existen | `cupon` (con `usos_por_cliente`), `uso_cupon`, `promocion`, `promocion_producto`, `campana`, `banner` |
| Clientes recurrentes en los datos | 65 de 72 con más de un pedido — **el hecho existe, pero ningún táctico lo mide como retención** |

**Lo que la traza de OE-08 sí tiene** (y por eso el objetivo no queda sin soporte): la relación de
largo plazo se sostiene hoy con **calidad de servicio** —Soporte completo (SOP-01 a SOP-08) y el
ciclo de devolución completo (LOG-06/07/08/10)—, con **la voz del cliente** (VEN-10, VEN-11) y con
**incentivo comercial** (GER-04, GER-05, GER-06). Y OTD-VEN-05 permite ver, cliente por cliente,
cuánto compra y **cuándo compró por última vez**, que es el insumo más cercano a la retención.

**Lo que NO existe**, y por tanto no puede aparecer en la traza:

1. **Programa de lealtad** (puntos, niveles, membresía, recompensa por acumulación) para el
   consumidor final: **no hay ninguna tabla ni columna**. No es un informe faltante, es una
   **capacidad de sistema inexistente** — construirla es un bloque nuevo de base de datos, backend y
   pantallas, no un objetivo táctico.
2. **Condiciones comerciales preferenciales** para el negocio recurrente: hoy el único vehículo es el
   cupón (que puede limitarse por cliente con `usos_por_cliente`). **No existe lista de precios por
   cliente ni condición de crédito pactada**, que es lo que la palabra «preferenciales» supone en un
   mayorista.
3. **Medición de la retención propiamente dicha**: cliente nuevo frente a recurrente, tasa de
   recompra, cliente que dejó de comprar. El catálogo ya registró **PC-03** («clientes nuevos vs.
   recurrentes por mes») y **PC-19** («clientes que reclaman una y otra vez») como puntos ciegos NO
   incorporados (§12.1), ambos hoy sin impedimento de datos.

**Tácticos futuros sugeridos** — *ninguno existe hoy*:

> **OTD-VEN-18 (propuesto)** · «Distinguir cada mes los clientes que compran por primera vez de los
> que ya compraban, y qué parte de la venta aporta cada grupo.» · **COMPUESTO** · Sin cambio de
> sistema: el dato ya está en `pedido.cliente_id/fecha_pedido` (es el PC-03 ya registrado).
>
> **OTD-VEN-19 (propuesto)** · «Detectar los clientes recurrentes que dejaron de comprar —cuántos
> días llevan sin pedido frente a su frecuencia habitual— para recuperarlos antes de perderlos.» ·
> **COMPUESTO** · Sin cambio de sistema: se deriva de VEN-05.
>
> **OTD-GER-12 (propuesto)** · «Seguir el programa de lealtad: puntos acumulados y canjeados, y qué
> venta traen de vuelta los clientes del programa.» · **REQUIERE CAMBIO EN EL SISTEMA** — exige
> crear el programa antes de poder medirlo (tablas de programa, saldo de puntos, movimiento y canje;
> backend de acumulación y canje en el checkout; pantallas de cliente y de gerencia). **Es una
> iniciativa futura de negocio, no una capacidad actual.**

### 6.3 OE-10 — «Anticipar la demanda» no tiene táctico que lo sostenga

El objetivo se apoya en dos compromisos y solo uno está trazado:

- **«Respaldar cada decisión gerencial con información verificable»: CUBIERTO.** GER-08 (auditoría
  con autor, fecha y detalle del cambio, 7.073 registros), GER-09 (intentos de acceso), GER-01 (foto
  del día) y los 29 informes SIMPLES ya consultables en pantalla.
- **«Anticipar la demanda»: SIN COBERTURA.** Ningún objetivo del catálogo —ni simple ni compuesto—
  es predictivo. Los tres más cercanos (**OTD-VEN-06** evolución mensual de la venta, **OTD-INV-04**
  rotación por categoría y **OTD-COM-12** evolución del costo de compra) describen el **pasado**;
  proyectar la demanda es otra clase de pregunta. El catálogo lo dejó fuera a propósito: el análisis
  de canasta (EX-1) y el embudo (EX-7) se remitieron a la analítica en ClickHouse, y la
  recuperación estratégica ya verificó que **ML, predicción de churn y precios dinámicos no existen
  en el sistema** (§6, veredicto de TA06/`OE4`).

Se añade una **dependencia declarada**: OE-10 se apoya en el pipeline ETL hacia ClickHouse, cuya
orquestación con Airflow **sigue pendiente** (Tarea02 `OE-05`, el único objetivo original vigente y
no cumplido). Mientras no exista, los 39 objetivos COMPUESTOS no se materializan y OE-10 vive solo
de sus 29 informes simples.

> **OTD-GER-13 (propuesto)** · «Proyectar la demanda esperada del próximo período por producto y
> categoría, a partir del historial de venta y de la rotación, para comprar y almacenar contra una
> previsión y no contra la intuición.» · **COMPUESTO** · Depende del pipeline ETL orquestado y de una
> técnica de proyección que hoy **no está implementada**.

### 6.4 OE-09 — Hueco menor: de los «costos internos» solo se mide el flete

El objetivo promete reducir **costos internos y tiempos**. Los tiempos están cubiertos de punta a
punta (LOG-03, LOG-04, LOG-12) y la pérdida de mercancía también (INV-05, INV-10), pero el único
costo interno con dato es el **costo de envío** (OTD-LOG-11, `envio.costo`, $32.723,25 en 2.848
envíos). **No existe en la base ningún costo de mano de obra, de almacenamiento ni de preparación
por pedido**, de modo que «reducir los costos internos» se sigue hoy por *proxies* —tiempo de ciclo,
merma y reproceso— y no por importe. Registrar un costo operativo por pedido sería un cambio de
sistema, no un informe.

---

## 7. TABLA DE REENCUADRE — texto original vs. texto vigente

Para trazabilidad académica: los cuatro objetivos heredados, con su redacción original **verbatim**
tal como la recuperó `docs/estrategico/RECUPERACION_ESTRATEGICA.md` §6, frente a su redacción
vigente. Los dos nuevos (OE-08 y OE-11) no aparecen aquí porque no tienen texto original que
contrastar.

| Nuevo ID | Texto ORIGINAL (verbatim, con su ID y fuente) | Texto VIGENTE (reencuadrado) | Qué cambió y por qué |
|---|---|---|---|
| **OE-06** Desarrollo Omnicanal con foco en expansión mayorista | **`OE-01` (EVF04, 2026-06-04)**: «Maximizar conversiones y ventas en la tienda online RetailMind Shop» · *Veredicto original: DESACTUALIZADO* | «Consolidar el liderazgo en la venta directa al consumidor final (B2C), que constituye la base actual del negocio, mientras se desarrolla estratégicamente el canal mayorista (B2B) como principal vía de crecimiento, ampliando la participación de tiendas y minoristas que compran para revender.» | Se conserva la intención (crecer en ventas) y se corrigen **dos alcances**. (1) **Canal**: la tienda en línea es 1 de 3 canales —53,87 % del monto vendido; mostrador 25,25 % y teléfono 20,88 %, medido por OTD-VEN-16—, no el negocio entero. (2) **Segmento, y aquí está el ajuste del 2026-07-29**: la redacción anterior («crecimiento sostenido **tanto** en B2B **como** en B2C») presuponía un equilibrio entre ambos que el dato no respalda — no hay **ningún** cliente registrado como negocio (§6.1.b). El texto vigente invierte la carga: nombra el B2C como **base actual** y el B2B como **vía de crecimiento por desarrollar**, con la conducta concreta que lo hará medible («ampliar la participación de tiendas y minoristas que compran para revender»). No es un objetivo más modesto: es el mismo objetivo dicho sin dar por hecho un segmento que hay que construir. Desaparece el vocabulario de «conversión» propio de la etapa de analítica de comportamiento |
| **OE-07** Rentabilidad por Volumen y Rotación | **`OE-01` (Tarea02, 2026-05-16)**: «Maximizar la tasa de conversión del usuario en retail — Desarrollar un sistema que identifique los patrones de comportamiento que llevan a una compra exitosa, permitiendo a los clientes retail incrementar su tasa de conversión y por ende sus ingresos.» · *Veredicto original: DESACTUALIZADO* · Complementado por el «Objetivo estratégico general» de **TA06**: «Incrementar la rentabilidad y competitividad internacional de RetailMind Shop…» · *Veredicto original: OBSOLETO* | «Maximizar los márgenes de beneficio no solo a través del precio unitario, sino impulsando la alta rotación de inventario y el volumen masivo de transacciones.» | Se invierte el **sujeto beneficiario**: ya no son «los clientes retail» a quienes se vende software, sino el propio negocio. Y se cambia la **palanca**: de la conversión del usuario al **margen de distribución por volumen y rotación**, que es como gana un mayorista. Se elimina «competitividad internacional», sin sustento (1 país, 3 zonas de envío) |
| **OE-09** Eficiencia Operativa | **`OE-03` (EVF04, 2026-06-04)**: «Automatizar el pipeline ETL para garantizar disponibilidad continua de datos en ClickHouse» · *Veredicto original: DESACTUALIZADO* | «Reducir los costos internos y los tiempos de ejecución mediante la automatización de los procesos logísticos y de despacho.» | Sobrevive la intención (**automatizar para eliminar la intervención manual**) y se desplaza su **objeto**: del pipeline de datos a la **operación física** (preparación, despacho, entrega, logística inversa), que es donde el negocio gasta tiempo y dinero. Se retira «disponibilidad continua en ClickHouse», que contradice el principio vigente de degradación (con ClickHouse apagado el sistema funciona). **La automatización del ETL con Airflow no se pierde**: sigue vigente como medio de OE-10 |
| **OE-10** Liderazgo en Decisiones Basadas en Datos | **`OE-03` (Tarea02, 2026-05-16)**: «Reducir el tiempo de toma de decisiones basadas en datos — Proveer dashboards e informes en tiempo real que permitan a los clientes retail tomar decisiones de negocio en minutos en lugar de días, eliminando la dependencia de reportes manuales en hojas de cálculo.» · *Veredicto original: DESACTUALIZADO, «el más cercano a cumplido de los 15»* · Junto con **`OE-02` (EVF04)**: «Generar inteligencia de negocio a partir de datos de comportamiento de usuarios» · *Veredicto original: DESACTUALIZADO* | «Consolidar una cultura de gestión analítica que aproveche el historial operativo para anticipar la demanda y respaldar cada decisión gerencial con información verificable.» | Dos correcciones y una ampliación. **Beneficiario**: de «los clientes retail» a las jefaturas del propio negocio. **Fuente del dato**: de «comportamiento de usuarios» al **historial operativo transaccional**, que es de donde salen los 69 objetivos tácticos y los 29 informes. **Ampliación**: se agrega «anticipar la demanda», que el original no pedía — y que hoy es el hueco declarado en §6.3 |

**Objetivos originales que NO se reencuadran** (y qué se hizo con ellos):

| Original | Veredicto en la recuperación | Destino en esta base |
|---|---|---|
| `OE-04` (EVF04) — seguridad mediante RBAC | **VIGENTE**, cumplido y superado | No es objetivo: es **capacidad transversal ya construida**, gobernada por la constitución (9 roles, RLS, horario, GRANT por columna) |
| `OE-05` (Tarea02) — automatizar el ETL con Airflow | **VIGENTE**, pendiente | **No derogado**: continúa como **medio técnico pendiente de OE-10** |
| `OE-02` (Tarea02) — escalar de 100K a 1.6M registros | DESACTUALIZADO | Derogado: la cifra caducó y el crecimiento real fue transaccional |
| `OE-04` (Tarea02) — portabilidad entre motores de BD | **OBSOLETO** | Derogado: el sistema apostó deliberadamente lo contrario (seguridad **dependiente del motor** PostgreSQL) |
| `OE-05` (EVF04) — escalabilidad y portabilidad | DESACTUALIZADO | Derogado como objetivo estratégico; la contenerización completa sigue como **deuda técnica declarada** |
| `OEG`, `OE1`, `OE2`, `OE3` (TA06) — pivote internacional (adquisición digital, marketplaces y APIs, cloud/Kubernetes) | **OBSOLETOS** (4 de los 5 obsoletos del diagnóstico) | Derogados en bloque: ninguna de esas piezas existe, y la constitución v2.0.0 ya trata la internacionalización como aspiración no implementada |
| `OE4` (TA06) — inteligencia de negocio global con ML | DESACTUALIZADO | Absorbido parcialmente por **OE-10**, sin sus mecanismos inexistentes (ML, churn, precios dinámicos, mercados internacionales) |

---

## 8. Anexo — verificaciones ejecutadas para este documento

Todas de **solo lectura**, el 2026-07-29, contra la base `retailmind` vía el MCP de consulta. No se
modificó ningún archivo del proyecto salvo la creación de este documento.

| Afirmación verificada | Consulta | Resultado |
|---|---|---|
| Distribución de pedidos por canal | `GROUP BY pedido.canal` | web 2.213 · tienda 1.030 · telefono 840 |
| Valores admitidos del canal | `pg_constraint` → `pedido_canal_check` | solo `'web'`, `'tienda'`, `'telefono'` — es el medio, no el segmento |
| Participación por canal, neta de cancelados *(v2, la que sostiene OTD-VEN-16)* | consulta del informe sobre los 19 meses | web 54,33 % / 53,87 % · tienda 25,23 % / 25,25 % · telefono 20,44 % / 20,88 % (pedidos / monto); suman **100,00 %** y $5.498.570,35 = $5.716.436,55 − $217.866,20 de cancelados |
| Segmentación de clientes vacía | `count(*)` en `grupo_cliente`, `segmento_cliente`, `cliente_segmento` | 0 · 0 · 0 |
| Ningún cliente clasificado | `count(*) FROM cliente WHERE grupo_cliente_id IS NOT NULL` | 0 de 72 |
| Ningún indicio de cliente-empresa | `GROUP BY cliente.tipo_identificacion` | 'cedula' × 72 (sin RUC) |
| Ninguna factura a nombre de un negocio *(v2)* | `GROUP BY length(factura_venta.identificacion)` | **10 dígitos en las 3.887 facturas** (cédula; el RUC ecuatoriano tiene 13) |
| Ningún pedido con conducta mayorista *(v2)* | unidades por pedido (`sum(pedido_detalle.cantidad)`) por canal | mediana 4-5 · máximo 24 · **0 pedidos de ≥50 unidades en los tres canales** |
| Ninguna estructura B2B/B2C | `information_schema.columns` con `tipo_cliente`, `b2b`, `mayorista`, `segmento` | ninguna coincidencia |
| Ningún programa de lealtad | `information_schema.tables/columns` con `lealtad`, `punto`, `membresia`, `fideliz`, `recompensa`, `nivel` | solo `ubicacion_bodega.nivel` (ubicación física) |
| Vehículos de incentivo existentes | `information_schema.tables` con `cupon`, `promo`, `descuento`, `precio`, `lista`, `credito` | `cupon`, `uso_cupon`, `promocion`, `promocion_producto` — **ninguna lista de precios ni crédito por cliente** |
| Recurrencia real de la cartera | clientes con más de un pedido | 65 de 72 |
| Existencia de los 69 `OTD-` citados | contraste ID a ID contra `CATALOGO_OBJETIVOS_TACTICOS.md` §§3-8 | los 69 existen; los 69 quedan reclamados por algún OE |
| Estado de implementación citado | `CATALOGO_OBJETIVOS_TACTICOS.md` §11.5 y `PATRON_INFORMES.md` | **29 de 30** informes simples implementados (v2: OTD-VEN-16 sumó uno a cada lado); pendiente OTD-COM-11 |
| OTD-VEN-16 responde y respeta la segregación *(v2)* | `GET /api/informes/ventas/participacion-canal` con JWT de cada rol | **200** con ADMIN, GERENTE y ANALISTA; **403** con BODEGA, DESPACHO, VENDEDOR, COMPRAS y SOPORTE |
