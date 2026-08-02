"""
etl/dwh/modelos/
Los MODELOS del nivel estratégico, separados de las tareas que los cargan.

La razón de que sean un paquete aparte y no código dentro de
`etl/dwh/tablas/fact_prevision_demanda.py` es que el algoritmo tiene que poder
ejecutarse SIN ClickHouse delante: el backtest de §5.1.6 se mide sobre listas de
números, no sobre una tabla, y una función que solo se puede probar levantando
el almacén no se prueba.

Regla del paquete: **aquí no se abre ninguna conexión**. Entra un vector de
meses y de valores, sale una previsión con su banda y su error. Quien lee de
ClickHouse y quien escribe en ClickHouse es la tarea.
"""
