#!/usr/bin/env bash
# =====================================================================
# verificar_v11.sh — V11 de la seccion 9.5: prueba funcional de la
# APLICACION con tres roles reales, por la API.
#
# V10 prueba el MOTOR (RLS y GRANT por columna con SET LOCAL ROLE).
# V11 prueba que la APLICACION llega al motor con la identidad correcta.
# Son fallos distintos y las dos hacen falta.
#
# Uso:  ./verificar_v11.sh http://localhost:8081
#
# Se lanza con el backend apuntando al CONTENEDOR y despues con el mismo
# backend apuntando al LOCAL, y se DIFERENCIAN las dos salidas.
#
# OJO CON EL HORARIO: bodega, despacho y compras tienen restriccion por
# franja (`grupo_horario` + `esta_en_horario()`). Fuera de su ventana el
# 403 es LEGITIMO y no un fallo de la migracion. Por eso el script
# imprime la ventana vigente de bodega y el veredicto de
# `esta_en_horario` ANTES de las pruebas: sin ese dato, el resultado de
# (b) no se puede interpretar.
# =====================================================================
set -u
API="${1:-http://localhost:8081}"

login() {  # $1=usuario $2=clave -> token o cadena vacia
  curl -s -m 20 -X POST "$API/api/auth/login" \
       -H 'Content-Type: application/json' \
       -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

codigo() { # $1=token $2=ruta -> codigo HTTP
  curl -s -m 30 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1" "$API$2"
}

cuerpo() { # $1=token $2=ruta
  curl -s -m 30 -H "Authorization: Bearer $1" "$API$2"
}

echo "===== V11 · PRUEBA FUNCIONAL DE LA APLICACION ($API) ====="
echo "-- /api/health: $(curl -s -m 10 $API/api/health)"

# ---------- (a) ADMIN: un informe simple y un tablero ----------
echo ''
echo '--- (a) admin@retailmind.com ---'
TA=$(login 'admin@retailmind.com' 'Admin2026!')
[ -n "$TA" ] && echo "login: OK" || { echo "login: FALLO"; }
echo "informe SIMPLE  /api/informes/ventas/cartera-pedidos            -> $(codigo "$TA" /api/informes/ventas/cartera-pedidos)"
echo "informe COMPUES /api/informes/ventas/evolucion-mensual  -> $(codigo "$TA" /api/informes/ventas/evolucion-mensual)"
echo "TABLERO         /api/tableros/omnicanal                 -> $(codigo "$TA" /api/tableros/omnicanal)"
echo -n "total de la cartera (cifra de negocio): "
cuerpo "$TA" '/api/informes/ventas/cartera-pedidos' | sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1

# ---------- (b) BODEGA: segregacion financiera ----------
echo ''
echo '--- (b) bodega@retailmind.com (segregacion financiera) ---'
TB=$(login 'bodega@retailmind.com' 'Retail2026!')
[ -n "$TB" ] && echo "login: OK" || echo "login: FALLO (ojo: fuera de horario el propio login se bloquea)"
echo "cola de preparacion /api/ventas/preparacion             -> $(codigo "$TB" /api/ventas/preparacion)"
echo -n "  la cola trae columnas de dinero? "
CB=$(cuerpo "$TB" '/api/ventas/preparacion')
if echo "$CB" | grep -qE '"(total|subtotal|precio_unitario|monto[a-z_]*|costo[a-z_]*)"'; then
  echo "SI  <-- FALLO DE SEGREGACION"
else
  echo "NO  (correcto)"
fi
echo "capital inmovilizado (debe ser 403) /informes/inventario/capital-inmovilizado -> $(codigo "$TB" /api/informes/inventario/capital-inmovilizado)"
echo "rotacion (bodega SI entra, debe ser 200)                -> $(codigo "$TB" /api/informes/inventario/rotacion)"

# ---------- (c) CLIENTE: aislamiento RLS ----------
echo ''
echo '--- (c) maria.lopez@demo.com (CLIENTE, aislamiento RLS) ---'
TC=$(login 'maria.lopez@demo.com' 'Cliente2026!')
[ -n "$TC" ] && echo "login: OK" || echo "login: FALLO"
echo "catalogo /api/catalogo                                  -> $(codigo "$TC" /api/catalogo)"
echo -n "  el catalogo trae precios? "
if cuerpo "$TC" '/api/catalogo' | grep -qE '"precio'; then echo "SI (correcto)"; else echo "NO  <-- revisar"; fi
echo "mis pedidos /api/ventas/pedidos                         -> $(codigo "$TC" /api/ventas/pedidos)"
echo -n "  cuantos pedidos ve el cliente (debe ser SOLO los suyos): "
cuerpo "$TC" '/api/ventas/pedidos' | sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' | head -1
echo -n "  un informe de gerencia con el token del cliente (debe ser 403): "
codigo "$TC" /api/tableros/omnicanal; echo ''
echo '===== FIN V11 ====='
