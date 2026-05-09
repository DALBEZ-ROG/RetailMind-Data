# Requirements Document

## Introduction

RetailMind Analytics S.A. requiere un pipeline ETL en Python que extraiga datos de una tabla staging PostgreSQL (`dataset_temporal`, 100 000 registros, 18 columnas en formato TEXT) y los cargue en 10 tablas normalizadas. El pipeline debe ejecutarse de forma modular (cuatro scripts independientes orquestados por `main.py`), garantizar integridad referencial, manejar errores descriptivamente y reportar progreso en consola.

## Glossary

- **ETL_Pipeline**: El sistema completo compuesto por los cuatro scripts ETL y `main.py`.
- **Staging_Table**: La tabla PostgreSQL `dataset_temporal` que contiene los datos fuente en formato TEXT.
- **Lookup_Tables**: Las tablas de catálogo sin dependencias externas: `regiones`, `dispositivos`, `canales`, `fuentes_trafico`, `categorias`.
- **Main_Tables**: Las tablas con claves foráneas: `usuarios`, `productos`, `sesiones`, `eventos`, `conversiones`.
- **DB_Connector**: El módulo `config/db_connection.py` responsable de establecer y cerrar conexiones a PostgreSQL usando psycopg2.
- **Script_01**: El script `etl/01_create_tables.py` que crea las 10 tablas normalizadas.
- **Script_02**: El script `etl/02_load_lookup_tables.py` que carga las Lookup_Tables.
- **Script_03**: El script `etl/03_load_main_tables.py` que carga las Main_Tables.
- **Script_04**: El script `etl/04_verify_load.py` que verifica y reporta los conteos de registros.
- **Batch**: Conjunto de hasta 1 000 registros procesados en una sola llamada a `executemany()`.
- **BATCH_SIZE**: Constante con valor 1 000 que define el tamaño máximo de cada Batch.
- **Environment_Variables**: Variables de entorno `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` cargadas desde el archivo `.env`.

---

## Requirements

### Requirement 1: Gestión de conexión a la base de datos

**User Story:** Como desarrollador, quiero un módulo centralizado de conexión a PostgreSQL, para que todos los scripts ETL reutilicen la misma lógica de conexión sin duplicar código.

#### Acceptance Criteria

1. THE DB_Connector SHALL leer las Environment_Variables desde el archivo `.env` usando `python-dotenv` al inicializarse.
2. WHEN se solicita una conexión, THE DB_Connector SHALL retornar un objeto de conexión psycopg2 válido usando los valores de las Environment_Variables.
3. IF alguna Environment_Variable requerida (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`) está ausente o vacía, THEN THE DB_Connector SHALL lanzar una excepción con un mensaje que identifique la variable faltante.
4. IF la conexión a PostgreSQL falla, THEN THE DB_Connector SHALL lanzar una excepción con el mensaje de error original de psycopg2.
5. WHEN se cierra una conexión, THE DB_Connector SHALL cerrar el cursor y la conexión de forma segura sin lanzar excepciones adicionales.

---

### Requirement 2: Creación de tablas normalizadas

**User Story:** Como desarrollador, quiero que Script_01 cree las 10 tablas normalizadas en la base de datos, para que el esquema destino esté listo antes de cargar datos.

#### Acceptance Criteria

1. WHEN Script_01 se ejecuta, THE Script_01 SHALL leer y ejecutar el archivo `sql/create_tables.sql` contra la base de datos destino.
2. THE Script_01 SHALL crear las 10 tablas en el orden correcto respetando las dependencias de claves foráneas: primero las Lookup_Tables y luego las Main_Tables.
3. THE `sql/create_tables.sql` SHALL usar `CREATE TABLE IF NOT EXISTS` para cada una de las 10 tablas, de modo que la ejecución repetida no genere errores.
4. WHEN Script_01 completa la creación de todas las tablas, THE Script_01 SHALL imprimir en consola un mensaje de confirmación por cada tabla creada.
5. IF la ejecución del SQL falla, THEN THE Script_01 SHALL realizar rollback de la transacción e imprimir el error descriptivo en consola.

---

### Requirement 3: Carga de tablas de catálogo (Lookup_Tables)

**User Story:** Como desarrollador, quiero que Script_02 cargue los valores únicos de las Lookup_Tables desde la Staging_Table, para que las claves foráneas de las Main_Tables tengan referencia válida.

#### Acceptance Criteria

1. WHEN Script_02 se ejecuta, THE Script_02 SHALL extraer los valores únicos de las columnas `region`, `device_type`, `channel`, `traffic_source` y `category` de la Staging_Table.
2. THE Script_02 SHALL insertar los valores únicos en las tablas `regiones`, `dispositivos`, `canales`, `fuentes_trafico` y `categorias` respectivamente usando `INSERT ... ON CONFLICT DO NOTHING`.
3. WHEN Script_02 inserta registros en una Lookup_Table, THE Script_02 SHALL imprimir en consola el nombre de la tabla y la cantidad de registros insertados.
4. IF la extracción o inserción de una Lookup_Table falla, THEN THE Script_02 SHALL realizar rollback de esa tabla, imprimir el error descriptivo en consola y continuar con la siguiente Lookup_Table.
5. THE Script_02 SHALL completar la carga de las 5 Lookup_Tables sin detener la ejecución por conflictos de duplicados.

---

### Requirement 4: Carga de tablas principales (Main_Tables)

**User Story:** Como desarrollador, quiero que Script_03 cargue los datos transformados en las Main_Tables usando inserts por lotes, para que los 100 000 registros se procesen eficientemente respetando la integridad referencial.

#### Acceptance Criteria

1. WHEN Script_03 se ejecuta, THE Script_03 SHALL truncar cada Main_Table antes de insertar datos nuevos, en el orden inverso de dependencias para respetar las claves foráneas.
2. THE Script_03 SHALL leer los datos de la Staging_Table en Batches de BATCH_SIZE registros usando cursores del lado del servidor (server-side cursor) de psycopg2.
3. THE Script_03 SHALL convertir los valores TEXT de la Staging_Table a los tipos de datos correctos de cada Main_Table: `price` a DECIMAL, `session_length` a FLOAT, `interaction_count` a INT, `time_spent_sec` a FLOAT, `is_conversion` y `drop_off_flag` a BOOLEAN.
4. THE Script_03 SHALL resolver las claves foráneas consultando los IDs generados en las Lookup_Tables antes de insertar en las Main_Tables.
5. WHEN Script_03 inserta un Batch en una Main_Table, THE Script_03 SHALL usar `executemany()` y SHALL imprimir en consola el nombre de la tabla y el acumulado de registros insertados hasta ese Batch.
6. IF la conversión de tipo de un campo falla para un registro, THEN THE Script_03 SHALL asignar `NULL` al campo afectado, registrar el `session_id` y el campo problemático en consola, y continuar con el siguiente registro.
7. IF la inserción de un Batch falla, THEN THE Script_03 SHALL realizar rollback de ese Batch, imprimir el error descriptivo en consola e intentar insertar los registros del Batch de forma individual.

---

### Requirement 5: Verificación de carga

**User Story:** Como desarrollador, quiero que Script_04 muestre un resumen con los conteos de registros de las 10 tablas normalizadas, para que pueda confirmar que la carga fue exitosa.

#### Acceptance Criteria

1. WHEN Script_04 se ejecuta, THE Script_04 SHALL ejecutar `SELECT COUNT(*)` en cada una de las 10 tablas normalizadas.
2. THE Script_04 SHALL imprimir en consola una tabla resumen con las columnas `Tabla` y `Registros` para las 10 tablas normalizadas.
3. THE Script_04 SHALL imprimir el total acumulado de registros de todas las tablas al final de la tabla resumen.
4. IF la consulta de conteo de alguna tabla falla, THEN THE Script_04 SHALL imprimir `ERROR` en la columna `Registros` de esa tabla y continuar con las demás.

---

### Requirement 6: Orquestación del pipeline completo

**User Story:** Como desarrollador, quiero ejecutar `main.py` para correr los cuatro scripts ETL en secuencia, para que el pipeline completo se ejecute con un solo comando.

#### Acceptance Criteria

1. WHEN `main.py` se ejecuta, THE ETL_Pipeline SHALL ejecutar Script_01, Script_02, Script_03 y Script_04 en ese orden secuencial.
2. THE ETL_Pipeline SHALL imprimir en consola el nombre y hora de inicio de cada script antes de ejecutarlo.
3. IF algún script termina con error no recuperado, THEN THE ETL_Pipeline SHALL imprimir el error descriptivo en consola, detener la ejecución de los scripts siguientes y retornar un código de salida distinto de cero.
4. WHEN todos los scripts completan sin error, THE ETL_Pipeline SHALL imprimir en consola un mensaje de éxito con el tiempo total de ejecución en segundos.

---

### Requirement 7: Configuración del entorno

**User Story:** Como desarrollador, quiero que las credenciales de base de datos se gestionen mediante variables de entorno, para que el proyecto no exponga información sensible en el código fuente.

#### Acceptance Criteria

1. THE ETL_Pipeline SHALL leer todas las credenciales de base de datos exclusivamente desde las Environment_Variables definidas en el archivo `.env`.
2. THE ETL_Pipeline SHALL incluir un archivo `requirements.txt` con las versiones exactas de las dependencias: `psycopg2`, `pandas`, `sqlalchemy` y `python-dotenv`.
3. WHERE el archivo `.env` no existe en el directorio raíz del proyecto, THE ETL_Pipeline SHALL imprimir un mensaje de error indicando la ruta esperada del archivo y detener la ejecución.
