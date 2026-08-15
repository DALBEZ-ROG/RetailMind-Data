/**
 * V1-V5 — El logotipo de marca en navegador REAL (Chrome headless vía puppeteer).
 *
 * Se entra con un usuario de verdad y se recorren 12 pantallas midiendo lo que
 * el navegador pinta de VERDAD (getBoundingClientRect / naturalWidth), no lo
 * que dice el CSS:
 *
 *   V1  el logotipo se ve nítido, a su proporción real y sin deformarse
 *   V2  el favicon carga y NO hay ni una petición 404 (el /favicon.ico se fue)
 *   V3  12 pantallas sin maquetación rota por el logotipo
 *   V4  ancho reducido (390 px): la cabecera degrada y nada se solapa
 *   V5  0 errores y 0 warnings nuevos en consola
 *
 * A DIFERENCIA de `verificar_pantallas.js`, aquí NO se filtra el ruido de
 * `favicon`: probar que ese 404 desapareció es justo el objeto de V2.
 *
 * Las claves llegan por ENTORNO (deuda C-4): si falta, el script se planta.
 *   export RETAILMIND_ADMIN_PASS='...'
 *   node retailmind/verificar_logotipo.js
 */
const path = require('path');
const { createRequire } = require('module');
const requireFront = createRequire(
  path.join(__dirname, '..', 'retailmind-frontend', 'package.json'));
const puppeteer = requireFront('puppeteer');

const APP = process.env.RETAILMIND_APP || 'http://localhost:4200';

function clave(v) {
  const x = process.env[v];
  if (!x) {
    console.error(`Falta la variable de entorno ${v}. Ver «Credenciales de desarrollo».`);
    process.exit(2);
  }
  return x;
}
const ADMIN = { u: 'admin@retailmind.com', p: clave('RETAILMIND_ADMIN_PASS') };

// La proporción del archivo fuente: 452 x 490.
const RAZON_FUENTE = 452 / 490;

// Las 12 pantallas de V3: operativas, informes, tableros, tienda y analítica.
const PANTALLAS = [
  '/inicio',
  '/operativo/productos',
  '/operativo/ventas/pedidos',
  '/operativo/compras/ordenes',
  '/operativo/inventario/kardex',
  '/operativo/informes/ventas',
  '/operativo/informes/gerencia',
  '/operativo/tableros/omnicanal',
  '/operativo/tableros/gobierno-dato',
  '/operativo/seguridad/permisos',
  '/operativo/marketing/cupones',
  '/shop'
];

const fallos = [];
function comprobar(ok, etiqueta, detalle) {
  console.log(`  ${ok ? 'OK  ' : 'FALLO'}  ${etiqueta}${detalle ? ' — ' + detalle : ''}`);
  if (!ok) fallos.push(`${etiqueta}${detalle ? ' — ' + detalle : ''}`);
}

/** Recolector de consola y de red por pantalla. */
function instrumentar(page) {
  const bolsa = { errores: [], warnings: [], fallidas: [] };
  page.on('console', m => {
    const t = m.text();
    if (m.type() === 'error') bolsa.errores.push(t);
    if (m.type() === 'warning') bolsa.warnings.push(t);
  });
  page.on('pageerror', e => bolsa.errores.push('pageerror: ' + e.message));
  page.on('requestfailed', r => bolsa.fallidas.push(`${r.failure().errorText} ${r.url()}`));
  page.on('response', r => {
    if (r.status() >= 400) bolsa.fallidas.push(`HTTP ${r.status()} ${r.url()}`);
  });
  return bolsa;
}

/** Mide una <img> tal como la pinta el navegador. */
async function medirImagen(page, selector) {
  return page.$eval(selector, el => {
    const r = el.getBoundingClientRect();
    const cs = getComputedStyle(el);
    return {
      alt: el.getAttribute('alt'),
      natural: [el.naturalWidth, el.naturalHeight],
      completa: el.complete && el.naturalWidth > 0,
      pintada: [+r.width.toFixed(2), +r.height.toFixed(2)],
      arriba: +r.top.toFixed(2),
      izquierda: +r.left.toFixed(2),
      derecha: +r.right.toFixed(2),
      visible: cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0
    };
  }).catch(() => null);
}

async function entrar(page, cred) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input[formcontrolname="username"]', { timeout: 30000 });
  await page.type('input[formcontrolname="username"]', cred.u);
  await page.type('input[formcontrolname="password"]', cred.p);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await new Promise(r => setTimeout(r, 1500));
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--window-size=1600,1000']
  });

  // ── V1a · el logotipo en la pantalla de inicio de sesión ────────────────────
  console.log('\n== V1a · Logotipo en el inicio de sesión ==');
  let page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });
  let bolsa = instrumentar(page);
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await new Promise(r => setTimeout(r, 800));

  const login = await medirImagen(page, 'img.login-logo');
  comprobar(!!login, 'el logotipo existe en el login');
  if (login) {
    comprobar(login.completa, 'la imagen carga',
      `natural ${login.natural[0]}x${login.natural[1]}`);
    comprobar(login.pintada[1] === 72, 'altura pintada = 72 px',
      `${login.pintada[0]}x${login.pintada[1]}`);
    const razon = login.pintada[0] / login.pintada[1];
    comprobar(Math.abs(razon - RAZON_FUENTE) < 0.02, 'sin deformación',
      `razón pintada ${razon.toFixed(4)} vs fuente ${RAZON_FUENTE.toFixed(4)}`);
    comprobar(login.natural[1] / login.pintada[1] >= 2, 'nítido en pantalla HiDPI',
      `${login.natural[1]}px de fuente para ${login.pintada[1]}px pintados ` +
      `(${(login.natural[1] / login.pintada[1]).toFixed(1)}x)`);
    comprobar(!!login.alt && login.alt.trim().length > 0, 'texto alternativo',
      `alt="${login.alt}"`);
  }
  // El icono genérico que sustituyó ya no debe quedar suelto
  const restos = await page.$$('.logo-icon-container');
  comprobar(restos.length === 0, 'la placa del icono genérico se retiró');

  // ── V2 + V5 en el login ─────────────────────────────────────────────────────
  const faviconLogin = bolsa.fallidas.filter(u => /favicon/i.test(u));
  comprobar(faviconLogin.length === 0, 'V2 · sin 404 de favicon en el login',
    faviconLogin.join(' | ') || 'ninguno');

  await page.close();

  // ── V1b + V2 + V3 + V5 · recorrido autenticado ──────────────────────────────
  console.log('\n== V1b/V2/V3/V5 · Recorrido de 12 pantallas (ADMIN, 1600x1000) ==');
  page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });
  bolsa = instrumentar(page);
  await entrar(page, ADMIN);

  let cabeceraMedida = null;
  for (const ruta of PANTALLAS) {
    const antesErr = bolsa.errores.length;
    const antesWarn = bolsa.warnings.length;
    const antesRed = bolsa.fallidas.length;

    await page.goto(`${APP}${ruta}`, { waitUntil: 'networkidle2', timeout: 60000 });
    await new Promise(r => setTimeout(r, 1200));

    const marca = await medirImagen(page, 'img.navbar-logo-mark');
    const geo = await page.evaluate(() => {
      const nav = document.querySelector('.navbar');
      const img = document.querySelector('img.navbar-logo-mark');
      const txt = document.querySelector('.navbar-logo-text');
      const acc = document.querySelector('.navbar-actions');
      if (!nav || !img) return null;
      const n = nav.getBoundingClientRect(), i = img.getBoundingClientRect();
      const a = acc ? acc.getBoundingClientRect() : null;
      const t = txt ? txt.getBoundingClientRect() : null;
      return {
        navAlto: +n.height.toFixed(2),
        dentro: i.top >= n.top - 0.5 && i.bottom <= n.bottom + 0.5,
        // ¿el logotipo pisa las acciones de la derecha o el nombre?
        solapaAcciones: a ? i.right > a.left + 0.5 : false,
        solapaTexto: t ? i.right > t.left + 0.5 : false,
        textoVisible: t ? t.width > 0 : false,
        scrollH: document.documentElement.scrollWidth,
        clientH: document.documentElement.clientWidth
      };
    });

    const nuevosErr = bolsa.errores.slice(antesErr);
    const nuevosWarn = bolsa.warnings.slice(antesWarn);
    const nuevosRed = bolsa.fallidas.slice(antesRed);
    const fav = nuevosRed.filter(u => /favicon/i.test(u));
    const logo = nuevosRed.filter(u => /ic_retailmind|apple-touch/i.test(u));

    const ok = !!marca && marca.completa && !!geo && geo.dentro
      && !geo.solapaAcciones && !geo.solapaTexto && geo.navAlto === 64
      && fav.length === 0 && logo.length === 0
      && nuevosErr.length === 0 && nuevosWarn.length === 0
      && geo.scrollH <= geo.clientH;

    comprobar(ok, `pantalla ${ruta}`,
      marca
        ? `logo ${marca.pintada[0]}x${marca.pintada[1]} · navbar ${geo.navAlto}px · ` +
          `err ${nuevosErr.length} · warn ${nuevosWarn.length} · 404 ${nuevosRed.length}`
        : 'sin cabecera con logotipo');
    if (nuevosErr.length) console.log('        errores: ' + nuevosErr.join(' | ').slice(0, 400));
    if (nuevosWarn.length) console.log('        warnings: ' + nuevosWarn.join(' | ').slice(0, 400));
    if (nuevosRed.length) console.log('        red: ' + nuevosRed.join(' | ').slice(0, 400));
    if (marca && !cabeceraMedida) cabeceraMedida = marca;
  }

  if (cabeceraMedida) {
    console.log('\n== V1b · Medidas del logotipo en la cabecera ==');
    comprobar(cabeceraMedida.pintada[1] === 32, 'altura pintada = 32 px',
      `${cabeceraMedida.pintada[0]}x${cabeceraMedida.pintada[1]}`);
    const razon = cabeceraMedida.pintada[0] / cabeceraMedida.pintada[1];
    comprobar(Math.abs(razon - RAZON_FUENTE) < 0.02, 'sin deformación',
      `razón pintada ${razon.toFixed(4)} vs fuente ${RAZON_FUENTE.toFixed(4)}`);
    comprobar(cabeceraMedida.natural[1] / cabeceraMedida.pintada[1] >= 2,
      'nítido en pantalla HiDPI',
      `${(cabeceraMedida.natural[1] / cabeceraMedida.pintada[1]).toFixed(1)}x`);
    comprobar(!!cabeceraMedida.alt, 'texto alternativo',
      `alt="${cabeceraMedida.alt}"`);
  }

  // ── V2 · el favicon se sirve de verdad ──────────────────────────────────────
  console.log('\n== V2 · El favicon responde 200 ==');
  for (const f of ['/assets/favicon-32.png', '/assets/apple-touch-icon.png',
                   '/assets/ic_retailmind.png']) {
    const r = await page.evaluate(async u => {
      const res = await fetch(u, { cache: 'no-store' });
      return { s: res.status, t: res.headers.get('content-type') };
    }, f);
    comprobar(r.s === 200 && /image\/png/.test(r.t || ''), `GET ${f}`, `HTTP ${r.s} ${r.t}`);
  }
  const refFavicon = await page.evaluate(() =>
    [...document.querySelectorAll('link[rel*="icon"]')].map(l => l.getAttribute('href')));
  comprobar(refFavicon.length > 0 && !refFavicon.some(h => /favicon\.ico/.test(h)),
    'index.html ya no referencia favicon.ico', refFavicon.join(', '));

  // ── V4 · ancho reducido ─────────────────────────────────────────────────────
  console.log('\n== V4 · Ancho reducido ==');
  for (const ancho of [1024, 768, 560, 390]) {
    await page.setViewport({ width: ancho, height: 900 });
    await page.goto(`${APP}/operativo/ventas/pedidos`,
                    { waitUntil: 'networkidle2', timeout: 60000 });
    await new Promise(r => setTimeout(r, 1000));
    const g = await page.evaluate(() => {
      const nav = document.querySelector('.navbar');
      const img = document.querySelector('img.navbar-logo-mark');
      const txt = document.querySelector('.navbar-logo-text');
      const acc = document.querySelector('.navbar-actions');
      const tog = document.querySelector('.sidebar-toggle');
      const i = img.getBoundingClientRect();
      const a = acc ? acc.getBoundingClientRect() : null;
      const t = tog.getBoundingClientRect();
      const w = txt ? txt.getBoundingClientRect() : null;
      const n = nav.getBoundingClientRect();
      return {
        logo: [+i.width.toFixed(1), +i.height.toFixed(1)],
        nombreVisible: w ? w.width > 0 : false,
        pisaAcciones: a ? i.right > a.left + 0.5 : false,
        pisaToggle: i.left < t.right - 0.5,
        desbordaNav: i.right > n.right + 0.5 || i.left < n.left - 0.5,
        scrollX: document.documentElement.scrollWidth >
                 document.documentElement.clientWidth
      };
    });
    comprobar(!g.pisaAcciones && !g.pisaToggle && !g.desbordaNav && !g.scrollX
              && g.logo[1] === 32,
      `viewport ${ancho}px`,
      `logo ${g.logo[0]}x${g.logo[1]} · nombre ${g.nombreVisible ? 'visible' : 'oculto'} · ` +
      `solapes: acciones=${g.pisaAcciones} toggle=${g.pisaToggle} desborde=${g.desbordaNav}`);
  }

  // ── V5 · recuento total ─────────────────────────────────────────────────────
  console.log('\n== V5 · Consola en todo el recorrido ==');
  comprobar(bolsa.errores.length === 0, 'errores de consola',
    bolsa.errores.slice(0, 3).join(' | ') || '0');
  comprobar(bolsa.warnings.length === 0, 'warnings de consola',
    bolsa.warnings.slice(0, 3).join(' | ') || '0');
  const red404 = bolsa.fallidas.filter(u => !/\/api\//.test(u));
  comprobar(red404.length === 0, 'peticiones de recurso fallidas',
    red404.slice(0, 5).join(' | ') || '0');

  await browser.close();

  console.log(`\n${'='.repeat(72)}`);
  if (fallos.length) {
    console.log(`RESULTADO: ${fallos.length} FALLO(S)`);
    fallos.forEach(f => console.log('  - ' + f));
    process.exit(1);
  }
  console.log('RESULTADO: TODO EN VERDE (V1 a V5)');
})();
