# Auditoría transversal del seed completo (Bloques A + B + C)

Fecha: 2026-07-24. Alcance: los 18 meses sembrados (2025-01-13 → 2026-07-22) mirados **como un
solo negocio**, no bloque por bloque. Toda cifra fue medida vía MCP `retailmind` sobre PostgreSQL
`localhost:5432/retailmind` en **modo estrictamente lectura**. No se modificó ni un dato, ni una
línea de código.

Los tres bloques se auto-verificaron por separado y los tres pasaron. Este documento busca
precisamente lo que esa forma de verificar no puede ver: incoherencias que solo existen **al
cruzar bloques**, y datos que **cuadran aritméticamente pero son irreales o inútiles para un
informe**. El precedente es el defecto ya conocido (costo = 0,60 × precio en todo el catálogo,
que aplana OTD-GER-03): ninguna de las 21 verificaciones internas lo detectó porque ninguna
preguntaba "¿esto discrimina?".

---

## 1. Resumen ejecutivo

**30 hallazgos**, distribuidos así:

| Severidad | Definición | Cantidad |
|---|---|---|
| **ALTA** — compromete la demo | Un evaluador que abra el informe correspondiente ve algo plano, vacío o contradictorio en el primer minuto | **9** |
| **MEDIA** — se nota si se busca | Aparece al cruzar dos pantallas o al preguntar "¿y por qué...?" | **12** |
| **BAJA** — cosmética | No compromete nada, pero suma a la sensación de artificialidad | **9** |

Lectura honesta en tres frases:

1. **El seed cumplió su objetivo de volumen y de series temporales.** Los 11 objetivos que
   dibujaban "un punto" ahora dibujan 19 meses reales, con estacionalidad que se sostiene y sin
   días de volumen imposible. Eso está bien hecho y no se toca.
2. **El seed falló en generar CONTRASTE.** Casi todo lo que debería tener ganadores y perdedores
   —categorías, productos, canales, vendedores, meses— salió parejo. Los informes cargan, pero no
   dicen nada. Esta es la familia dominante: 6 de los 9 hallazgos ALTA son variantes del mismo
   defecto de raíz que el costo = 0,60 × precio.
3. **El Bloque C quedó desconectado del Bloque B en el dinero.** Cupones y promociones existen y
   tienen historia, pero no tocaron ni un centavo del total de ningún pedido. Es la única
   incoherencia dura (no estética) del conjunto, y es la que peor se ve si alguien abre un pedido
   con cupón.

Y una advertencia sobre el resto: **hay cosas que están genuinamente bien**, listadas en §9 para
que no se corrijan por error. La coherencia contable (CxP cuadra al centavo), la cronología de
stock (cero negativos en 12.396 movimientos) y la estacionalidad son sólidas.

---

## 2. Dimensión 1 — Coherencia compra ↔ venta por producto

### A1 · ALTA · La compra no abastece la venta; lo que la sostiene es un asiento de apertura ficticio

```sql
WITH compras AS (SELECT producto_variante_id v, sum(cantidad_recibida) qty FROM orden_compra_detalle GROUP BY 1),
     ventas  AS (SELECT producto_variante_id v, sum(cantidad) qty FROM pedido_detalle GROUP BY 1)
SELECT (SELECT count(*) FROM ventas v LEFT JOIN compras c USING(v) WHERE c.v IS NULL) vendidos_sin_comprar,
       (SELECT count(*) FROM ventas v JOIN compras c USING(v) WHERE v.qty > c.qty)     vendido_mas_que_comprado;
```

| Métrica | Valor |
|---|---|
| Variantes con venta | 845 |
| Variantes con alguna compra | 423 |
| **Variantes vendidas que NUNCA se compraron** | **561 (66,4 % de las vendidas)** |
| Variantes vendidas por encima de lo recibido | 65 |
| Unidades vendidas (18 meses) | 20.687 |
| Unidades recibidas por compra (18 meses) | 32.523 |
| **Unidades ingresadas por "apertura"** | **120.160 en un solo día** |

El kardex lo explica:

```sql
SELECT tm.codigo, count(*) n, sum(mi.cantidad) uds
FROM movimiento_inventario mi JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
GROUP BY 1 ORDER BY 2 DESC;
```

| Tipo | Movs | Unidades |
|---|---|---|
| `entrada_ajuste` (`referencia_tipo='inventario_inicial'`) | 1.216 | **120.160** |
| `entrada_compra` | 1.245 | 32.523 |
| `salida_venta` | 9.785 | 19.503 |
| resto (devoluciones, transferencias, reposición) | 150 | ~430 |

**El 78,7 % de todas las unidades que entraron al almacén en 18 meses entraron como un ajuste
manual el 2025-01-01.** Aritméticamente todo cuadra (por eso las verificaciones de bloque
pasaron: `kardex = stock`, cero negativos). Pero el negocio que describen los datos es uno que
compró $4M de mercadería que en dos tercios de los casos no es la que vende.

Qué rompe en pantalla: rotación por categoría (OTD-INV-04) mide contra un stock que no vino de
compras; evolución del costo de compra (OTD-COM-12) y proveedor por producto (OTD-COM-10) no
tienen contraparte de venta para 561 variantes; y cualquier pregunta del tipo "¿de dónde salió
este producto?" sobre un producto vendido tiene 2 de 3 probabilidades de no tener respuesta.

### M1 · MEDIA · 139 variantes compradas y jamás vendidas; 376 nunca vendidas

De 1.221 variantes, 376 (30,8 %) no registran una sola venta en 18 meses. Es una **mejora enorme**
frente a las 1.197 de la auditoría de densidad (OTD-VEN-04 pasa de inútil a útil), pero 139 de
ellas sí se compraron: mercadería comprada que nunca se movió, sin que exista ningún ajuste,
merma ni promoción de liquidación asociada.

### M2 · MEDIA · 261 filas de inventario intactas en 100 unidades exactas

```sql
SELECT stock_actual, count(*) FROM inventario GROUP BY 1 ORDER BY 2 DESC LIMIT 3;
-- 100 → 261 filas | 91 → 30 | 90 → 29
```

261 de 1.372 filas (19 %) siguen exactamente en el valor plano de apertura: no las tocó una sola
entrada ni salida en 18 meses. Además el reparto entre bodegas es casi ficticio: Bodega Central
Quevedo con 1.220 filas y 128.849 unidades, **Bodega Norte con 152 filas y 4.530 (3,4 %)**.
OTD-INV-02 muestra dos barras de 96 % y 4 %.

### M3 · MEDIA · `stock_reservado` = 0 en 1.372 de 1.372 filas

Hay 87 pedidos vivos (21 pagados, 21 facturados, 18 confirmados, 15 en preparación, 12 preparados)
y ninguno tiene una sola unidad apartada. La nota que la auditoría de densidad puso sobre
OTD-INV-02 sigue exactamente igual después del seed.

### B6 · BAJA · 3 de los 72 clientes no tienen ni un pedido

---

## 3. Dimensión 2 — Uniformidades sospechosas

Esta es la dimensión con más hallazgos y la que más peso tiene. El patrón se repite: **la columna
existe, tiene valores distintos, y aun así el informe sale plano** porque la variación es
demasiado pequeña para lo que la magnitud representa.

### A2 · ALTA · Margen por categoría: 1,35 puntos de rango entre las 8 categorías

```sql
SELECT cat.nombre,
       round(((sum(pd.cantidad*pd.precio_unitario - pd.monto_descuento) - sum(pd.cantidad*pv.costo))
            / sum(pd.cantidad*pd.precio_unitario - pd.monto_descuento) * 100)::numeric, 2) margen_pct
FROM pedido_detalle pd
JOIN producto_variante pv ON pv.id = pd.producto_variante_id
JOIN producto_categoria pc ON pc.producto_id = pv.producto_id
JOIN categoria cat ON cat.id = pc.categoria_id
GROUP BY 1 ORDER BY margen_pct;
```

| Categoría | Unidades | Venta | Margen |
|---|---|---|---|
| Accesorios | 2.853 | $649.093 | **35,58 %** |
| Calzado | 2.543 | $587.692 | 36,31 % |
| Hogar | 2.519 | $581.937 | 36,70 % |
| Belleza | 2.719 | $640.471 | 36,75 % |
| Deportes | 2.450 | $663.410 | 36,82 % |
| Electrónica | 2.481 | $668.038 | 36,84 % |
| Abarrotes | 2.464 | $569.673 | 36,86 % |
| Ropa | 2.624 | $634.478 | **36,93 %** |

Confirmado y **peor de lo reportado**: no es solo que el catálogo tenga costo = 0,60 × precio
(1.083 de 1.221 variantes con ratio exactamente 0,6000; otras 130 entre 0,5995 y 0,6005). Es que
ni el ruido que el seed sí introdujo en `precio_unitario` alcanza para separar las categorías.
Electrónica y Abarrotes —dos negocios que en la realidad tienen márgenes que difieren en 20
puntos— aquí difieren en **0,02 puntos**. OTD-GER-03 ("ganancia por categoría") y OTD-GER-10
("margen por producto") cargan bien y no discriminan nada.

### A3 · ALTA · Margen mensual: 0,60 puntos de rango en 18 meses

36,41 % (ene-25) → 37,01 % (jul-25) → 36,88 % (jun-26). La línea es una recta. Ningún mes con
liquidación, ningún mes con presión de costos, ninguna campaña que sacrifique margen por volumen.
El único mes fuera de banda es 2026-07 con 32,79 %, y es porque está incompleto.

### A4 · ALTA · Los tres canales son estadísticamente el mismo canal

```sql
SELECT canal, count(*), round(avg(total)::numeric,2) ticket, round(stddev(total)::numeric,2) sd,
       min(total), max(total) FROM pedido GROUP BY 1;
```

| Canal | Pedidos | Ticket promedio | Desv. estándar | Mín | Máx |
|---|---|---|---|---|---|
| web | 2.213 | $1.416,58 | 1.048,11 | 20,98 | 6.468,67 |
| tienda | 1.030 | $1.397,83 | 1.040,14 | 7,75 | 6.701,02 |
| teléfono | 840 | $1.435,49 | 1.037,40 | 20,35 | 6.479,99 |

No solo el promedio coincide: **la desviación estándar coincide hasta el segundo decimal en los
tres**. Es la firma de haber generado los tres canales con la misma distribución y luego
etiquetarlos. OTD-VEN-07 ("ticket promedio por período y canal") produce tres barras idénticas —
justo el informe que se pide para decidir en qué canal invertir.

### A5 · ALTA · No existen best-sellers

```sql
SELECT pv.sku, sum(pd.cantidad) uds FROM pedido_detalle pd
JOIN producto_variante pv ON pv.id = pd.producto_variante_id GROUP BY 1 ORDER BY 2 DESC LIMIT 5;
-- 90, 85, 84, 83, 82
```

Los cinco productos más vendidos de 18 meses venden 90, 85, 84, 83 y 82 unidades. El top 20 % de
las variantes vendidas concentra **45,9 %** de las unidades; en retail real la concentración es
del orden de 70–80 %. La demanda del seed es prácticamente uniforme entre 845 productos.

OTD-VEN-03 ("Top 10 productos más vendidos") entrega diez barras del mismo alto. Un evaluador que
abra ese informe —y es de los primeros que se abren— ve inmediatamente que los datos no son de un
negocio real, porque en un negocio real siempre hay un producto que manda.

### A6 · ALTA · Las categorías venden todas lo mismo

Ver la tabla de A2: 2.450 a 2.853 unidades (rango 16 %) y $569.673 a $668.038 de venta en las
ocho categorías. Ninguna categoría lidera, ninguna es marginal. Cualquier gráfico de mezcla por
categoría es una fila de barras iguales, y la pregunta obvia del evaluador —"¿cuál es su
categoría fuerte?"— no tiene respuesta en los datos.

### M4 · MEDIA · Ticket promedio mensual plano en 19 meses

$1.272 a $1.491, sin tendencia. El negocio triplica su volumen entre enero-25 y diciembre-25 y el
ticket no se mueve un dólar. Ni inflación, ni upselling, ni efecto de las campañas del Bloque C.

### M5 · MEDIA · Los vendedores solo se diferencian en volumen

| Vendedor | Pedidos | Monto | Ticket |
|---|---|---|---|
| vendedor@retailmind.com | 539 | $763.572 | $1.416,65 |
| luis.alvarado@ | 386 | $537.081 | $1.391,40 |
| jorge.moreira@ | 337 | $468.451 | $1.390,06 |
| andres.loor@ | 260 | $366.995 | $1.411,52 |
| diego.macias@ | 202 | $316.424 | $1.566,45 |
| marco.intriago@ | 136 | $186.628 | $1.372,26 |

El reparto de volumen sí es realista (539 vs 136, buena pirámide). Lo que no varía es la
**calidad** de la venta: seis vendedores con el mismo ticket promedio. OTD-VEN-02 responde "quién
vendió más" pero nunca "quién vende mejor", que es la pregunta gerencial.

### M6 · MEDIA · El día de la semana no existe, y el domingo contradice la regla de horario

```sql
SELECT extract(dow FROM fecha_pedido) dow, count(*) FROM pedido GROUP BY 1 ORDER BY 1;
-- dom 604 | lun 551 | mar 575 | mié 590 | jue 601 | vie 579 | sáb 583
```

Un rango de 53 pedidos sobre ~580, y **el domingo es el día de mayor venta**. Dos problemas:

1. Una tienda física que vende igual el domingo que el martes no es creíble.
2. **283 de esos pedidos dominicales son de canal `tienda` (149) y `telefono` (134)** — es decir,
   pedidos creados por personal interno. El propio sistema tiene restricción de horario por día
   de la semana que bloquea al staff los domingos (`grupo_horario` + `esta_en_horario()`). Los
   datos sembrados describen algo que la aplicación en vivo no permitiría hacer. Si alguien cruza
   el informe de ventas por día con la matriz de horarios, la contradicción es visible.

### M7 · MEDIA · La mezcla de canal está congelada

Participación del canal web por mes: mínimo 50,0 %, máximo 59,0 %, desviación estándar **2,76 pp**
en 19 meses. OTD-VEN-13 ("venta por canal, por período") dibuja tres líneas rigurosamente
paralelas. No hay migración a digital, que es exactamente la historia que un informe de mezcla de
canal existe para contar.

### M8 · MEDIA · Las líneas por pedido están topadas en 5, y se acumulan en el tope

```sql
SELECT lineas, count(*) FROM (SELECT pedido_id, count(*) lineas FROM pedido_detalle GROUP BY 1) t GROUP BY 1;
-- 1→940 | 2→1.357 | 3→875 | 4→450 | 5→461
```

La cola decae bien de 2 a 4 y luego **repunta en 5** (461 > 450). Es el patrón clásico de un
`min(n, 5)`: todo lo que la distribución quería poner en 6, 7 u 8 líneas se acumuló en el corte.
Un pedido de 5 líneas debería ser más raro que uno de 4, no más frecuente. Idem `cantidad` por
línea, topada en 12 con promedio 1,99.

### B3 · BAJA · Ningún pedido web fuera del horario de oficina

99,6 % de los 4.083 pedidos caen entre las 08:00 y las 18:59; solo 16 quedan fuera. Que la tienda
física opere en ese horario es correcto; que **una tienda online no reciba pedidos a las 21:00 ni
los fines de semana por la noche** no lo es. El pico nocturno es una de las señales que cualquiera
con experiencia en e-commerce busca primero.

### B4 · BAJA · Cuatro variantes con costo mayor que el precio

Ratios costo/precio de 1,8000 (×2), 1,6012 y 1,5152: productos que se venden con hasta **−80 % de
margen**. Como el resto del catálogo está clavado en 40 % teórico, estos cuatro son los únicos
outliers y por tanto lo primero que aparece si alguien ordena OTD-GER-10 por margen ascendente.

---

## 4. Dimensión 3 — Distribución temporal

Esta dimensión es, con diferencia, la mejor resuelta del seed.

### Lo que está bien (verificado, no es un hallazgo)

```sql
SELECT count(DISTINCT fecha_pedido::date) dias_con_pedido, max(n) max_dia, avg(n) avg_dia FROM ...;
-- 496 días con pedido de 556 posibles | máximo 20 pedidos en un día | promedio 8,2
```

- **Sin concentraciones imposibles**: ningún día acapara un mes. El máximo diario (20) es 2,4× el
  promedio (8,2), que es una relación sana.
- **La estacionalidad declarada se sostiene con datos reales**: diciembre 326 pedidos y noviembre
  261 (pico navideño/Black Friday), mayo 306 y abril 268 (temporada escolar de la Costa
  ecuatoriana), contra una base de ~190. No es una afirmación del documento del bloque: sale de
  agrupar `fecha_pedido` por mes.
- **El crecimiento interanual es gradual**: +6 % a +15 % en los meses comparables, no escalonado.

### B1 · BAJA · Enero 2025 es un mes parcial y falsea el crecimiento interanual de enero

El primer pedido es del 2025-01-13, de modo que enero-25 tiene 89 pedidos contra los 178 de
enero-26: **+100 % interanual**, cuando el resto de los meses crece entre 6 % y 15 %. Un informe
de crecimiento YoY muestra un pico falso en enero que no corresponde a ningún fenómeno del
negocio.

### B2 · BAJA · El kardex tiene un día 1.216 veces más alto que cualquier otro

Los 1.216 movimientos de apertura (120.160 unidades) caen todos el 2025-01-01, una fecha en la que
además **no existe ningún pedido, ninguna orden de compra ni ninguna operación**. Cualquier
gráfico de movimientos de inventario por día tiene una sola barra que aplasta la escala y obliga a
explicarla. Es el reverso visual del hallazgo A1.

---

## 5. Dimensión 4 — Coherencia de entidades

### A7 · ALTA · El cliente 52 hace 673 pedidos en 504 días

```sql
SELECT cliente_id, count(*) pedidos, sum(total) monto, min(fecha_pedido), max(fecha_pedido)
FROM pedido GROUP BY 1 ORDER BY 2 DESC LIMIT 3;
```

| Cliente | Pedidos | Monto | Desde | Hasta |
|---|---|---|---|---|
| **52** | **673** | **$996.397,27** | 2025-03-02 | 2026-07-18 |
| 54 | 297 | $419.676,19 | 2025-01-13 | 2026-05-09 |
| 61 | 230 | $301.808,35 | 2025-03-07 | 2026-07-16 |

El cliente 52 hace **1,34 pedidos por día, todos los días, durante 17 meses seguidos**, y él solo
representa el **17,2 % del ingreso total** del negocio. No es un cliente minorista; ni siquiera es
un cliente mayorista creíble (un mayorista compra en lotes grandes y poco frecuentes, no 673
veces).

El problema de fondo es de escala del universo: **72 clientes generando $5,78M son $80.284 por
cliente y 59,2 pedidos por cliente en 18 meses**. Eso describe una distribuidora B2B con cartera
corporativa, no la "tienda PyME de comercio minorista" que el proyecto declara. La concentración
en sí es correcta (el top 20 % concentra 68,5 % de la venta, que es un Pareto sano); lo irreal es
la **frecuencia individual**, y "¿quién es su mejor cliente?" es de las primeras cosas que se
abren en una demo de retail.

### M9 · MEDIA · Un transportista acapara el 80 %, y el más barato es además el más rápido

```sql
SELECT t.nombre, count(e.id), avg(e.costo), avg(EXTRACT(epoch FROM (e.fecha_entrega_real - e.fecha_despacho))/86400)
FROM transportista t LEFT JOIN envio e ON e.transportista_id = t.id GROUP BY 1;
```

| Transportista | Envíos | Costo prom. | Días de tránsito |
|---|---|---|---|
| Servientrega | **2.293 (79,8 %)** | $11,67 | 3,79 |
| Tramaco Express | 180 | **$4,71** | **1,40** |
| Speed Mail Ecuador | 140 | $13,19 | 4,23 |
| Laar Courier | 132 | $12,61 | 3,93 |
| Urbano Express | 127 | $12,64 | 3,82 |

Dos cosas: (a) los cuatro transportistas menores tienen muestras de 127–180 envíos, de modo que
OTD-LOG-04 compara cinco actores donde uno tiene 13 veces más datos que el resto; (b) **Tramaco es
simultáneamente el más barato ($4,71 contra $11,67–13,19) y el más rápido (1,40 días contra
3,79–4,23)**. No hay trade-off costo/velocidad, que es la tensión que ese informe existe para
mostrar. Con estos datos la recomendación gerencial obvia sería "mande todo por Tramaco", y no
hay nada en los datos que la contradiga.

### Lo que está bien (verificado)

- **Proveedores bien repartidos**: 52 / 50 / 41 / 40 / 31 / 31 / 31 / 22 / 21 órdenes de compra
  en los nueve principales. Buena curva, sin acaparador. Los dos restantes (Distribuidora
  Deportiva Andina con 9 OCs solo en julio-26, Importadora Global Sport con 8 por $18.767) son
  proveedores tardíos/marginales, lo cual es realista.
- **Cronología cliente ↔ primera compra correcta**: 0 casos de compra anterior al alta; el retraso
  entre alta y primera compra va de 0 a 88 días, con 62 fechas de alta distintas sobre 69
  clientes. Bien hecho.

---

## 6. Dimensión 5 — Coherencia monetaria cruzando bloques

### A8 · ALTA · Los cupones del Bloque C no tocan el dinero de los pedidos del Bloque B

```sql
SELECT count(*) usos, sum(uc.monto_descontado) descuento_en_uso_cupon,
       count(*) FILTER (WHERE p.monto_descuento = 0) sobre_pedido_sin_descuento
FROM uso_cupon uc JOIN pedido p ON p.id = uc.pedido_id;
```

| Fuente | Descuento total | Registros |
|---|---|---|
| `uso_cupon.monto_descontado` | **$50.867,48** | 564 usos |
| `pedido.monto_descuento` | **$52,91** | 3 pedidos |
| Usos sobre un pedido con descuento 0 | — | **561 de 564** |

**Esta es la única incoherencia dura del conjunto** (el resto son problemas de realismo o de poder
discriminante; esta es una contradicción entre dos tablas). El Bloque C se sembró replicando el
registro de uso pero saltándose el efecto sobre el total, y el resultado es que:

- Abrir en pantalla cualquiera de esos 561 pedidos muestra un cupón aplicado y un total que **no
  lo descuenta**. Es visible a simple vista en el detalle del pedido y en Mis Pedidos.
- Un informe "descuento otorgado sobre ingresos" da **0,880 %** si se lee `uso_cupon` y
  **0,0009 %** si se lee `pedido`. Dos respuestas a la misma pregunta con tres órdenes de
  magnitud de diferencia.
- El ingreso total ($5.780.474) está inflado en ~$50.867 respecto de lo que el negocio realmente
  habría cobrado con esos cupones vigentes.
- Las facturas de venta correspondientes tampoco prorratean el cupón entre líneas, que es lo que
  el sistema real hace (`factura_venta_detalle.monto_descuento`).

### A9 · ALTA · Las 19 promociones no se aplicaron a ninguna venta

Solo **3 líneas de 10.384** tienen `pedido_detalle.monto_descuento > 0`, por un total de $181,64.
Las 19 promociones y sus 176 productos asociados existen como catálogo, pero jamás rebajaron una
línea. Consecuencia: es **imposible evaluar el efecto de una promoción** (el objetivo que la
auditoría de densidad marcaba como REQUIERE VOLUMEN sigue exactamente igual), y el descuento por
línea que el motor de `DescuentosService` aplica en producción no tiene ni un caso histórico que
mostrar.

### M10 · MEDIA · El marketing sembrado está casi todo vencido

```sql
SELECT (SELECT count(*) FROM promocion WHERE activo AND fecha_inicio<=now() AND fecha_fin>=now()),
       (SELECT count(*) FROM campana  WHERE fecha_inicio<=now() AND fecha_fin>=now()), ...;
```

| Entidad | Sembradas | **Vigentes hoy** |
|---|---|---|
| Promociones | 19 | **1** |
| Campañas | 14 | **2** |
| Banners | 17 | **2** |
| Cupones | 29 | **2** |

El Bloque C repartió las vigencias a lo largo de los 18 meses históricos, lo cual es correcto para
el análisis retrospectivo, pero dejó el presente casi vacío. **OTD-GER-06 ("acciones de marketing
vigentes") muestra hoy prácticamente las mismas tres filas que mostraba antes del seed** — el
objetivo que este bloque debía curar sigue delgado.

### M11 · MEDIA · Se cobró $189.954 más de lo que se facturó

| Flujo | Monto |
|---|---|
| Facturas de venta (no anuladas) | $5.481.546,92 |
| Cobrado (`pago`) | **$5.671.500,58** |
| Diferencia | **+$189.953,66** |

Parte se explica (pedidos pagados aún no facturados: 21 en estado `pagado`), pero la magnitud
—3,5 % del total— no cierra solo con eso. Un informe de entradas vs salidas de dinero (OTD-GER-02)
muestra la barra de cobros por encima de la de facturación de forma sostenida, que es una bandera
roja contable.

### M12 · MEDIA · Las metas nunca se fallan ni se superan de verdad

Los 19 meses de la meta 'general' caen entre **91,0 % y 113,8 %** de cumplimiento. Ni un mes
catastrófico, ni un récord. La meta se derivó del resultado real con un ruido de ±10 %, y se nota:
el informe de meta vs. real es la herramienta gerencial para detectar el mes que se salió de
control, y aquí no hay ninguno. Se agrava porque solo hay **2 departamentos** con meta ('general'
y 'ventas'), de los 7 que la lista blanca de `MetasVentaService` admite.

### Lo que está bien (verificado)

- **Cuentas por pagar cuadran al centavo**: facturas de compra $3.815.107,62 − pagos a proveedor
  $2.803.140,54 = saldo pendiente $1.011.967,08. Exacto. 310 CxP, 36 vencidas, los 4 estados en
  uso. Excelente.
- **Reembolsos coherentes**: $44.525,63 reembolsados sobre $95.693,89 de devoluciones (46,5 %),
  consistente con que 56 de 196 devoluciones estén en estado 'reembolsada' y 143 sigan en curso.

---

## 7. Dimensión 6 — Casos borde y valores extremos

Se buscó deliberadamente lo que un profesor curioso tocaría. **Los montos están todos sanos**; los
extremos absurdos están en las frecuencias, no en el dinero.

| Caso borde | Valor | Veredicto |
|---|---|---|
| Pedido más caro | $6.701,02 (id 1537, canal tienda) | Razonable |
| Pedido más barato | $7,75 | Razonable |
| Envío más pesado | 49,49 kg (promedio 7,50, sd 7,22) | Razonable |
| Stock máximo en una variante | 838 unidades | Razonable |
| Descuento de cupón mayor | $676,32 (ninguno supera el total del pedido) | Razonable |
| Movimientos con stock negativo | **0** de 12.396 | Correcto |
| Inventario negativo | **0** de 1.372 | Correcto |
| **Cliente con más pedidos** | **673 (ver A7)** | **Absurdo** |
| **Día de kardex más movido** | **1.216 movs / 120.160 uds (ver B2)** | **Absurdo** |

### B5 · BAJA · 24 envíos con costo $0,00 y peso NULL

Son los envíos históricos anteriores al seed. Está documentado que el arreglo no reescribe
historia, pero en OTD-LOG-11 aparecen como 24 envíos gratis de peso desconocido.

### B7 · BAJA · Solo 4 motivos de devolución, y con distribución plana

59 / 54 / 42 / 41 sobre los cuatro únicos motivos del catálogo. No hay un motivo dominante, que es
justo lo que el informe de motivos existe para revelar ("¿por qué nos devuelven?" → "por las
cuatro razones por igual" no es una respuesta accionable).

### B8 · BAJA · Universo maestro estrecho en dos ejes

2 bodegas (con la segunda al 3,4 %) y 2 departamentos con meta. Ambos son límites del entorno, no
del seed, pero acotan lo que los informes por bodega y por departamento pueden mostrar.

### B9 · BAJA · Calificaciones de reseña: distribución correcta

Se auditó específicamente por ser un candidato clásico a "too perfect", y **no lo es**: 5★ 43,0 %,
4★ 28,2 %, 3★ 15,1 %, 2★ 6,4 %, 1★ 7,3 %. Es una curva en J de libro. Se registra aquí para dejar
constancia de que se revisó y salió bien.

---

## 8. Dimensión 7 — Objetivos que el seed debía llenar y no llenó

Cruce contra `docs/tactico/AUDITORIA_DENSIDAD_DATOS.md` (29 DELGADOS + 1 HUECO = 30 objetivos con
déficit de densidad).

### Curados por el seed — 23 de 30

Verificados uno por uno con la consulta que los limitaba:

| Objetivo | Antes | Ahora |
|---|---|---|
| VEN-06/07/09/13/14, COM-04, INV-04/09, LOG-09, GER-02/03 | 1 mes | **19 meses** |
| VEN-04 | 1.197 sin venta, todos empatados | 376 sin venta, con historia diferenciable |
| VEN-10 (reseñas) | 2 pendientes | **53 pendientes** |
| COM-03 | 10/10 a tiempo | **131 pagos tarde de 334 (39 %)** |
| COM-06 | 2 valores (0 y 1 día) | **24 valores, prom. 10 días, máx. 25** |
| COM-11 | 7 líneas incompletas | 310 recepciones sobre 336 OCs |
| COM-12 | 1 variante con cambio de precio | **314 variantes** (de 318 con recompra) |
| **COM-08 (el único HUECO)** | 0 pendientes, 0 en curso | **10 pendientes + 3 en devolución**; devoluciones a proveedor en 3 estados vivos |
| INV-01 | 1 fila bajo mínimo | **114 filas bajo mínimo**; 134 valores distintos de mínimo |
| LOG-01 | 0 preparados | **12 preparados**, 15 en preparación |
| LOG-03 | 7/7 a tiempo | **1.019 entregas tarde de 2.723 (37 %)** |
| LOG-04 | 2 valores de tránsito | 1,40–4,23 días con 5 transportistas |
| LOG-06 | 1 devolución en curso | **143 en curso**, los 9 estados poblados |
| LOG-10 | 1 reembolso | **85 reembolsos** |
| SOP-06 | 8 tickets con respuesta | 248 tickets, 523 mensajes, 5 estados |
| GER-10 | 17 productos con venta | 845 productos con venta |

Nota aparte: el bug conocido de `MetasVentaService` (`venta_realFROM` sin separador) **ya no
reproduce** — el archivo actual tiene el salto de línea correcto en
`MetasVentaService.java:47-62`. Se verificó porque bloqueaba el informe de metas por completo.

### Siguen sin datos suficientes — 7 de 30

> **Actualización 2026-07-25 (scripts 79-84):** seis de estos siete quedaron **CERRADOS**;
> el séptimo (OTD-GER-07) se documenta como **limitación conocida y aceptada**. Ver §12.

| # | Objetivo | Qué sigue faltando | Causa |
|---|---|---|---|
| 1 | **OTD-INV-05** — Ajustes de inventario y motivos | Sigue con **3 filas** en `ajuste_inventario` | Los 1.216 movimientos de apertura entraron como kardex huérfano (`referencia_tipo='inventario_inicial'`), sin documento de ajuste que los respalde. El informe de ajustes no los ve |
| 2 | **OTD-INV-06** — Transferencias entre bodegas | Sigue con **10 transferencias, todas 'recibida'**, todas de julio-2026. **Cero en camino** | Ningún bloque sembró transferencias. Es el objetivo más intacto del conjunto |
| 3 | **OTD-VEN-10** — Cola de moderación (mitad preguntas) | `pregunta_producto` sigue con **1 fila** y 1 respuesta | Las reseñas sí se sembraron (53 pendientes); las preguntas de producto, no |
| 4 | **OTD-GER-06** — Acciones de marketing vigentes | **1 promo + 2 campañas + 2 banners** vigentes hoy | Todo el Bloque C tiene vigencias históricas (ver M10) |
| 5 | **OTD-GER-07** — Efecto de las promociones | **3 líneas de 10.384** con descuento de promoción | Las promociones nunca se aplicaron a ventas (ver A9) |
| 6 | **OTD-GER-09** — Log de acceso | **39 filas, todas de 2026-07** | Ningún bloque sembró historial de acceso; sigue con un mes |
| 7 | **OTD-INV-02** (nota) — Stock apartado | `stock_reservado` = **0 en 1.372/1.372** | Los pedidos vivos no reservan (ver M3) |

Además, **OTD-GER-01** ("foto del día") vuelve a devolver 0 pedidos y 0 pagos: el último pedido es
del 2026-07-22 y hoy es 2026-07-24. Es el mismo problema de momento operativo que ya tenía; el
seed lo alivió pero no lo resolvió, porque el horizonte se detiene dos días antes de "hoy".

---

## 9. DEMASIADO LIMPIO

Uniformidades aritméticamente correctas que, precisamente por ser correctas, delatan que los datos
no vienen de un negocio. Van juntas aquí porque comparten causa raíz —**el generador usó la misma
distribución para poblaciones que en la realidad son distintas**— y probablemente comparten
solución.

| Qué debería variar | Rango real medido | Lo que se esperaría |
|---|---|---|
| Margen por categoría | 35,58 – 36,93 % (**1,35 pp**) | 15–25 pp entre Electrónica y Abarrotes |
| Margen por mes | 36,41 – 37,01 % (**0,60 pp**) | Caídas en meses de liquidación |
| Ticket por canal | $1.397 – $1.435, **sd idéntica (1.037–1.048)** | Web ≠ teléfono ≠ mostrador |
| Ticket por vendedor | $1.372 – $1.566 | Diferencias de 2× entre el mejor y el peor |
| Ticket por mes | $1.272 – $1.491 en 19 meses | Tendencia visible |
| Unidades por categoría | 2.450 – 2.853 (**16 %**) | Una categoría con 3–5× otra |
| Unidades del top 5 de productos | 90, 85, 84, 83, 82 | Un líder claro con 2–3× el segundo |
| Concentración Pareto de productos | Top 20 % = **45,9 %** de unidades | 70–80 % |
| Pedidos por día de semana | 551 – 604 (**9 %**), domingo el mayor | Fin de semana marcadamente distinto |
| Participación del canal web | 50 – 59 %, **sd 2,76 pp** en 19 meses | Migración progresiva a digital |
| Hora del pedido | 99,6 % entre 08:00 y 18:59 | Cola nocturna en el canal web |
| Costo / precio del catálogo | **1.083 de 1.221 en 0,6000 exacto** | Márgenes por familia de producto |
| Cumplimiento de meta | 91,0 – 113,8 % en 19 meses | Al menos un mes por debajo de 80 % |
| Tránsito por transportista | El más barato es también el más rápido | Trade-off costo/velocidad |

Contrapeso justo — **lo que NO está demasiado limpio** y no debe tocarse:

- Calificaciones de reseña (curva en J genuina: 43/28/15/6/7 %).
- Estados de pedido, ticket y devolución (los 9–10 estados poblados, con reparto creíble).
- Métodos de pago (50,8 % tarjeta / 27,8 % transferencia / 21,3 % efectivo).
- Reparto de órdenes entre proveedores (52 → 8, buena curva).
- Concentración de compra por cliente (top 20 % = 68,5 % de la venta; el Pareto de **monto** sí
  está bien, es la frecuencia individual lo que falla).
- Estacionalidad mensual, densidad diaria (496/556 días) y cronología de stock (0 negativos).
- Tiempos entre estados: `despachado→entregado` tiene 1.432 valores distintos sobre 2.727 casos,
  `pagado→facturado` 83 valores. Granularidad temporal genuinamente variada.

---

## 10. Priorización

Ordenados por cuánto comprometen una demostración ante un evaluador experto. El esfuerzo es
estimado para **corregir el dato**, no para rehacer el bloque.

| # | Sev. | Hallazgo | Dim. | Esfuerzo | Nota |
|---|---|---|---|---|---|
| A8 | **ALTA** | 561 usos de cupón por $50.867 no descuentan el total del pedido ($52,91 en `pedido.monto_descuento`) | 5 | **Alto** | Única incoherencia dura. Requiere recalcular totales y prorratear en facturas; los totales son GENERATED/trigger, así que hay que ir por el camino del sistema |
| A5 | **ALTA** | No hay best-sellers: top 5 = 90/85/84/83/82 uds; Pareto 45,9 % | 2 | **Alto** | Exige reponderar la demanda y regenerar líneas de venta |
| A2 | **ALTA** | Margen idéntico en las 8 categorías (1,35 pp de rango) | 2 | **Medio** | Reasignar `producto_variante.costo` por familia; el ratio 0,60 está en 1.083 filas. No toca transacciones, pero sí cambia el margen histórico |
| A7 | **ALTA** | Cliente 52 con 673 pedidos ($996k, 17,2 % del ingreso); 59,2 pedidos/cliente | 4 | **Alto** | O se reparte entre más clientes (ampliar el universo de 72) o se redistribuyen los pedidos del top |
| A9 | **ALTA** | 19 promociones sin efecto: 3 líneas de 10.384 con descuento | 5 | **Alto** | Mismo problema de totales que A8 |
| A1 | **ALTA** | 561 de 845 variantes vendidas nunca se compraron; apertura ficticia de 120.160 uds (78,7 % de las entradas) | 1 | **Alto** | Ampliar la cobertura de OCs para que la compra respalde la venta, o asumir y documentar la apertura |
| A6 | **ALTA** | Categorías con volumen y venta casi idénticos | 2 | **Medio** | Se cura junto con A5 (misma reponderación de demanda) |
| A3 | **ALTA** | Margen mensual plano (0,60 pp en 18 meses) | 2 | **Medio** | Se cura junto con A2 |
| A4 | **ALTA** | Los 3 canales con ticket **y desviación estándar** idénticos | 2 | **Medio** | Reescalar `pedido.total` por canal choca con los triggers; más limpio es regenerar el mix |
| M10 | MEDIA | Marketing sembrado pero vencido: 1/2/2/2 vigentes hoy | 5 | **Bajo** | Extender `fecha_fin` de unas pocas filas, o crear 4–6 registros vigentes por la pantalla de marketing |
| M6 | MEDIA | Día de semana plano y 283 pedidos internos en domingo contra la regla de horario | 2 | **Medio** | Redistribuir `fecha_pedido`; ojo con la coherencia del historial de estados |
| M3 | MEDIA | `stock_reservado` = 0 en 1.372/1.372 con 87 pedidos vivos | 1 | **Bajo** | Se puebla solo si los pedidos vivos pasan por el flujo real de reserva |
| M12 | MEDIA | Metas siempre entre 91 % y 114 %; solo 2 de 7 departamentos | 5 | **Bajo** | Ajustar unas pocas filas de `meta_venta` y agregar los 5 departamentos faltantes |
| M9 | MEDIA | Servientrega 79,8 % de envíos; Tramaco barato **y** rápido | 4 | **Medio** | Redistribuir `envio.transportista_id` y ajustar tarifas para que haya trade-off |
| M11 | MEDIA | Cobrado $189.954 por encima de lo facturado | 5 | **Medio** | Hay que rastrear el origen antes de decidir; puede ser legítimo en parte |
| M2 | MEDIA | 261 filas de inventario intactas en 100; Bodega Norte al 3,4 % | 1 | **Medio** | Se cura junto con A1/A5 |
| M5 | MEDIA | Vendedores solo diferenciados por volumen | 2 | **Medio** | Se cura junto con A4 |
| M1 | MEDIA | 139 variantes compradas y jamás vendidas | 1 | **Medio** | Se cura junto con A1 |
| M7 | MEDIA | Mezcla de canal congelada (sd 2,76 pp) | 2 | **Medio** | Se cura junto con A4 |
| M4 | MEDIA | Ticket mensual plano en 19 meses | 2 | **Medio** | Se cura junto con A4 |
| M8 | MEDIA | Líneas por pedido topadas en 5 con repunte en el tope | 2 | **Alto** | Exige regenerar líneas; probablemente no vale la pena por sí solo |
| B4 | BAJA | 4 variantes con costo > precio (margen hasta −80 %) | 2 | **Muy bajo** | 4 UPDATEs; se lleva por delante los outliers que más se ven en GER-10 |
| B1 | BAJA | Enero-25 parcial infla el YoY de enero a +100 % | 3 | **Bajo** | Sembrar los 12 primeros días de enero-25, o documentarlo |
| B3 | BAJA | Sin pedidos web fuera del horario de oficina | 2 | **Bajo** | Redistribuir la hora de una fracción de los pedidos web |
| B7 | BAJA | Solo 4 motivos de devolución, distribución plana | 6 | **Bajo** | Ampliar el catálogo de motivos y resesgar |
| B2 | BAJA | Un día de kardex con 1.216 movimientos | 3 | **Bajo** | Repartir la apertura en varios días, o documentarla como saldo inicial |
| B5 | BAJA | 24 envíos legacy con costo $0 y peso NULL | 6 | **Muy bajo** | Ya documentado; se puede dejar |
| B6 | BAJA | 3 de 72 clientes sin pedidos | 4 | **Muy bajo** | Es realista tal cual; no requiere acción |
| B8 | BAJA | Solo 2 bodegas activas y 2 departamentos con meta | 6 | **Bajo** | Límite del entorno |
| B9 | BAJA | (Verificado OK) Calificaciones con curva en J correcta | 6 | — | **No corregir** |

### Cómo se agrupa el trabajo

Los hallazgos no son 30 problemas independientes. Son **cuatro familias**:

1. **Falta de contraste en el catálogo y la demanda** (A2, A3, A5, A6, M1, M2, B4, y arrastra M4,
   M5, M7): una sola decisión de raíz —dar márgenes y popularidad diferenciados por familia de
   producto— cura nueve hallazgos, seis de ellos ALTA. **Es la corrección de mayor retorno.**
2. **Dinero del Bloque C desconectado del Bloque B** (A8, A9, M10): la única incoherencia dura.
   Corregir A8/A9 bien exige pasar por los triggers de total; M10 es trivial y se puede hacer ya.
3. **Escala del universo de clientes y transportistas** (A7, M9): decidir si el negocio es
   minorista (⇒ más clientes) o mayorista (⇒ ajustar el relato del proyecto).
   > **La mitad «minorista o mayorista» de esta decisión quedó RESUELTA el 2026-07-30**:
   > `docs/estrategico/DIAGNOSTICO_SEGMENTO_CLIENTE.md` midió las siete dimensiones de compra y
   > concluyó **(c) población homogénea — comercio MINORISTA multicanal de ticket alto**: el 99,94 %
   > de las líneas pide 1–4 unidades y no hay ni un cliente con conducta mayorista. Se ajustó el
   > relato del proyecto (base estratégica y catálogo táctico), **no los datos**. Lo que sigue
   > abierto de A7 es solo la **escala del universo** (72 clientes ⇒ 59,2 pedidos por cliente), que
   > es un problema de densidad del seed, no de tipo de negocio.
4. **Realismo temporal fino** (M6, B1, B2, B3): cosmético, barato, se hace al final.

Y los **7 objetivos que siguen sin datos** (§8) son un frente aparte, en su mayoría de esfuerzo
bajo: transferencias entre bodegas, preguntas de producto, log de acceso y marketing vigente son
cuatro tablas pequeñas que ningún bloque tocó.

---

*Auditoría read-only. No se ejecutó ningún INSERT, UPDATE, DELETE ni DDL; no se modificó ningún
archivo de código; no se tocó `analytics/` ni ClickHouse; no se hizo commit.*

---

## 11. Estado de corrección (actualizado 2026-07-24)

| Hallazgo | Estado | Script | Resultado medido |
|---|---|---|---|
| **A2** margen por categoría | **CORREGIDO** | 67 | 1,35 pp → **26,2 pp** (Electrónica 9,4 % … Belleza 35,6 %) |
| **B4** costo > precio | **CORREGIDO** | 67 | 4 outliers eliminados; 3 loss-leaders deliberados |
| **A5** no hay best-sellers | **CORREGIDO (parcial)** | 68-70 | top 20 % 45,89 % → **62,19 %**; top 5 SKU 90/85/84/83/82 → **242/221/174/168/166**. La meta era 65-80 %: faltan 2,8 pp, **limitados por stock** (39 variantes capeadas por su histórico de entradas recortan 6.084 uds de cuota). Subirlo más exigiría comprar más de esos SKU (Bloque A, fuera de alcance) o ensanchar la banda de descuento a 25 %, lo que daría margen negativo en Electrónica y rompería A2 |
| **A6** categorías idénticas | **CORREGIDO** | 68-70 | rango 16 % → Abarrotes $1.373.300 vs Accesorios $372.000 = **3,69×** en venta (2,91× en unidades) |
| **A3** margen mensual plano | **MEJORADO** (colateral de A5/A6) | 68-70 | rango 1,97 pp → **4,56 pp**; sd 0,555 → 0,882 |
| **A8** cupones sin efecto monetario | **CORREGIDO** | 71-73 | `pedido.monto_descuento` $52,91 → **$50.590,25** en 562 pedidos; 561 usos sobre pedido con descuento 0 → **0** (quedan 2 legacy declarados) |
| **A9** promociones sin efecto | **CORREGIDO** | 71-73 | líneas con `pedido_detalle.monto_descuento` > 0: 3 → **123**; 17 promociones con al menos una venta rebajada |

**Cómo se corrigieron A5/A6 sin descuadrar nada** (Bloque D, scripts 68 → 69 → 70, reversión
`99_revert_bloque_d_demanda.sql`): se **redistribuyó** la demanda existente, sin crear ni borrar
una sola venta. Se reasignó *qué variante* se vendió en 9.167 de 10.384 líneas dejando intactos
`cantidad` y `precio_unitario`, de modo que el impacto monetario es **$0,00 exacto** en los 16
agregados de referencia y en los 19 meses. La factibilidad de stock se garantizó recorriendo la
línea de tiempo cronológicamente, no el balance final.

Efectos colaterales declarados:

- Variantes con venta 845 → **834** (−11); sin venta 376 → 387. Es el precio inherente de
  concentrar: un negocio con best-sellers tiene más cola muerta. No revierte la mejora de
  OTD-VEN-04 (que venía de 1.197 sin venta).
- **M2 empeora levemente**: filas de inventario intactas en 100 exactas 261 → **268**, porque
  algunas variantes de cola perdieron todas sus ventas. En cambio `inventario_bajo_minimo`
  sube de 114 a **162** (mejor para OTD-INV-01) y aparecen **9 filas en stock 0**, realistas
  para best-sellers agotados.
- El kardex quedó encadenado en orden **cronológico**. Antes, `stock_anterior` seguía el orden
  de *inserción*: 5.860 de 12.396 movimientos eran incoherentes con su propia cronología. Se
  verificó de antemano que reordenar no produce ningún negativo.

**Cómo se corrigieron A8/A9 sin descuadrar un centavo** (scripts 71 respaldo → 72 cupón → 73
promoción, reversión `99_revert_descuentos.sql`): se aplicaron los descuentos **por el camino del
sistema real** (`marketing/DescuentosService` + `VentasService.emitirFactura`), escribiendo solo
`pedido.monto_descuento`, `pedido_detalle.monto_descuento/monto_impuesto` y
`factura_venta_detalle.monto_descuento/monto_impuesto` — los totales de pedido y factura los
rehacen sus triggers, y `pago.monto` (tabla sin trigger de recálculo) es el único ajuste manual.

- **Alcance**: solo pedidos con pago `completado`. Los 176 pagos fallidos quedaron intactos al
  centavo, igual que kardex (12.396 movimientos), inventario y `cupon.usos_actuales`.
- **Orden de aplicación** (documentado, sin doble descuento): la **promoción** muerde la LÍNEA;
  el **cupón** muerde la CABECERA calculado sobre el subtotal YA rebajado por la promoción; el
  IVA se reescala una sola vez por línea sobre la base realmente cobrada. `envio_gratis` acredita
  el flete y NO toca base imponible, pero SÍ se prorratea en el descuento de línea de la factura
  (es lo que hace `emitirFactura`, que lee `pedido.monto_descuento` sin mirar el tipo).
- **Cuadre**: el ingreso baja **$64.037,45** ($5.780.474,00 → $5.716.436,55) = cupón $50.537,34 +
  promoción $5.205,94 + **IVA liberado $8.294,17**. El IVA es parte inseparable de la baja: el
  descuento reduce la base imponible, así que el total cae 1,15× el descuento.
- En los **24 pedidos con promoción Y cupón** el cupón porcentual muerde una base menor, así que
  su descuento baja $139,59; `uso_cupon.monto_descontado` se actualizó en 17 filas para que no
  vuelva a contradecir al pedido (que es exactamente el defecto A8).
- **Excepción declarada**: los pedidos **20 y 21** (legacy, `devuelto`, con factura pero **sin
  ninguna fila en `pago`**) quedaron fuera por no estar cobrados. Son los 2 únicos usos de cupón
  que siguen sin reflejarse en el pedido ($137,64).
- Cobertura de A9 limitada por los datos, no por el método: solo **120 líneas nuevas** califican
  porque las ventanas de promoción son de 10–20 días y el Bloque D (script 70) reasignó qué
  variante se vendió *después* de que el Bloque C eligiera los 176 productos en promoción.
  Ampliarla exigiría sembrar más `promocion_producto`, que es otra decisión.

### Rebalanceo del abastecimiento — A1 / M1 / B2 (2026-07-25, scripts 74-78)

| Hallazgo | Estado | Script | Resultado medido |
|---|---|---|---|
| **A1** la compra no abastece la venta | **CORREGIDO** | 74-78 | apertura 120.160 uds (**78,54 %** de las entradas) → **34.210 uds (22,36 %)**; compras 32.523 → **118.473 uds (77,43 %)**. Variantes vendidas sin ninguna compra **557 → 184** |
| **B2** pico de 1.216 movimientos en un día | **CORREGIDO** | 74-78 | día más alto del kardex **1.216 → 83 movimientos**; la apertura pasa de 1 día a **10 días** (2 al 11 de enero de 2025) |
| **M1** compradas y jamás vendidas | **EMPEORA (declarado)** | 74-78 | 139 → **386**. Es la contrapartida aritmética de A1: las 386 variantes que nunca salieron del almacén ahora tienen su compra documentada en vez de aparecer de la nada |
| **M2** filas de inventario intactas en 100 | **sin cambio** | — | 268. `inventario` no se escribe: el rebalanceo cambia el ORIGEN del stock, no su cantidad |

**Cómo se rebalanceó sin mover una sola unidad de stock** (74 respaldo → 75 plan/factibilidad →
76 OC+recepciones → 77 facturas+CxP+pagos → 78 kardex; reversión `99_revert_abastecimiento.sql`,
probada tres veces con ciclo aplicar→revertir→**bit-idéntico** sobre 16 huellas md5):

- **Principio**: por cada unidad de apertura que se convierte en compra se resta de la apertura y
  se suma como `entrada_compra` **antes de la primera salida** de esa variante. El balance por
  variante no cambia: `inventario.stock_actual` es idéntico al respaldo en las **1.372 filas**.
- **Segmentación** de las 1.216 variantes con apertura: **temprana** (343 var, 34.210 uds, primera
  salida antes del 2025-03-01) conserva su apertura — no hay espacio temporal para una compra
  previa creíble; **tardía** (487 var, 47.980 uds) y **sin salida** (386 var, 37.970 uds) migran
  el 100 %. Migradas: **85.950 uds** en **529 OC / 1.610 líneas**, repartidas entre los 11
  proveedores por giro de categoría y en los 19 meses (3.137–10.008 uds/mes, sin picos).
- **Factibilidad temporal**: cada variante recibe un lote 1 anterior a su primera salida y, si su
  apertura ≥ 40 uds, un lote 2 más adelante acotado por el mínimo balance del tramo
  (`max_seguro`). **0 variantes** necesitaron conservar apertura extra: el mínimo disponible fue
  de 79 uds frente a peticiones de ≤ 49 %. Resultado: **0 saldos negativos** y **0 eslabones
  rotos** en 13.133 movimientos.
- **Ventas intactas**: las huellas md5 de `pedido`, `pedido_detalle`, `factura_venta` y `pago`
  son idénticas al respaldo, y las 9.798 salidas de kardex conservan fila, cantidad y fecha. En
  1.006 de ellas cambió solo el **saldo corrido** (`stock_anterior/stock_nuevo`), que es la
  consecuencia inevitable de recomponer las entradas anteriores.
- **Consecuencia financiera declarada** (no evitable con `stock_actual` congelado): documentar
  85.950 uds de apertura como compra real multiplica el ciclo de compra. Facturas de compra
  **$3.815.107,62 → $22.467.387,27**, pagos **$2.803.140,54 → $16.084.462,74**, saldo CxP
  **$1.011.967,08 → $6.382.924,53**, y el cuadre `facturas − pagos = saldo` sigue exacto al
  centavo (descuadre $0,00). La compra queda muy por encima de la venta ($5,7 M) porque el
  inventario sembrado ya era de ~6,8 años de rotación (ese es el defecto de fondo de M2, que
  exigiría bajar el stock y está fuera de este alcance).

Siguen **sin corregir**: A4, A7 y el resto de la familia MEDIA/BAJA. M2 y M3 quedan como el
siguiente bloque natural: el stock plano es ahora la única pieza del abastecimiento que no tiene
historia detrás.

---

## 12. Cierre de los objetivos sin datos — §8 (2026-07-25, scripts 79-84)

Los **7 objetivos** que el seed dejó vacíos (§8) eran seis tablas pequeñas más un séptimo caso
que no se puede resolver sin tocar ventas. Se cerraron los seis; el séptimo se documenta.

| # | Objetivo | Antes | Ahora | Script |
|---|---|---|---|---|
| 1 | **OTD-INV-06** transferencias entre bodegas | 10, todas `recibida`, todas de jul-2026, **0 en camino** | **71** en 19 meses: 57 `recibida`, 7 `en_transito`, 4 `pendiente`, 3 `cancelada` → **11 en camino** | 80 |
| 2 | **OTD-VEN-10** preguntas de producto | 1 pregunta, 1 respuesta | **49 preguntas** (jun-2025 → jul-2026) y **29 respuestas**; **16 sin responder** (13 `pendiente` + 3 `publicada`), 4 `rechazada` | 81 |
| 3 | **OTD-GER-09** log de acceso | 39 filas, **1 mes** | **1.439 filas en 19 meses**, 88 usuarios, 86,2 % exitosos y los **4 motivos** de fallo poblados | 82 |
| 4 | **OTD-GER-06** marketing vigente | 1 promo / 2 campañas / 2 banners / 4 cupones | **6 promos / 6 campañas / 8 banners / 8 cupones** vigentes hoy (M10 cerrado) | 83 |
| 5 | **OTD-INV-05** ajustes con motivo | 3 filas | **53 ajustes** en 19 meses, 3 tipos (`negativo`/`positivo`/`conteo`), 2 estados (`aplicado`/`anulado`) y **7 motivos** | 80 |
| 6 | **OTD-VEN-15** metas por departamento | 38 metas, **2 de 7** departamentos | **133 metas**, los **7 departamentos** de la lista blanca × 19 meses | 84 |
| 7 | **OTD-GER-07** efecto de las promociones | 123 líneas con descuento de promoción | **sin cambio — limitación aceptada (ver abajo)** | — |

### Cómo se sembró el stock sin descuadrar el kardex (script 80)

Transferencias y ajustes son los **dos únicos** objetivos de este bloque que mueven stock. Se
sembraron por el camino del sistema real (`InventarioService` + `StockService`): ambas tablas son
**solo cabecera**, así que la variante y la cantidad viven en el kardex
(`referencia_tipo = 'transferencia_bodega' | 'ajuste_inventario'`) y, en texto, en la
observación/motivo con el formato `[SKU xN] …`.

- **No-negatividad cronológica**: para cada SALIDA nueva en el pasado se calculó, sobre la cadena
  **pristina** de esa `(variante, bodega)`, el `max_seguro` = mínimo saldo desde esa fecha hasta
  hoy (*suffix-min*). Solo se sembró si `cantidad ≤ max_seguro`. El balance final no basta: hay
  que respetar la cronología completa (misma lección del script 78). **1 evento** de 112 se
  descartó por no encontrar variante con saldo suficiente, y quedó contado en la marca.
- **Una sola salida por `(variante, bodega)`**, para que el `max_seguro` calculado sobre la cadena
  pristina siga siendo válido en todos los eventos (las entradas solo suben el saldo).
- **Reencadenado** de las **150 cadenas** afectadas (el kardex se encadena por
  `(fecha_creacion, id)` y toda cadena arranca en 0).
- Resultado medido: **13.287 movimientos**, **0 saldos negativos**, **0 desalineados**,
  **0 eslabones rotos**, y `kardex = inventario.stock_actual` en las **1.406 filas**.
- `inventario.stock_actual` **sí cambia aquí** (a diferencia del 78): una merma y una
  transferencia en tránsito son pérdidas y traslados reales. Total **133.379 → 133.220 uds
  (−159)**, que se descompone exacto: ajustes **−48** (85 de entrada − 133 de salida) y
  transferencias **−111** (748 despachadas − 637 recibidas = las 7 en tránsito).
- Efecto colateral favorable sobre **M2/OTD-INV-02**: Bodega Norte pasa de 152 filas / 4.530 uds
  a **186 filas / 5.056 uds**.
- **Ventas, compras y dinero intactos al centavo**: las 9 huellas md5 (`pedido`,
  `pedido_detalle`, `factura_venta`, `pago`, `uso_cupon`, `orden_compra`, `factura_compra`,
  `cuenta_por_pagar`, `pago_proveedor`) son idénticas al respaldo del script 79.

Modelado declarado: `en_transito` = solo la salida en origen (la mercadería salió y no llegó);
`pendiente` y `cancelada` = **ningún** movimiento de kardex. El sistema real no tiene endpoint de
recepción diferida (`transferir` crea la transferencia ya `recibida`), así que esos tres estados
son **dato histórico**, no un flujo vivo que la UI pueda avanzar.

### OTD-GER-07 — limitación conocida y aceptada (no se corrige)

**Efecto de las promociones**: siguen siendo **123 líneas de 10.384** con
`pedido_detalle.monto_descuento > 0` proveniente de promoción (17 promociones con al menos una
venta rebajada, script 73). El informe carga y muestra casos reales, pero la muestra es demasiado
delgada para medir el efecto de una promoción con solidez estadística.

- **Por qué queda así**: la cobertura no la limita el método sino los datos. Las ventanas de las
  promociones históricas son de 10–20 días, y el Bloque D (script 70) reasignó *qué variante* se
  vendió **después** de que el Bloque C eligiera los 176 `promocion_producto`. Densificarlo
  exigiría (a) sembrar más `promocion_producto` — otra decisión de negocio — o (b) **reasignar
  ventas** para hacerlas caer dentro de las ventanas de promoción, que es tocar el ciclo de venta
  y el dinero ya cuadrado por los scripts 71-73 y 74-78.
- **Decisión**: fuera de alcance. Se prefiere un informe con 123 casos verdaderos y un sistema
  monetariamente coherente, antes que inflar la muestra moviendo ventas.
- Las **5 promociones vigentes** creadas por el script 83 no cambian este número a propósito: son
  de vigencia futura y solo rebajarán pedidos que se creen desde hoy, por el motor real
  (`marketing/DescuentosService`).

### Limitación de backend anotada (no es del dato)

`MetasVentaService.VENTA_REAL_SQL` calcula el avance contra la meta **solo** para `general` y
`ventas` (el `CASE` deja NULL en los otros cinco departamentos). Las 95 metas nuevas pueblan el
catálogo por departamento; medir su avance exige ampliar ese `CASE`, que es cambio de código y
quedó fuera de este alcance.
