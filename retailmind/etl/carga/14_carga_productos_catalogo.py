"""
14_carga_productos_catalogo.py — Carga inicial (one-time) de los productos del
dataset original al catálogo operativo de PostgreSQL.

>>> HISTÓRICO. YA SE EJECUTÓ (2026-07-10) y NO hay que volver a correrlo. <<<
Se conserva porque es el origen documentado del catálogo vivo y del puente
P####↔`producto.slug` (§4.4 de `docs/estrategico/DIAGNOSTICO_CLICKHOUSE.md`),
no porque quede trabajo por hacer. Es idempotente, así que re-ejecutarlo no
duplicaría nada, pero tampoco arreglaría nada: los valores que sembró aquí
—`costo = price*0.6` y stock plano de 100— los REESCRIBIERON después los
scripts 67 (bandas de costo por categoría) y 74-84 (abastecimiento y kardex),
y este script no los volvería a tocar (ON CONFLICT ... DO NOTHING).

Fuente : retailmind/data/stage/datos.parquet (eventos; solo se usan las
         columnas product_id, category, brand, price). Autosuficiente: NO
         requiere ClickHouse ni PocketBase levantados.
Destino: BD `retailmind` (PostgreSQL local) — tablas marca, categoria,
         producto, producto_categoria, producto_variante, inventario.

Decisiones de diseño (ver INFORME_DIAGNOSTICO_PRODUCTOS_ETL.md):
  * Nombre de producto = "{marca} {product_id}" (el dataset no trae nombres).
  * slug de producto = slugify(product_id); SKU = "SKU-{product_id}".
  * Variante única por producto, es_predeterminada = true.
  * costo = round(price * 0.6, 2); stock inicial = 100 en la bodega principal
    ("Bodega Central Quevedo").
  * Categorías traducidas al español y FUSIONADAS con las existentes por slug
    (Shoes→Calzado, Accessories→Accesorios ya existen; Apparel→Ropa se crea
    nueva porque el dataset no distingue Ropa Hombre / Ropa Mujer).
  * Marcas fusionadas por nombre (Nike/Adidas/Puma ya existen).

Idempotente: todos los INSERT usan ON CONFLICT ... DO NOTHING sobre las claves
naturales (marca.nombre, categoria.slug, producto.slug, producto_variante.sku,
inventario (producto_variante_id, bodega_id)); re-ejecutarlo no duplica nada.

Se conecta como `postgres` (seed administrativo de datos maestros, igual que
los scripts 25/26/27 de sql/postgres): NO pasa por el backend transaccional,
así que ni `retailmind_etl` —solo lectura— ni `retailmind_app` —NOINHERIT, sin
privilegios de negocio hasta el `SET LOCAL ROLE` del backend— servirían.
La conexión sale del ENTORNO (`retailmind/.env`, vía python-dotenv, igual que
`etl/dwh/conexiones.py`): host/puerto/base de `ETL_PG_HOST`/`ETL_PG_PORT`/
`ETL_PG_DATABASE`, y la clave del superusuario de `PG_SUPERUSER_PASSWORD` o
del secreto `deploy/secrets/pg_superuser.txt`. Sin valores por defecto: falta
una y el script muere diciendo cuál. OJO con el `DB_NAME` del .env, que apunta
a la BD antigua; aquí NO se usa.

Todo corre en UNA transacción con commit al final.
"""

from __future__ import annotations

import os
import re
import sys
import unicodedata
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

import pandas as pd
import psycopg2
from dotenv import load_dotenv
from psycopg2.extras import execute_values

# ── Configuración ────────────────────────────────────────────────────────────
RAIZ_ETL = Path(__file__).resolve().parents[2]
RAIZ_PROYECTO = RAIZ_ETL.parent
PARQUET = RAIZ_ETL / "data" / "stage" / "datos.parquet"

load_dotenv(RAIZ_ETL / ".env")          # sin override: el entorno manda
load_dotenv(RAIZ_PROYECTO / ".env")

SECRETO_SUPERUSUARIO = RAIZ_PROYECTO / "deploy" / "secrets" / "pg_superuser.txt"


def _clave_superusuario() -> str:
    """Del entorno o del secreto de Docker. Nunca un valor por defecto."""
    clave = os.getenv("PG_SUPERUSER_PASSWORD")
    if clave:
        return clave
    if SECRETO_SUPERUSUARIO.is_file():
        clave = SECRETO_SUPERUSUARIO.read_text(encoding="utf-8").strip()
        if clave:
            return clave
    sys.exit(
        "ERROR: falta la contraseña del superusuario de PostgreSQL.\n"
        "  Se busca en PG_SUPERUSER_PASSWORD y luego en\n"
        f"  {SECRETO_SUPERUSUARIO}"
    )


def _conexion() -> dict:
    """
    Parámetros de conexión desde el entorno, sin defecto silencioso.

    Desde la contenerización el 5432 es el CONTENEDOR y el PostgreSQL local
    quedó en el 5433: caer en un `localhost:5432` por defecto no es un detalle,
    es escribir datos maestros en la base que no toca.
    """
    variables = {"host": "ETL_PG_HOST", "port": "ETL_PG_PORT",
                 "dbname": "ETL_PG_DATABASE"}       # NO el DB_NAME del .env (BD antigua)
    cfg = {campo: os.getenv(var) for campo, var in variables.items()}
    cfg["user"] = os.getenv("PG_SUPERUSER_USER", "postgres")   # nombre de rol, no secreto

    faltantes = [var for campo, var in variables.items() if not cfg[campo]]
    if faltantes:
        sys.exit(
            f"ERROR: faltan variables de conexión: {', '.join(faltantes)}.\n"
            f"  Defínelas en {RAIZ_ETL / '.env'} (plantilla: .env.example)."
        )
    cfg["port"] = int(cfg["port"])
    cfg["password"] = _clave_superusuario()
    return cfg

BODEGA_PRINCIPAL = "Bodega Central Quevedo"
STOCK_INICIAL = 100
FACTOR_COSTO = Decimal("0.6")

# Traducción EN → (nombre ES, slug). La fusión con las categorías existentes es
# por slug (UNIQUE): 'calzado' y 'accesorios' ya existen y se reutilizan;
# 'ropa' se crea nueva (no se fusiona con ropa-hombre/ropa-mujer porque el
# dataset no distingue género); el resto son nuevas.
CATEGORIAS_ES = {
    "Electronics": ("Electrónica", "electronica"),
    "Groceries":   ("Abarrotes",   "abarrotes"),
    "Sports":      ("Deportes",    "deportes"),
    "Accessories": ("Accesorios",  "accesorios"),   # fusiona con existente
    "Beauty":      ("Belleza",     "belleza"),
    "Home":        ("Hogar",       "hogar"),
    "Shoes":       ("Calzado",     "calzado"),      # fusiona con existente
    "Apparel":     ("Ropa",        "ropa"),
}


def slugify(texto: str) -> str:
    s = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode("ascii")
    s = re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")
    return s


def costo_de(precio: Decimal) -> Decimal:
    return (precio * FACTOR_COSTO).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


# ── 1. Leer y deduplicar el Parquet ─────────────────────────────────────────
def cargar_productos_unicos() -> pd.DataFrame:
    if not PARQUET.exists():
        sys.exit(f"ERROR: no existe el Parquet {PARQUET}")
    df = pd.read_parquet(PARQUET, columns=["product_id", "category", "brand", "price"])

    incons = df.groupby("product_id")[["category", "brand", "price"]].nunique()
    malos = incons[(incons > 1).any(axis=1)]
    if not malos.empty:
        sys.exit(f"ERROR: {len(malos)} product_id con atributos inconsistentes:\n{malos.head()}")

    prods = df.drop_duplicates("product_id").sort_values("product_id").reset_index(drop=True)
    prods["precio"] = prods["price"].map(lambda p: Decimal(str(p)).quantize(Decimal("0.01")))
    if (prods["precio"] <= 0).any():
        sys.exit("ERROR: hay precios <= 0 en el dataset")
    if prods["category"].map(lambda c: c not in CATEGORIAS_ES).any():
        desconocidas = sorted(set(prods["category"]) - set(CATEGORIAS_ES))
        sys.exit(f"ERROR: categorías sin traducción definida: {desconocidas}")
    return prods


# ── 2..7. Carga en PostgreSQL (una transacción) ─────────────────────────────
def main() -> None:
    prods = cargar_productos_unicos()
    print(f"Parquet: {len(prods)} productos únicos, "
          f"{prods['category'].nunique()} categorías, {prods['brand'].nunique()} marcas")

    conn = psycopg2.connect(**_conexion())
    try:
        with conn, conn.cursor() as cur:
            antes = _conteos(cur)

            # 2. Marcas (fusión por nombre UNIQUE)
            marcas = sorted(prods["brand"].unique())
            execute_values(
                cur,
                "INSERT INTO marca (nombre, slug) VALUES %s ON CONFLICT (nombre) DO NOTHING",
                [(m, slugify(m)) for m in marcas],
            )
            cur.execute("SELECT nombre, id FROM marca")
            marca_id = dict(cur.fetchall())

            # 3. Categorías (traducción ES, fusión por slug UNIQUE)
            execute_values(
                cur,
                "INSERT INTO categoria (nombre, slug) VALUES %s ON CONFLICT (slug) DO NOTHING",
                sorted(set(CATEGORIAS_ES.values())),
            )
            cur.execute("SELECT slug, id FROM categoria")
            cat_por_slug = dict(cur.fetchall())
            categoria_id = {en: cat_por_slug[slug] for en, (_, slug) in CATEGORIAS_ES.items()}

            # 4. Productos (nombre = marca + código, slug = slugify(product_id))
            execute_values(
                cur,
                "INSERT INTO producto (marca_id, nombre, slug, publicado, activo) VALUES %s "
                "ON CONFLICT (slug) DO NOTHING",
                [
                    (marca_id[r.brand], f"{r.brand} {r.product_id}", slugify(r.product_id), True, True)
                    for r in prods.itertuples()
                ],
            )
            slugs = [slugify(r.product_id) for r in prods.itertuples()]
            cur.execute("SELECT slug, id FROM producto WHERE slug = ANY(%s)", (slugs,))
            producto_id = dict(cur.fetchall())
            if len(producto_id) != len(prods):
                raise RuntimeError(f"Lookup de productos incompleto: {len(producto_id)}/{len(prods)}")

            # 5. producto_categoria (es_principal = true)
            execute_values(
                cur,
                "INSERT INTO producto_categoria (producto_id, categoria_id, es_principal) VALUES %s "
                "ON CONFLICT (producto_id, categoria_id) DO NOTHING",
                [
                    (producto_id[slugify(r.product_id)], categoria_id[r.category], True)
                    for r in prods.itertuples()
                ],
            )

            # 6. Variante única (SKU-{product_id}, costo = 60 % del precio)
            execute_values(
                cur,
                "INSERT INTO producto_variante "
                "(producto_id, sku, precio, costo, es_predeterminada, activo) VALUES %s "
                "ON CONFLICT (sku) DO NOTHING",
                [
                    (producto_id[slugify(r.product_id)], f"SKU-{r.product_id}",
                     r.precio, costo_de(r.precio), True, True)
                    for r in prods.itertuples()
                ],
            )
            skus = [f"SKU-{r.product_id}" for r in prods.itertuples()]
            cur.execute("SELECT sku, id FROM producto_variante WHERE sku = ANY(%s)", (skus,))
            variante_id = dict(cur.fetchall())
            if len(variante_id) != len(prods):
                raise RuntimeError(f"Lookup de variantes incompleto: {len(variante_id)}/{len(prods)}")

            # 7. Inventario en la bodega principal (stock inicial 100)
            cur.execute("SELECT id FROM bodega WHERE nombre = %s", (BODEGA_PRINCIPAL,))
            fila = cur.fetchone()
            if fila is None:
                raise RuntimeError(f"No existe la bodega principal '{BODEGA_PRINCIPAL}'")
            bodega_id = fila[0]
            execute_values(
                cur,
                "INSERT INTO inventario (producto_variante_id, bodega_id, stock_actual) VALUES %s "
                "ON CONFLICT (producto_variante_id, bodega_id) DO NOTHING",
                [(v, bodega_id, STOCK_INICIAL) for v in variante_id.values()],
            )

            despues = _conteos(cur)

        # el context manager `with conn` ya hizo commit
        print("Carga confirmada (COMMIT). Filas por tabla (antes -> después):")
        for tabla in antes:
            print(f"  {tabla:20s} {antes[tabla]:5d} -> {despues[tabla]:5d} (+{despues[tabla] - antes[tabla]})")
    finally:
        conn.close()


def _conteos(cur) -> dict[str, int]:
    tablas = ["marca", "categoria", "producto", "producto_categoria",
              "producto_variante", "inventario"]
    out = {}
    for t in tablas:
        cur.execute(f"SELECT count(*) FROM {t}")
        out[t] = cur.fetchone()[0]
    return out


if __name__ == "__main__":
    main()
