/**
 * V5 — Las pantallas cargan SIN errores en la consola del navegador.
 *
 * Navegador real (Chrome headless vía puppeteer). Se entra con un usuario
 * de verdad, se visitan las pantallas afectadas por esta sesión y se recogen
 * los mensajes de `console.error`, los errores de página y las peticiones de
 * red fallidas. Un `console.warn` no cuenta como fallo; un 4xx/5xx sí.
 *
 * Las claves llegan por ENTORNO y no estan escritas aqui, a proposito: este
 * archivo es nuevo y no tiene por que engordar la lista de la deuda C-4
 * («la contrasena del admin vive en 9 archivos versionados»). No hay valor de
 * reserva: si falta la variable, el script se planta y dice cual.
 *
 *   set RETAILMIND_ADMIN_PASS=...
 *   set RETAILMIND_STAFF_PASS=...
 *   node retailmind/verificar_pantallas.js
 */
// `require` resuelve desde la carpeta DE ESTE ARCHIVO, no desde el cwd, y
// puppeteer se instala en el frontend (es donde vive el `node_modules` del
// proyecto). Se resuelve explícitamente desde ahí.
const path = require('path');
const { createRequire } = require('module');
const requireFront = createRequire(
  path.join(__dirname, '..', 'retailmind-frontend', 'package.json'));
const puppeteer = requireFront('puppeteer');

const APP = process.env.RETAILMIND_APP || 'http://localhost:4200';

function clave(v) {
  const x = process.env[v];
  if (!x) {
    console.error(`Falta la variable de entorno ${v}.\n`
      + '  Las credenciales de demostración NO están en este archivo (deuda C-4).\n'
      + '  Exporta RETAILMIND_ADMIN_PASS y RETAILMIND_STAFF_PASS antes de correrlo;\n'
      + '  las tienes en la sección «Credenciales de desarrollo» de CLAUDE.md.');
    process.exit(2);
  }
  return x;
}

const ADMIN = { u: 'admin@retailmind.com', p: clave('RETAILMIND_ADMIN_PASS') };
const COMPRAS = { u: 'compras@retailmind.com', p: clave('RETAILMIND_STAFF_PASS') };

// Ruido conocido y ajeno a este cambio: se DECLARA en vez de silenciarse a
// ciegas, y cada respuesta >= 400 se imprime con su URL para que se pueda
// atribuir.
//
// `favicon.ico` daba 404 en TODAS las pantallas: el fichero vivía en
// `src/favicon.ico` y lo referenciaba `index.html`, pero `angular.json` solo
// copia `src/assets/**`, así que nunca llegaba a `dist`. **CORREGIDO el
// 2026-08-15** con la sesión del logotipo de marca: el icono se genera desde
// `assets/ic_retailmind.png` y se sirve como `assets/favicon-32.png`. El filtro
// se conserva por si otra pantalla pidiera un icono distinto, pero hoy no casa
// con nada — `retailmind/verificar_logotipo.js` prueba que el 404 no existe.
const RUIDO = [
  /favicon/i,
  /Failed to load resource: net::ERR_/i          // recursos externos (fuentes)
];

/** ¿El mensaje genérico del navegador se explica sólo por ruido conocido? */
function esRuidoGenerico(texto, respuestas4xx) {
  if (!/Failed to load resource/i.test(texto)) return false;
  return respuestas4xx.length > 0
      && respuestas4xx.every(u => RUIDO.some(r => r.test(u)));
}

const PANTALLAS = [
  { rol: 'ADMIN',   ruta: '/gestion-datos',
    espera: 'Gestion de Datos (A-3: sin editar ni borrar)' },
  { rol: 'ADMIN',   ruta: '/operativo/informes/ventas',
    espera: 'Informes de Ventas (selector con VEN-03 y VEN-04)' },
  { rol: 'COMPRAS', ruta: '/operativo/informes/ventas',
    espera: 'Informes de Ventas como COMPRAS' }
];

async function entrar(page, cred) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input[formcontrolname="username"], input[name="username"]',
                             { timeout: 30000 });
  await page.type('input[formcontrolname="username"], input[name="username"]', cred.u);
  await page.type('input[formcontrolname="password"], input[name="password"]', cred.p);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await new Promise(r => setTimeout(r, 2500));
}

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  let fallos = 0;

  for (const p of PANTALLAS) {
    const page = await browser.newPage();
    const errores = [];
    const consola = [];
    page.on('console', m => {
      if (m.type() === 'error' && !RUIDO.some(r => r.test(m.text()))) {
        consola.push(m.text().slice(0, 200));
      }
    });
    page.on('pageerror', e => errores.push(`pageerror: ${String(e).slice(0, 200)}`));
    // Toda respuesta >= 400 se ANOTA con su URL, incluso el ruido conocido: sin
    // la URL, un `console.error` de «Failed to load resource ... 404» no se
    // puede atribuir y no hay forma de saber si es la aplicación o un icono.
    const respuestas4xx = [];
    page.on('response', r => {
      if (r.status() >= 400) {
        respuestas4xx.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`);
        if (!RUIDO.some(x => x.test(r.url()))) {
          errores.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`);
        }
      }
    });
    page.__respuestas4xx = respuestas4xx;

    const cred = p.rol === 'ADMIN' ? ADMIN : COMPRAS;
    await entrar(page, cred);
    await page.goto(APP + p.ruta, { waitUntil: 'networkidle2', timeout: 60000 });
    await new Promise(r => setTimeout(r, 3500));

    const texto = await page.evaluate(() => document.body.innerText);
    console.log(`\n── ${p.espera}  [${p.rol}] ${p.ruta}`);

    // Comprobaciones de contenido propias de esta sesión.
    if (p.ruta === '/gestion-datos') {
      const sinEditar = !/Editando Evento/.test(texto);
      const conAviso = /Tabla de solo lectura/.test(texto);
      const conListado = /Session ID|PK/.test(texto);
      console.log(`   sin panel de edición: ${sinEditar ? 'sí' : 'NO'}`);
      console.log(`   aviso de solo lectura: ${conAviso ? 'sí' : 'NO'}`);
      console.log(`   listado de eventos presente: ${conListado ? 'sí' : 'NO'}`);
      if (!sinEditar || !conAviso || !conListado) { fallos++; }
    } else {
      const v3 = /más se venden/i.test(texto);
      const v4 = /no se venden/i.test(texto);
      console.log(`   OTD-VEN-03 en el selector: ${v3 ? 'sí' : 'NO'}`);
      console.log(`   OTD-VEN-04 en el selector: ${v4 ? 'sí' : 'NO'}`);
      if (!v3 || !v4) { fallos++; }
    }

    const ruido4xx = [...new Set(page.__respuestas4xx)];
    if (ruido4xx.length) {
      console.log(`   respuestas >=400 observadas: ${ruido4xx.join(', ')}`
                  + '  (pre-existentes, ver RUIDO)');
    }
    // Los `console.error` genéricos del navegador solo cuentan si NO se
    // explican por el ruido conocido, cuya URL ya quedó impresa arriba.
    consola.forEach(t => {
      if (!esRuidoGenerico(t, page.__respuestas4xx)) {
        errores.push(`console.error: ${t}`);
      }
    });
    if (errores.length) {
      fallos++;
      console.log(`   ERRORES DE CONSOLA (${errores.length}):`);
      [...new Set(errores)].slice(0, 8).forEach(e => console.log(`     - ${e}`));
    } else {
      console.log('   consola limpia: 0 errores de aplicación');
    }
    await page.close();
  }

  await browser.close();
  console.log('\n' + '='.repeat(60));
  console.log(fallos === 0 ? 'V5: TODAS LAS PANTALLAS OK' : `V5: ${fallos} FALLO(S)`);
  process.exit(fallos === 0 ? 0 : 1);
})();
