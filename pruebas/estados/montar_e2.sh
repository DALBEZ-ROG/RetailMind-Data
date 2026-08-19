#!/usr/bin/env bash
# montar_e2.sh — Monta el estado E2 (BASE SEMBRADA) en `retailmind_pruebas`.
#
# E2 = el seed histórico: ~4.083 pedidos, 19 meses de operación completa. Es el
# estado sobre el que se construyeron y validaron TODOS los informes, así que
# sus cifras están escritas en `CLAUDE.md` y en
# `docs/estrategico/CORRECCIONES_DISENO_ETL.md` — y por eso E2 es el único
# estado con **ORÁCULO**: no hay que suponer qué debería salir, está publicado.
#
# DE DÓNDE SALE: del volcado `deploy/postgres/initdb/01_retailmind.dump`, del
# 2026-08-03 20:25. Esa fecha es lo que lo hace válido: la contenerización fue
# el 03 de agosto y la carga masiva el 10/11, así que el volcado captura el
# sistema JUSTO ANTES de los 3.000.000 de pedidos. No hay que reconstruir nada
# ni revertir fases — es una foto real del estado.
#
# GUARDIA: escribe SOLO en `retailmind_pruebas`. La base viva `retailmind` no
# se lee ni se toca en todo el script.
#
# Uso:  bash pruebas/estados/montar_e2.sh
set -euo pipefail

BASE_DESTINO="retailmind_pruebas"
VOLCADO="deploy/postgres/initdb/01_retailmind.dump"
PG="docker compose exec -T postgres"

if [ "$BASE_DESTINO" = "retailmind" ]; then
  echo "ABORTA: el destino no puede ser la base viva." >&2; exit 1
fi
[ -f "$VOLCADO" ] || { echo "ABORTA: no existe $VOLCADO" >&2; exit 1; }
# El backend gemelo mantiene un pool de conexiones abierto contra esta base:
# sin pararlo, el DROP DATABASE falla con «is being accessed by other users».
# Se para SIEMPRE, aunque no estuviera arriba (de ahí el `|| true`).
docker compose -f docker-compose.yml -f pruebas/estados/compose.e0.yml \
  --profile e0 stop backend-e0 >/dev/null 2>&1 || true

# Y por si quedara alguna sesión suelta (un psql abierto, el arnés a medias),
# se cortan las que apunten a la base de pruebas — nunca a ninguna otra.
$PG psql -U postgres -d postgres -q -c "
  SELECT pg_terminate_backend(pid) FROM pg_stat_activity
   WHERE datname = '$BASE_DESTINO' AND pid <> pg_backend_pid();" >/dev/null 2>&1 || true


echo "== 1/4 · recreando $BASE_DESTINO =="
$PG psql -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS $BASE_DESTINO;" -c "CREATE DATABASE $BASE_DESTINO;"

echo "== 2/4 · restaurando el volcado del 2026-08-03 =="
# `--no-owner` porque el propietario del volcado puede no coincidir; los roles
# de grupo son del CLÚSTER y ya existen, así que los GRANT sí se restauran.
$PG pg_restore -U postgres -d "$BASE_DESTINO" --no-owner --exit-on-error < "$VOLCADO" \
  2> /tmp/e2_restore.err || {
    echo "   avisos durante la restauración:"; tail -5 /tmp/e2_restore.err; }

# ── El volcado es del 03 de agosto y EL CÓDIGO HA AVANZADO ─────────────────
# Los datos de E2 son los que interesan, pero su ESQUEMA es el de esa fecha, y
# desde entonces entraron scripts que el backend de hoy da por hechos. El más
# visible: el **87** creó `rol_personalizado`, y la consulta de login la une —
# así que sin él nadie entra, con un `bad SQL grammar` que no parece un
# problema de esquema.
#
# Se aplican SOLO los de DDL y funciones posteriores al volcado. Los de DATOS
# (92-105, la carga masiva) quedan fuera a propósito: meterlos convertiría E2
# en E3 y se perdería el estado que se quería reproducir.
echo "== 3/4 · poniendo el esquema al día (DDL posterior al 2026-08-03) =="
# El 88 va aquí aunque sea de DATOS y no de DDL: las ventanas horarias son
# CONFIGURACIÓN, no dato de negocio sembrado. El volcado es del 03 de agosto y
# el 88 puso el 24/7 el día 6, así que sin él E2 arranca con las 53 ventanas
# estrechas de entonces y P03 suspende — con razón, pero por una diferencia
# histórica y no por un defecto. Un patrón de regresión tiene que correr con la
# configuración de HOY; lo que se conserva de E2 son sus datos.
for s in 86_permisos_motor 87_roles_personalizados 88_horario_24_7 \
         91_invariante_kardex 106_rendimiento_informes_simples \
         110_indice_cubriente_factura_venta 111_rls_initplan; do
  archivo="retailmind/sql/postgres/${s}.sql"
  [ -f "$archivo" ] || { printf "   %-42s no existe, se omite\n" "$s"; continue; }
  if $PG psql -U postgres -d "$BASE_DESTINO" -q -v ON_ERROR_STOP=1 < "$archivo" \
       > /tmp/e2_${s}.log 2>&1; then
    printf "   %-42s aplicado\n" "$s"
  else
    # Un script puede no aplicar si depende de datos que E2 no tiene; se avisa
    # y se sigue, porque ninguno de ellos es imprescindible salvo el 87.
    printf "   %-42s AVISO: %s\n" "$s" "$(tail -1 /tmp/e2_${s}.log | cut -c1-70)"
  fi
done

echo "== 4/4 · verificación contra el ORÁCULO publicado en CLAUDE.md =="
$PG psql -U postgres -d "$BASE_DESTINO" -c "
WITH real AS (
  SELECT (SELECT count(*) FROM pedido)                AS pedidos,
         (SELECT count(*) FROM pedido_detalle)        AS lineas,
         (SELECT count(*) FROM movimiento_inventario) AS kardex,
         (SELECT count(*) FROM inventario)            AS posiciones,
         (SELECT count(*) FROM factura_venta)         AS facturas,
         (SELECT count(*) FROM cliente)               AS clientes,
         (SELECT count(*) FROM producto_variante)     AS variantes)
SELECT 'pedidos'    AS medida, pedidos    AS observado, 4083  AS esperado, pedidos    = 4083  AS cuadra FROM real
UNION ALL SELECT 'lineas',     lineas,     10384, lineas     = 10384 FROM real
UNION ALL SELECT 'kardex',     kardex,     13288, kardex     = 13288 FROM real
UNION ALL SELECT 'posiciones', posiciones,  1406, posiciones =  1406 FROM real
UNION ALL SELECT 'facturas',   facturas,    3887, facturas   =  3887 FROM real
UNION ALL SELECT 'clientes',   clientes,      72, clientes   =    72 FROM real
UNION ALL SELECT 'variantes',  variantes,   1221, variantes  =  1221 FROM real;"

echo
echo "E2 montada en '$BASE_DESTINO'."
echo "Las cifras de arriba son el ORÁCULO de CLAUDE.md («antes de la carga»)."
echo "Para probar contra ella:"
echo "  docker compose -f docker-compose.yml -f pruebas/estados/compose.e0.yml \\"
echo "         --profile e0 up -d backend-e0        # el gemelo apunta a retailmind_pruebas"
echo "  export RETAILMIND_API='http://localhost:8082' RETAILMIND_DB='retailmind_pruebas'"
