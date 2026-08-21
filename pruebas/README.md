# `pruebas/` — Arnés de ejecución del plan

Implementa `docs/PLAN_DE_PRUEBAS.md`. Los defectos que encuentra se registran en
`docs/pruebas/DEFECTOS.md`.

```
pruebas/
├─ comun/
│  ├─ arnes.py        Cliente HTTP por rol, registro de casos, informes
│  ├─ catalogo.py     Extrae los 362 endpoints DEL CÓDIGO en cada corrida
│  └─ motor.py        SQL como un ROL concreto (RLS, GRANTs, horario)
├─ estados/
│  ├─ montar_e0.sh    Base VACÍA en `retailmind_pruebas`
│  ├─ montar_e1.sh    E0 + una fila de cada entidad del camino crítico
│  ├─ montar_e2.sh    El SEED histórico, desde el volcado del 2026-08-03
│  └─ compose.e0.yml  Backend gemelo contra E0/E1/E2, en el puerto 8082
├─ p01_arranque.py    Sin secretos el backend debe NEGARSE a arrancar
├─ p02_barrido.py     Todos los GET × los 9 roles: caza 500, 401 y rutas muertas
├─ p03_motor.py       Seguridad de motor: RLS, GRANTs, horario, rol del ETL
├─ p04_validacion.py  Entrada: lista blanca, tipos, paginación, tokens
├─ p05_compuertas.py  Ciclo de venta y compras — ESCRIBE, solo contra E0/E1
├─ p05_puesta_en_marcha.py   ¿Se puede poner el sistema en marcha sin SQL? (D-09)
├─ p06_invariantes.py Kardex, cuadre contable, huérfanos, GENERATED
├─ p07_vacio.py       Estado vacío: contenido de los informes, no solo el código
├─ p08_compuestos.py  43 rutas + 7 tableros: fuente, datosAl, denominador, dinero
├─ p09_etl.py         Almacén, bitácora y modelos (+ el control de universo)
├─ p11_interfaz.js    32 pantallas en Chrome headless (Puppeteer)
├─ p12_rendimiento.py Latencia aislada y repetida, p50/p95
├─ p13_resiliencia.py Para ClickHouse de verdad y mide la degradación
├─ p14_tienda.js      Tienda del cliente: filtros, envío, perfil y MIS PEDIDOS
├─ p15_validacion_campos.js  Qué admite cada campo escribible, TECLEANDO
├─ p16_tienda_publica.js     Mirar sin cuenta, el muro y el alta en 4 pasos
└─ informes/          Salida por corrida (JSON + Markdown)
```

## Qué corre contra qué

| Suite | Lee | Escribe | Estado |
|---|:--:|:--:|---|
| P02, P03, P04, P06, P07, P09, P12 | ✅ | — | cualquiera |
| **P05** | ✅ | **✅** | **solo E0/E1** — se planta si la apuntan a otro sitio |
| **P13** | ✅ | — | para y levanta el contenedor de ClickHouse (~1 min) |
| P11 | ✅ | — | navegador; puede desviarse a otro backend |
| P15 | ✅ | — | navegador, con ADMIN **y** CLIENTE. Teclea basura en cada campo y **no guarda nada**: ningún caso pulsa Guardar, y los formularios se cierran con Escape. Necesita las DOS claves |
| **P16** | ✅ | **✅** | navegador. **CREA CUENTAS DE CLIENTE** por el alta pública —es lo que viene a probar— con un correo y una cédula sellados por la hora, y **las deja DESACTIVADAS** al terminar (baja lógica por `PATCH /usuarios/{id}/activo`, el mismo camino que la pantalla de administración). No se borran: un usuario deja rastro en `log_acceso` y borrarlo obligaría a tocar la base por fuera de la aplicación. Necesita ADMIN y STAFF |
| **P14** | ✅ | **✅** | navegador, con el usuario CLIENTE. Escribe en el carrito, la lista de deseos, la dirección de envío elegida y **los datos del perfil** de `maria.lopez@demo.com`, y **lo deja todo como estaba**. Única huella: la dirección que crea y borra queda como fila **inactiva** (`activo = false`) — el sistema hace baja LÓGICA porque `grp_cliente` no tiene DELETE sobre `direccion`, así que no reaparece en ninguna pantalla. Su clave es `RETAILMIND_CLIENTE_PASS` |

## Credenciales

Se piden **por entorno y sin valor por defecto**, igual que
`verificar_ven0304.py` y por el mismo motivo: no engordar la lista de archivos
versionados que reproducen la clave de demo (deuda **C-4**). Si falta una, el
arnés se planta y dice cuál.

```bash
export RETAILMIND_ADMIN_PASS='…'     # ver «Credenciales de desarrollo» en CLAUDE.md
export RETAILMIND_STAFF_PASS='…'
export RETAILMIND_CLIENTE_PASS='…'
export PYTHONIOENCODING=utf-8         # obligatorio en Windows
```

## Correr contra el sistema vivo (E3)

```bash
docker compose up -d
py -3 pruebas/p03_motor.py       E3    # ~1 min
py -3 pruebas/p06_invariantes.py E3    # ~2 min
py -3 pruebas/p09_etl.py         E3    # ~1 min
py -3 pruebas/p04_validacion.py  E3    # ~2 min
py -3 pruebas/p13_resiliencia.py E3    # ~3 min · PARA ClickHouse y lo levanta
py -3 pruebas/p12_rendimiento.py E3    # ~10 min
py -3 pruebas/p02_barrido.py     E3    # ~13 min · 1.962 llamadas
node  pruebas/p11_interfaz.js          # ~4 min · 32 pantallas
node  pruebas/p14_tienda.js            # ~6 min · tienda, perfil y mis pedidos (86 casos)
node  pruebas/p15_validacion_campos.js # ~5 min · los 179 campos escribibles (85 casos)
node  pruebas/p16_tienda_publica.js    # ~2 min · escaparate, muro y alta (47 casos)
```

## Correr contra la base vacía (E0)

E0 se monta en una base **aparte** (`retailmind_pruebas`) y se sirve con un
backend gemelo en el **8082**. La base viva no se toca: el montaje solo le hace
un `pg_dump --schema-only`, que es de lectura, y aborta si el destino fuera
`retailmind`.

```bash
bash pruebas/estados/montar_e0.sh
docker compose -f docker-compose.yml -f pruebas/estados/compose.e0.yml \
       --profile e0 up -d backend-e0

export RETAILMIND_API='http://localhost:8082'
py -3 pruebas/p07_vacio.py      E0
py -3 pruebas/p04_validacion.py E0
py -3 pruebas/p02_barrido.py    E0
```

## Correr contra el seed histórico (E2)

E2 es el **único estado con ORÁCULO**: sus cifras están publicadas en
`CLAUDE.md`, así que no hay que suponer qué debería salir. Sale del volcado
`deploy/postgres/initdb/01_retailmind.dump` (2026-08-03), anterior a la carga
masiva del 10/11 — es una foto real, no una reconstrucción.

```bash
bash pruebas/estados/montar_e2.sh     # verifica 7 medidas contra el oráculo
docker compose -f docker-compose.yml -f pruebas/estados/compose.e0.yml \
       --profile e0 up -d backend-e0

export RETAILMIND_API='http://localhost:8082' RETAILMIND_DB='retailmind_pruebas'
py -3 pruebas/p06_invariantes.py E2
py -3 pruebas/p03_motor.py       E2
py -3 pruebas/p04_validacion.py  E2
```

**Ojo**: el volcado trae el esquema del 3 de agosto y el código ha avanzado. El
montador aplica el DDL posterior (86, 87, 88, 91, 106, 110, 111) y deja fuera a
propósito los de DATOS (92-105) — meterlos convertiría E2 en E3.

## Correr contra la base mínima (E1)

E1 = E0 + una fila de cada entidad del camino crítico. Es el estado mínimo en
que el ciclo de venta **puede recorrerse**, y por eso es donde vive P05.

```bash
bash pruebas/estados/montar_e1.sh          # requiere E0 montada

export RETAILMIND_API='http://localhost:8082'
export RETAILMIND_DB='retailmind_pruebas'   # para las suites que van al motor
py -3 pruebas/p05_compuertas.py  E1
py -3 pruebas/p06_invariantes.py E1        # comprueba que P05 dejó el kardex sano

# La interfaz contra E1, SIN reconstruir el frontend: se desvían las
# llamadas /api/ en el navegador, así se prueba el MISMO bundle compilado.
RETAILMIND_API_DESVIO='http://localhost:8082' RETAILMIND_ESTADO='E1' \
  node pruebas/p11_interfaz.js
```

Para desmontarlo:

```bash
docker compose -f docker-compose.yml -f pruebas/estados/compose.e0.yml \
       --profile e0 down backend-e0
docker compose exec -T postgres psql -U postgres -d postgres \
       -c "DROP DATABASE retailmind_pruebas;"
```

## Cinco trampas del propio arnés, ya pagadas

Se disfrazaron de defecto del sistema. Las tres primeras están en `DEFECTOS.md`
como FP-01/02/03; las dos últimas salieron con P14. Todas aquí porque son de
quien toque este código:

1. **`requests.Response.__bool__` devuelve `self.ok`**, así que una respuesta
   **400 es *falsy***. `r.status_code if r else -1` convierte cada rechazo
   correcto en «sin respuesta»: dio **110 fallos falsos de 124**. Usa siempre
   `r is not None`, o el helper `Cliente.codigo()`.
2. **El backend de E0 tiene que usar la MISMA imagen que producción**
   (`retailmind-backend:latest`). Con una imagen vieja se prueba otro sistema.
3. **El contenedor de E0 tiene que unirse a la red `retailmind`**, o no alcanza
   ni PostgreSQL ni ClickHouse y el síntoma imita una caída de servicio.
4. **`ng-reflect-*` solo existe en modo DESARROLLO.** Una comprobación que lea
   el valor de un `<mat-option>` por ese atributo pasa contra `ng serve` y falla
   contra el contenedor: **la misma aplicación, distinto veredicto**, y el
   informe acusa a la pantalla. Se comprueba el COMPORTAMIENTO —qué acepta el
   servidor— y, del DOM, solo lo que el usuario ve (los rótulos).
5. **Un caso que provoca un rechazo A PROPÓSITO ensucia el recuento de errores
   de consola.** El 400 que devuelve un género inválido es el resultado
   buscado, pero el oyente de respuestas lo apuntaba como fallo y el último
   caso suspendía por hacer bien su trabajo. P14 abre una **ventana acotada**
   (`rechazoEsperado`) alrededor de esa llamada y la cierra justo después;
   silenciar el 400 de forma permanente habría escondido los de verdad.

## Estado

**Las 13 suites del plan implementadas y los 4 estados de datos ejecutados.**

Lo que queda como limitación declarada, no como hueco:

- **E0/E1/E2 comparten el ClickHouse de E3.** Los informes COMPUESTOS de esos
  estados leen el almacén de la base masiva, así que no se juzgan como estado
  vacío — la suite lo declara y lo mide (caso `P08-COHERENCIA` de `p07_vacio.py`,
  que es el defecto **D-07**). Un almacén propio por estado exigiría correr el
  ETL entero contra cada uno.
- **P12 mide contra el sistema real**, así que sus cifras dependen de la máquina.
  Los umbrales son de esta máquina; en otra hay que recalibrarlos, no copiarlos.
