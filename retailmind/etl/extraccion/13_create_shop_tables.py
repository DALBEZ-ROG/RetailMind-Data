"""
etl/13_create_shop_tables.py
Crea las tablas de la tienda en ClickHouse y pobla productos_catalogo desde dim_producto.
"""
import os
import sys
import time
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from config.clickhouse_connection import get_clickhouse_client, CH_DATABASE

DB = CH_DATABASE

CATEGORIAS = {
    1: "Electronics", 2: "Groceries", 3: "Sports", 4: "Accessories",
    5: "Beauty", 6: "Home", 7: "Shoes", 8: "Apparel"
}

DESCRIPCIONES = {
    1: "Dispositivo electronico de alta calidad con tecnologia de ultima generacion.",
    2: "Producto alimenticio fresco y natural, ideal para tu despensa.",
    3: "Equipamiento deportivo profesional para mejorar tu rendimiento.",
    4: "Accesorio elegante y funcional para complementar tu estilo.",
    5: "Producto de belleza premium para el cuidado personal.",
    6: "Articulo para el hogar que combina funcionalidad y diseno.",
    7: "Calzado comodo y resistente para cada ocasion.",
    8: "Prenda de vestir con estilo moderno y materiales de calidad."
}

PREFIJOS_NOMBRE = {
    1: ["Pro", "Ultra", "Max", "Smart", "Digital"],
    2: ["Fresh", "Natural", "Organic", "Premium", "Select"],
    3: ["Elite", "Pro", "Power", "Active", "Turbo"],
    4: ["Classic", "Luxury", "Slim", "Bold", "Elegant"],
    5: ["Glow", "Pure", "Radiant", "Silk", "Velvet"],
    6: ["Comfort", "Modern", "Cozy", "Essential", "Prime"],
    7: ["Runner", "Walker", "Sprint", "Flex", "Air"],
    8: ["Urban", "Casual", "Street", "Trend", "Style"]
}


def main():
    print("=" * 60)
    print("  CREACIÓN DE TABLAS DE TIENDA")
    print("=" * 60)
    inicio = time.time()

    client = get_clickhouse_client()

    # 1. Crear tablas
    print("\n📐 Creando tablas...")

    client.command(f"""
        CREATE TABLE IF NOT EXISTS {DB}.productos_catalogo (
            producto_id String,
            nombre String,
            descripcion String,
            categoria_id UInt32,
            brand String,
            price Float32,
            stock UInt32,
            imagen_url String,
            activo UInt8,
            fecha_creacion String
        ) ENGINE = MergeTree() ORDER BY producto_id
    """)
    print("   ✅ productos_catalogo")

    client.command(f"""
        CREATE TABLE IF NOT EXISTS {DB}.carrito_items (
            carrito_id String,
            user_id String,
            producto_id String,
            cantidad UInt32,
            precio_unitario Float32,
            fecha_agregado String,
            activo UInt8
        ) ENGINE = MergeTree() ORDER BY (user_id, carrito_id)
    """)
    print("   ✅ carrito_items")

    client.command(f"""
        CREATE TABLE IF NOT EXISTS {DB}.ordenes (
            orden_id String,
            user_id String,
            total Float32,
            estado String,
            fecha_orden String,
            canal String
        ) ENGINE = MergeTree() ORDER BY (user_id, orden_id)
    """)
    print("   ✅ ordenes")

    client.command(f"""
        CREATE TABLE IF NOT EXISTS {DB}.orden_items (
            orden_id String,
            producto_id String,
            cantidad UInt32,
            precio_unitario Float32
        ) ENGINE = MergeTree() ORDER BY orden_id
    """)
    print("   ✅ orden_items")

    # 2. Poblar productos_catalogo desde dim_producto
    print("\n📦 Poblando productos_catalogo desde dim_producto...")

    # Verificar si ya tiene datos
    count = client.query(f"SELECT count() FROM {DB}.productos_catalogo").result_rows[0][0]
    if count > 0:
        print(f"   ⚠️  productos_catalogo ya tiene {count:,} registros. Saltando.")
    else:
        # Leer dim_producto
        productos = client.query(
            f"SELECT producto_id, categoria_id, brand, price FROM {DB}.dim_producto"
        ).result_rows

        if not productos:
            print("   ⚠️  dim_producto está vacía. No se pueden poblar productos.")
        else:
            rng = np.random.default_rng(42)
            rows = []
            now = "2026-01-01 00:00:00"

            for prod_id, cat_id, brand, price in productos:
                cat_id = int(cat_id)
                prefijos = PREFIJOS_NOMBRE.get(cat_id, ["Item"])
                prefijo = rng.choice(prefijos)
                cat_name = CATEGORIAS.get(cat_id, "General")
                nombre = f"{brand} {cat_name} {prefijo}"
                descripcion = DESCRIPCIONES.get(cat_id, "Producto de calidad.")
                stock = int(rng.integers(10, 101))

                rows.append([
                    str(prod_id), nombre, descripcion, int(cat_id),
                    str(brand), float(price), stock, "", 1, now
                ])

            client.insert(f"{DB}.productos_catalogo", rows, column_names=[
                "producto_id", "nombre", "descripcion", "categoria_id",
                "brand", "price", "stock", "imagen_url", "activo", "fecha_creacion"
            ])
            print(f"   ✅ {len(rows):,} productos insertados en catalogo")

    elapsed = time.time() - inicio
    print(f"\n{'=' * 60}")
    print(f"  COMPLETADO ({elapsed:.1f}s)")
    print(f"{'=' * 60}")
    client.close()


if __name__ == "__main__":
    main()
