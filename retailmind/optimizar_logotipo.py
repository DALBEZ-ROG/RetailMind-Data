"""
Optimiza el logotipo de marca y deriva de él los iconos de pestaña.

POR QUÉ PESABA 158 kB. El archivo original era arte plano guardado con ruido de
export: **12.224 colores únicos** donde el ojo ve tres. Los cuatro colores más
frecuentes eran el MISMO índigo con ±1 de diferencia —(51,66,170), (51,66,169),
(52,67,170), (51,67,170)— y había 12.508 píxeles con alfa residual (1..8), es
decir invisibles pero ocupando sitio. PNG comprime prediciendo cada píxel desde
su vecino: un ruido de 1 bit por canal rompe la predicción en CADA píxel y
convierte una imagen de ~16 kB en una de 158 kB.

QUÉ SE HACE. Se sanea el alfa residual y se cuantiza a una paleta de 256
colores. Medido a los tamaños en que el navegador lo pinta de verdad (32 px de
cabecera y 72 px de login, a densidad 1x/2x/3x), la diferencia máxima de canal
frente al original es de 4 a 22 sobre 255, y el mapa de diferencia la confina al
CONTORNO antialiado: las masas de color salen más limpias que con un aplanado
de ruido, que reparte el error por toda la superficie.

Se descartó reducir las dimensiones: a 452x490 el maestro conserva holgura para
pantallas de densidad 3x (216 px) y el peso ya no es el problema.

ES IDEMPOTENTE: volver a cuantizar a 256 colores algo que ya tiene 256 o menos
no pierde nada, así que se puede re-ejecutar sin degradar el archivo.

    py -3 retailmind/optimizar_logotipo.py [--verificar]

`--verificar` no escribe: solo informa de qué haría.
"""
import os
import sys

from PIL import Image

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(RAIZ, 'retailmind-frontend', 'src', 'assets')

MAESTRO = os.path.join(ASSETS, 'ic_retailmind.png')
DERIVADOS = [
    ('favicon-32.png', 32),        # <link rel="icon"> de index.html
    ('apple-touch-icon.png', 180),  # atajo en pantalla de inicio iOS
]

# Alfa por debajo de este valor es basura del export: invisible, pero cuenta
# como color único y estorba a la compresión.
UMBRAL_ALFA = 8
# 256 entradas bastan: por debajo el error se dispara en los bordes sin ahorrar
# apenas peso (128 -> 15,0 kB, 64 -> 13,4 kB, frente a 16,0 kB de 256).
COLORES = 256


def sanear(im):
    """Alfa residual a cero y casi-opaco a opaco puro, sin tocar el color."""
    im = im.convert('RGBA')
    px = im.load()
    ancho, alto = im.size
    residuales = 0
    for y in range(alto):
        for x in range(ancho):
            r, g, b, a = px[x, y]
            if a <= UMBRAL_ALFA:
                if a != 0:
                    residuales += 1
                px[x, y] = (0, 0, 0, 0)
            elif a >= 250 and a != 255:
                px[x, y] = (r, g, b, 255)
    return im, residuales


def colores_unicos(im):
    datos = (im.get_flattened_data() if hasattr(im, 'get_flattened_data')
             else im.getdata())
    return len(set(datos))


def main():
    solo_ver = '--verificar' in sys.argv

    if not os.path.exists(MAESTRO):
        print(f'No existe {MAESTRO}')
        return 2

    antes = os.path.getsize(MAESTRO)
    original = Image.open(MAESTRO)
    print(f'maestro    : {original.size[0]}x{original.size[1]} {original.mode} · '
          f'{antes / 1024:.1f} kB · {colores_unicos(original.convert("RGBA"))} colores')

    limpio, residuales = sanear(original)
    print(f'saneado    : {residuales} píxeles de alfa residual llevados a 0')

    optimizado = limpio.quantize(colors=COLORES, method=Image.FASTOCTREE)

    if solo_ver:
        import io
        buf = io.BytesIO()
        optimizado.save(buf, 'PNG', optimize=True)
        print(f'--verificar: quedaría en {len(buf.getvalue()) / 1024:.1f} kB '
              f'(no se ha escrito nada)')
        return 0

    optimizado.save(MAESTRO, 'PNG', optimize=True)
    despues = os.path.getsize(MAESTRO)
    print(f'optimizado : {despues / 1024:.1f} kB '
          f'({despues / antes * 100:.1f} % del original, '
          f'-{(antes - despues) / 1024:.1f} kB)')

    # Los derivados se sacan del maestro SANEADO a resolución completa y se
    # reducen con LANCZOS; recortarlos desde el ya cuantizado apilaría dos
    # pérdidas sin necesidad.
    for nombre, lado in DERIVADOS:
        destino = os.path.join(ASSETS, nombre)
        lienzo = Image.new('RGBA', (lado, lado), (0, 0, 0, 0))
        razon = min(lado / limpio.width, lado / limpio.height)
        ancho, alto = round(limpio.width * razon), round(limpio.height * razon)
        lienzo.paste(limpio.resize((ancho, alto), Image.LANCZOS),
                     ((lado - ancho) // 2, (lado - alto) // 2))
        lienzo.quantize(colors=COLORES, method=Image.FASTOCTREE).save(
            destino, 'PNG', optimize=True)
        print(f'derivado   : {nombre} {lado}x{lado} · '
              f'{os.path.getsize(destino) / 1024:.1f} kB')

    total = sum(os.path.getsize(os.path.join(ASSETS, n))
                for n, _ in DERIVADOS) + despues
    print(f'\ntotal de la marca en assets/: {total / 1024:.1f} kB')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
