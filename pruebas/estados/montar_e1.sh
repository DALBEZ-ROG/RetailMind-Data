#!/usr/bin/env bash
# montar_e1.sh — Lleva `retailmind_pruebas` de E0 a E1 (BASE MÍNIMA).
#
# E1 = E0 + exactamente UNA fila por entidad del camino crítico. Es el estado
# que discrimina el fallo de frontera (`n = 1` rompe medianas, cuartiles,
# `lagInFrame`, `row_number() > 1` y todo «comparar con el anterior»), y es
# también el estado mínimo en el que el ciclo de venta PUEDE recorrerse.
#
# POR QUÉ ESTO ES UN SCRIPT SQL Y NO LLAMADAS AL API:
# porque no hay API. `bodega`, `transportista`, `metodo_envio`, `zona_envio` y
# `tarifa_envio` **no tienen ni un solo endpoint de escritura en el backend**
# (verificado: cero `INSERT INTO` sobre ellas en todo `src/main`). Solo se
# pueblan por script. Eso es el defecto D-09 del registro, y este archivo es a
# la vez su banco de pruebas y su demostración.
#
# Uso:  bash pruebas/estados/montar_e1.sh   (requiere E0 ya montada)
set -euo pipefail

BASE_DESTINO="retailmind_pruebas"
BASE_ORIGEN="retailmind"
PG="docker compose exec -T postgres"

[ "$BASE_DESTINO" = "$BASE_ORIGEN" ] && { echo "ABORTA: destino = base viva." >&2; exit 1; }

existe=$($PG psql -U postgres -d postgres -t -A -c \
  "SELECT count(*) FROM pg_database WHERE datname='$BASE_DESTINO';")
[ "$existe" = "1" ] || { echo "ABORTA: monta E0 primero." >&2; exit 1; }

echo "== copiando UNA fila de cada entidad del camino crítico =="

# El orden importa: las FK encadenan bodega ← inventario, zona ← tarifa,
# transportista ← metodo_envio.
for t in bodega transportista metodo_envio zona_envio tarifa_envio ubicacion_bodega; do
  hay=$($PG psql -U postgres -d "$BASE_ORIGEN" -t -A -c \
    "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
     WHERE n.nspname='public' AND c.relkind='r' AND c.relname='$t';")
  [ "$hay" = "1" ] || { echo "   -- $t no existe"; continue; }

  ya=$($PG psql -U postgres -d "$BASE_DESTINO" -t -A -c "SELECT count(*) FROM $t;")
  if [ "$ya" != "0" ]; then
    printf "   %-20s ya tenía %s filas, se deja\n" "$t" "$ya"; continue
  fi

  # No basta con `LIMIT 1` por tabla: `metodo_envio` apunta a un
  # `transportista` y `tarifa_envio` a una `zona_envio` y a un `metodo_envio`.
  # Tomar «las primeras por id» de cada una copia filas cuyas referencias no
  # están, y el COPY falla por FK — que es lo que pasó en el primer montaje.
  # Cada tabla se filtra por lo que YA se copió.
  case "$t" in
    zona_envio)
      origen="SELECT * FROM zona_envio ORDER BY id LIMIT 3" ;;
    metodo_envio)
      origen="SELECT * FROM metodo_envio
              WHERE transportista_id IN (SELECT id FROM transportista ORDER BY id LIMIT 1)
              ORDER BY id LIMIT 3" ;;
    tarifa_envio)
      origen="SELECT t.* FROM tarifa_envio t
              WHERE t.zona_envio_id IN (SELECT id FROM zona_envio ORDER BY id LIMIT 3)
                AND t.metodo_envio_id IN (
                    SELECT id FROM metodo_envio
                    WHERE transportista_id IN (SELECT id FROM transportista ORDER BY id LIMIT 1))
              ORDER BY t.id LIMIT 6" ;;
    ubicacion_bodega)
      origen="SELECT * FROM ubicacion_bodega
              WHERE bodega_id IN (SELECT id FROM bodega ORDER BY id LIMIT 1)
              ORDER BY id LIMIT 1" ;;
    *)
      origen="SELECT * FROM $t ORDER BY id LIMIT 1" ;;
  esac

  $PG psql -U postgres -d "$BASE_ORIGEN" -c "COPY ($origen) TO STDOUT" 2>/dev/null \
    | $PG psql -U postgres -d "$BASE_DESTINO" -c "COPY $t FROM STDIN" 2>/dev/null \
    || echo "   !! fallo copiando $t"
  n=$($PG psql -U postgres -d "$BASE_DESTINO" -t -A -c "SELECT count(*) FROM $t;")
  printf "   %-20s %s\n" "$t" "$n"
done

# Las secuencias de identidad quedan detrás de los ids copiados: el siguiente
# INSERT chocaría con la clave primaria. Se reajustan todas de una vez.
echo "== reajustando secuencias de identidad =="
$PG psql -U postgres -d "$BASE_DESTINO" -q -c "
DO \$\$
DECLARE r record; maximo bigint;
BEGIN
  FOR r IN
    SELECT c.relname AS tabla, a.attname AS col,
           pg_get_serial_sequence(quote_ident(c.relname), a.attname) AS sec
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0
    WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attidentity <> ''
  LOOP
    IF r.sec IS NOT NULL THEN
      EXECUTE format('SELECT COALESCE(max(%I),0) FROM %I', r.col, r.tabla) INTO maximo;
      PERFORM setval(r.sec, GREATEST(maximo, 1));
    END IF;
  END LOOP;
END \$\$;"

echo "== estado E1 =="
$PG psql -U postgres -d "$BASE_DESTINO" -c "
SELECT 'bodega' t, count(*) FROM bodega UNION ALL
SELECT 'transportista', count(*) FROM transportista UNION ALL
SELECT 'metodo_envio', count(*) FROM metodo_envio UNION ALL
SELECT 'zona_envio', count(*) FROM zona_envio UNION ALL
SELECT 'tarifa_envio', count(*) FROM tarifa_envio UNION ALL
SELECT '--- pedidos (deben ser 0)', count(*) FROM pedido UNION ALL
SELECT '--- productos (deben ser 0 o los de prueba)', count(*) FROM producto;"

echo
echo "E1 lista. El ciclo de venta ya puede recorrerse:"
echo "  export RETAILMIND_API='http://localhost:8082'"
echo "  py -3 pruebas/p05_compuertas.py E1"
