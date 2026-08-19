/**
 * p11_interfaz.js — Interfaz (suite P11).
 *
 * Recorre las rutas de Angular con Chrome headless y comprueba TRES cosas que
 * ninguna prueba de API puede ver:
 *
 *   1. que la pantalla CARGUE sin errores de aplicación en consola;
 *   2. que no se escape a la vista un `NaN`, un `undefined`, un `[object
 *      Object]` ni un `null` crudo — los síntomas de la familia V-a/V-b del
 *      plan (división por cero y agregados sobre conjunto vacío);
 *   3. que el ESTADO VACÍO sea explícito. Una tabla sin filas y sin mensaje no
 *      es una pantalla vacía: es una pantalla que parece rota.
 *
 * Corre contra el estado que se le indique. Contra E0 (puerto 4200 apuntando al
 * backend 8082) es donde tiene sentido el punto 3; contra E3 comprueba el 1 y
 * el 2 con volumen real.
 *
 * Uso:
 *   node pruebas/p11_interfaz.js            # E3, frontend en 4200
 *   RETAILMIND_WEB=http://localhost:4200 RETAILMIND_ESTADO=E0 node pruebas/p11_interfaz.js
 *
 * Requiere: npm i --no-save puppeteer  (ya instalado en retailmind-frontend)
 */

const path = require('path');
const fs = require('fs');

const RAIZ = path.resolve(__dirname, '..');
const puppeteer = require(path.join(RAIZ, 'retailmind-frontend', 'node_modules', 'puppeteer'));

const WEB = (process.env.RETAILMIND_WEB || 'http://localhost:4200').replace(/\/$/, '');
const ESTADO = process.env.RETAILMIND_ESTADO || 'E3';
const USUARIO = process.env.RETAILMIND_USER || 'admin@retailmind.com';
const CLAVE = process.env.RETAILMIND_ADMIN_PASS;

if (!CLAVE) {
  console.error('FALTA RETAILMIND_ADMIN_PASS. Las credenciales no se escriben en el repo (deuda C-4).');
  process.exit(2);
}

/** Rutas de ADMIN, que es el rol que ve más pantallas. */
const RUTAS = [
  '/inicio',
  '/operativo/productos',
  '/operativo/catalogo/marcas',
  '/operativo/catalogo/categorias',
  '/operativo/compras/ordenes',
  '/operativo/compras/recepciones',
  '/operativo/compras/facturas',
  '/operativo/compras/devoluciones-proveedor',
  '/operativo/compras/proveedores',
  '/operativo/inventario/transferencias',
  '/operativo/inventario/ajustes',
  '/operativo/inventario/kardex',
  '/operativo/ventas/pedidos',
  '/operativo/ventas/facturas',
  '/operativo/ventas/preparacion',
  '/operativo/ventas/despachos',
  '/operativo/ventas/devoluciones',
  '/operativo/gerencia/metas',
  '/operativo/informes/ventas',
  '/operativo/informes/inventario',
  '/operativo/informes/compras',
  '/operativo/informes/logistica',
  '/operativo/informes/soporte',
  '/operativo/informes/gerencia',
  '/operativo/tableros/omnicanal',
  '/operativo/tableros/rentabilidad',
  '/operativo/tableros/operacion',
  '/operativo/tableros/gobierno-dato',
  '/operativo/seguridad/permisos',
  '/admin-usuarios',
  '/dashboard',
  '/gestion-datos',
];

/**
 * Ruido de consola que NO es un defecto de la aplicación.
 *
 * La lista va ENUMERADA y comentada a propósito: un filtro amplio («ignora
 * todo lo que diga warning») convierte esta prueba en decorativa, que es el
 * modo más habitual de que una suite de interfaz pase siempre.
 */
const RUIDO = [
  /favicon/i,                        // el navegador lo pide solo
  /Download the Angular DevTools/i,  // aviso de desarrollo de Angular
  /\[webpack-dev-server\]/i,         // recarga en caliente
  /sockjs-node|ws:\/\/localhost/i,   // canal del dev-server
  /Failed to load resource.*404.*favicon/i,
];

const TEXTO_ENFERMO = [
  { patron: /\bNaN\b/, nombre: 'NaN' },
  { patron: /\bundefined\b/, nombre: 'undefined' },
  { patron: /\[object Object\]/, nombre: '[object Object]' },
  { patron: /\bInfinity\b/, nombre: 'Infinity' },
];

const resultados = [];

function anotar(caso, titulo, ok, observado, esperado, severidad = 'S3') {
  resultados.push({ caso, titulo, estado_datos: ESTADO,
                    veredicto: ok ? 'PASA' : 'FALLA',
                    severidad: ok ? '' : severidad, observado, esperado });
  const marca = ok ? 'ok   ' : 'FALLA';
  console.log(`[${marca}] ${caso}  ${titulo}`);
  if (!ok) {
    console.log(`         observado: ${observado}`);
    console.log(`         esperado : ${esperado}`);
  }
}

(async () => {
  const navegador = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });

  try {
    const pagina = await navegador.newPage();
    await pagina.setViewport({ width: 1440, height: 900 });

    // ── Probar la MISMA interfaz contra OTRO backend, sin reconstruirla ─────
    //
    // El frontend pide `/api/...` a su propio origen y nginx lo proxea al
    // backend de demo. Para recorrer las pantallas contra E0 haría falta una
    // segunda imagen del frontend… o esto: reescribir el destino de las
    // peticiones en el navegador. Así se prueba EL MISMO bundle compilado —que
    // es justo lo que hay que probar— contra la base vacía.
    const DESVIO = process.env.RETAILMIND_API_DESVIO;   // p. ej. http://localhost:8082
    if (DESVIO) {
      await pagina.setRequestInterception(true);
      pagina.on('request', (req) => {
        const url = req.url();
        const i = url.indexOf('/api/');
        if (i < 0 || url.startsWith(DESVIO)) { req.continue(); return; }
        // `continue({url})` a secas PIERDE el cuerpo y el método: el POST del
        // login llegaba vacío y el navegador se quedaba en /login sin decir por
        // qué. Hay que reenviar método, cabeceras y cuerpo explícitamente.
        // Las cabeceras van TAL CUAL. Poner `origin: undefined` para «quitarla»
        // no la quita: deja la clave con valor indefinido y Chrome descarta el
        // juego entero, con lo que el login se quedaba en /login sin un solo
        // error visible. Si hiciera falta suprimir una, se construye el objeto
        // sin ella, nunca asignándole undefined.
        req.continue({
          url: DESVIO.replace(/\/$/, '') + url.slice(i),
          method: req.method(),
          postData: req.postData(),
          headers: req.headers(),
        });
      });
      console.log(`>>> las llamadas /api/ se desvían a ${DESVIO}`);
    }

    // ── Sesión: se entra por la pantalla de login, como una persona ────────
    await pagina.goto(`${WEB}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
    await pagina.type('input[type="email"], input[formcontrolname="username"], input[name="username"]', USUARIO);
    await pagina.type('input[type="password"]', CLAVE);
    await Promise.all([
      pagina.click('button[type="submit"]'),
      pagina.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    ]);
    await new Promise(r => setTimeout(r, 2500));

    const entro = !pagina.url().includes('/login');
    anotar('P11-001', 'Se puede iniciar sesión desde la pantalla', entro,
           `url tras el login: ${pagina.url()}`, 'una url distinta de /login', 'S1');
    if (!entro) throw new Error('sin sesión no se puede recorrer nada');

    for (const ruta of RUTAS) {
      const errores = [];
      const enConsola = (msg) => {
        if (msg.type() !== 'error') return;
        const texto = msg.text();
        if (RUIDO.some(r => r.test(texto))) return;
        errores.push(texto.slice(0, 180));
      };
      const enFallo = (err) => errores.push(`pageerror: ${String(err).slice(0, 180)}`);

      pagina.on('console', enConsola);
      pagina.on('pageerror', enFallo);

      let cuerpo = '';
      try {
        await pagina.goto(`${WEB}${ruta}`, { waitUntil: 'networkidle2', timeout: 90000 });
        // Las pantallas de informe piden sus datos tras pintar: se espera a que
        // el spinner desaparezca, con tope. Sin esta espera se mide el esqueleto.
        await new Promise(r => setTimeout(r, 3500));
        cuerpo = await pagina.evaluate(() => document.body.innerText || '');
      } catch (e) {
        errores.push(`navegación: ${String(e).slice(0, 160)}`);
      }

      pagina.off('console', enConsola);
      pagina.off('pageerror', enFallo);

      anotar('P11-001', `Carga sin errores de consola: ${ruta}`,
             errores.length === 0,
             errores.length ? errores.slice(0, 3).join(' | ') : 'consola limpia',
             '0 errores de aplicación', 'S2');

      const enfermos = TEXTO_ENFERMO.filter(t => t.patron.test(cuerpo)).map(t => t.nombre);
      anotar('P11-002', `Sin valores rotos a la vista: ${ruta}`,
             enfermos.length === 0,
             enfermos.length ? `aparece ${enfermos.join(', ')} en la página` : 'ninguno',
             'ni NaN, ni undefined, ni [object Object], ni Infinity', 'S2');

      // Estado vacío explícito: solo se exige donde de verdad no hay datos.
      if (ESTADO === 'E0' || ESTADO === 'E1') {
        const vacio = /sin datos|no hay|ningún|ninguna|vacío|aún no|todavía no|0 resultados|sin resultados/i.test(cuerpo);
        const conFilas = cuerpo.length > 2000;
        anotar('P11-002', `Estado vacío explícito: ${ruta}`,
               vacio || conFilas,
               vacio ? 'declara el vacío' :
                 (conFilas ? 'trae contenido' : `pantalla casi vacía (${cuerpo.length} car.) y sin mensaje`),
               'un mensaje que diga que aún no hay datos', 'S3');
      }
    }
  } catch (e) {
    anotar('P11-000', 'La suite pudo ejecutarse', false, String(e).slice(0, 200),
           'sin excepciones', 'S1');
  } finally {
    await navegador.close();
  }

  const fallos = resultados.filter(r => r.veredicto !== 'PASA');
  const sello = new Date().toISOString().replace(/[-:]/g, '').replace(/\..+/, 'Z');
  const destino = path.join(RAIZ, 'pruebas', 'informes', `p11_interfaz_${ESTADO}_${sello}.json`);
  fs.mkdirSync(path.dirname(destino), { recursive: true });
  fs.writeFileSync(destino, JSON.stringify(resultados, null, 2), 'utf8');

  console.log('\n' + '='.repeat(70));
  console.log(`${resultados.length} casos · PASA=${resultados.length - fallos.length}` +
              (fallos.length ? ` · FALLA=${fallos.length}` : ''));
  console.log('informe:', destino);
  process.exit(fallos.length ? 1 : 0);
})();
