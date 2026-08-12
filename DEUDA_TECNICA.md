# Deuda técnica — RetailMind

Registro de lo que este sistema **no** hace, o hace apoyado en algo que puede romperse.
Reestructurado el **2026-08-07** a partir del levantamiento del 2026-08-06.
Última incorporación: **2026-08-11**. Entraron **C-17** (la serie de la década es
demasiado regular y el modelo de previsión no supera al ingenuo) y **C-18** (constantes
de configuración dimensionadas para el volumen anterior).
Recuento vigente: **A = 0 · B = 28 · C = 17**.

> **Las cifras del sistema cambiaron el 2026-08-10/11** con la carga masiva a 2.999.991
> pedidos en una década (2025-2034). Toda entrada anterior a esa fecha que cite conteos
> —4.083 pedidos, 1.406 posiciones, 66.082 filas del DWH— describe el estado PREVIO; el
> defecto que documenta sigue vigente salvo que se diga lo contrario, pero su magnitud
> hay que releerla contra las cifras de hoy, que están en `CLAUDE.md`.

## Cómo se lee este archivo

Tres categorías, porque se defienden de forma distinta:

| Sección | Qué contiene | Cómo se defiende |
|---|---|---|
| **A. DEFECTOS** | Se comporta mal. Debería arreglarse. | No se defiende: se arregla. |
| **B. FUERA DE ALCANCE** | No implementado a propósito. | Se defiende, no se disculpa. |
| **C. FRAGILIDADES CONOCIDAS** | Funciona hoy, apoyado en un supuesto que puede romperse sin aviso. | Se explica y se vigila. |
| **D. Resuelto (histórico)** | Lo cerrado, por fase. | Evidencia de que el registro se mantiene vivo. |

Cada entrada usa la misma **ficha de seis campos**:

| Campo | Para qué sirve |
|---|---|
| **Qué es** | Una frase. El hecho, sin adjetivos. |
| **Dónde** | Archivo y línea, o tabla/objeto de BD. Verificable. |
| **Si no se toca** | La consecuencia real, no la hipotética. |
| **Costaría** | Orden de magnitud del arreglo (columna / trigger / fase). |
| **Sustentación** | `Sí — <cómo se cuenta>` / `No`. |
| **Verificado** | Fecha + la consulta o el grep que lo respalda. |

**No hay columna `Severidad`.** La categoría ya carga esa información: todo lo de **B**
es intencional y todo lo de **C** funciona hoy (severidad = 0 hasta que el supuesto se
rompe). El campo **Verificado** es el que evita que el archivo vuelva a envejecer:
obliga a citar la evidencia, no la memoria.

---

# A. DEFECTOS

> **Vigentes: 0.**
>
> Las tres entradas que ha tenido esta sección —**A-1** (ventana horaria desviada),
> **A-2** (`GET /api/gerencia/metas/vigente` en HTTP 500) y **A-3** (la pantalla de
> gestión de datos borraba `fact_eventos` por una columna que no es clave)— están
> cerradas y en la sección **D**, con lo que se hizo y su verificación.
>
> A-3 se cerró **retirando la escritura**, no reconstruyendo la tabla: `fact_eventos`
> sigue sin identificador único y eso queda vivo como fragilidad **C-15**.

---

# B. FUERA DE ALCANCE

Límites declarados. Se evaluaron, se dimensionaron y se pospusieron; no son pendientes
olvidados. **28 entradas.**

### B-1 · Trazabilidad por lote y vencimiento (FEFO)

- **Qué es**: la tabla `lote` existe y está vacía; no hay captura de lote en la
  recepción, ni stock por lote, ni salida FEFO en el despacho.
- **Dónde**: tabla `lote` (0 filas); FK ya previstas en `movimiento_inventario.lote_id`
  y `recepcion_detalle.lote_id`.
- **Si no se toca**: nada. El flujo retail general no lo necesita; el sistema es
  coherente sin ello.
- **Costaría**: una fase propia — toca recepción, inventario, kardex y despacho.
- **Sustentación**: **Sí**, como decisión de alcance: *se evaluó, se dimensionó y se
  pospuso*, con las FK dejadas listas. Eso es diseño, no omisión (ver `ROADMAP.md`).
- **Verificado** (2026-08-07): `SELECT count(*) FROM lote;` → **0**.

### B-2 · `ajuste_inventario.estado = 'borrador'` sin flujo

- **Qué es**: el CHECK admite `'borrador'`, pero un borrador aplicable exigiría una
  tabla de detalle de líneas del ajuste, que no existe.
- **Dónde**: `ajuste_inventario`; el ajuste escribe el movimiento de kardex directo al
  aplicarse.
- **Si no se toca**: nada. `'aplicado'` y `'anulado'` (con contramovimiento) cubren el
  ciclo real.
- **Costaría**: una tabla de detalle + su flujo de aplicación.
- **Sustentación**: **Sí** — un estado admitido por el CHECK y no usado es una
  extensión prevista, no un bug.
- **Verificado** (2026-08-07): `WHERE estado='borrador'` → **0 filas**.

### B-3 · Sin método contra-entrega en el checkout online

- **Qué es**: no existe pago contra-entrega.
- **Dónde**: checkout online (`CarritoService.checkout`).
- **Si no se toca**: nada. Se excluyó porque rompería el invariante «el pedido online
  nace PAGADO».
- **Costaría**: decidir el estado inicial del pedido y su compuerta de cobro — es un
  cambio del invariante, no una opción de pago más.
- **Sustentación**: **Sí** — el invariante es explicable y la exclusión se deriva de él.
- **Verificado** (2026-08-06): 0 coincidencias de `contra.?entrega` en el código.

### B-4 · Sin notificación proactiva al cliente (email / push)

- **Qué es**: no hay canal de correo ni push. El cliente se entera de novedades en
  «Mis Pedidos» y en el timeline del envío.
- **Dónde**: no existe infraestructura de correo en ninguna fase.
- **Si no se toca**: el cliente debe entrar a mirar. Funcionalmente completo, no
  proactivo.
- **Costaría**: infraestructura propia (SMTP/proveedor, plantillas, reintentos, bajas).
- **Sustentación**: **Sí** — es integración externa, no lógica de negocio faltante.
- **Verificado** (2026-08-06): 0 coincidencias de `JavaMailSender` / `smtp`.

### B-5 · GERENTE conserva acceso total a tickets

- **Qué es**: tras crear el rol SOPORTE, GERENTE sigue viendo y gestionando tickets.
- **Dónde**: matriz de roles (`SecurityConfig` + grants).
- **Si no se toca**: solapamiento de responsabilidades; ningún dato en riesgo.
- **Costaría**: cambio de matriz (ruta + nav + grants), decidido aparte.
- **Sustentación**: **Sí** — se mantuvo por continuidad al introducir el 9.º rol.
- **Verificado** (2026-08-06): decisión de matriz, documentada en la fase 2a.

### B-6 · Soporte ve todos los tickets (sin RLS por agente)

- **Qué es**: no hay aislamiento por agente; la bandeja filtra «míos» / «sin asignar»
  en la UI, no en el motor.
- **Dónde**: `ticket_soporte`, políticas RLS (hay `pol_soporte`, no una por agente).
- **Si no se toca**: nada, para un equipo pequeño.
- **Costaría**: una política RLS por `asignado_usuario_id`.
- **Sustentación**: **Sí** — el aislamiento por agente es política de organización, y
  el mecanismo (RLS) ya está probado en el cliente.
- **Verificado** (2026-08-06): políticas de `ticket_soporte` sin filtro por agente.

### B-7 · Reapertura solo desde `resuelto`; `cerrado` es terminal

- **Qué es**: si el cliente responde un ticket `'resuelto'` se reabre a `'en_proceso'`;
  `'cerrado'` no se reabre.
- **Dónde**: guardia de transición en `SoporteService`.
- **Si no se toca**: nada; el mensaje al cliente es claro.
- **Costaría**: decidir política de reapertura y su auditoría.
- **Sustentación**: **Sí** — un estado terminal es una decisión de proceso.
- **Verificado** (2026-08-06): guardia explícita en el código.

### B-8 · `devolucion_detalle.accion` — solo `reembolso` tiene flujo

- **Qué es**: el CHECK admite `cambio` y `credito`, y **ya hay filas con `cambio`**
  (dato de seed), pero ninguna rama del flujo las procesa.
- **Dónde**: `devolucion_detalle.accion`; `DevolucionService`.
- **Si no se toca**: esas filas son dato histórico inerte. Ningún cálculo las usa.
- **Costaría**: una rama por acción tras la inspección (pedido de reposición o nota de
  crédito de tienda).
- **Sustentación**: **Sí**, con el matiz: *el modelo lo prevé, el flujo implementa una*.
- **Verificado** (2026-08-07): `WHERE accion='cambio'` → **2 filas**.

### B-9 · Reembolso sin asiento negativo en `pago` / `transaccion_pago`

- **Qué es**: existe la tabla `reembolso` (85 filas) y la devolución guarda monto,
  método y fecha, pero no se crea una transacción negativa; el pago original sigue
  `'completado'`. `transaccion_pago.tipo` solo admite `autorizacion` / `captura`.
- **Dónde**: `reembolso`, `devolucion.monto_reembolsado`, `transaccion_pago.tipo`.
- **Si no se toca**: el reembolso es rastreable pero no *contable*. Ya está declarado en
  los informes que lo tocan (LOG-10 dice qué registro usa).
- **Costaría**: modelar la nota de reembolso como tipo de transacción + su aplicación.
- **Sustentación**: **Sí** — depende de una pasarela real; el pago es SIMULADO por
  diseño en todo el sistema.
- **Verificado** (2026-08-07): `SELECT count(*) FROM reembolso;` → **85**.

### B-10 · Guía de retorno sin costo ni tracking

- **Qué es**: `guia_retorno` es un número simulado `RET-…`; no hay tarifa, peso ni
  `seguimiento_envio` para el viaje inverso.
- **Dónde**: `devolucion.guia_retorno`.
- **Si no se toca**: el historial de la devolución cumple el rol de trazabilidad a
  nivel de proceso.
- **Costaría**: tarifar el retorno y crear su cadena de seguimiento.
- **Sustentación**: **Sí** — simetría con el pago simulado.
- **Verificado** (2026-08-06): formato `RET-fecha-rand`, sin tabla de seguimiento.

### B-11 · `rechazado` en inspección consume cupo devolvible

- **Qué es**: un ítem rechazado (daño imputable al cliente) cuenta como devuelto y no
  puede volver a solicitarse. Solo las devoluciones rechazadas *por soporte* liberan
  cupo.
- **Dónde**: `DevolucionService.elegibilidad()`.
- **Si no se toca**: no hay apelación. Evita reintentos infinitos del mismo ítem.
- **Costaría**: un flujo de apelación con su propia autoridad.
- **Sustentación**: **Sí** — es una regla de negocio elegida, con su contrapartida
  declarada.
- **Verificado** (2026-08-06): guardia en el cálculo de elegibilidad.

### B-12 · VENDEDOR consulta el tablero RMA sin acciones

- **Qué es**: conserva ruta y SELECT sobre devoluciones, sin transiciones.
- **Dónde**: `SecurityConfig` + grants de `devolucion`.
- **Si no se toca**: nada; es visibilidad de lectura.
- **Costaría**: retirar ruta + nav + grants.
- **Sustentación**: **Sí** — continuidad con la pantalla anterior.
- **Verificado** (2026-08-06): matriz rol × endpoint del RMA.

### B-13 · Pedido `no_entregado` sin reingreso formal de stock

- **Qué es**: «devolver al almacén» deja el envío `'devuelto'` y el pedido
  `'no_entregado'` **sin mover stock**. La mercancía vuelve físicamente pero el
  inventario sigue descontado.
- **Dónde**: `VentasService` (novedades de envío, script 44); 121 pedidos en ese estado.
- **Si no se toca**: el reingreso y el reembolso se gestionan por ticket de soporte +
  decisión de gerencia (manual).
- **Costaría**: un flujo de inspección de bodega para no entregados, reutilizando el
  patrón RMA con `entrada_devolucion_cliente`.
- **Sustentación**: **Sí** — el criterio es coherente con el RMA: *el kardex solo se
  mueve tras inspección física*, nunca por un cambio de estado administrativo.
- **Verificado** (2026-08-07): 121 pedidos en estado `no_entregado`.

### B-14 · `no_entregado` es terminal: sin re-despacho

- **Qué es**: un pedido devuelto al almacén no puede volver a despacharse (la compuerta
  exige `'preparado'`).
- **Dónde**: guardia de transición de despacho.
- **Si no se toca**: la vía es que el cliente vuelva a comprar, o que soporte gestione
  el caso.
- **Costaría**: una transición `no_entregado` → `preparado` con su guardia.
- **Sustentación**: **Sí** — se cruza con B-13: sin reingreso de stock, re-despachar
  vendería mercancía que el inventario no tiene.
- **Verificado** (2026-08-06): compuerta de `/despacho` exige `preparado`.

### B-15 · Preparación sin picking por ítem ni asignación por operario

- **Qué es**: `'preparado'` es un interruptor de todo el pedido; no hay confirmación
  línea a línea ni ubicación de estantería (no existe en el modelo).
- **Dónde**: cola de preparación (`/operativo/ventas/preparacion`).
- **Si no se toca**: la trazabilidad de quién preparó vive solo en el historial.
- **Costaría**: se cruza con **B-1** (el picking real por ítem quiere lote/FEFO).
- **Sustentación**: **Sí** — dependencia declarada con B-1.
- **Verificado** (2026-08-06): no existe tabla de detalle de picking.

### B-16 · Override de transportista sin catálogo tarifado

- **Qué es**: despacho puede elegir cualquier transportista/método activos aunque la
  zona no tenga tarifa para esa combinación.
- **Dónde**: `POST /pedidos/{id}/despacho` (transportista y método opcionales).
- **Si no se toca**: nada; el override queda registrado en historial y seguimiento.
- **Costaría**: validar la combinación contra el catálogo de tarifas.
- **Sustentación**: **Sí** — el override existe *justamente* para excepciones
  operativas; validarlo lo anularía.
- **Verificado** (2026-08-06): el override se registra, no se restringe.

### B-17 · Sin tope máximo en cupones porcentuales

- **Qué es**: no se puede expresar «20 % hasta $50». El único límite natural es el
  subtotal.
- **Dónde**: tabla `cupon` (sin columna de tope).
- **Si no se toca**: un porcentaje alto sobre un carrito grande descuenta sin techo.
- **Costaría**: una columna + un `min()` en `DescuentosService.validarCupon`.
- **Sustentación**: **Sí**, y es el ítem más barato del archivo — se cita bien como
  ejemplo de deuda dimensionada.
- **Verificado** (2026-08-06): `cupon` tiene 14 columnas, ninguna `%tope%`.

### B-18 · Cupones solo en el checkout ONLINE

- **Qué es**: el pedido interno (vendedor) no tiene campo de cupón. `aplicarCupon` es
  genérico pero ningún endpoint interno lo invoca.
- **Dónde**: `CarritoService:271` es el único invocador.
- **Si no se toca**: las PROMOCIONES sí aplican a pedidos internos (van dentro de
  `crearPedido`); solo el cupón queda fuera.
- **Costaría**: un campo en el alta de pedido interno + la llamada.
- **Sustentación**: **Sí** — la capa genérica ya existe; falta el punto de entrada.
- **Verificado** (2026-08-06): un solo call site de `aplicarCupon`.

### B-19 · Promociones solo por producto; sin precio promocional en la vitrina

- **Qué es**: `promocion_producto` no admite categorías ni promociones de carrito
  completo, y el precio rebajado se muestra en carrito/checkout pero no en `/shop` ni
  en el detalle del producto.
- **Dónde**: `ProductoCatalogoService` (la señal `descuentoPromocional` existe; falta
  pintarla).
- **Si no se toca**: el descuento se aplica correctamente, pero se descubre tarde.
- **Costaría**: la vitrina es pintar una señal que ya se calcula; las promociones por
  categoría son modelo nuevo.
- **Sustentación**: **Sí**, distinguiendo las dos mitades: una es cosmética, la otra es
  alcance.
- **Verificado** (2026-08-06): la señal existe en el servicio de catálogo.

### B-20 · `uso_cupon` sin devolución del cupo al cancelar o devolver

- **Qué es**: si un pedido con cupón se cancela o termina en devolución total, el uso
  **no** se libera. Solo el DELETE administrativo decrementa, por trigger.
- **Dónde**: `uso_cupon`; ninguna ruta libera el uso.
- **Si no se toca**: un cliente puede «gastar» su cupo en un pedido que no se concretó.
- **Costaría**: decidir la política (liberar al cancelar, retener en devolución) y una
  transición.
- **Sustentación**: **Sí** — es decisión de negocio, no un olvido técnico.
- **Verificado** (2026-08-06): sin ruta de liberación.

### B-21 · Reseñas sin edición/borrado por el cliente ni filtro de lenguaje

- **Qué es**: una reseña por producto, sin edición ni borrado; la moderación manual de
  ADMIN/GERENTE es la única barrera de contenido.
- **Dónde**: paquete `resenas/`.
- **Si no se toca**: nada estructural; el cliente no puede corregir un error de
  redacción.
- **Costaría**: endpoints de edición con re-moderación.
- **Sustentación**: **Sí** — el UPDATE está revocado a `grp_cliente` **a propósito**
  (script 43), así que la ausencia es coherente con el motor.
- **Verificado** (2026-08-06): 0 `PutMapping` / `DeleteMapping` en `resenas/`.

### B-22 · Rechazo TOTAL en puerta imposible en una línea

- **Qué es**: `recepcion_detalle` tiene `CHECK (cantidad_recibida > 0)`. Para rechazar
  mercancía hay que recibir al menos 1 unidad de esa línea.
- **Dónde**: `recepcion_detalle`, constraint de tabla.
- **Si no se toca**: si el proveedor entrega TODO dañado, se recibe y se marca
  defectuoso después (sale de stock con kardex `salida_devolucion_proveedor`).
- **Costaría**: relajar el CHECK y revisar la cuenta de pendientes de la OC.
- **Sustentación**: **Sí** — hay camino alternativo y está implementado.
- **Verificado** (2026-08-07): `CHECK ((cantidad_recibida > 0))`.

### B-23 · Devolución a proveedor: cuarentena, nota de crédito, anulación y reposición

Cuatro límites de la misma fase (script 45), agrupados porque se defienden juntos:

- **Qué es**: (a) no hay bodega de cuarentena física; (b) `monto_credito` es simulado y
  no compensa `cuenta_por_pagar`; (c) una `devolucion_proveedor` registrada no admite
  `'anulada'` ni edición; (d) la resolución `reposicion` reingresa en un solo paso, sin
  recepción física separada.
- **Dónde**: `devolucion_proveedor` (estados `registrada/enviada/resuelta/cerrada`),
  `item_defectuoso`, `bodega`.
- **Si no se toca**: el defectuoso es documental (con su bodega de origen); deshacer un
  agrupamiento erróneo exige intervención de ADMIN en BD.
- **Costaría**: (a) una bodega y su flujo; (b) modelar la nota de crédito como
  documento aplicable a CxP; (c) una transición más con guardia; (d) un estado
  `reposicion_en_transito` + recepción de BODEGA.
- **Sustentación**: **Sí** — (b) es la misma familia que B-9 (todo lo contable es
  simulado en este sistema, de forma consistente).
- **Verificado** (2026-08-07): 0 bodegas con nombre `%cuarentena%`; estados de
  `devolucion_proveedor` sin `anulada`.

### B-24 · Trazabilidad futura: producto, marketing e historial de ticket

- **Qué es**: crear/editar producto no registra autor; las entidades de marketing
  (cupón, promoción, campaña, banner) no guardan quién las creó o activó; los cambios de
  estado de ticket no tienen tabla de historial (solo asignado y fechas).
- **Dónde**: `log_auditoria.tabla` no contiene `producto` / `cupon` / `promocion` /
  `campana` / `banner`; no existe tabla de historial de ticket.
- **Si no se toca**: esas acciones no son atribuibles a una persona.
- **Costaría**: el mismo patrón ya probado — columna de autor + `AuditoriaService` +
  grant INSERT de `log_auditoria` al rol ejecutor.
- **Sustentación**: **Sí**, y fuerte: *el patrón está resuelto y probado en 5 entidades*
  (script 42); extenderlo es repetición, no diseño.
- **Verificado** (2026-08-06): valores distintos de `log_auditoria.tabla`.

### B-25 · `log_auditoria` sin registro del checkout online ni de la preparación

- **Qué es**: `grp_cliente` NO recibe INSERT sobre `log_auditoria`, y la preparación de
  bodega se traza solo en `historial_estado_pedido`.
- **Dónde**: grants de `log_auditoria`.
- **Si no se toca**: el rastro del checkout es `pedido.cliente_id` + canal `'web'` +
  historial con RLS. Suficiente para reconstruir, no centralizado.
- **Costaría**: si se quisiera log central, un trigger SECURITY DEFINER en vez de un
  grant.
- **Sustentación**: **Sí, y es un argumento de seguridad**: no se abre un canal de
  escritura del log de auditoría a un rol que llega desde internet. *La ausencia es la
  decisión.*
- **Verificado** (2026-08-06): `grp_cliente` sin INSERT sobre `log_auditoria`;
  `grp_bodega` sí lo tiene desde el script 45 (marcado de defectuosos).

### B-26 · OTD-GER-07 (efecto de promociones): muestra insuficiente

- **Qué es**: 123 líneas con descuento promocional frente a una base de miles.
  Densificarlo exigiría reasignar ventas ya sembradas.
- **Dónde**: `docs/tactico/AUDITORIA_TRANSVERSAL_SEED.md` §12.
- **Si no se toca**: el informe existe y **declara su muestra en pantalla** (ordenado
  por volumen, nunca por la variación).
- **Costaría**: reasignar ventas del seed — se descartó por no falsear el histórico.
- **Sustentación**: **Sí** — es el caso modelo de «limitación aceptada y publicada
  encima de la cifra».
- **Verificado** (2026-08-06): campo `salvedad` en la respuesta de GER-07.

### B-27 · Segmento B2B/B2C no medible

- **Qué es**: `pedido.canal` es el MEDIO (web/tienda/teléfono), no el segmento.
  `grupo_cliente` está vacío y no hay facturas con RUC.
- **Dónde**: `docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md` (OTD-VEN-16).
- **Si no se toca**: el informe **mide la ausencia** (`clientes_negocio = 0`) en vez de
  inventar una clasificación.
- **Costaría**: capturar el segmento en el alta de cliente — dato de negocio que no
  existe.
- **Sustentación**: **Sí**, y es de los mejores: *se prefirió publicar un cero honesto a
  derivar un segmento de una columna que significa otra cosa*. Además la población se
  midió y resultó homogénea (99,94 % de líneas piden 1–4 unidades: no hay mayorista).
- **Verificado** (2026-08-06): `grupo_cliente` vacío; 0 facturas con RUC.

### B-28 · Alerta de abandono: no viable como modelo entrenado

- **Qué es**: no hay etiqueta de abandono y el generador del seed sortea al cliente con
  peso constante — **nadie abandona nunca**. La correlación entre el mejor predictor y
  el resultado real es **0,039**.
- **Dónde**: `docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md` §5.2.2;
  `fact_alerta_cliente`.
- **Si no se toca**: se publica un modelo del **proceso** (supervivencia exponencial),
  no uno aprendido, **con su lift (1,99×), su muestra (14 positivos) y su valor p
  (0,1019) en las tres primeras tarjetas**.
- **Costaría**: datos reales de comportamiento; no es cuestión de código.
- **Sustentación**: **Sí, y es la entrada más valiosa del archivo**: el sistema publica
  un modelo *diciendo que no supera al azar*. Eso es criterio, no una carencia.
- **Verificado** (2026-08-02): correlación 0,039; dictamen «¿Supera al azar? NO» visible
  en pantalla.

---

# C. FRAGILIDADES CONOCIDAS

Funciona hoy. Apoyado en algo que puede romperse sin aviso. **15 entradas.**

### C-2 · `fecha_creacion DEFAULT now()` es el instante de INICIO de transacción

- **Qué es**: el kardex se lee y se encadena por `(fecha_creacion, id)`, y `now()` en
  PostgreSQL devuelve el instante en que **arrancó la transacción**, no el del INSERT.
  Dos transacciones solapadas pueden grabar movimientos cuyo orden por fecha no es el
  orden real de ejecución.
- **Dónde**: `movimiento_inventario.fecha_creacion`, `column_default = now()`.
- **Si no se toca**: hoy nada — no hay concurrencia real de escritura de kardex, y el
  desempate por `id` resuelve el empate. El riesgo aparece con carga concurrente.
- **Costaría**: `clock_timestamp()` como default, o aceptar el desempate por `id` y
  documentarlo como el criterio oficial (que es lo que ya hacen los scripts 78/80/84).
- **Sustentación**: **Sí** — es una distinción fina de PostgreSQL que se explica bien y
  demuestra lectura del motor, no de un tutorial.
- **Verificado** (2026-08-07): `column_default` = `now()`.

### C-3 · Tres concatenaciones que dependen de una línea en blanco

- **Qué es**: el patrón `"""  + CONSTANTE + """` cierra un bloque de texto y abre otro.
  Si la constante interpolada **no** acaba en `\n`, lo único que separa el fragmento de
  la palabra siguiente es una **línea en blanco invisible** en el punto de uso.
  Borrarla produce SQL pegado (`…venta_realFROM…`) que solo revienta en ejecución.
- **Dónde**: quedan **3** sitios, todos con esa dependencia:
  - `ResenasService:183` y `ResenasService:468` — interpolan `ESTADOS_COMPRA`, un
    literal de una línea sin `\n`.
  - `CatalogoAdminService:132` — interpola el `where` local, un bloque que cierra en
    línea.
- **Si no se toca**: funcionan. Un reformateo automático o un «limpiar líneas en
  blanco» las rompe las tres a la vez, y en tiempo de ejecución.
- **Costaría**: dar un `\n` final a cada fragmento, como se hizo con `VENTA_REAL_SQL`
  el 2026-08-07 (ver **D**, A-2). Es una línea por sitio.
- **Sustentación**: **Sí** — el equipo ya identificó la trampa y la documentó **en el
  código** (`InformesComprasService:541-544`), y una de las cuatro llegó a romperse de
  verdad: la ficha de A-2 en la sección D es la demostración.
- **Verificado** (2026-08-07): los 3 sitios siguen sin `\n` en el fragmento
  interpolado; sus endpoints responden 200 (probados tras el cambio de A-2).
  El 4.º sitio (`MetasVentaService:61`) **dejó de depender** de la línea en blanco.

### C-4 · La contraseña del admin vive en 9 archivos versionados

- **Qué es**: la credencial de demostración del admin aparece en 9 archivos rastreados
  por git. *El valor literal se omite aquí a propósito: reproducirlo convertiría a este
  documento en el décimo.*
- **Dónde**:

  | Archivo | Naturaleza |
  |---|---|
  | `retailmind/sql/postgres/23_seed_roles_admin.sql` | **el seed real** (`crypt(...)`) |
  | `retailmind/matriz_alerta_cliente.py` | script de verificación |
  | `retailmind/matriz_prevision.py` | script de verificación |
  | `retailmind/matriz_tableros.py` | script de verificación |
  | `retailmind/validar_tableros.py` | por defecto de `RETAILMIND_PASS` |
  | `deploy/verificar_v11.sh` | script de humo |
  | `docs/DESPLIEGUE_EJECUTADO.md` | documentación |
  | `.kiro/steering/tech.md` | documentación |
  | `CLAUDE.md` | documentación |

- **Si no se toca**: nada hoy. El problema aparece el día que ese usuario deje de ser de
  demo: hay nueve sitios que actualizar y **uno de ellos crea la cuenta**.
- **Costaría**: parametrizar por variable de entorno los 5 scripts; los 3 documentos son
  decisión editorial.
- **Sustentación**: **Sí, con el matiz explícito** — esto **no** contradice «no hay
  secretos en el repositorio». Los cuatro secretos internos rotados (superusuario,
  `retailmind_app`, `retailmind_etl`, `jwt.secret`) sí están fuera del índice. Esto es
  una **credencial de demostración documentada a propósito**.
- **Verificado** (2026-08-07): `git grep -l` → exactamente **9 archivos**.

### C-5 · `dim_fecha` con rango codificado en duro hasta 2026-12-31

- **Qué es**: el calendario del almacén se genera para un rango fijo (730 días,
  2025-01-01 → 2026-12-31).
- **Dónde**: `retailmind/etl/dwh/tablas/dim_fecha.py`.
- **Si no se toca**: el 1 de enero de 2027 los hechos nuevos no encuentran fila de
  calendario. Un JOIN interno los **descartaría en silencio**.
- **Costaría**: derivar el rango del mínimo y máximo de los hechos, más margen.
- **Sustentación**: **Sí** — es el modo de fallo clásico de una dimensión de fecha, y
  aquí está identificado antes de ocurrir.
- **Verificado** (2026-08-07): rango literal en el módulo; `dim_fecha` = **730 filas**.

### C-6 · Sin columna de procedencia seed / no-seed

- **Qué es**: el origen sintético se marca con un prefijo de texto (`[SEED-*]`) en
  campos de nota, y el marcado está incompleto.
- **Dónde**: notas y observaciones de varias tablas; 254 filas sin marca, 93 de ellas
  anteriores a 2026.
- **Si no se toca**: separar dato sembrado de dato real exige inspección manual.
- **Costaría**: una columna booleana de procedencia, o aceptar que el corte es la fecha.
- **Sustentación**: **Sí** — la alternativa (una columna en cada tabla) contamina el
  modelo con metadatos de construcción.
- **Verificado** (2026-08-06): recuento del levantamiento.

### C-7 · Una corrida de Airflow escribe 22 pares de marcadores `corrida`

- **Qué es**: cada tarea del DAG es un proceso `run_etl.py` independiente que abre y
  cierra su **propio** marcador. Una corrida deja 22 `en_curso` y 22 cierres, no uno.
- **Dónde**: `retailmind_dwh.etl_ejecucion`, filas con `tarea = 'corrida'`.
- **Si no se toca**: nada — los datos son correctos y el backend ya lee de forma
  defensiva (`DwhActualizacionService` colapsa con `argMax`). Sumar `filas_escritas` sin
  excluir esos marcadores da **el doble exacto**.
- **Costaría**: un marcador de corrida a nivel de DAG (una tarea de apertura y una de
  cierre), o dejarlo y mantener la lectura defensiva.
- **Sustentación**: **Sí** — el consumidor ya está blindado; se cita como ejemplo de
  «conocer el modo de fallo antes de que muerda».
- **Verificado** (2026-08-07): dos corridas del DAG → **44 `en_curso`** (22 por corrida).

### C-8 · `grp_bodega` conserva SELECT de `precio_unitario` en los detalles

- **Qué es**: excepción documentada de la segregación financiera (script 41): bodega lee
  `pedido_detalle.precio_unitario` y `orden_compra_detalle.precio_unitario`.
- **Dónde**: grants por columna del script 41.
- **Si no se toca**: nada visible — la app valoriza el kardex bajo `grp_bodega` (recepción
  y reingreso RMA) y **ninguna pantalla ni consulta de bodega expone esos precios**.
- **Costaría**: mover la valorización a SECURITY DEFINER.
- **Sustentación**: **Sí, y conviene sacarla uno mismo**: es el punto donde la
  segregación financiera se apoya en la CONSULTA y no en el motor, y está documentado
  como excepción, no descubierto por un auditor.
- **Verificado** (2026-08-06): bodega tiene 90 ACL de columna y **cero** SELECT de tabla
  sobre `pedido`.

### C-9 · `grp_compras` con INSERT/UPDATE de `inventario` a nivel de motor

- **Qué es**: el script 45 le dio INSERT/UPDATE de `inventario` + INSERT de
  `movimiento_inventario` para que la resolución `reposicion` reingrese vía
  `StockService` bajo su rol. El motor no restringe esa escritura al caso de uso.
- **Dónde**: grants del script 45.
- **Si no se toca**: la UI y el backend no exponen otra vía. Análogo a C-8.
- **Costaría**: mover el reingreso a SECURITY DEFINER.
- **Sustentación**: **Sí** — mismo argumento que C-8, y ahora con una mitigación nueva:
  desde el script 91 el motor **sí** garantiza que cualquier escritura de kardex de ese
  rol cuadre (ver D, C-1).
- **Verificado** (2026-08-06): `INSERT, SELECT, UPDATE` de `grp_compras` sobre
  `inventario`.

### C-10 · Backfill parcial de autores históricos

- **Qué es**: `factura_compra.registrado_por` y `resena/pregunta_producto.moderado_por`
  quedaron NULL en filas anteriores al script 42: no había historial del que derivarlos.
- **Dónde**: `factura_compra.registrado_por` NULL en **13 de 839**.
- **Si no se toca**: esas filas no son atribuibles. `pedido.vendedor_id` y
  `envio.despachado_por` sí se poblaron desde `historial_estado_pedido`.
- **Costaría**: nada honesto — inventar el autor sería peor que el NULL.
- **Sustentación**: **Sí** — *se pobló lo que se podía derivar y se dejó NULL lo que no*.
  Un backfill inventado sería un dato falso.
- **Verificado** (2026-08-07): 13 de 839 sin autor.

### C-11 · `grupo_horario` no está congelado: la pantalla de admin puede reescribirlo

- **Qué es**: la pantalla de horarios hace INSERT y UPDATE sobre `grupo_horario` para
  `grp_administrador`. Las ventanas 24/7 del script 88 son **datos**, no una constante.
- **Dónde**: `HorariosAdminService:50` (INSERT) y `:61` (UPDATE).
- **Si no se toca**: es la **causa directa de A-1** — la fila desviada no la dejó ningún
  script; se escribió por esa pantalla. Puede volver a ocurrir.
- **Costaría**: nada, si se acepta que la pantalla existe para eso. Mitigación real:
  correr `90_horario_demo_restaurar.sql`, que **valida y aborta** si algún rol conserva
  un minuto bloqueado.
- **Sustentación**: **Sí**, y en positivo: la herramienta de detección **ya existía y
  funciona**; lo que falló fue no ejecutarla al terminar.
- **Verificado** (2026-08-07): `HorariosAdminService:50/61`; A-1 reproducido y cerrado.

### C-12 · El primer pedido de un mes nuevo aborta el modelo de abandono

- **Qué es**: `fact_alerta_cliente` lleva un guardia de **concentración** que aborta la
  publicación si algún mes de la ventana tiene un cliente por encima del **25 %** de los
  pedidos. El seed llega al 2026-07-22, así que el **primer pedido real de un mes nuevo**
  crea un bucket con 1 pedido de 1 cliente = **100 %**, y el modelo no se publica.
- **Dónde**: `retailmind/etl/dwh/modelos/alerta_abandono` (guardia de concentración);
  tarea `fact_alerta_cliente` del DAG `retailmind_dwh`.
- **Si no se toca**: la tarea del DAG queda en rojo y `validar_dwh` no corre
  (`upstream_failed`). **Las otras 20 tablas SÍ se publican** y las pantallas siguen
  sirviendo; lo que no se refresca es la alerta. No hay pérdida de dato.
- **Costaría**: exigir un mínimo de pedidos por mes antes de evaluar la concentración, o
  excluir el mes en curso incompleto — el mismo criterio de «mes truncado» que la
  previsión de demanda ya aplica (§CE3.4).
- **Sustentación**: **Sí, y es un hallazgo fuerte**: el guardia **funciona exactamente
  como se diseñó** (impedir que un régimen de cartera degenerado invierta la alerta), y
  al hacerlo destapa que el criterio no distingue «cartera concentrada» de «mes que
  acaba de empezar». Es la diferencia entre un modelo que falla en silencio y uno que se
  niega a publicar.
- **Verificado en vivo** (2026-08-07): una venta de prueba fechada hoy dejó agosto de
  2026 con 1 pedido de 1 cliente; la tarea abortó con *«en 2026-08-01 un solo cliente
  hizo el 100.0 % de los pedidos, por encima del 25 % admitido […] NO se publica»*. Al
  revertir esa venta, la corrida siguiente publicó las 21 tablas y los 49 controles
  cuadraron.
- **Nota operativa — si se dispara el DAG EN VIVO a principios de mes**: es cuando esto
  se manifiesta. Con el mes recién empezado y uno o dos pedidos reales, la tarea
  `fact_alerta_cliente` **acabará en rojo** y arrastrará `validar_dwh` a
  `upstream_failed`; en la interfaz de Airflow se verán **dos cuadros rojos sobre 22**.
  Qué hacer:
  - **No hace falta hacer nada para que las pantallas funcionen.** Las otras 20 tablas se
    publican igual y todos los informes y tableros siguen sirviendo; lo único que no se
    refresca es `/api/informes/ventas/clientes-en-riesgo`, que seguirá mostrando la
    última publicación buena con su fecha ancla a la vista.
  - **Si se va a proyectar la pantalla de Airflow**, conviene anticiparlo en vez de
    explicarlo después: el rojo es el guardia funcionando, y esa es justamente la
    historia que cuenta esta entrada.
  - **Si se prefiere una corrida limpia**, la vía es no incluir el mes en curso: disparar
    con el DAG tal cual **antes** de que exista el primer pedido del mes, o demostrar la
    carga con `python -m etl.dwh.cargar --tabla <otra>` y dejar la alerta fuera de la
    demostración. **No** se toca el umbral del 25 % para la ocasión: bajarlo desactiva la
    protección que impide que un régimen de cartera degenerado invierta la alerta, que es
    exactamente el hallazgo de la fase E3 (CE4.1).

### C-13 · El bean único de ClickHouse apunta a la base LEGADA, y `dim_producto` existe en las dos

- **Qué es**: el backend tiene **un solo** bean de ClickHouse —`jdbcTemplate` y
  `clickHouseJdbc` son **dos nombres del mismo**— y su base por defecto es `retailmind`,
  la **legada de analítica web**, no el almacén. El almacén solo se lee cuando la
  consulta lo cualifica a mano con la constante `DWH`. Y **`dim_producto` es el único
  nombre de tabla que existe en AMBAS bases**, con contenidos distintos: **1.200 filas**
  en la legada y **1.221** en el almacén.
- **Dónde**:
  - `config/ClickHouseConfig.java:25` — `@Bean(name = {"jdbcTemplate", "clickHouseJdbc"})`.
  - `application.properties:35` — `jdbc:ch://…/retailmind?compress=0`; confirmado en el
    contenedor vivo (`CLICKHOUSE_DATASOURCE_URL=jdbc:ch://clickhouse:8123/retailmind`).
  - `informes/InformeCompuestoServiceBase.java:72` — `protected static final String DWH =
    "retailmind_dwh"`, el único punto que cualifica.
- **Si no se toca**: el modo de fallo es **asimétrico, y esa es la parte que importa**.
  Una consulta que olvide cualificar cualquier tabla del almacén salvo una revienta con
  `UNKNOWN_TABLE` — ruidoso, se ve en la primera prueba. Pero olvidarlo en
  **`dim_producto`** no falla: lee la tabla equivocada y devuelve **21 productos menos**,
  con las columnas que comparten nombre, **sin un solo error**. La superficie de fallo
  silencioso es exactamente **una tabla**.
- **Costaría**: un segundo bean cualificado contra `retailmind_dwh` (y que la base por
  defecto deje de ser un valor con significado), o —más barato— una prueba que recorra el
  código buscando tablas del almacén sin prefijo, que es justo el barrido con el que se
  verificó esta entrada.
- **Sustentación**: **Sí** — es el ejemplo limpio de por qué dos bases con un nombre de
  tabla en común son un riesgo de *corrección*, no de estilo, y de que el modo de fallo
  peligroso no es el que da error.
- **Verificado** (2026-08-07): `SELECT name FROM system.tables WHERE database='retailmind'
  INTERSECT … 'retailmind_dwh'` → **`dim_producto` y ninguna más**. Conteos: **1.200** vs
  **1.221**. Modo de fallo probado con `clickhouse-client --database retailmind`:
  `SELECT count() FROM dim_producto` → **1200 sin error**;
  `SELECT count() FROM fact_venta_linea` → `Code: 60 … (UNKNOWN_TABLE)`.
  **Barrido del backend**: las 22 tablas de `retailmind_dwh` contrastadas contra todos los
  `.java` de `retailmind-backend/src/main/java`, con expresión tolerante a saltos de línea
  (`(FROM|JOIN)\s+(?![\w%$]*\.)<tabla>`) → **cero ocurrencias sin cualificar**. Los
  informes usan `%s.%s` con `DWH`, las dimensiones pasan por
  `InformeCompuestoServiceBase:96-98` (`dimension()`), y `analytics/` cualifica sus 18
  lecturas como `retailmind.fact_eventos`. **Hoy no hay ninguna consulta expuesta**: por
  eso es fragilidad y no defecto.

### C-14 · «39 informes compuestos» no reconcilia con las 41 rutas, y dos objetivos del catálogo no están construidos

- **Qué es**: la documentación usa **39** como si fuera a la vez el número de objetivos
  compuestos del catálogo y el número de endpoints en producción. Son cosas distintas y
  ninguna vale 39: las rutas reales son **41**, y de los 39 objetivos del catálogo hay
  **2 sin ninguna implementación** con ese nombre.
- **Dónde**: los 6 controladores `Informes*CompuestosController.java`. El conteo afirmado
  está en `CLAUDE.md` (dos sitios), `.kiro/steering/product.md:37`,
  `.kiro/steering/tech.md:167` y `docs/DESPLIEGUE_DISENO.md:117`.
- **La reconciliación exacta** (2026-08-07):

  | | |
  |---|---:|
  | Rutas `@GetMapping` en los 6 controladores compuestos | **41** |
  | − `prevision-demanda` servida en DOS departamentos (gerencia y compras) | −1 |
  | **Endpoints distintos** | **40** |
  | − los 2 MODELOS estratégicos (`prevision-demanda` E2, `clientes-en-riesgo` E3) | −2 |
  | − `costo-envio-mensual`, declarado *«fuera de los 39, servido de regalo»* en `DISENO_ETL_CLICKHOUSE.md:174-175` porque OTD-LOG-11 es SIMPLE | −1 |
  | **Objetivos OTD del catálogo con ruta propia** | **37** |
  | Objetivos del catálogo **sin construir**: `OTD-VEN-03` (top 10 más vendidos) y `OTD-VEN-04` (producto hueso) | **2** |

- **Si no se toca**: nada deja de funcionar — los 41 endpoints responden. Lo que falla es
  la **afirmación de completitud**: «catálogo táctico COMPLETO, 39 compuestos» invita a
  una pregunta de sustentación («enséñemelos») cuya respuesta real son 37 más dos que no
  existen. La pregunta de VEN-04 la contesta de hecho el bloque `producto_hueso` del
  tablero T-2 (`TableroRentabilidadService.java:444`), pero **nada en el código lo declara
  como OTD-VEN-04**, así que no cuenta como cobertura trazable.
- **Costaría**: separar los dos conteos en la documentación (hecho el 2026-08-07) y, si se
  quiere cerrar el catálogo de verdad, dos informes más — ambos sobre `fact_venta_linea`
  y `dim_producto`, tablas ya cargadas y validadas.
- **Sustentación**: **Sí, y conviene abrirla uno mismo**: un catálogo que se declara
  completo y no lo está es exactamente lo que una auditoría busca. Contarlo al revés —«37
  de 39, y estos son los dos que faltan y por qué»— es más fuerte que el 39 redondo.
- **Verificado** (2026-08-07): `grep -oE '@GetMapping\("[^"]*"'` sobre los 6
  `Informes*CompuestosController.java` → **41 rutas** (Compras 8, Gerencia 7, Inventario
  3, Logística 9, Soporte 5, Ventas 9). `grep -rn "VEN-03\|VEN-04"` sobre
  `retailmind-backend/src` y `retailmind-frontend/src` → **cero ocurrencias**. El catálogo
  declara **69 objetivos, 30 simples** (`CATALOGO_OBJETIVOS_TACTICOS.md:31`), de donde
  sale el 39.

  **ACTUALIZACIÓN (2026-08-07)**: **OTD-VEN-03** y **OTD-VEN-04** ya están construidos
  (`/api/informes/ventas/top-productos` y `/productos-hueso`). El catálogo queda en
  **39 de 39** y las rutas compuestas pasan de 41 a **43**. La entrada se conserva porque
  la lección sigue viva: **rutas y objetivos no son la misma cuenta**, y las 43 rutas
  son 39 objetivos + 2 modelos + `costo-envio-mensual` + `prevision-demanda` duplicada.

### C-15 · `fact_eventos` sigue sin identificador único de fila

- **Qué es**: la tabla de la analítica web no tiene ninguna clave practicable.
  `event_pk` reparte **50.000 valores entre 2.823.245 filas**; `(session_id,
  event_index)` deja **253.372 pares repetidos** (hasta 5 filas por par); añadiendo
  `timestamp_utc` **aún queda 1 colisión**. Solo la fila entera —las 15 columnas—
  identifica.
- **Dónde**: `retailmind.fact_eventos` (base LEGADA de ClickHouse), columna
  `event_pk UInt64 DEFAULT rowNumberInAllBlocks()`; el DDL de origen está en
  `retailmind/etl/carga/09_load_clickhouse.py:73`.
- **Si no se toca**: **hoy nada**, y esa es la diferencia con A-3. Desde el 2026-08-07
  ninguna ruta escribe en la tabla: los 8 lectores de `analytics/` agregan por
  `session_id`, `channel` o `user_action` y ninguno direcciona una fila. El riesgo es
  prospectivo — cualquier código futuro que tome `event_pk` por identificador repetirá
  el defecto, y el nombre de la columna invita a ello.
- **Costaría**: una reconstrucción de la tabla (tabla nueva + `INSERT SELECT` +
  `EXCHANGE TABLES`) con un identificador de verdad. **Con respaldo previo**: son
  2.823.245 filas irreproducibles en un volumen declarado `external: true`. **Trampa
  documentada**: volver a usar `rowNumberInAllBlocks()` en la reconstrucción reproduce
  el defecto salvo que la inserción sea de un solo hilo y un solo bloque; un `UUID` o un
  contador monótono es más seguro.
- **Sustentación**: **Sí** — es el ejemplo limpio de que *el nombre de una columna no es
  una garantía*, y de una decisión de riesgo tomada a conciencia: se cortó el acceso que
  destruía dato (barato y reversible) en vez de reescribir 2,8 millones de filas
  irrecuperables con una sustentación encima.
- **Verificado** (2026-08-07): `count(), uniqExact(event_pk)` → **2.823.245 / 50.000**;
  `uniqExact((session_id, event_index))` → **2.550.854** (253.372 pares con colisión,
  máximo 5 filas por par); con `timestamp_utc` → **2.823.244**; la fila completa →
  **2.823.245**, sin duplicados exactos. Barrido de escritores tras el cierre de A-3:
  **cero** rutas que escriban en la tabla.

### C-16 · `insertDimensionAutoId` genera el id con `max(id) + 1` y ClickHouse no impone unicidad

- **Qué es**: las altas de las cinco dimensiones genéricas de la pantalla de gestión de
  datos calculan el identificador leyendo el máximo actual y sumando uno. No hay
  secuencia, no hay bloqueo y **ClickHouse no tiene restricción de unicidad**: dos altas
  concurrentes leen el mismo máximo y escriben el mismo id, sin error.
- **Dónde**: `admin/gestion/GestionDatosService.java:118-124`
  (`insertDimensionAutoId`), usado por `POST /api/gestion/{dim-canal|dim-region|
  dim-dispositivo|dim-categoria|dim-fuente-trafico}`.
- **Si no se toca**: **hoy nada**. Las cinco dimensiones tienen su clave limpia
  —verificado— y la pantalla es de ADMIN, de uso raro y de un solo operador, así que la
  ventana de carrera no se abre. Pero si llegara a abrirse, el resultado sería
  exactamente el estado que acaba de costar el defecto A-3: dos filas con el mismo id,
  y un `UPDATE`/`DELETE` por ese id actuando sobre las dos. Los catálogos tienen entre
  3 y 9 filas: la duplicación se vería a simple vista, que es lo único que la hace
  benigna.
- **Costaría**: poco — un `generateUUIDv4()` como identificador, o mover el alta a una
  tabla de PostgreSQL con secuencia (que es donde vive el resto de los catálogos del
  sistema). También vale retirar el alta: estas cinco dimensiones no las lee **nada**
  del backend fuera de la propia pantalla.
- **Sustentación**: **Sí** — es la MISMA causa raíz que A-3 vista antes de que muerda:
  un identificador que el motor no garantiza. Contar las dos juntas —una que ya costó y
  otra que se detectó latente— es más fuerte que contar solo la que falló.
- **Verificado** (2026-08-07): las cinco dimensiones tienen hoy la clave única
  (`dim_canal` 3/3, `dim_region` 9/9, `dim_dispositivo` 4/4, `dim_categoria` 9/9,
  `dim_fuente_trafico` 7/7), y las cinco altas pasan por
  `insertDimensionAutoId`. Ninguna de las cinco tablas la lee un servicio distinto de
  `admin/gestion` (`DimCanalRepository` y `DimCategoria` son código muerto: no se
  inyectan en ninguna parte).

---

# D. Resuelto (histórico)

### C-17 · La serie de la década es demasiado regular, y el modelo de previsión no supera al ingenuo

**Qué pasa.** Tras la carga masiva, cada mes de los diez años vale casi exactamente
`300.000 × w_mes/Σw`. Medido sobre el almacén: el **coeficiente de variación interanual
del total mensual está entre 0,19 % y 0,68 %** — cada enero de los diez años difiere
menos del 1 % del siguiente.

**Qué rompe.** El ingenuo estacional («mismo mes del año anterior × crecimiento») saca un
**MAPE del 0,40 %** contra una serie así. El modelo de previsión saca **0,83 %** y por
tanto NO lo supera: publica en `modo=linea_base` con 6.237 series. La tabla se publica y
los 49 controles cuadran, así que no es un defecto que se vea; es una limitación del
DATO que hace que el modelo no pueda demostrar su valor.

**Por qué no se arregla parcheando el modelo.** El modelo acierta: contra una serie
determinista, la línea base ES la mejor predicción. Lo que falta es variación interanual
—tendencia de crecimiento, choques puntuales, ruido de mes—, y eso se añade regenerando
bloques de la carga (el método está en `100_fase3_carga.sql`), no tocando el estimador.

**Lo que NO es.** No confundir con el problema de COBERTURA de banda, que sí era del
estimador y se cerró el 2026-08-11: la banda suponía ruido de Poisson puro
(`varianza = media`) mientras la demanda es Poisson COMPUESTO —la línea lleva
`cantidad ∈ {1,2,3,4}`, índice de dispersión teórico 2,56 y medido **2,53**—. Corregido
con `INDICE_DISPERSION` en `prevision_demanda.py`; la cobertura pasó de 59,7 % a 78,4 %.

### C-18 · Constantes dimensionadas para el volumen anterior

Tres topes se escribieron cuando el sistema tenía 4.083 pedidos y 18 meses, y a escala de
década dejaron de servir. Los tres corregidos el 2026-08-11, pero la CLASE de problema
sigue viva: son constantes que no fallan cuando el volumen crece, **se quedan cortas en
silencio**.

| constante | dónde | qué pasaba |
|---|---|---|
| `max_partitions_per_insert_block` (100) | ClickHouse | la década son 120 particiones mensuales; el INSERT moría con un HTTP 500 que solo se explicaba en el `err.log` del motor |
| `shm_size` (64 MB, defecto de Docker) | `docker-compose.yml` | las consultas paralelas sobre tablas de millones de filas agotaban `/dev/shm`; el error NOMBRA a PostgreSQL y no tiene que ver con el disco |
| `execution_timeout` (5 min) | DAG `retailmind_dwh` | `fact_movimiento_inventario` pasa de 5 min; la tarea moría por reloj, no por error, dejando la tabla con la versión anterior |

**Y una cuarta de la misma familia, la más cara porque la ve el usuario**: los tableros
arrancaban con `desde=2025-01-01` / `hasta=2026-12-31` escritos a mano en
`tableros.ts`. Cuando se escribieron ERAN «todo el histórico»; con la década cargada
recortaban al 20 % **con aspecto de estar completo**. Un filtro por defecto que caduca no
falla: miente. Corregido dejándolo vacío (el backend omite la condición y sirve la serie
entera, y medido no cuesta más).

## Resuelto — A-3: `fact_eventos` en solo lectura (2026-08-07, sin script)

| Ítem original | Resolución |
|---------------|------------|
| **A-3 · La pantalla de gestión de datos editaba y borraba `fact_eventos` por una columna que no es clave**: `event_pk` está declarada `UInt64 DEFAULT rowNumberInAllBlocks()` y ese contador reinicia en cada bloque de inserción — **50.000 valores distintos para 2.823.245 filas, entre 52 y 139 filas por valor**. `GET .../{eventPk}` devolvía una fila **arbitraria**, `PUT` reescribía las 52-139 y `DELETE` **las borraba**, informando de un borrado correcto de «un evento». `fact_eventos` no es reproducible (volumen `external: true`). | **RESUELTO retirando la escritura** (opción evaluada en el diagnóstico del 2026-08-07 y elegida por coste y por riesgo). **Backend**: se suprimieron los tres mappings de `GestionDatosController` (`GET/PUT/DELETE /api/gestion/fact-eventos/{eventPk}`) y los tres métodos de `GestionDatosService` (`getFactEventoById`, `updateFactEvento`, `deleteFactEvento`). **Frontend**: fuera el panel de edición, la columna «Acciones» de la tabla de eventos, los cuatro métodos del componente y los dos del servicio Angular; en su lugar, un aviso que explica por qué la tabla no se edita. En los tres archivos queda un comentario extenso con el motivo y la instrucción de NO reintroducirlo. **Se llevó por delante, de regalo, la única concatenación de un NOMBRE DE COLUMNA en SQL del archivo**: `updateFactEvento` construía `k + " = '" + v + "'"` con las claves del cuerpo de la petición, o sea con un identificador SQL de origen externo (regla de oro n.º 2). **NO se tocó la tabla**: cero `UPDATE`, `DELETE` o `ALTER` sobre `fact_eventos`. **Verificado por API** (`retailmind/verificar_ven0304.py`): los tres endpoints dan **404**; el listado paginado sigue en 200 con `totalElements = 2.823.245`; el filtro por semana coincide con ClickHouse (108.581 = 108.581); **las siete dimensiones siguen sirviendo** (dim_canal 3, dim_region 9, dim_dispositivo 4, dim_categoria 9, dim_fuente_trafico 7, dim_producto y dim_usuario paginadas); `fact_eventos` conserva **2.823.245 filas exactas**. **Verificado en navegador real** (`retailmind/verificar_pantallas.js`, Chrome headless): `/gestion-datos` carga sin errores de aplicación, sin panel de edición y con el aviso visible. **Lo que NO se hizo**: reconstruir la tabla con un identificador de verdad. Vive como **C-15**. |

## Resuelto — Defectos y fragilidad del kardex (2026-08-07, script 91)

| Ítem original | Resolución |
|---------------|------------|
| **A-1 · Ventana horaria desviada**: `grupo_horario` id 54 (`grp_analista`, domingo) en `00:00–23:30` en vez de `00:00–24:00`; única desviada de las 56. El rol quedaba bloqueado 30 minutos de cada 10.080 y, como `pol_horario` es `cmd=ALL`, RLS habría devuelto **cero filas sin error**. | **RESUELTO** ejecutando `90_horario_demo_restaurar.sql` (no un UPDATE manual): `UPDATE 1` — exactamente la fila desviada — y su guardia confirmó *«24/7 restaurado, 0 minutos bloqueados de 10080»*. **Verificado**: barrido de los 10.080 minutos de la semana con la MISMA condición de `esta_en_horario()` (`dia_semana = dow AND t >= hora_inicio AND t < hora_fin`) → **0 bloqueados en los 8 roles**; las 56 filas en `[00:00:00, 24:00:00)` y activas. **Mecanismo intacto**: 34 triggers `trg_horario_*`, 50 políticas `pol_horario` con `cmd=ALL`, 50 tablas con RLS y los md5 de `esta_en_horario()`, `fn_grupo_actual()` y `fn_bloquear_fuera_horario()` sin cambio (el script 90 no contiene una sola sentencia DDL). Causa raíz vigente como **C-11**. |
| **A-2 · `GET /api/gerencia/metas/vigente` respondía HTTP 500**: `VENTA_REAL_SQL` cerraba su bloque de texto EN LÍNEA (`… END AS venta_real"""`), así que no terminaba en `\n`; `listar()` lo compensaba con una línea en blanco y `vigente()` no, produciendo `… END AS venta_realFROM meta_venta m`. | **RESUELTO en la causa raíz** (`MetasVentaService.java`): el bloque ahora **cierra en su propia línea**, de modo que la constante termina en `\n` una sola vez y no en cada punto de uso; se eliminó la línea en blanco compensatoria de `listar()`, que ya era innecesaria; y se dejó un comentario explicando la trampa, en la línea del que ya existía en `InformesComprasService:541-544`. **Verificado**: `/metas/vigente` → **200** con `venta_real = 225.463,58`, contrastado contra PostgreSQL con la misma agregación (**Δ = 0**); `/metas` sigue en **200**. Los otros tres sitios del patrón siguen vigentes como **C-3** — *no los arregla este cambio*, porque interpolan constantes distintas. |
| **C-1 · El invariante del kardex vivía fuera del motor**: `stock_nuevo = stock_anterior + tipo_movimiento.factor * cantidad` no lo garantizaba ningún trigger ni restricción (los CHECK solo exigían `cantidad > 0`, `costo_unitario >= 0`, `stock_anterior >= 0`, `stock_nuevo >= 0`). Además había **dos** escrituras del kardex: `StockService` consulta `factor`, y `ComprasService:338` codifica el signo `+` a mano. | **RESUELTO con el script `91_invariante_kardex.sql`**: función `fn_validar_ecuacion_kardex()` (SECURITY DEFINER, `search_path` fijado) + triggers `trg_kardex_ecuacion_ins` (BEFORE INSERT) y `trg_kardex_ecuacion_upd` (BEFORE UPDATE, con `WHEN` sobre las columnas de la ecuación). **VALIDA, no calcula** — la justificación completa está en la cabecera del script: la aplicación escribe además `inventario.stock_actual` con su propia variable en otra sentencia que el trigger no ve, así que calcular convertiría una incoherencia ruidosa de una fila en una divergencia silenciosa entre dos tablas. **NO se tocó una línea de Java**: las dos rutas pasan porque las dos calculan bien hoy. **Verificado**: 13.288/13.288 movimientos existentes cumplían la ecuación *antes* de instalar (guardia previa del propio script); 4 violaciones sintéticas rechazadas con mensaje y `HINT`; una venta por `StockService` y una recepción por `ComprasService` completas de extremo a extremo; kardex cuadrado **1.406/1.406** posiciones; script idempotente (2.ª ejecución sin cambios). Se demostró además el escenario que motivaba la entrada: con `entrada_compra` apuntado a `factor = -1` dentro de una transacción, la fórmula de `ComprasService` es **rechazada** y la de `StockService` **aceptada**. |

## Resuelto — Entradas verificadas como FALSAS o ya cerradas (2026-08-06/07)

Seis filas seguían en «Vigente» describiendo un estado que el sistema ya no tiene. Se
comprobaron una a una contra la base viva y se retiran de las secciones activas.

| Ítem original | Comprobación |
|---------------|--------------|
| **`costo_envio` del pedido sigue en 0** (Fase 3) | **FALSO desde el script 54.** `SELECT count(*) FILTER (WHERE costo_envio > 0) FROM pedido` → **3.026 de 4.083 pedidos con flete, $34.798,02 en total**. El flete se cobra y entra al total. |
| **Tarifa ignora peso (`costo_por_kg`, `peso_min/max_kg`)** (Fase 3) | **FALSO desde el script 54.** **2.848 de 2.872 envíos** tienen `peso_total_kg` y costo calculado. Los 24 sin peso son los envíos **sin tarifar** anteriores al script 54, ya documentados y **excluidos explícitamente** de la serie de costo (Fase 3C, campo `salvedad`). |
| **Cupón `envio_gratis` inaplicable** (Fase 3b) | **FALSO.** Su premisa era «`costo_envio` siempre en 0». Hoy hay **2 cupones `envio_gratis` con 40 usos**, y `DescuentosService:156` implementa la rama. |
| **SLA calculado, no persistido** (Fase 2a) | **RESUELTO (scripts 48-50, 2026-07-22).** `ticket_soporte.fecha_limite` está poblada en **249 de 249** tickets, con backfill derivado. El vencimiento ya no se recalcula retroactivamente al cambiar la prioridad. |
| **Defectuoso queda como merma documental** (Fase 2b) | **RESUELTO (script 45, 2026-07-18)** — y ya venía marcado como tal *dentro de su propia celda*, sin salir de la tabla de vigentes. El ítem `defectuoso` cae al pool `item_defectuoso` y se devuelve al proveedor (`DevolucionProveedorService`). |
| **Orquestación ETL con Airflow** (Base, pre-fases) | **RESUELTO (2026-08-06).** DAG `retailmind_dwh` **activo** (`is_paused = False`) con schedule `0 2 * * *`, 21 tareas de carga + `validar_dwh`; el `@Scheduled` del backend quedó apagado con `DWH_CRON=-` para que no compitan por el `EXCHANGE TABLES`. |

## Resuelto — Auditoría de re-consolidación (2026-07-18)

| Ítem original | Resolución |
|---------------|------------|
| `/api/health` se colgaba con ClickHouse apagado (Tipo 1 nuevo detectado en la auditoría — el datasource ClickHouse no tenía NINGÚN timeout: Hikari esperaba 30s por defecto y el probe quedaba bloqueado, contradiciendo el invariante "ClickHouse apagado no afecta lo operativo"; crítico para healthchecks de contenedores) | **RESUELTO (2026-07-18, sin script — solo backend)**: `ClickHouseConfig` construye el pool con `connectionTimeout=3s`, `validationTimeout=1.5s`, `initializationFailTimeout=-1` y propiedades del driver `connect_timeout=2.5s` / `socket_timeout=30s`; `checkPythonRuntime` con `waitFor(3s)` + `destroyForcibly`. Verificado en vivo con ClickHouse apagado: el backend viejo colgaba >5s; el nuevo responde `/api/health` en ~3.1s acotados con `status: UP, analytics: DEGRADED` (3 llamadas consecutivas estables) y el login sigue en ~260 ms. |

## Resuelto — Novedades / incidencias de envío (2026-07-18, script 44)

| Ítem original | Resolución |
|---------------|------------|
| "Novedades de envío" (Tipo 2 ítem 1 del inventario consolidado, parte operativa) | **IMPLEMENTADO**: tabla `novedad_envio` (tipo por CHECK: cliente_ausente / direccion_incorrecta / cliente_rechazo / zona_dificil_acceso / dano_en_transito; autor `registrado_por`/`resuelto_por` del JWT; RLS pol_horario + pol_cliente_propio) sobre el envío en tránsito. El envío pasa a 'fallido' (estado que el CHECK de `envio` ya admitía) y DESPACHO resuelve: REPROGRAMAR (nuevo intento, máx. 3, el envío vuelve a 'en_transito' con nueva fecha estimada) o DEVOLVER AL ALMACÉN (envío 'devuelto', pedido 'no_entregado' — estado nuevo — SIN reingreso de stock). Guardias: solo envíos en tránsito, una novedad abierta a la vez, no entregar con novedad abierta, no reprogramar más de 3 intentos ni novedades resueltas. Rastro completo: `seguimiento_envio`, `historial_estado_pedido`, `log_auditoria` (AuditoriaService). UI: tarjeta de novedades en Despachos + mensaje amable y timeline en Mis Pedidos del cliente. Lo que el inventario agrupaba bajo el mismo nombre y NO es esta fase (cobro real de `costo_envio`, tarifas por peso, tracking del retorno RMA, cupón `envio_gratis`) sigue vigente como "cobro de envío". |

## Resuelto — Saneamiento Tipo 1 (2026-07-18, script 43)

Los 10 bugs/inconsistencias reales del inventario consolidado
(`docs/INVENTARIO_DEUDA_CONSOLIDADO.md`, Tipo 1) se cerraron por causa raíz y se
verificaron contra el sistema real (curl + psql SET ROLE + MCP):

| Ítem original | Resolución |
|---------------|------------|
| El cupón no recalcula el IVA (Media) | **RESUELTO**: `DescuentosService.aplicarCupon` prorratea el cupón entre las líneas (en proporción al neto de promos, ajuste de redondeo en la última — mismo criterio que la factura) y reescala `pedido_detalle.monto_impuesto` a la base realmente cobrada; el trigger de cabecera rehace los totales ANTES de crear el pago, así pago = pedido = factura. `envio_gratis` no toca base imponible. El preview del checkout (front) calcula el IVA con la misma regla. Verificado: checkout con VERANO26 (20%) sobre $54.90 → IVA $6.59 (antes $8.24), total $50.51 coherente en pedido/pago/factura/PDF. |
| `pago`/`transaccion_pago` sin RLS (Media) | **RESUELTO**: RLS habilitado con el patrón del proyecto — `pol_horario` (admin/analista/gerente/vendedor), cliente con INSERT solo sobre pedidos propios (`pol_cliente_pago`; en `transaccion_pago` vía helper SECURITY DEFINER `fn_pago_del_cliente` porque el cliente solo tiene SELECT(id) de pago) y SELECT de pago limitado a los suyos. Verificado: maria ve 6/24 pagos; vendedor sigue viendo todos; checkout y cobro interno intactos. |
| `cupon`/`uso_cupon` sin RLS (Media) | **RESUELTO**: RLS habilitado — staff con `pol_horario`; cliente solo ve cupones ACTIVOS (un código desactivado responde "no existe") o que él ya usó (Mis Pedidos conserva el código), y en `uso_cupon` solo SUS usos (el conteo global de límites ya vivía en el trigger SECURITY DEFINER `fn_registrar_uso_cupon`). Verificado: maria ve 1/3 usos. |
| Correlativo `TICK-AAAA-NNNN` por `count(+1)` (Media) | **RESUELTO**: función `fn_siguiente_numero_ticket()` (SECURITY DEFINER) con tabla `correlativo_ticket` por año y UPSERT bajo lock de fila: dos creaciones simultáneas se serializan sin chocar con el UNIQUE. Sembrada desde los tickets existentes. Verificado: siguiente ticket salió TICK-2026-0007. |
| Pedido interno 'confirmado' con factura legacy (PED-20260711-24662) (Media) | **RESUELTO**: la factura legacy FV-20260711-55374 (emitida sin pago, previa a la compuerta) quedó ANULADA con nota en el historial, y la guardia de idempotencia de `emitirFactura` ahora IGNORA facturas 'anulada'. Verificado: el endpoint ya no responde "ya fue facturado" sino la compuerta correcta ("debe estar pagado"); el pedido sigue su flujo normal desde 'confirmado'. |
| Pedido web 'pagado' legacy sin factura (PED-20260715-87538) (Baja) | **RESUELTO**: facturado por el endpoint manual de respaldo (ADMIN) → FV-20260718-100000, pedido 'facturado' y dentro de la cola de preparación. El invariante "online nace facturado" queda sin excepciones en datos. |
| Número de documento por azar (`PED/FV/EN/OC/RM/FC/DV-fecha-rand`) (Baja) | **RESUELTO**: secuencia global `seq_numero_documento` (arranca en 100000 para no chocar con los sufijos legacy de 5 dígitos) usada por los tres `siguienteNumero` (ventas, compras, devoluciones). USAGE para los 8 grupos que emiten documentos. Sin colisiones posibles. |
| `resena.moderado_por` escribible por grp_cliente (Baja) | **RESUELTO**: grant de cliente reescrito POR COLUMNA (patrón script 41): INSERT solo de producto/cliente/pedido/calificación/título/comentario/compra_verificada y UPDATE revocado por completo (el cliente no edita reseñas). Verificado: UPDATE de moderación bajo grp_cliente → "permiso denegado"; crear reseña por la app sigue OK. |
| `grp_soporte` con escritura en `categoria_ticket` (Baja) | **RESUELTO**: INSERT/UPDATE/DELETE revocados (queda SELECT); la gestión de categorías es solo ADMIN, como en `SecurityConfig`. Verificado: INSERT bajo grp_soporte → "permiso denegado". |
| RMA: 'despachado' sin plazo (Baja) | **RESUELTO**: para el rechazo en puerta el plazo de 30 días corre desde la FECHA DE DESPACHO (`envio.fecha_despacho`, historial como respaldo), misma regla en `elegibilidad()` y `solicitar()`; un envío nunca entregado ya no deja la ventana abierta para siempre. Verificado: pedido despachado 2026-07-16 → elegible con 29 días restantes. |

## Resuelto / descartado (auditoría 2026-07-17)

| Ítem original | Resolución |
|---------------|------------|
| Fase 1: "Lógica de cupones pendiente" (Media) | **RESUELTO en fase 3b (script 40)**: el cupón del checkout se valida y recalcula SIEMPRE en backend (`DescuentosService`), se aplica a `pedido.monto_descuento` y registra `uso_cupon` en la misma transacción. |
| "Total no se recalcula al modificar el pedido con descuento" (DT-05 propuesto) | **NO VIGENTE — descartado**: no existe NINGÚN endpoint que modifique líneas de un pedido creado (verificado: solo `crearPedido` inserta `pedido_detalle`; el resto son transiciones de estado que no tocan montos). El pedido es inmutable tras el checkout, así que descuento y total no pueden descuadrarse. Si algún día se agrega edición de pedidos, ese endpoint deberá recalcular promociones y re-validar el cupón (invalidándolo si deja de cumplir el mínimo). |
| "Concurrencia en uso de cupón" (DT-08 propuesto) | **RESUELTO en fase 3b (script 40) y VERIFICADO 2026-07-17**: el trigger `fn_registrar_uso_cupon` (SECURITY DEFINER) bloquea la fila del cupón con `FOR UPDATE` y re-verifica vigencia/`usos_maximos`/`usos_por_cliente` dentro de la transacción. Prueba real con dos transacciones simultáneas sobre un cupón de 1 uso: la segunda quedó bloqueada por el lock y fue rechazada al commitear la primera ("agotó sus usos disponibles"); `usos_actuales` terminó 1/1. |
| Ítems de severidad Alta | **No existía ninguno vigente** en el inventario al momento de la auditoría. |
| Reseñas sin compra verificada | **RESUELTO (fase 4)**: `crearResena` EXIGE un pedido pagado→entregado (incluye 'devuelto') del propio cliente que contenga el producto; si no, 409 "Solo puedes reseñar productos que has comprado". El selector del formulario ofrece solo productos comprados (`GET /api/resenas/productos-comprados`) y Mis Pedidos muestra "Reseñar" por ítem en pedidos pagados. Las reseñas existentes no se tocaron. |
| Trazabilidad: "quién ejecutó" ausente/parcial en ventas, despacho, factura de compra y moderación de reseñas; `log_auditoria` infrautilizada | **RESUELTO (fase 5, script 42, 2026-07-17)**: columnas de autor directas con FK a `usuario` — `pedido.vendedor_id` (NULL si canal 'web': el autor del checkout es el cliente, trazado por `cliente_id`+historial), `envio.despachado_por`, `factura_compra.registrado_por`, `resena.moderado_por`+`fecha_moderacion` y `pregunta_producto.moderado_por`+`fecha_moderacion` (`respuesta_pregunta` ya tenía `usuario_id`) — pobladas SIEMPRE del JWT. El patrón de `log_auditoria` de la aprobación de OC se extrajo a `AuditoriaService` (componente reutilizable, mismo formato jsonb antes/después) y ahora registran: crear pedido interno, despachar, registrar factura de compra, moderar reseña/pregunta (+ la aprobación de OC, intacta). Grants nuevos: INSERT de `log_auditoria` a grp_vendedor/grp_despacho/grp_compras. Backfill desde `historial_estado_pedido` (15 pedidos, 21 envíos). Verificado end-to-end con los 6 roles implicados. |
| Bodega/Despacho veían montos | **RESUELTO (fase 4, script 41)**: grants POR COLUMNA sin dinero para `grp_bodega`/`grp_despacho` (pedido, pedido_detalle, factura_venta, factura_compra, orden_compra, orden_compra_detalle, devolucion; `pago` revocado por completo a despacho); consultas dedicadas/role-aware en backend (`colaPreparacion`, `detalleLogistico`, `listarPedidos`, `entregar`, `listarOrdenes`, `obtenerOrden`, RMA `listar`/`obtener`) y UI sin columnas de monto para esos roles; BODEGA fuera de la pantalla y ruta de Facturas de Compra. Verificado: preparar→despachar→entregar y recepción siguen funcionando; ADMIN/GERENTE/VENDEDOR/COMPRAS/CLIENTE conservan sus montos. |

---

## Futuro (decidido, no tocar sin fase propia)

- **Pago y reembolso reales por pasarela** — asiento negativo en `pago`/`transaccion_pago`
  (B-9), nota de crédito aplicable a CxP (B-23). Todo el dinero es SIMULADO hoy, de
  forma consistente.
- **Trazabilidad por lote / FEFO** (B-1), que arrastra el picking por ítem (B-15).
- **Cobro de envío**: el flete ya se cobra y se tarifa por peso desde el script 54; lo
  que queda es el **tracking del retorno RMA** (B-10).
- **Notificación proactiva al cliente** (B-4) — infraestructura de correo, ninguna fase
  la ha construido.
