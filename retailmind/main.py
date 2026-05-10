"""
main.py — RetailMind Analytics S.A.
Orquestador ETL: ejecuta los 4 pasos en orden.
Los módulos ETL tienen nombres que empiezan con número, por lo que se
importan dinámicamente con importlib.util para evitar errores de sintaxis.
"""

import sys
import time
import importlib.util
import os

ETL_DIR = os.path.join(os.path.dirname(__file__), "etl")

STEPS = [
    ("01_create_tables.py",      "create_tables",      "Paso 1 — Crear tablas"),
    ("02_load_lookup_tables.py", "load_lookup_tables", "Paso 2 — Cargar catálogos"),
    ("03_load_main_tables.py",   "load_main_tables",   "Paso 3 — Cargar tablas principales"),
    ("04_verify_load.py",        "verify_load",        "Paso 4 — Verificar carga"),
]


def load_module(filename):
    """Carga un módulo Python desde su ruta de archivo."""
    filepath = os.path.join(ETL_DIR, filename)
    spec = importlib.util.spec_from_file_location(filename, filepath)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run_step(filename, func_name, label):
    try:
        mod = load_module(filename)
        func = getattr(mod, func_name)
        func()
    except SystemExit as e:
        print(f"\n[ABORTADO] El proceso se detuvo en '{label}' con código {e.code}.")
        sys.exit(e.code)
    except Exception as e:
        print(f"\n[ERROR INESPERADO] en '{label}':\n  {e}")
        sys.exit(1)


if __name__ == "__main__":
    start = time.time()

    print("\n" + "=" * 55)
    print("  RetailMind Analytics S.A. — Pipeline ETL")
    print("=" * 55)

    for filename, func_name, label in STEPS:
        print()
        run_step(filename, func_name, label)

    elapsed = time.time() - start
    print(f"\n{'=' * 55}")
    print(f"  Pipeline completado en {elapsed:.2f} segundos OK")
    print(f"{'=' * 55}\n")
