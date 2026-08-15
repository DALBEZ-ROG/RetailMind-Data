/**
 * V5-V7 — Recorrido de las 33 pantallas operativas EN UNA SOLA SESIÓN, SIN
 * RECARGAR, más los 7 tableros, el panorama y los informes compuestos (V6).
 *
 * La navegación se hace pulsando los enlaces del menú lateral (`routerLink`),
 * no con `page.goto`: recargar la página vaciaría el montón del navegador entre
 * pantalla y pantalla y la prueba dejaría de medir lo que importa —que la
 * sesión aguanta acumulando—. Se recogen los `console.error`, los `warning`,
 * las respuestas >= 400 y el montón de JS al principio y al final.
 *
 *   set RETAILMIND_ADMIN_PASS=...
 *   node retailmind/verificar_recorrido.js
 */
const path = require('path');
const { createRequire } = require('module');
const requireFront = createRequire(
  path.join(__dirname, '..', 'retailmind-frontend', 'package.json'));
const puppeteer = requireFront('puppeteer');

const APP = process.env.RETAILMIND_APP || 'http://localhost:4200';
const CLAVE = process.env.RETAILMIND_ADMIN_PASS;
if (!CLAVE) {
  console.error('Falta RETAILMIND_ADMIN_PASS.');
  process.exit(2);
}

const RUIDO = [/favicon/i, /Failed to load resource: net::ERR_/i];

/** V5 — las 33 pantallas operativas del back-office que abre ADMIN. */
const OPERATIVAS = [
  '/operativo/productos', '/operativo/catalogo/marcas', '/operativo/catalogo/categorias',
  '/operativo/compras/ordenes', '/operativo/compras/recepciones',
  '/operativo/compras/devoluciones-proveedor', '/operativo/compras/proveedores',
  '/operativo/compras/facturas',
  '/operativo/inventario/transferencias', '/operativo/inventario/ajustes',
  '/operativo/inventario/kardex',
  '/operativo/ventas/pedidos', '/operativo/ventas/facturas', '/operativo/ventas/preparacion',
  '/operativo/ventas/despachos', '/operativo/ventas/devoluciones',
  '/operativo/gerencia/metas',
  '/operativo/marketing/cupones', '/operativo/marketing/promociones',
  '/operativo/marketing/campanas', '/operativo/marketing/banners',
  '/operativo/marketing/newsletter',
  '/operativo/soporte/tickets', '/operativo/soporte/categorias', '/operativo/soporte/faq',
  '/operativo/resenas', '/operativo/resenas/preguntas',
  '/operativo/horarios', '/operativo/seguridad/accesos', '/operativo/seguridad/permisos',
  '/operativo/informes/ventas', '/operativo/informes/inventario', '/operativo/informes/compras'
];

/** V6 — los 7 tableros, el panorama y los 3 departamentos de informes restantes. */
const ESTRATEGICAS = [
  '/operativo/panorama',
  '/operativo/tableros/omnicanal', '/operativo/tableros/rentabilidad',
  '/operativo/tableros/cliente-posventa', '/operativo/tableros/operacion',
  '/operativo/tableros/costo-operacion', '/operativo/tableros/abastecimiento',
  '/operativo/tableros/gobierno-dato',
  '/operativo/informes/logistica', '/operativo/informes/soporte',
  '/operativo/informes/gerencia'
];

async function entrar(page) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 90000 });
  await page.waitForSelector('input[formcontrolname="username"], input[name="username"]',
                             { timeout: 30000 });
  await page.type('input[formcontrolname="username"], input[name="username"]',
                  'admin@retailmind.com');
  await page.type('input[formcontrolname="password"], input[name="password"]', CLAVE);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 90000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await new Promise(r => setTimeout(r, 2500));
}

/** Montón de JS del documento, en MiB. */
async function heap(page) {
  const m = await page.evaluate(() => (performance).memory
    ? (performance).memory.usedJSHeapSize : null);
  return m == null ? null : (m / 1048576).toFixed(1);
}

/** Navega PULSANDO el enlace del menú: nada de recargar la página. */
async function irA(page, ruta) {
  const ok = await page.evaluate(r => {
    const a = document.querySelector(`a[href="${r}"]`);
    if (!a) return false;
    a.click();
    return true;
  }, ruta);
  if (!ok) return { ok: false };
  const t0 = Date.now();
  await page.waitForFunction(r => location.pathname === r,
                             { timeout: 120000, polling: 100 }, ruta).catch(() => {});
  // Se espera a que la pantalla tenga contenido de verdad: una tabla con filas,
  // un bloque de tablero o un estado vacío declarado.
  await page.waitForFunction(
    () => document.querySelectorAll('table tbody tr td').length > 0
       || document.querySelectorAll('.empty-state, .kpi-card, .bloque, svg').length > 0,
    { timeout: 120000, polling: 150 }).catch(() => {});
  await new Promise(r => setTimeout(r, 600));
  return { ok: true, ms: Date.now() - t0 };
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1500, height: 1000 });

  const problemas = [];
  const cuatroXX = [];
  page.on('console', m => {
    const t = m.text();
    if (RUIDO.some(r => r.test(t))) return;
    if (m.type() === 'error' && !/Failed to load resource/i.test(t)) {
      problemas.push(`console.error @${location0}: ${t.slice(0, 180)}`);
    }
    if (m.type() === 'warning') {
      problemas.push(`console.warn @${location0}: ${t.slice(0, 180)}`);
    }
  });
  page.on('pageerror', e => problemas.push(`pageerror @${location0}: ${String(e).slice(0, 180)}`));
  page.on('response', r => {
    if (r.status() >= 400) {
      const u = `HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`;
      cuatroXX.push(u);
      if (!RUIDO.some(x => x.test(r.url()))) { problemas.push(`${u} @${location0}`); }
    }
  });

  let location0 = '(login)';
  await entrar(page);
  const heapInicial = await heap(page);
  console.log(`montón tras entrar: ${heapInicial} MiB\n`);

  const t0 = Date.now();
  const medidas = [];
  for (const grupo of [['V5 · 33 pantallas operativas', OPERATIVAS],
                       ['V6 · tableros, panorama e informes compuestos', ESTRATEGICAS]]) {
    console.log(`\n── ${grupo[0]}`);
    for (const ruta of grupo[1]) {
      location0 = ruta;
      const r = await irA(page, ruta);
      if (!r.ok) {
        console.log(`  ${ruta.padEnd(46)} SIN ENLACE EN EL MENÚ`);
        problemas.push(`sin enlace: ${ruta}`);
        continue;
      }
      const url = page.url().replace(/^https?:\/\/[^/]+/, '');
      const bien = url === ruta;
      medidas.push({ ruta, ms: r.ms });
      console.log(`  ${ruta.padEnd(46)} ${String(r.ms).padStart(6)} ms  `
        + (bien ? '' : `LLEGÓ A ${url}`));
      if (!bien) { problemas.push(`no navegó a ${ruta} (quedó en ${url})`); }
    }
  }
  const total = Date.now() - t0;
  const heapFinal = await heap(page);
  const recargas = await page.evaluate(() => performance.getEntriesByType('navigation').length);

  console.log(`\n${'='.repeat(70)}`);
  console.log(`pantallas recorridas: ${medidas.length}  ·  tiempo total: `
    + `${(total / 1000).toFixed(1)} s  ·  media ${Math.round(total / medidas.length)} ms`);
  const lenta = medidas.slice().sort((a, b) => b.ms - a.ms)[0];
  console.log(`la más lenta: ${lenta.ruta} (${lenta.ms} ms)`);
  console.log(`montón: ${heapInicial} MiB al entrar → ${heapFinal} MiB al terminar`);
  console.log(`navegaciones de documento (recargas): ${recargas}  `
    + `[1 = solo la carga inicial, o sea NINGUNA recarga durante el recorrido]`);
  if (cuatroXX.length) {
    console.log(`respuestas >=400: ${[...new Set(cuatroXX)].join(', ')}`);
  }
  const unicos = [...new Set(problemas)];
  console.log(unicos.length
    ? `\nPROBLEMAS (${unicos.length}):\n  - ` + unicos.slice(0, 20).join('\n  - ')
    : '\n0 errores y 0 warnings de aplicación en todo el recorrido');
  await browser.close();
  process.exit(unicos.length ? 1 : 0);
})();
