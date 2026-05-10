"""
09_create_refresh_function.py
Crea la funcion refresh_dashboard_views() en PostgreSQL.
Se ejecuta por separado porque usa $$ que no se puede partir por ;
"""

import os
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), ".."))

from config.db_connection import get_connection

FUNCTION_SQL = """
CREATE OR REPLACE FUNCTION refresh_dashboard_views()
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_resumen_dashboard;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_canal;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_region;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_sesiones_por_dispositivo;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_tasa_conversion_semanal;
    RETURN 'Vistas materializadas refrescadas exitosamente';
END;
$$;
"""


def create_function():
    print("Creando funcion refresh_dashboard_views()...")

    try:
        conn = get_connection()
        cur = conn.cursor()
        cur.execute(FUNCTION_SQL)
        conn.commit()
        cur.close()
        conn.close()
        print("[OK] Funcion creada exitosamente.")
    except Exception as e:
        print(f"[ERROR] {e}")
        sys.exit(1)


if __name__ == "__main__":
    create_function()
