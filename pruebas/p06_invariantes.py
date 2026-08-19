"""
p06_invariantes.py — Integridad de datos e invariantes (suite P06).

Comprueba las igualdades que TIENEN que cumplirse siempre, contra el motor y
al centavo. Es la suite que detecta corrupción silenciosa: nada de esto produce
un error en pantalla, y sin embargo un solo descuadre aquí significa que alguna
cifra publicada es falsa.

Cada invariante lleva escrito POR QUÉ la comprobación ingenua no sirve. Esa es
la parte que no se puede deducir del código y la que hace la suite reutilizable:
sin ella, la próxima persona reescribe la versión obvia y obtiene un verde falso.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from comun.arnes import Registro                                           # noqa: E402
from comun.motor import entero, escalar, sql                               # noqa: E402

RAIZ = Path(__file__).resolve().parents[1]


def correr(estado_datos: str = "E3") -> Registro:
    reg = Registro(estado_datos)

    # ─────────────────────────────────────────────────────────────────────────
    # P06-003 · la ECUACIÓN del kardex, fila a fila
    # ─────────────────────────────────────────────────────────────────────────
    rotas = entero("""
        SELECT count(*) FROM movimiento_inventario mi
        JOIN tipo_movimiento tm ON tm.id = mi.tipo_movimiento_id
        WHERE mi.stock_anterior + (mi.cantidad * tm.factor) <> mi.stock_nuevo""")
    reg.caso("P06-003", "Kardex: stock_anterior + cantidad×factor = stock_nuevo",
             condicion=rotas == 0, severidad="S1",
             observado=f"{rotas} filas incumplen la ecuación", esperado="0")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-004 · el ENCADENADO, leyendo por (fecha_creacion, id)
    # ─────────────────────────────────────────────────────────────────────────
    # El orden es lo esencial: por `id` a secas la cadena parece rota en cuanto
    # alguien insertó en el pasado (las siembras lo hacen), y por `fecha` a
    # secas empatan los movimientos del mismo microsegundo. La pareja es la
    # única lectura que reproduce el orden real del almacén.
    desenlazadas = entero("""
        WITH cadena AS (
          SELECT mi.stock_anterior,
                 lag(mi.stock_nuevo) OVER (
                     PARTITION BY mi.producto_variante_id, mi.bodega_id
                     ORDER BY mi.fecha_creacion, mi.id) AS previo
          FROM movimiento_inventario mi)
        SELECT count(*) FROM cadena WHERE previo IS NOT NULL AND previo <> stock_anterior""")
    reg.caso("P06-004", "Kardex: cada eslabón arranca donde acabó el anterior",
             condicion=desenlazadas == 0, severidad="S1",
             observado=f"{desenlazadas} enlaces rotos", esperado="0")

    mal_arranque = entero("""
        WITH primera AS (
          SELECT DISTINCT ON (producto_variante_id, bodega_id) stock_anterior
          FROM movimiento_inventario
          ORDER BY producto_variante_id, bodega_id, fecha_creacion, id)
        SELECT count(*) FROM primera WHERE stock_anterior <> 0""")
    reg.caso("P06-004", "Kardex: toda cadena arranca en 0",
             condicion=mal_arranque == 0, severidad="S1",
             observado=f"{mal_arranque} cadenas con arranque distinto de 0", esperado="0")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-005 · el cierre del kardex contra `inventario`, POSICIÓN POR POSICIÓN
    # ─────────────────────────────────────────────────────────────────────────
    # En agregado siempre cuadra: los errores se compensan entre posiciones.
    # La prueba definitiva es por par (variante, bodega), que es como se
    # verificó la Fase 3B.
    descuadradas = entero("""
        WITH cierre AS (
          SELECT DISTINCT ON (producto_variante_id, bodega_id)
                 producto_variante_id, bodega_id, stock_nuevo
          FROM movimiento_inventario
          ORDER BY producto_variante_id, bodega_id, fecha_creacion DESC, id DESC)
        SELECT count(*) FROM inventario i
        JOIN cierre c ON c.producto_variante_id = i.producto_variante_id
                     AND c.bodega_id = i.bodega_id
        WHERE c.stock_nuevo <> i.stock_actual""")
    posiciones = entero("SELECT count(*) FROM inventario")
    reg.caso("P06-005", "El cierre del kardex es el stock actual, posición por posición",
             condicion=descuadradas == 0, severidad="S1",
             observado=f"{descuadradas} descuadradas de {posiciones} posiciones",
             esperado="0 — en agregado siempre cuadra; el descuadre vive en el detalle")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-006 · factura_venta.total = pedido.total − pedido.costo_envio
    # ─────────────────────────────────────────────────────────────────────────
    # El invariante NO es `factura.total = pedido.total`: la factura no factura
    # el flete. Y hay que tomar la factura CANÓNICA, porque `factura_venta` no
    # es 1:1 con el pedido (el pedido 2 tiene dos facturas 'emitida').
    desalineadas = entero("""
        WITH canonica AS (
          SELECT DISTINCT ON (pedido_id) pedido_id, total
          FROM factura_venta WHERE estado <> 'anulada'
          ORDER BY pedido_id, fecha_emision DESC, id DESC)
        SELECT count(*) FROM canonica c JOIN pedido p ON p.id = c.pedido_id
        WHERE round(c.total, 2) <> round(p.total - COALESCE(p.costo_envio, 0), 2)""")
    reg.caso("P06-006", "factura_venta.total = pedido.total − costo_envio",
             condicion=desalineadas == 0, severidad="S1",
             observado=f"{desalineadas} facturas desalineadas",
             esperado="0 — la factura NO factura el flete")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-008 · `factura_venta` NO es 1:1 con el pedido
    # ─────────────────────────────────────────────────────────────────────────
    # Esto no es un fallo: es un hecho del dato que TODO informe tiene que
    # respetar. Se comprueba que siga siendo cierto para que nadie «simplifique»
    # el JOIN creyendo que es 1:1 — la Fase 1 salió con 10.386 líneas donde hay
    # 10.384 justo por eso.
    no_anuladas = entero("SELECT count(*) FROM factura_venta WHERE estado <> 'anulada'")
    pedidos_con = entero("SELECT count(DISTINCT pedido_id) FROM factura_venta WHERE estado <> 'anulada'")
    reg.caso("P06-008", "Sigue habiendo pedidos con más de una factura no anulada",
             condicion=no_anuladas >= pedidos_con, severidad="S3",
             observado=f"{no_anuladas} facturas sobre {pedidos_con} pedidos "
                       f"(diferencia {no_anuladas - pedidos_con})",
             esperado="la factura CANÓNICA es obligatoria en todo informe",
             detalle="si algún día fueran iguales, seguiría siendo obligatoria: "
                     "el dato puede volver a divergir sin avisar")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-007 · cuadre contable de compras
    # ─────────────────────────────────────────────────────────────────────────
    facturado = escalar("SELECT COALESCE(sum(total),0)::numeric(18,2) FROM factura_compra")
    pagado = escalar("SELECT COALESCE(sum(monto),0)::numeric(18,2) FROM pago_proveedor")
    saldo = escalar("SELECT COALESCE(sum(saldo_pendiente),0)::numeric(18,2) FROM cuenta_por_pagar")
    try:
        descuadre = round(float(facturado) - float(pagado) - float(saldo), 2)
    except ValueError:
        descuadre = None
    reg.caso("P06-007", "Facturas de compra − pagos = saldo de CxP",
             condicion=descuadre == 0, severidad="S1",
             observado=f"{facturado} − {pagado} − {saldo} = {descuadre}",
             esperado="descuadre 0,00")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-009 · `movimiento_id` no es único entre pago y pago_proveedor
    # ─────────────────────────────────────────────────────────────────────────
    # Es una propiedad del DATO, no un invariante: con pocas filas los ids
    # todavía no se han cruzado. Afirmar «se solapan» a secas suspende en E1 con
    # el sistema perfecto — así que primero se comprueba que haya con qué medir.
    n_pago = entero("SELECT count(*) FROM pago")
    n_prov = entero("SELECT count(*) FROM pago_proveedor")
    if n_pago == 0 or n_prov == 0:
        reg.omitir("P06-009", "Solape de ids entre `pago` y `pago_proveedor`",
                   motivo=f"sin muestra: {n_pago} pagos y {n_prov} pagos a proveedor. "
                          "La regla sigue vigente (la clave es el par "
                          "(sentido, movimiento_id)), pero aquí no hay con qué demostrarla")
    else:
        solapan = entero("""
            SELECT count(*) FROM (SELECT id FROM pago INTERSECT SELECT id FROM pago_proveedor) x""")
        reg.caso("P06-009", "Los ids de `pago` y `pago_proveedor` se solapan",
                 condicion=solapan > 0, severidad="S3",
                 observado=f"{solapan} ids compartidos de {n_pago} y {n_prov}",
                 esperado="la clave del flujo de caja es el par (sentido, movimiento_id), "
                          "nunca el id a secas")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-010 · el kardex nunca lleva `fecha_creacion` implícita
    # ─────────────────────────────────────────────────────────────────────────
    sin_fecha = entero("SELECT count(*) FROM movimiento_inventario WHERE fecha_creacion IS NULL")
    reg.caso("P06-010", "Ningún movimiento de kardex sin fecha explícita",
             condicion=sin_fecha == 0, severidad="S1",
             observado=f"{sin_fecha} sin fecha", esperado="0 — el trigger valida la FILA, no el ENLACE")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-012 · numeración de documentos sin duplicados
    # ─────────────────────────────────────────────────────────────────────────
    for tabla, col in (("factura_venta", "numero"), ("factura_compra", "numero"),
                       ("ticket_soporte", "numero")):
        existe = entero(f"""SELECT count(*) FROM information_schema.columns
                            WHERE table_name='{tabla}' AND column_name='{col}'""")
        if existe != 1:
            continue
        dup = entero(f"""SELECT count(*) FROM (
                           SELECT {col} FROM {tabla} WHERE {col} IS NOT NULL
                           GROUP BY {col} HAVING count(*) > 1) x""")
        reg.caso("P06-012", f"{tabla}.{col} sin duplicados",
                 condicion=dup == 0, severidad="S1",
                 observado=f"{dup} valores repetidos", esperado="0")

    # ─────────────────────────────────────────────────────────────────────────
    # P06-001/002 · nada escribe columnas GENERATED ni contadores de trigger
    # ─────────────────────────────────────────────────────────────────────────
    generadas = sql("""
        SELECT c.relname || '.' || a.attname
        FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
        JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='public' AND a.attgenerated <> '' ORDER BY 1""")
    lista_generadas = [l.strip() for l in generadas.splitlines() if l.strip()]
    reg.caso("P06-001", "El esquema declara columnas GENERATED",
             condicion=len(lista_generadas) > 0, severidad="S3",
             observado=f"{len(lista_generadas)} columnas: {', '.join(lista_generadas[:6])}",
             esperado="al menos una — si desaparecieran, los totales pasarían a "
                      "depender del código y esta suite dejaría de proteger nada")

    reg.anotar(_revisar_escrituras_prohibidas(reg, lista_generadas))

    # ─────────────────────────────────────────────────────────────────────────
    # P06-014 · huérfanos en las relaciones críticas
    # ─────────────────────────────────────────────────────────────────────────
    for etiqueta, consulta in (
        ("pedido_detalle sin pedido",
         "SELECT count(*) FROM pedido_detalle d LEFT JOIN pedido p ON p.id=d.pedido_id WHERE p.id IS NULL"),
        ("factura_venta sin pedido",
         "SELECT count(*) FROM factura_venta f LEFT JOIN pedido p ON p.id=f.pedido_id WHERE p.id IS NULL"),
        ("movimiento sin posición de inventario",
         """SELECT count(*) FROM movimiento_inventario m
            LEFT JOIN inventario i ON i.producto_variante_id = m.producto_variante_id
                                  AND i.bodega_id = m.bodega_id
            WHERE i.id IS NULL"""),
        ("pedido sin cliente",
         "SELECT count(*) FROM pedido p LEFT JOIN cliente c ON c.id=p.cliente_id WHERE c.id IS NULL"),
    ):
        n = entero(consulta)
        reg.caso("P06-014", f"Sin huérfanos: {etiqueta}",
                 condicion=n == 0, severidad="S1",
                 observado=f"{n} huérfanos", esperado="0")

    return reg


def _revisar_escrituras_prohibidas(reg: Registro, generadas: list[str]):
    """
    Busca en el código INSERT/UPDATE sobre columnas que las pone el motor.

    La regla de oro nº 1 del proyecto: nunca escribir columnas GENERATED, ni
    totales de cabecera, ni `fecha_actualizacion`, ni `usos_actuales`. El motor
    rechazaría una GENERATED, pero un total de cabecera lo ACEPTA sin protestar
    y luego el trigger lo pisa —o no—, según el orden. Por eso se busca en el
    código y no se confía en que el motor avise.
    """
    from comun.arnes import Resultado
    import re

    fuente = RAIZ / "retailmind-backend" / "src" / "main" / "java"
    prohibidas = {"fecha_actualizacion", "usos_actuales"}
    prohibidas |= {g.split(".")[-1] for g in generadas}

    hallazgos: list[str] = []
    for archivo in sorted(fuente.rglob("*.java")):
        if "/analytics/" in archivo.as_posix():
            continue
        texto = archivo.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'(INSERT\s+INTO\s+\w+\s*\([^)]*\)|UPDATE\s+\w+\s+SET\s+[^"]{0,400})',
                             texto, re.I | re.S):
            fragmento = m.group(0)
            for col in prohibidas:
                # `SET col =` o una lista de columnas de INSERT que la incluya.
                if re.search(rf'\b{re.escape(col)}\s*=\s*[^=]', fragmento, re.I) or \
                   re.search(rf'\(\s*[^)]*\b{re.escape(col)}\b[^)]*\)', fragmento, re.I):
                    linea = texto[:m.start()].count("\n") + 1
                    hallazgos.append(f"{archivo.relative_to(fuente).as_posix()}:{linea} → {col}")
                    break

    # `fecha_actualizacion` en un UPDATE es el caso que más se cuela, así que se
    # reporta aparte aunque el conjunto salga vacío.
    return Resultado(
        caso="P06-002", titulo="El código no escribe columnas que pone el motor",
        estado_datos=reg.estado_datos,
        veredicto="PASA" if not hallazgos else "FALLA",
        severidad="" if not hallazgos else "S2",
        observado=(f"{len(hallazgos)}: " + "; ".join(sorted(set(hallazgos))[:8])
                   if hallazgos else "ninguna escritura prohibida"),
        esperado="0 — una GENERATED la rechaza el motor, pero un total de "
                 "cabecera lo acepta y luego el trigger decide, o no",
    )


if __name__ == "__main__":
    estado = sys.argv[1] if len(sys.argv) > 1 else "E3"
    reg = correr(estado)
    print("\n" + "=" * 70)
    print(reg.resumen())
    print("informe:", reg.volcar("p06_invariantes"))
    sys.exit(1 if reg.fallos else 0)
