# =====================================================================
# Imagen de Airflow para RetailMind
#
# Airflow ENVUELVE el ETL, no lo reescribe: este contenedor solo necesita
# poder ejecutar `python -m etl.dwh.run_etl`, que es exactamente el mismo
# comando que se corre a mano y el que lanza el backend. Por eso lo unico
# que se anade a la imagen oficial son las CUATRO dependencias que
# `etl/dwh/` importa de verdad.
#
# El codigo del ETL NO se copia: se MONTA desde el compose (`./retailmind`
# -> `/opt/retailmind`), igual que hace el servicio `backend`. Asi un
# cambio en el pipeline no obliga a reconstruir esta imagen, que es la
# misma propiedad que ya tiene el contenedor `etl`.
#
# VERSION FIJA, nunca `latest`: una imagen de Airflow que cambia sola
# entre dos ensayos es exactamente lo que no se quiere la semana de una
# sustentacion.
# =====================================================================
FROM apache/airflow:2.10.5-python3.12

# Las versiones son las MISMAS que `retailmind/requirements.txt` para que
# el pipeline se comporte igual aqui que en el contenedor `etl`. Se
# instalan solo las que `etl/dwh/` importa:
#   psycopg2-binary   -> conexiones.py (origen PostgreSQL)
#   clickhouse-connect-> conexiones.py (destino ClickHouse)
#   python-dotenv     -> conexiones.py (load_dotenv)
#   pandas            -> lo arrastra el modelo de prevision de demanda
# Quedan FUERA a proposito `pocketbase`, `pyarrow` y `sqlalchemy`: son del
# ETL legado (PocketBase -> Parquet) y el paquete `dwh/` no los toca.
# `pandas` ya viene en la imagen de Airflow, pero se fija la version del
# ETL para no depender de cual traiga la base.
RUN pip install --no-cache-dir \
        psycopg2-binary==2.9.9 \
        clickhouse-connect==0.7.16 \
        python-dotenv==1.0.1 \
        pandas==2.2.2
