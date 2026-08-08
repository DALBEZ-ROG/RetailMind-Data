"""Auxiliar de diagnostico: muestra la PRIMERA diferencia entre origen y copia
de una tabla. No forma parte del experimento; sirve para depurar la
normalizacion de `02_verificar_copia.py`.

    py -3 _diff.py <base_ch> <tabla> <esquema_pg>
"""
import importlib.util
import io
import sys

from comun import BASE_BENCH, ch, columnas_ch, pg

spec = importlib.util.spec_from_file_location("v", "02_verificar_copia.py")
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)

base_ch, tabla, esq = sys.argv[1], sys.argv[2], sys.argv[3]
conn = pg(BASE_BENCH)
cols = columnas_ch(base_ch, tabla)
orden = v.clave_orden(base_ch, tabla)
oc = ", ".join(f"`{c}`" for c in orden)
op = ", ".join(f'"{c}"' for c in orden)
a = ch(f"SELECT {', '.join(v.sel_ch(n, t) for n, t in cols)} FROM `{base_ch}`.`{tabla}` "
       f"ORDER BY {oc} FORMAT TabSeparated", base_ch).split("\n")
buf = io.StringIO()
with conn.cursor() as cur:
    cur.copy_expert(
        f"COPY (SELECT {', '.join(v.sel_pg(n, t) for n, t in cols)} "
        f'FROM {esq}."{tabla}" ORDER BY {op}) TO STDOUT WITH (FORMAT text)', buf)
b = buf.getvalue().split("\n")

vistos = set()
n = 0
for i, (x, y) in enumerate(zip(a, b)):
    if x == y:
        continue
    for j, (p, q) in enumerate(zip(x.split("\t"), y.split("\t"))):
        if p != q and cols[j][0] not in vistos:
            vistos.add(cols[j][0])
            print(f"fila {i}  col {cols[j][0]} ({cols[j][1]}):  CH={p!r}  PG={q!r}")
    n += 1
    if n > 400:
        break
print(f"-- filas distintas (primeras {min(n, 400)} inspeccionadas) --")
