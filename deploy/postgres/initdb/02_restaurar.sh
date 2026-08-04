#!/bin/sh
# =====================================================================
# 02_restaurar.sh — fija las contrasenas de los dos roles con LOGIN y
#                   restaura la base `retailmind` desde el dump custom.
#
# El entrypoint de la imagen ejecuta este directorio en ORDEN
# LEXICOGRAFICO y SOLO la primera vez (volumen vacio):
#     00_roles.sql  ->  02_restaurar.sh
# `01_retailmind.dump` no lo lee el entrypoint (no es .sql/.sh): lo
# aplica este script.
# =====================================================================
set -e

DUMP=/docker-entrypoint-initdb.d/01_retailmind.dump

# --- 1. Contrasenas: fallo ruidoso ANTES de la restauracion ----------
# `${VAR:?msg}` aborta el script con un mensaje claro si la variable no
# esta definida o esta vacia. Va lo PRIMERO para no descubrir el fallo
# despues de restaurar 95 MB.
: "${PG_APP_PASSWORD:?FALTA PG_APP_PASSWORD (contrasena de retailmind_app)}"
: "${PG_ETL_PASSWORD:?FALTA PG_ETL_PASSWORD (contrasena de retailmind_etl)}"

echo "== 02_restaurar.sh: fijando contrasenas de los roles con LOGIN =="
# Se pasan como variables de psql y se citan con :'var' para que un
# caracter especial en la contrasena no se interprete como SQL.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     --set ON_ERROR_STOP=1 \
     --set app_pw="$PG_APP_PASSWORD" \
     --set etl_pw="$PG_ETL_PASSWORD" <<'SQL'
ALTER ROLE retailmind_app PASSWORD :'app_pw';
ALTER ROLE retailmind_etl PASSWORD :'etl_pw';
SQL

# --- 2. Restauracion --------------------------------------------------
# --exit-on-error es OBLIGATORIO. Sin el, pg_restore informa de los
# errores y CONTINUA, y el resultado mas probable de un fallo de orden
# es una base con datos pero sin politicas ni GRANT: arranca, sirve el
# login de admin y tiene la seguridad rota. Con `set -e`, el contenedor
# muere y el fallo es visible.
#
# --no-create NO se usa: el dump se tomo SIN --create precisamente
#   porque el locale `Spanish_Ecuador.1252` del origen (libc de Windows)
#   NO EXISTE en Debian y el CREATE DATABASE fallaria con «invalid
#   locale name». La base ya la creo el entrypoint con ICU es-EC.
# Se restaura como `postgres` y SIN --no-owner: las 13 funciones
#   SECURITY DEFINER se ejecutan con los privilegios de SU PROPIETARIO;
#   cambiarlo cambia la seguridad del sistema.
echo "== 02_restaurar.sh: restaurando $DUMP =="
pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
           --exit-on-error --verbose "$DUMP"

echo "== 02_restaurar.sh: OK =="
