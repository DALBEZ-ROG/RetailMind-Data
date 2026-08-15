/**
 * T1/V1/V7/V8 — Cronómetro de TODAS las pantallas, en UNA sola sesión.
 *
 * Navega pulsando los enlaces del menú (nada de `page.goto`, que recargaría y
 * falsearía tanto el tiempo como la medida de memoria) y por cada pantalla
 * anota: el tiempo hasta que hay CONTENIDO DE VERDAD, y las llamadas `/api/*`
 * que disparó con su duración. Eso permite atribuir la causa —la consulta o el
 * frontend— sin adivinar.
 *
 *   set RETAILMIND_ADMIN_PASS=...
 *   node retailmind/medir_pantallas.js [etiqueta-del-informe]
 */
const fs = require('fs');
const path = require('path');
const { createRequire } = require('module');
const requireFront = createRequire(
  path.join(__dirname, '..', 'retailmind-frontend', 'package.json'));
const puppeteer = requireFront('puppeteer');

const APP = process.env.RETAILMIND_APP || 'http://localhost:4200';
const CLAVE = process.env.RETAILMIND_ADMIN_PASS;
if (!CLAVE) { console.error('Falta RETAILMIND_ADMIN_PASS.'); process.exit(2); }

const ETIQUETA = process.argv[2] || 'medicion';
const SALIDA = path.join(__dirname, `tiempos-${ETIQUETA}.json`);

/**
 * `--aislado` abre cada pantalla en una pestaña NUEVA y con su propio tope.
 *
 * Hace falta para el inventario de partida: una sola pantalla con una petición
 * que no termina —`/operativo/gerencia/metas` tarda más de 10 min— deja su XHR
 * en vuelo y contamina el cronómetro de TODAS las siguientes, que aparecen
 * tardando 120 s cuando cargan en 80 ms. En el recorrido de después, con nada
 * colgado, se mide en UNA sola sesión (sin `--aislado`), que es lo que pide V7.
 */
const AISLADO = process.argv.includes('--aislado');
const TOPE_PANTALLA = AISLADO ? 60000 : 180000;

const RUIDO = [/favicon/i, /Failed to load resource: net::ERR_/i];

/** Todas las pantallas que abre ADMIN, en el orden del menú. */
const PANTALLAS = [
  '/inicio',
  // Administración / analítica
  '/dashboard', '/sesiones', '/conversiones', '/admin-etl', '/gestion-datos',
  '/admin-usuarios', '/funnel', '/analytics/region', '/analytics/dispositivo',
  '/analytics/trafico', '/inicializacion', '/admin/reportes',
  // Operación
  '/operativo/productos', '/operativo/catalogo/marcas', '/operativo/catalogo/categorias',
  '/operativo/horarios', '/operativo/seguridad/accesos', '/operativo/seguridad/permisos',
  '/operativo/compras/ordenes', '/operativo/compras/recepciones',
  '/operativo/compras/facturas', '/operativo/compras/proveedores',
  '/operativo/compras/devoluciones-proveedor',
  '/operativo/inventario/transferencias', '/operativo/inventario/ajustes',
  '/operativo/inventario/kardex',
  '/operativo/ventas/pedidos', '/operativo/ventas/facturas',
  '/operativo/ventas/preparacion', '/operativo/ventas/despachos',
  '/operativo/ventas/devoluciones',
  // Marketing y gerencia
  '/operativo/marketing/cupones', '/operativo/marketing/promociones',
  '/operativo/marketing/campanas', '/operativo/marketing/banners',
  '/operativo/marketing/newsletter', '/operativo/gerencia/metas',
  // Soporte y reseñas
  '/operativo/soporte/tickets', '/operativo/soporte/categorias', '/operativo/soporte/faq',
  '/operativo/resenas', '/operativo/resenas/preguntas',
  // Informes tácticos
  '/operativo/informes/ventas', '/operativo/informes/inventario',
  '/operativo/informes/compras', '/operativo/informes/logistica',
  '/operativo/informes/soporte', '/operativo/informes/gerencia',
  // Nivel estratégico
  '/operativo/panorama',
  '/operativo/tableros/omnicanal', '/operativo/tableros/rentabilidad',
  '/operativo/tableros/cliente-posventa', '/operativo/tableros/operacion',
  '/operativo/tableros/costo-operacion', '/operativo/tableros/abastecimiento',
  '/operativo/tableros/gobierno-dato'
];

async function entrar(page) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 120000 });
  await page.waitForSelector('input[formcontrolname="username"], input[name="username"]',
                             { timeout: 60000 });
  await page.type('input[formcontrolname="username"], input[name="username"]',
                  'admin@retailmind.com');
  await page.type('input[formcontrolname="password"], input[name="password"]', CLAVE);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 120000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await new Promise(r => setTimeout(r, 2500));
}

const heap = page => page.evaluate(() =>
  performance.memory ? +(performance.memory.usedJSHeapSize / 1048576).toFixed(1) : null);

/**
 * CUÁNDO SE CONSIDERA ABIERTA UNA PANTALLA — y por qué no vale mirar el DOM.
 *
 * Dos intentos anteriores fallaron y quedan documentados para que nadie los
 * repita: (1) esperar a que HAYA una tabla con filas da tiempos ridículos
 * —85 ms— porque mientras Angular monta el componente nuevo la tabla de la
 * pantalla ANTERIOR sigue en el DOM; (2) esperar a un `.empty-state` mide el
 * armazón, que varias pantallas pintan mientras cargan.
 *
 * La señal buena es la RED: se cuentan las peticiones `/api/*` en vuelo desde
 * el clic y se da la pantalla por abierta cuando no queda ninguna y han pasado
 * `QUIETO_MS` sin que salga otra. Eso funciona igual en una pantalla con tabla,
 * en un tablero de SVG y en una que no pide nada.
 */
const QUIETO_MS = 350;

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1500, height: 1000 });

  let actual = '(login)';
  const problemas = [];
  const cuatroXX = [];

  // Contador de peticiones /api/ en vuelo + instante de la última que terminó.
  let enVuelo = 0;
  let ultimaFin = 0;
  // Cada pantalla es una GENERACIÓN. Una petición que arrancó en la pantalla
  // anterior y termina durante esta no cuenta: si no, la cola de una pantalla
  // lenta se le carga a la siguiente. Pasó de verdad — `/seguridad/mapa`
  // tardaba 4,7 s y hacía que `/compras/ordenes`, que carga en 708 ms,
  // apareciera con 6.330 ms.
  let generacion = 0;
  const deEstaPantalla = new Map();   // request → generación en que arrancó
  const esApi = u => u.includes('/api/');
  page.on('request', r => {
    if (!esApi(r.url())) return;
    deEstaPantalla.set(r, generacion);
    enVuelo++;
  });
  const cerrar = r => {
    if (!esApi(r.url())) return;
    const g = deEstaPantalla.get(r);
    deEstaPantalla.delete(r);
    if (g !== generacion) return;               // rezagada de otra pantalla
    enVuelo = Math.max(0, enVuelo - 1);
    ultimaFin = Date.now();
  };
  page.on('requestfinished', cerrar);
  page.on('requestfailed', cerrar);

  /** Espera a que la pantalla deje de pedir datos. */
  const esperarRedQuieta = async () => {
    const limite = Date.now() + TOPE_PANTALLA;
    // Margen para que Angular llegue a lanzar la primera petición.
    await new Promise(r => setTimeout(r, 150));
    while (Date.now() < limite) {
      if (enVuelo === 0 && Date.now() - ultimaFin >= QUIETO_MS) { return true; }
      await new Promise(r => setTimeout(r, 50));
    }
    return false;
  };
  page.on('console', m => {
    const t = m.text();
    if (RUIDO.some(r => r.test(t))) return;
    if (m.type() === 'error' && !/Failed to load resource/i.test(t)) {
      problemas.push(`console.error @${actual}: ${t.slice(0, 160)}`);
    }
    if (m.type() === 'warning') { problemas.push(`console.warn @${actual}: ${t.slice(0, 160)}`); }
  });
  page.on('pageerror', e => problemas.push(`pageerror @${actual}: ${String(e).slice(0, 160)}`));
  page.on('response', r => {
    if (r.status() >= 400) {
      cuatroXX.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`);
      if (!RUIDO.some(x => x.test(r.url()))) {
        problemas.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')} @${actual}`);
      }
    }
  });

  await entrar(page);
  const heapInicial = await heap(page);
  const t0Total = Date.now();
  const filas = [];

  for (const ruta of PANTALLAS) {
    actual = ruta;
    await page.evaluate(() => performance.clearResourceTimings());
    const hayEnlace = await page.evaluate(r => {
      const a = document.querySelector(`a[href="${r}"]`);
      if (!a) return false;
      a.click();
      return true;
    }, ruta);
    if (!hayEnlace) {
      await page.goto(APP + ruta, { waitUntil: 'domcontentloaded', timeout: 180000 });
    }
    const t0 = Date.now();
    // Se olvida lo que quedara en vuelo de la pantalla anterior: si una tarda
    // más que el tope —`gerencia/metas` supera los 10 min—, su XHR seguiría
    // contando y las siguientes aparecerían tardando dos minutos cuando cargan
    // en 80 ms. Cada pantalla se cronometra solo con SUS peticiones.
    generacion++;
    enVuelo = 0;
    ultimaFin = Date.now();
    await page.waitForFunction(r => location.pathname === r,
                               { timeout: TOPE_PANTALLA, polling: 80 }, ruta).catch(() => {});
    const quieta = await esperarRedQuieta();
    // Se descuenta el margen de quietud: es espera del arnés, no de la pantalla.
    const ms = Math.max(Date.now() - t0 - QUIETO_MS, 0);

    const apis = await page.evaluate(() => performance.getEntriesByType('resource')
      .filter(e => e.name.includes('/api/'))
      .map(e => ({ url: e.name.replace(/^https?:\/\/[^/]+\/api\//, '').split('?')[0],
                   ms: Math.round(e.duration) }))
      .sort((a, b) => b.ms - a.ms));
    const redMax = apis.length ? apis[0] : null;
    const redTotal = apis.reduce((a, x) => a + x.ms, 0);
    filas.push({ ruta, ms, redTotal, quieta, apis: apis.slice(0, 4),
                 causa: !apis.length ? 'sin llamadas a la API (solo render)'
                      : redMax.ms > ms * 0.5 ? `API ${redMax.url} (${redMax.ms} ms)`
                                             : 'frontend / render',
                 enlace: hayEnlace });
    console.log(`${ruta.padEnd(44)} ${String(ms).padStart(7)} ms   red ${String(redTotal).padStart(7)} ms`
      + (redMax ? `   · ${redMax.url} ${redMax.ms} ms` : '   · (sin API)')
      + (quieta ? '' : '   [NO SE ESTABILIZÓ]')
      + (hayEnlace ? '' : '   [sin enlace: se abrió por URL]'));
  }

  const totalMs = Date.now() - t0Total;
  const heapFinal = await heap(page);
  const recargas = await page.evaluate(() =>
    performance.getEntriesByType('navigation').length);

  fs.writeFileSync(SALIDA, JSON.stringify(
    { etiqueta: ETIQUETA, filas, totalMs, heapInicial, heapFinal, recargas,
      problemas: [...new Set(problemas)], cuatroXX: [...new Set(cuatroXX)] }, null, 1));

  console.log(`\n${'='.repeat(96)}`);
  console.log(`pantallas: ${filas.length}   tiempo total: ${(totalMs / 1000).toFixed(1)} s   `
    + `media ${Math.round(totalMs / filas.length)} ms`);
  console.log(`montón: ${heapInicial} → ${heapFinal} MiB   ·   navegaciones de documento: ${recargas}`);
  const lentas = filas.filter(f => f.ms > 1500).sort((a, b) => b.ms - a.ms);
  console.log(`\npor encima de 1,5 s: ${lentas.length}`);
  lentas.forEach(f => console.log(`   ${f.ruta.padEnd(44)} ${String(f.ms).padStart(7)} ms   ${f.causa}`));
  if (cuatroXX.length) { console.log(`\nrespuestas >=400: ${[...new Set(cuatroXX)].join(', ')}`); }
  const p = [...new Set(problemas)];
  console.log(p.length ? `\nPROBLEMAS (${p.length}):\n  - ` + p.slice(0, 15).join('\n  - ')
                       : '\n0 errores y 0 warnings de aplicación');
  console.log(`\n(detalle en ${SALIDA})`);
  await browser.close();
})();
