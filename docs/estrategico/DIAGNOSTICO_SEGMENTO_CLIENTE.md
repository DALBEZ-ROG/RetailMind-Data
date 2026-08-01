# Diagnóstico de segmentación de cliente (B2B / B2C) — RetailMind

**Universidad Técnica Estatal de Quevedo (UTEQ)** · Facultad de Ciencias de la Ingeniería
**Asignatura**: Construcción de Software — 6.º semestre
**Proyecto**: RetailMind (Quevedo, Los Ríos, Ecuador)
**Documento**: diagnóstico de segmento de cliente · **Fecha: 2026-07-30** · **Modo: SOLO LECTURA**

> **Garantía de no escritura.** Todo lo que sigue se obtuvo con `SELECT` a través del MCP
> `retailmind` (solo lectura) contra `postgres@localhost:5432/retailmind`. **No se creó, modificó
> ni borró ninguna fila, tabla, índice ni archivo de código o de seed.** No se etiquetó a ningún
> cliente ni se pobló `grupo_cliente` / `segmento_cliente`. El único archivo creado es este
> documento. La pregunta que se responde es **si se podría** derivar el segmento, no ejecutarlo.
>
> **Universo analizado**: 4.083 pedidos, 10.384 líneas, 3.887 facturas de venta, 72 clientes
> (69 con al menos un pedido), del **2025-01-13 al 2026-07-22**, $5.716.436,55 facturados.

---

## 1. Resumen ejecutivo

**Veredicto: (c) POBLACIÓN HOMOGÉNEA.** No hay dos poblaciones de compra en los datos: el
comportamiento por pedido es estadísticamente idéntico entre el cliente que compró 1 vez y el que
compró 673 veces (ticket medio $1.258,97 vs $1.415,96; unidades 4,79 vs 5,09; mezcla de categorías
con desviación máxima de 0,6 pp). Sostener el corte B2B/B2C sobre estos datos sería inventarlo.

**La métrica más decisiva: 10.378 de 10.384 líneas de pedido (99,94 %) piden entre 1 y 4 unidades;
el máximo histórico de una línea es 12 unidades y el de un pedido entero, 24.** En una red con
mayoristas reales existirían líneas de decenas o cientos de unidades. Aquí **no existe ni una sola
compra de volumen**: el ticket alto ($1.400 de media) lo produce el **precio unitario** ($276,36 de
media), no la cantidad.

Sí existe una **concentración fuerte de facturación** (top 10 % de clientes = 49,34 %), pero es
concentración por **frecuencia**, no por **comportamiento**: los clientes grandes compran igual que
los pequeños, solo que más seguido. Eso identifica clientes *valiosos*, no clientes *mayoristas*
(véase §4.4 y §6).

---

## 2. Señales de esquema encontradas

Se barrió `information_schema.columns` sobre las 108 tablas de `public` buscando cualquier columna
con nombre relacionado a tipo/segmento/grupo/empresa/razón social/RUC/lista de precios/canal.

### 2.1 Columnas candidatas y su estado real

| Tabla.columna | Qué podría indicar | Estado real | ¿Sirve? |
|---|---|---|---|
| `cliente.grupo_cliente_id` | Grupo comercial (mayorista/minorista) | **72 de 72 clientes en NULL** | ❌ |
| `grupo_cliente` (tabla) | Catálogo de grupos con `porcentaje_descuento` | **0 filas** | ❌ |
| `segmento_cliente` (tabla) | Segmentos con `criterio` jsonb | **0 filas** | ❌ |
| `cliente_segmento` (tabla) | Asignación cliente↔segmento | **0 filas** | ❌ |
| `cliente.tipo_identificacion` | `cedula` (persona) vs `ruc` (empresa) | **72 de 72 = `cedula`**, 0 `ruc` | ❌ |
| `cliente.numero_identificacion` | RUC ecuatoriano = 13 dígitos | **72 de 72 con longitud 10** (cédula) | ❌ |
| `factura_venta.identificacion` | Identificación fiscal del receptor | **3.887 de 3.887 con longitud 10**; 0 con 13 | ❌ |
| `factura_venta.razon_social` | Razón social empresarial | Poblada al 100 %, pero **es exactamente `cliente.nombre‖' '‖cliente.apellido` en las 3.887 facturas**; 69 valores distintos = los 69 clientes | ❌ |
| `factura_venta.direccion_facturacion` | Domicilio fiscal empresarial | Poblada al 100 %, derivada de la dirección personal del cliente | ❌ |
| `direccion.tipo` | `facturacion` empresarial vs `envio` | 72 `envio` + 2 `ambas`; **0 direcciones de facturación diferenciadas** | ❌ |
| `pedido.canal` | Medio del pedido | `web` 2.213 / `tienda` 1.030 / `telefono` 840 — **es el medio, no el segmento** (§5) | ❌ |
| `producto_variante.precio` | Lista de precios única | **No existe tabla de listas de precios ni precio por grupo**; el descuento mayorista solo podría venir de `grupo_cliente.porcentaje_descuento`, que está vacío | ❌ |
| `proveedor.ruc`, `transportista.ruc` | RUC | Existen, pero son de **proveedores y transportistas**, no de clientes | ❌ (fuera de alcance) |

### 2.2 Señales negativas adicionales (ausencias que también informan)

- **No existe ninguna tabla de cuentas por cobrar de cliente.** `cuenta_por_pagar` es solo del lado
  de compras (proveedores). Una operación B2B real implica **crédito a clientes**; aquí el 100 %
  de los pedidos se cobra al contado por `pago` + `transaccion_pago`.
- **Los 72 clientes son personas naturales completas**: 72/72 con `usuario_id`, 70/72 con
  `fecha_nacimiento` y 70/72 con `genero`. Son atributos demográficos de consumidor final, no de
  cuenta empresarial.
- Los clientes de mayor volumen **escriben reseñas de producto** (el nº1 tiene 30, el nº2 tiene 30):
  comportamiento de consumidor, no de comprador profesional.

**Conclusión de §2: no hay ni una sola señal de esquema con datos que insinúe tipo de cliente.**
La conclusión previa (memoria `b2b-b2c-no-medible-otd-ven-16`, 2026-07-29) queda confirmada y
ampliada: no es solo que `pedido.canal` sea el medio; es que **ninguna** columna fiscal, comercial
o de precios lleva la señal.

---

## 3. Distribuciones de comportamiento

### 3.1 Ticket por pedido — **unimodal, log-normal**

Estadísticos sobre `pedido.total` (n = 4.083):

| Métrica | Valor |
|---|---|
| Media | $1.400,06 |
| Desviación | $1.035,66 |
| Mínimo | $7,75 |
| P05 / P25 | $153,69 / $586,27 |
| **Mediana (P50)** | **$1.173,04** |
| P75 / P90 | $1.978,36 / $2.843,34 |
| P95 / P99 | $3.410,23 / $4.544,73 |
| Máximo | $6.701,02 |

Histograma en bandas de $200 (una sola cresta, decaimiento monótono a partir del máximo):

| Desde | n | | Desde | n |
|---|---|---|---|---|
| $0 | 284 | | $2.000 | 163 |
| $200 | 330 | | $2.200 | 164 |
| **$400** | **439 ← moda** | | $2.400 | 135 |
| $600 | 360 | | $2.600 | 108 |
| $800 | 356 | | $2.800 | 106 |
| $1.000 | 329 | | $3.000 | 62 |
| $1.200 | 274 | | $3.200 | 58 |
| $1.400 | 274 | | $3.400–$6.600 | 206 (cola decreciente) |
| $1.600 | 231 | | | |
| $1.800 | 204 | | | |

En escala logarítmica —donde una mezcla de dos poblaciones se separa mejor— la forma es una
**campana única y limpia** con moda en el entorno $1.400–$1.800 y descenso simétrico a ambos lados:

```
     $148  ######### 75
     $191  ########### 90
     $245  ########### 89
     $314  ###################### 179
     $403  ############################### 254
     $518  #################################### 295
     $665  ########################################## 337
     $854  ##################################################### 430
   $1.097  ###################################################### 436
   $1.408  ############################################################### 506  ← moda única
   $1.808  ######################################################### 462
   $2.322  ################################################## 400
   $2.981  ########################### 217
   $3.828  ############ 98
   $4.915  ## 18
   $6.311  # 3
```

`ln(total)`: media 6,8983 · desviación 0,9552 · **asimetría −1,0077** · **exceso de curtosis
+1,2075**. La asimetría negativa en logaritmos corresponde a la cola de pedidos pequeños de la
izquierda; **no hay ningún segundo máximo local ni meseta**. Una mezcla mayorista/minorista se
manifestaría como dos crestas o, como mínimo, como un hombro pronunciado a la derecha. No aparece.

**Forma: unimodal (log-normal con cola izquierda). Una sola población.**

### 3.2 Unidades por pedido — **la evidencia decisiva**

| Métrica | Valor |
|---|---|
| Media | 5,07 uds |
| Mediana | 4 uds |
| P75 / P90 / P99 | 7 / 10 / 13 uds |
| **Máximo absoluto** | **24 uds** (un único pedido de 4.083) |

Distribución completa (unidades por pedido):

| Uds | Pedidos | % | | Uds | Pedidos | % |
|---|---|---|---|---|---|---|
| 1 | 390 | 9,55 % | | 10 | 158 | 3,87 % |
| 2 | 548 | 13,42 % | | 11 | 108 | 2,65 % |
| 3 | 550 | 13,47 % | | 12 | 74 | 1,81 % |
| **4** | **560** | **13,72 % ← moda** | | 13 | 44 | 1,08 % |
| 5 | 469 | 11,49 % | | 14 | 17 | 0,42 % |
| 6 | 409 | 10,02 % | | 15 | 7 | 0,17 % |
| 7 | 311 | 7,62 % | | 16 | 5 | 0,12 % |
| 8 | 245 | 6,00 % | | 17 / 18 / 24 | 1 / 1 / 1 | 0,02 % c/u |
| 9 | 185 | 4,53 % | | | | |

Y a nivel de **línea**, que es donde se pide el volumen:

| Cantidad de la línea | Líneas | % |
|---|---|---|
| 1 | 4.484 | 43,18 % |
| 2 | 2.997 | 28,86 % |
| 3 | 1.442 | 13,89 % |
| 4 | 1.455 | 14,01 % |
| 6 | 1 | 0,01 % |
| 10 | 1 | 0,01 % |
| 11 | 1 | 0,01 % |
| 12 | 3 | 0,03 % |
| **> 4 unidades** | **6 de 10.384** | **0,058 %** |

**Esto cierra el caso.** El 99,94 % de las líneas pide entre 1 y 4 unidades y el techo histórico es
12. No existe el pedido de volumen. Correlación ticket↔unidades = **0,815**, con precio medio por
unidad de **$276,36** (mediana $278,77): el ticket de $1.400 es *caro*, no *voluminoso*.

**Forma: unimodal, truncada por arriba. No hay población de volumen.**

### 3.3 Líneas por pedido — **sin variedad de surtido**

| Métrica | Valor |
|---|---|
| Media | 2,54 líneas |
| Mediana | 2 |
| P75 / P90 / P99 | 3 / 5 / 5 |
| **Máximo** | **5** |

Ningún pedido supera las 5 líneas. Un pedido mayorista de reposición de surtido típicamente
recorre decenas de SKU. Aquí el techo es 5 para todos, en los tres canales y en todos los clientes.

**Forma: unimodal y acotada. No discrimina.**

### 3.4 Frecuencia de compra por cliente — **continuo sin corte natural**

69 clientes con pedidos. Media 59,17 pedidos; mediana 21; P75 60; P90 ≈ 172; máximo **673**.
Solo **4 clientes** hicieron una única compra.

| Pedidos del cliente | Clientes | Pedidos que aportan |
|---|---|---|
| 1 | 4 | 4 |
| 2–5 | 3 | 10 |
| 6–15 | 16 | 170 |
| 16–30 | 19 | 394 |
| 31–60 | 10 | 487 |
| 61–120 | 7 | 595 |
| 121–250 | 8 | 1.453 |
| > 250 | 2 | 970 |

Es una distribución **de cola larga continua**: cada banda está poblada, no hay ningún hueco entre
un grupo "de negocio" y uno "de consumo". Cualquier línea que se trace (¿40? ¿100?) cae en medio de
una rampa suave y sería **una decisión del analista, no un hallazgo en el dato**.

Intervalo entre compras (días), por grupo de frecuencia:

| Grupo | Intervalos | Media | Mediana | Desv. | CV |
|---|---|---|---|---|---|
| ≥ 100 pedidos | 2.413 | 1,81 d | 0,84 d | 3,30 | 1,817 |
| 40–99 pedidos | 1.000 | 4,41 d | 2,17 d | 5,93 | 1,344 |
| < 40 pedidos | 601 | 12,57 d | 6,17 d | 17,21 | 1,369 |

El intervalo cambia **solo de escala**, y el coeficiente de variación (1,34–1,82) indica un proceso
**irregular tipo Poisson** en los tres grupos. Un cliente B2B real se reabastece con **cadencia
regular** (semanal, quincenal), lo que daría un CV bajo (< 0,5) en el grupo de alta frecuencia.
Aquí ocurre lo contrario: el grupo más frecuente es el **más irregular**.

### 3.5 Concentración — **Pareto real, pero de frecuencia**

| Segmento | Clientes | % de la facturación |
|---|---|---|
| Top 10 % | 7 | **49,34 %** |
| Top 20 % | 14 | **68,76 %** |
| Top 30 % | 21 | 80,15 % |
| Top 50 % | 35 | 91,32 % |

Los 10 mayores clientes:

| # | Cliente | Pedidos | Facturación | **Ticket medio** | % | % acum. |
|---|---|---|---|---|---|---|
| 1 | 52 | 673 | $993.207,56 | **$1.475,79** | 17,37 % | 17,37 % |
| 2 | 54 | 297 | $418.267,30 | **$1.408,31** | 7,32 % | 24,69 % |
| 3 | 33 | 224 | $323.201,34 | **$1.442,86** | 5,65 % | 30,35 % |
| 4 | 61 | 230 | $299.438,51 | **$1.301,91** | 5,24 % | 35,58 % |
| 5 | 11 | 180 | $272.129,15 | **$1.511,83** | 4,76 % | 40,34 % |
| 6 | 29 | 186 | $268.273,89 | **$1.442,33** | 4,69 % | 45,04 % |
| 7 | 39 | 178 | $245.701,69 | **$1.380,35** | 4,30 % | 49,34 % |
| 8 | 16 | 170 | $222.447,86 | **$1.308,52** | 3,89 % | 53,23 % |
| 9 | 65 | 150 | $198.561,22 | **$1.323,74** | 3,47 % | 56,70 % |
| 10 | 71 | 135 | $189.632,91 | **$1.404,69** | 3,32 % | 60,02 % |

Los diez tickets medios caben en la banda **$1.301–$1.512**, alrededor de la media global de
$1.400,06. **La concentración la produce el número de pedidos, no el tamaño del pedido.**

---

## 4. Prueba de bimodalidad

Se contrastó cada dimensión que separaría un mayorista de un minorista. Se agrupó a los clientes por
frecuencia (A: ≥ 100 pedidos, B: 40–99, C: 15–39, D: < 15) y se comparó su **comportamiento por
pedido**. Si existieran dos poblaciones, el grupo A debería comportarse distinto.

### 4.1 Comportamiento por pedido según frecuencia del cliente

| Grupo | Clientes | Pedidos | Ticket medio | Mediana | Desv. | Uds/pedido |
|---|---|---|---|---|---|---|
| A: ≥ 100 | 10 | 2.423 | $1.415,96 | $1.184,70 | $1.047,19 | **5,09** |
| B: 40–99 | 12 | 1.015 | $1.399,92 | $1.170,18 | $1.006,48 | **5,03** |
| C: 15–39 | 13 | 491 | $1.366,14 | $1.092,15 | $1.067,08 | **5,14** |
| D: < 15 | 12 | 154 | $1.258,97 | $1.074,97 | $921,65 | **4,79** |

Diferencia máxima de ticket entre el grupo más frecuente y el menos frecuente: **12,5 %**
(y en la dirección esperada por puro efecto de tamaño muestral). En unidades: **6 %**. Las
desviaciones típicas también coinciden — no es solo que los promedios se parezcan, es que **las
distribuciones completas se superponen**.

### 4.2 Mezcla de categorías (¿compran cosas distintas?)

| Categoría | A: ≥ 100 pedidos | D: < 100 pedidos | Δ |
|---|---|---|---|
| Abarrotes | 22,95 % | 23,12 % | −0,17 pp |
| Ropa | 14,23 % | 13,98 % | +0,25 pp |
| Deportes | 13,02 % | 12,62 % | +0,40 pp |
| Calzado | 12,50 % | 11,88 % | +0,62 pp |
| Hogar | 10,53 % | 11,07 % | −0,54 pp |
| Electrónica | 9,52 % | 9,48 % | +0,04 pp |
| Belleza | 8,91 % | 9,31 % | −0,40 pp |
| Accesorios | 8,30 % | 8,02 % | +0,28 pp |

**Desviación máxima: 0,62 puntos porcentuales.** Un mayorista compraría concentrado en pocas
categorías rotativas; aquí la canasta de los clientes grandes es indistinguible de la de los
pequeños.

### 4.3 Método de pago

| Método | A: ≥ 100 pedidos | D: < 100 pedidos | Δ |
|---|---|---|---|
| Tarjeta de débito/crédito | 49,44 % | 50,03 % | −0,59 pp |
| Transferencia bancaria | 27,71 % | 28,56 % | −0,85 pp |
| Efectivo | 22,85 % | 21,41 % | +1,44 pp |

Idéntico. Ni siquiera hay un sesgo del cliente grande hacia la transferencia (que sería el patrón
B2B natural), ni existe crédito comercial en ninguna parte del sistema.

### 4.4 Resultado de la prueba

| Dimensión | ¿Separa dos poblaciones? | Evidencia |
|---|---|---|
| Ticket | **No** | Unimodal log-normal, una sola cresta, sin hombro |
| Unidades por pedido | **No** | 99,94 % de líneas con 1–4 uds; techo 12; techo de pedido 24 |
| Líneas por pedido | **No** | Máximo 5 para todos |
| Mezcla de categorías | **No** | Δ máx. 0,62 pp |
| Método de pago | **No** | Δ máx. 1,44 pp |
| Canal | **No** | §5 |
| Regularidad de compra | **No** | CV 1,34–1,82 en los tres grupos (irregular en todos) |
| **Frecuencia / facturación acumulada** | **Solo en escala** | Top 10 % = 49,34 %, pero con el mismo ticket y la misma canasta |

**No hay umbral candidato defendible.** La única dimensión con dispersión relevante es la
frecuencia, y (i) su distribución es un continuo sin hueco, (ii) el comportamiento a ambos lados de
cualquier corte es el mismo, y (iii) los clientes de alta frecuencia muestran señales inequívocas
de consumidor final: son personas naturales con cédula de 10 dígitos, tienen fecha de nacimiento y
género registrados, escriben reseñas de producto (30 el mayor de todos) y compran indistintamente
por web, tienda y teléfono.

Un corte por frecuencia produciría un segmento de **"clientes de alto valor / recurrentes"**, que es
una segmentación **RFM legítima** — pero *no* es la distinción **mayorista vs. minorista** que exige
el discurso híbrido B2B + B2C. Llamar "B2B" a ese grupo sería ponerle una etiqueta que el dato no
respalda.

---

## 5. Correlación con el canal

`pedido.canal` es **independiente** del comportamiento de compra:

| Canal | Pedidos | Ticket medio | Mediana | P90 | Máximo | Uds/pedido | Líneas | Uds máx. | Clientes |
|---|---|---|---|---|---|---|---|---|---|
| web | 2.213 | $1.388,72 | $1.165,15 | $2.823,91 | $6.422,67 | 5,08 | 2,57 | 24 | 66 |
| tienda | 1.030 | $1.396,64 | $1.151,57 | $2.822,45 | $6.701,02 | 4,96 | 2,46 | 17 | 66 |
| telefono | 840 | $1.434,12 | $1.188,77 | $2.889,79 | $6.479,99 | 5,15 | 2,58 | 15 | 61 |

- Dispersión del ticket medio entre canales: **3,3 %** ($1.388,72 → $1.434,12). El canal telefónico
  es marginalmente el mayor, no el de mostrador, y la diferencia es ruido.
- Unidades por pedido: 4,96–5,15. Líneas: 2,46–2,58. Los P90 coinciden en $2.82x en los tres.
- **64 de los 69 clientes compran por los dos mundos** (web e interno); solo 2 son exclusivamente
  web y 3 exclusivamente internos. El cliente medio hace el 53,29 % de sus pedidos por web.

**Los pedidos de mostrador y teléfono NO son más grandes que los web.** El canal describe *cómo*
entró el pedido, no *quién* lo hizo ni *para qué*. Esto ratifica formalmente el hallazgo de
OTD-VEN-16 (2026-07-29): `pedido.canal` es el **medio**, no el **segmento**.

---

## 6. Veredicto final

### (c) POBLACIÓN HOMOGÉNEA

**El híbrido B2B + B2C no está sembrado en los datos.** RetailMind, tal como está poblado hoy, es
una operación **exclusivamente B2C de ticket alto**: 69 personas naturales, todas con cédula, que
compran entre 1 y 5 líneas y entre 1 y 10 unidades de productos caros ($276 por unidad), pagan al
contado con tarjeta/transferencia/efectivo, reseñan lo que compran y alternan libremente entre la
tienda web, el mostrador y el teléfono.

Los tres pilares de la conclusión:

1. **No existe compra de volumen.** 99,94 % de las líneas piden 1–4 unidades; el máximo histórico de
   una línea es 12 y el de un pedido completo, 24. Sin volumen no hay mayorista, y sin mayorista no
   hay B2B — con independencia de lo que digan las etiquetas.
2. **Ninguna dimensión de comportamiento separa dos grupos.** Ticket unimodal log-normal sin segunda
   cresta; mezcla de categorías con Δ máx. 0,62 pp; método de pago con Δ máx. 1,44 pp; ticket medio
   de los clientes grandes ($1.415,96) prácticamente igual al de los pequeños ($1.258,97).
3. **No existe soporte estructural para B2B.** Cero RUC (0 de 3.887 facturas), cero razones sociales
   empresariales (las 3.887 son el nombre de la persona), cero listas de precios por grupo, cero
   cuentas por cobrar de cliente, y las tres tablas del modelo pensadas para esto
   (`grupo_cliente`, `segmento_cliente`, `cliente_segmento`) están **vacías**.

### Por qué no es (b) "señal débil"

Hay una tentación legítima: la concentración es real (top 10 % = 49,34 % de la facturación, el
cliente nº1 solo aporta el 17,37 %) y encaja con la intuición de "unos pocos clientes grandes tipo
negocio". Se descarta como señal de segmento porque **la concentración es de frecuencia pura**: esos
clientes hacen el mismo pedido que todos los demás, solo que muchas más veces, con la misma canasta,
el mismo método de pago y una cadencia *más* irregular (CV 1,82) que la del resto. Interpretar eso
como B2B sería confundir **valor de cliente** con **tipo de cliente**.

### Consecuencia para el OE-01 y la misión

La decisión posterior —fuera del alcance de este diagnóstico— tiene tres caminos, y el dato solo
respalda honestamente los dos últimos:

1. **Mantener el corte B2B/B2C sobre lo que hay**: no es defendible. Cualquier informe segmentado
   mediría una etiqueta inventada, no una realidad.
2. **Quitar el corte B2B/B2C** del OE-01 y de la misión, y reposicionar RetailMind como lo que los
   datos sí sostienen: comercio minorista multicanal de ticket alto. Sin coste técnico.
3. **Sembrar de verdad el B2B** en un bloque dedicado (clientes con RUC de 13 dígitos, líneas de
   decenas/cientos de unidades, `grupo_cliente` con `porcentaje_descuento`, cadencia regular de
   reabastecimiento y, para ser creíble, crédito a cliente). Es un bloque de seed nuevo con impacto
   sobre stock, kardex y compras — coste real, decisión de alcance.

Si —y solo si— se opta por documentar el hallazgo sin sembrar nada, la segmentación **honesta** que
los datos sí permiten derivar es **RFM por valor** (p. ej. top 20 % de facturación = 14 clientes =
68,76 % del ingreso), etiquetada como *"clientes de alto valor"*, **nunca** como *"clientes B2B"*.

### Criterio de derivación propuesto

**No aplica.** El veredicto es (c); no se propone criterio de derivación de segmento B2B/B2C porque
ningún criterio objetivo lo sostiene sobre los datos actuales.

---

## 7. Trazabilidad de la evidencia

Todas las cifras de este documento provienen de consultas `SELECT` sobre `retailmind`, ejecutadas el
2026-07-30 vía el MCP de solo lectura. Las tablas consultadas fueron: `information_schema.columns`,
`information_schema.tables`, `cliente`, `grupo_cliente`, `segmento_cliente`, `cliente_segmento`,
`usuario`, `direccion`, `pedido`, `pedido_detalle`, `factura_venta`, `pago`, `metodo_pago`,
`producto`, `producto_variante`, `producto_categoria`, `categoria` y `resena`. **Ninguna escritura,
ningún DDL, ningún cambio en código ni en el seed.**
