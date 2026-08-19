#!/usr/bin/env bash
# montar_e0.sh — Construye el estado E0 (BASE VACÍA) en `retailmind_pruebas`.
#
# E0 = «instalación nueva de un cliente el primer día»: el esquema COMPLETO
# (113 tablas, triggers, RLS, funciones, GRANTs) más los datos de REFERENCIA
# que cualquier instalación necesita para arrancar, y CERO datos de negocio.
#
# GUARDIA: este script escribe SOLO en `retailmind_pruebas`. La base viva
# `retailmind` no se toca ni se lee en modo escritura — se le hace un
# `pg_dump --schema-only`, que es de lectura. Es el mismo patrón que
# `comun.guardia_base` del benchmark, y por el mismo motivo.
#
# Uso:  bash pruebas/estados/montar_e0.sh
set -euo pipefail

BASE_DESTINO="retailmind_pruebas"
BASE_ORIGEN="retailmind"
PG="docker compose exec -T postgres"

if [ "$BASE_DESTINO" = "$BASE_ORIGEN" ]; then
  echo "ABORTA: el destino no puede ser la base viva." >&2
  exit 1
fi

echo "== 1/5 · esquema completo desde $BASE_ORIGEN (solo lectura) =="
$PG pg_dump -U postgres --schema-only --no-owner "$BASE_ORIGEN" \
  > /tmp/e0_esquema.sql
echo "   $(wc -l < /tmp/e0_esquema.sql) líneas de DDL"

echo "== 2/5 · recreando $BASE_DESTINO =="
$PG psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c \
  "DROP DATABASE IF EXISTS $BASE_DESTINO;" -c "CREATE DATABASE $BASE_DESTINO;"

echo "== 3/5 · aplicando el esquema =="
$PG psql -U postgres -d "$BASE_DESTINO" -q -v ON_ERROR_STOP=0 < /tmp/e0_esquema.sql \
  2> /tmp/e0_esquema.err || true
echo "   avisos: $(grep -c '^ERROR' /tmp/e0_esquema.err || echo 0)"

# ── Tablas de REFERENCIA ────────────────────────────────────────────────────
# Lo que un sistema recién instalado TIENE que traer para poder arrancar:
# catálogos de estados y tipos, geografía, unidades monetarias e impuestos, la
# tabla de roles, las ventanas horarias y la configuración.
#
# Deliberadamente FUERA: producto, cliente, pedido, inventario, factura,
# kardex, ticket, reseña, cupón, promoción, meta, proveedor, orden de compra.
# Eso es dato de NEGOCIO, y su ausencia es justo lo que E0 prueba.
REFERENCIA=(
  rol permiso rol_permiso
  estado_pedido tipo_movimiento motivo_devolucion categoria_ticket
  moneda impuesto tipo_cambio metodo_pago pasarela_pago
  pais provincia ciudad
  idioma etiqueta grupo_cliente segmento_cliente
  configuracion_tienda grupo_horario correlativo_ticket
)

# Identidad: sin los usuarios de login nadie entra y NINGUNA prueba puede
# correr. Van los 10 de demo (8 de personal + 2 clientes) que crean los scripts
# 23/27, y sus filas de `usuario_rol`.
#
# La lista va ENUMERADA y no con un `NOT LIKE '%@demo.com'`: los 50.072 clientes
# de la carga masiva NO usan ese dominio, así que el filtro «obvio» los deja
# pasar a todos y E0 nace con 50.181 usuarios. Pasó en el primer montaje.
#
# De `cliente` se copian SOLO las dos filas de los usuarios cliente: sin ellas
# el login de CLIENTE entra pero ninguna pantalla de tienda tiene con qué
# resolver `app.cliente_id`, y el vacío que se probaría sería el del arnés.
IDENTIDAD=( usuario usuario_rol )

CORREOS_DEMO="'admin@retailmind.com','gerente@retailmind.com','vendedor@retailmind.com',
              'compras@retailmind.com','bodega@retailmind.com','despacho@retailmind.com',
              'analista@retailmind.com','soporte@retailmind.com',
              'maria.lopez@demo.com','carlos.vera@demo.com'"

echo "== 4/5 · copiando referencia + identidad =="
for t in "${REFERENCIA[@]}" "${IDENTIDAD[@]}"; do
  existe=$($PG psql -U postgres -d "$BASE_ORIGEN" -t -A -c \
    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='r' AND c.relname='$t';")
  if [ "$existe" != "1" ]; then
    echo "   -- $t no existe, se omite"; continue
  fi
  # Los usuarios se recortan a los 10 de demo: copiar los 50.182 metería
  # 50.072 clientes por la puerta de atrás y E0 dejaría de estar vacía.
  if [ "$t" = "usuario" ]; then
    filtro="COPY (SELECT * FROM usuario WHERE email IN ($CORREOS_DEMO)) TO STDOUT"
  elif [ "$t" = "usuario_rol" ]; then
    filtro="COPY (SELECT ur.* FROM usuario_rol ur JOIN usuario u ON u.id=ur.usuario_id
            WHERE u.email IN ($CORREOS_DEMO)) TO STDOUT"
  else
    filtro="COPY $t TO STDOUT"
  fi
  $PG psql -U postgres -d "$BASE_ORIGEN" -c "$filtro" 2>/dev/null \
    | $PG psql -U postgres -d "$BASE_DESTINO" -c "COPY $t FROM STDIN" 2>/dev/null \
    || echo "   !! fallo copiando $t"
  n=$($PG psql -U postgres -d "$BASE_DESTINO" -t -A -c "SELECT count(*) FROM $t;" 2>/dev/null || echo "?")
  printf "   %-24s %s\n" "$t" "$n"
done

# Las dos filas de `cliente` de los usuarios cliente (ver nota arriba).
$PG psql -U postgres -d "$BASE_ORIGEN" -c \
  "COPY (SELECT c.* FROM cliente c JOIN usuario u ON u.id = c.usuario_id
         WHERE u.email IN ($CORREOS_DEMO)) TO STDOUT" 2>/dev/null \
  | $PG psql -U postgres -d "$BASE_DESTINO" -c "COPY cliente FROM STDIN" 2>/dev/null \
  || echo "   !! fallo copiando cliente"
printf "   %-24s %s\n" "cliente (los 2 demo)" \
  "$($PG psql -U postgres -d "$BASE_DESTINO" -t -A -c 'SELECT count(*) FROM cliente;')"

echo "== 5/5 · verificación: E0 debe tener CERO negocio =="
$PG psql -U postgres -d "$BASE_DESTINO" -c "
SELECT 'pedido' t, count(*) FROM pedido UNION ALL
SELECT 'cliente', count(*) FROM cliente UNION ALL
SELECT 'producto', count(*) FROM producto UNION ALL
SELECT 'producto_variante', count(*) FROM producto_variante UNION ALL
SELECT 'inventario', count(*) FROM inventario UNION ALL
SELECT 'movimiento_inventario', count(*) FROM movimiento_inventario UNION ALL
SELECT 'factura_venta', count(*) FROM factura_venta UNION ALL
SELECT 'ticket_soporte', count(*) FROM ticket_soporte UNION ALL
SELECT 'meta_venta', count(*) FROM meta_venta UNION ALL
SELECT '--- usuarios (deben ser 10)', count(*) FROM usuario UNION ALL SELECT '--- clientes (deben ser 2)', count(*) FROM cliente;"

echo
echo "E0 montada en '$BASE_DESTINO'."
echo "Para probar contra ella, levanta un backend apuntando ahí:"
echo "  bash pruebas/estados/backend_e0.sh"
