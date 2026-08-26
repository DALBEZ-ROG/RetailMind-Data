/**
 * p17_mejoras.js — La parte de INTERFAZ de las mejoras del 2026-08-25.
 *
 * Lo que no se puede medir desde la API va aquí, y son cuatro cosas:
 *
 *   1. **El alta pública avisa de que la cuenta ya está creada.** Nace al
 *      terminar el paso 2, pero hasta ahora nada se lo decía a quien la estaba
 *      creando: se pasaba de «Crear mi cuenta» a un formulario de dirección sin
 *      una sola señal de que lo suyo ya estaba hecho.
 *   2. **Los campos tienen tope de caracteres.** Un `maxlength` solo se puede
 *      comprobar escribiendo: desde fuera del navegador no existe. El caso que
 *      lo destapó es el número de tarjeta, que admitía tantos dígitos como
 *      cupieran porque su formateo colgaba de un `(input)` de plantilla —y ése
 *      corre ANTES de que `ngModel` escriba en el modelo, así que leía el valor
 *      de la tecla anterior—.
 *   3. **La pantalla de Existencias.** Que cargue, que enseñe sus indicadores y
 *      que el reparto por bodega se abra al pulsar una fila. Se recorre también
 *      con BODEGA, que es el rol que más la necesita y al que un JOIN a `marca`
 *      dejaba fuera con un 403 del motor.
 *   4. **La columna Proveedor del catálogo**, en la grilla y en las variantes.
 *
 * Las cifras y la seguridad de todo esto las mide `p17_mejoras.py`; aquí solo
 * se mira lo que hace el navegador.
 *
 * ESCRIBE, y lo deshace: crea UNA cuenta de cliente por el alta pública —es lo
 * que viene a probar— y la deja DESACTIVADA al terminar, igual que P16; y
 * agrega una línea al carrito de `maria.lopez` para poder abrir el checkout,
 * que borra al acabar. Ningún caso pulsa «Pagar».
 *
 *   export RETAILMIND_ADMIN_PASS='…'
 *   export RETAILMIND_STAFF_PASS='…'
 *   export RETAILMIND_CLIENTE_PASS='…'
 *   node pruebas/p17_mejoras.js
 */

const path = require('path');

const RAIZ = path.resolve(__dirname, '..');
const puppeteer = require(path.join(RAIZ, 'retailmind-frontend', 'node_modules', 'puppeteer'));

const WEB = (process.env.RETAILMIND_WEB || 'http://localhost:4200').replace(/\/$/, '');
const API = (process.env.RETAILMIND_API || 'http://localhost:8080').replace(/\/$/, '');
const CLAVE_ADMIN = process.env.RETAILMIND_ADMIN_PASS;
const CLAVE_STAFF = process.env.RETAILMIND_STAFF_PASS;
const CLAVE_CLIENTE = process.env.RETAILMIND_CLIENTE_PASS;
const CLIENTE = process.env.RETAILMIND_CLIENTE || 'maria.lopez@demo.com';

if (!CLAVE_ADMIN || !CLAVE_STAFF || !CLAVE_CLIENTE) {
  console.error('FALTAN RETAILMIND_ADMIN_PASS, RETAILMIND_STAFF_PASS y/o RETAILMIND_CLIENTE_PASS.\n'
    + '  Las credenciales de demostración no se escriben en el repo (deuda C-4).');
  process.exit(2);
}

/** La suite tiene que poder correr dos veces seguidas: correo y cédula sellados. */
const SELLO = new Date().toISOString().replace(/\D/g, '').slice(2, 14);
const NUEVO_CORREO = `p17.${SELLO}@demo.com`;
const NUEVA_CLAVE = 'PruebaP17.2026';
const NUEVA_CEDULA = SELLO.slice(-10);

let casos = [];
function caso(nombre, ok, detalle) {
  casos.push({ nombre, ok: !!ok, detalle: detalle || '' });
  console.log(`  [${ok ? 'OK  ' : 'FALLA'}] ${nombre}${detalle ? '  — ' + detalle : ''}`);
}
const pausa = ms => new Promise(r => setTimeout(r, ms));

// ── Utilidades ───────────────────────────────────────────────────────────────

async function tokenDe(usuario, clave) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: usuario, password: clave })
  });
  if (!r.ok) { return null; }
  return (await r.json()).token;
}

async function entrar(page, usuario, clave) {
  await page.goto(`${WEB}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input[formcontrolname="username"]', { timeout: 30000 });
  await page.type('input[formcontrolname="username"]', usuario);
  await page.type('input[formcontrolname="password"]', clave);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await pausa(1500);
}

async function irA(page, ruta, ms = 1500) {
  await page.goto(WEB + ruta, { waitUntil: 'networkidle2', timeout: 60000 });
  await pausa(ms);
}

/** Escribe sin pasar por el teclado, para partir siempre del mismo estado. */
async function vaciar(page, sel) {
  await page.evaluate(s => {
    const el = document.querySelector(s);
    const proto = el instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, sel);
}

/** Teclea de verdad: pasa por `keydown`, que es la primera capa del tope. */
async function teclear(page, sel, texto) {
  await page.focus(sel);
  await vaciar(page, sel);
  await page.focus(sel);
  await page.type(sel, texto, { delay: 4 });
  await pausa(120);
  return page.$eval(sel, el => el.value);
}

/**
 * Inserta SIN teclado (`Input.insertText`): se salta el `keydown` a propósito.
 * Es lo que prueba que el tope existe también en la capa del `input`, la que
 * recoge el pegado y el autocompletado. Con el mismo gesto para las dos, una
 * podría no existir y la suite seguiría en verde.
 */
async function insertar(page, sel, texto) {
  await page.focus(sel);
  await vaciar(page, sel);
  await page.focus(sel);
  for (const ch of texto) { await page.keyboard.sendCharacter(ch); }
  await pausa(150);
  return page.$eval(sel, el => el.value);
}

async function pulsar(page, patron) {
  const ok = await page.evaluate(p => {
    const re = new RegExp(p, 'i');
    const b = [...document.querySelectorAll('button')]
      .find(e => e.offsetParent && re.test(e.textContent));
    if (b) { b.click(); return true; }
    return false;
  }, patron.source);
  await pausa(1200);
  return ok;
}

/** Espera a que el registro llegue al paso pedido (ver p16: BCrypt tarda). */
async function esperarPaso(page, numero, ms = 15000) {
  const hasta = Date.now() + ms;
  while (Date.now() < hasta) {
    const donde = await page.evaluate(() => ({
      texto: document.querySelector('.reg-subtitle')?.textContent || '',
      error: document.querySelector('.reg-error')?.textContent?.trim() || '',
      listo: !!document.querySelector('.reg-listo')
    }));
    if (numero === 5 && donde.listo) { return { ok: true }; }
    if (new RegExp('Paso ' + numero).test(donde.texto)) { return { ok: true }; }
    if (donde.error) { return { ok: false, error: donde.error }; }
    await pausa(250);
  }
  return { ok: false, error: 'no llegó al paso ' + numero + ' a tiempo' };
}

// ── Recorrido ────────────────────────────────────────────────────────────────

(async () => {
  const errores = [];
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1600,1100']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1100 });
  page.on('pageerror', e => errores.push(String(e).slice(0, 200)));
  page.on('console', m => {
    if (m.type() === 'error' && !/favicon|fonts\.(googleapis|gstatic)/i.test(m.text())) {
      errores.push(m.text().slice(0, 200));
    }
  });

  // ══ 1 · Existencias, con ADMIN ════════════════════════════════════════════
  console.log('\n── 1 · Existencias (ADMIN)');
  await entrar(page, 'admin@retailmind.com', CLAVE_ADMIN);

  const enMenu = await page.evaluate(() =>
    !!document.querySelector('a[href="/operativo/inventario/existencias"]'));
  caso('Existencias tiene su entrada en la barra lateral', enMenu);

  await irA(page, '/operativo/inventario/existencias', 2500);
  const pantalla = await page.evaluate(() => ({
    titulo: document.querySelector('.section-header h2')?.textContent?.trim() || '',
    kpis: document.querySelectorAll('.ex-kpi').length,
    unidades: document.querySelectorAll('.ex-kpi-valor')[1]?.textContent?.trim() || '',
    filas: document.querySelectorAll('table tbody tr').length,
    alcance: document.querySelector('.ex-alcance')?.textContent?.trim() || '',
    total: document.querySelectorAll('.op-card h3')[1]?.textContent?.trim() || ''
  }));
  caso('La pantalla carga con su título', /existencias/i.test(pantalla.titulo), pantalla.titulo);
  caso('Enseña los cuatro indicadores', pantalla.kpis === 4, `${pantalla.kpis} tarjetas`);
  caso('Las unidades salen calculadas, no en blanco',
       /\d/.test(pantalla.unidades), `«${pantalla.unidades}»`);
  caso('La tabla trae filas', pantalla.filas > 0, `${pantalla.filas} filas`);
  caso('Dice sobre qué conjunto están calculados los indicadores',
       /contando/i.test(pantalla.alcance), pantalla.alcance);

  // Pulsar una fila abre el reparto por bodega: es la otra mitad de «cuánto
  // tengo» —dónde está—, y la que hace falta para transferir o ajustar.
  await page.evaluate(() => document.querySelector('table tbody tr')?.click());
  await pausa(1800);
  const desglose = await page.evaluate(() => {
    const tarjetas = [...document.querySelectorAll('.op-card h3')];
    const t = tarjetas.find(h => /reparto/i.test(h.textContent));
    return {
      abierto: !!t,
      filas: t ? t.closest('.op-card').querySelectorAll('table tbody tr').length : 0
    };
  });
  caso('Pulsar una fila abre el reparto por bodega', desglose.abierto,
       `${desglose.filas} bodegas`);

  // El filtro tiene que CAMBIAR el conjunto; si no, es decorativo.
  const antes = await page.evaluate(() =>
    document.querySelectorAll('.op-card h3')[1]?.textContent || '');
  await page.select('mat-select', '').catch(() => {});
  await page.evaluate(() => {
    const sel = [...document.querySelectorAll('mat-select')][1];
    sel?.click();
  });
  await pausa(700);
  await page.evaluate(() => {
    const op = [...document.querySelectorAll('mat-option')]
      .find(o => /sin existencias/i.test(o.textContent));
    op?.click();
  });
  await pausa(2200);
  const despues = await page.evaluate(() =>
    document.querySelectorAll('.op-card h3')[1]?.textContent || '');
  caso('El filtro de situación cambia el conjunto', antes !== despues,
       `${antes.trim()} → ${despues.trim()}`);

  // ══ 2 · Proveedor en el catálogo ══════════════════════════════════════════
  console.log('\n── 2 · Proveedor en el catálogo');
  await irA(page, '/operativo/productos', 2500);
  const grilla = await page.evaluate(() => {
    const th = [...document.querySelectorAll('table th')].map(e => e.textContent.trim());
    const col = th.indexOf('Proveedor');
    const celdas = col < 0 ? [] : [...document.querySelectorAll('table tbody tr')]
      .slice(0, 10).map(tr => tr.children[col]?.textContent?.trim() || '');
    return { cabeceras: th, col, celdas };
  });
  caso('La grilla del catálogo tiene columna «Proveedor»', grilla.col >= 0,
       grilla.cabeceras.join(' · '));
  caso('Al menos un producto muestra su proveedor',
       grilla.celdas.some(c => c && c !== '—'),
       grilla.celdas.filter(Boolean).slice(0, 3).join(' | '));

  // El detalle de variantes lo dice por SKU, que es el grano real del dato:
  // `producto_proveedor` es (proveedor, VARIANTE), no (proveedor, producto).
  await page.evaluate(() => {
    const filas = [...document.querySelectorAll('table tbody tr')];
    filas[0]?.click();
  });
  await pausa(2000);
  const variantes = await page.evaluate(() => {
    const t = [...document.querySelectorAll('.op-card h3')].find(h => /variantes de/i.test(h.textContent));
    if (!t) { return { hay: false }; }
    const tabla = t.closest('.op-card').querySelector('table');
    const th = [...tabla.querySelectorAll('th')].map(e => e.textContent.trim());
    const col = th.indexOf('Proveedor');
    return {
      hay: true, col,
      valores: col < 0 ? [] : [...tabla.querySelectorAll('tbody tr')]
        .map(tr => tr.children[col]?.textContent?.trim() || '')
    };
  });
  caso('El detalle de variantes también declara el proveedor',
       variantes.hay && variantes.col >= 0,
       variantes.hay ? `columna ${variantes.col}` : 'no se abrió el detalle');
  caso('Cada variante dice su proveedor o que no tiene',
       (variantes.valores || []).every(v => v.length > 0),
       (variantes.valores || []).slice(0, 3).join(' | '));

  // ══ 3 · Existencias con BODEGA ════════════════════════════════════════════
  //
  // Es el rol que más necesita esta pantalla y el que la destapó: con un JOIN a
  // `marca` recibía 403 del MOTOR —`grp_bodega` no tiene SELECT sobre esa
  // tabla— mientras funcionaba con los otros tres roles.
  console.log('\n── 3 · Existencias con BODEGA');
  const pageB = await browser.newPage();
  await pageB.setViewport({ width: 1600, height: 1100 });
  const erroresB = [];
  pageB.on('pageerror', e => erroresB.push(String(e).slice(0, 200)));
  await entrar(pageB, 'bodega@retailmind.com', CLAVE_STAFF);
  await irA(pageB, '/operativo/inventario/existencias', 2500);
  const conBodega = await pageB.evaluate(() => ({
    filas: document.querySelectorAll('table tbody tr').length,
    unidades: document.querySelectorAll('.ex-kpi-valor')[1]?.textContent?.trim() || '',
    // Ni un importe: BODEGA no lee dinero, y lo garantiza la CONSULTA.
    dinero: /\$/.test(document.querySelector('.op-page')?.textContent || ''),
    enMenu: !!document.querySelector('a[href="/operativo/inventario/existencias"]')
  }));
  caso('BODEGA ve Existencias en su barra lateral', conBodega.enMenu);
  caso('BODEGA abre la pantalla y ve filas', conBodega.filas > 0, `${conBodega.filas} filas`);
  caso('A BODEGA no le llega ni un importe', !conBodega.dinero);
  await pageB.close();

  // ══ 4 · Tope de caracteres ════════════════════════════════════════════════
  console.log('\n── 4 · Tope de caracteres');
  const pageC = await browser.newPage();
  await pageC.setViewport({ width: 1500, height: 1100 });
  const erroresC = [];
  pageC.on('pageerror', e => erroresC.push(String(e).slice(0, 200)));
  await entrar(pageC, CLIENTE, CLAVE_CLIENTE);

  // El checkout solo se pinta con el carrito lleno: se agrega una línea y se
  // quita al terminar. Ningún caso pulsa «Pagar».
  const tkCliente = await tokenDe(CLIENTE, CLAVE_CLIENTE);
  let varianteAnadida = null;
  if (tkCliente) {
    const cat = await (await fetch(`${API}/api/catalogo/productos?size=1`)).json();
    const v = (cat.items || cat.content || [])[0];
    varianteAnadida = v ? (v.varianteId ?? v.id) : null;
    if (varianteAnadida) {
      await fetch(`${API}/api/carrito/items`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkCliente },
        body: JSON.stringify({ varianteId: varianteAnadida, cantidad: 1 })
      });
    }
  }

  await irA(pageC, '/shop/checkout', 3000);
  const hayTarjeta = await pageC.$('input[appTexto="tarjeta"]');
  if (!hayTarjeta) {
    caso('El checkout pinta el formulario de tarjeta', false,
         'no se pudo dejar el carrito con una línea');
  } else {
    // 25 dígitos tecleados: una tarjeta son 16, agrupados de cuatro en cuatro.
    const numTeclado = await teclear(pageC, 'input[appTexto="tarjeta"]', '4111222233334444555566');
    caso('El número de tarjeta se corta en 16 dígitos al TECLEAR',
         numTeclado.replace(/\D/g, '').length === 16 && numTeclado.length === 19,
         `→ «${numTeclado}» (${numTeclado.replace(/\D/g, '').length} dígitos)`);

    // Y por la vía que NO pasa por el teclado (pegar, autocompletar).
    const numPegado = await insertar(pageC, 'input[appTexto="tarjeta"]', '4111222233334444555566');
    caso('El número de tarjeta se corta también SIN pasar por el teclado',
         numPegado.replace(/\D/g, '').length === 16,
         `→ «${numPegado}»`);

    caso('El número de tarjeta se agrupa de cuatro en cuatro',
         /^\d{4} \d{4} \d{4} \d{4}$/.test(numTeclado), `→ «${numTeclado}»`);

    const venc = await teclear(pageC, 'input[appTexto="vencimiento"]', '1299887766');
    caso('El vencimiento queda en MM/AA', venc === '12/99', `→ «${venc}»`);

    const cvv = await teclear(pageC, 'input[appTexto="digitos"][maxlength="4"]', '1234567');
    caso('El CVV no pasa de 4 dígitos', cvv.length === 4, `→ «${cvv}»`);
  }

  // Barrido genérico: TODO campo de texto declara un tope y lo respeta. Es lo
  // que da cobertura — enumerarlos a mano dejaría fuera el que se añada mañana.
  async function barrerTopes(p, pantalla) {
    const campos = await p.evaluate(() => {
      return [...document.querySelectorAll('[appTexto]')].map((el, i) => {
        el.setAttribute('data-p17', 'c' + i);
        const oculto = !el.offsetParent && getComputedStyle(el).position !== 'fixed';
        return {
          sel: '[data-p17="c' + i + '"]',
          perfil: el.getAttribute('appTexto'),
          max: Number(el.getAttribute('maxlength') || 0),
          salta: oculto || el.disabled || el.readOnly
        };
      });
    });
    const utiles = campos.filter(c => !c.salta);
    if (!utiles.length) {
      caso(`${pantalla}: hay campos de texto que barrer`, false, 'no se pintó ninguno');
      return;
    }
    const sinTope = utiles.filter(c => !c.max || c.max > 2000);
    caso(`${pantalla}: los ${utiles.length} campos de texto declaran un tope`,
         sinTope.length === 0,
         sinTope.map(c => c.perfil).join(', ') || 'todos con maxlength');

    const largo = 'Ab1 '.repeat(700);          // 2.800 caracteres
    const pasados = [];
    for (const c of utiles) {
      const v = await insertar(p, c.sel, largo);
      if (v.length > c.max) { pasados.push(`${c.perfil}:${v.length}>${c.max}`); }
    }
    caso(`${pantalla}: ningún campo admite más de su tope`, pasados.length === 0,
         pasados.slice(0, 4).join(' | ') || `${utiles.length} campos comprobados`);
  }

  await barrerTopes(pageC, 'Checkout');

  // Y los NÚMEROS: sin tope de dígitos, un `type="number"` acepta veinte cifras
  // y el error llega del motor, hablando de desbordamiento y sin decir en qué
  // casilla está.
  const pageN = await browser.newPage();
  await pageN.setViewport({ width: 1500, height: 1100 });
  pageN.on('pageerror', e => errores.push(String(e).slice(0, 200)));
  await entrar(pageN, 'admin@retailmind.com', CLAVE_ADMIN);
  await irA(pageN, '/operativo/inventario/ajustes', 3000);
  const numericos = await pageN.evaluate(() => {
    return [...document.querySelectorAll('input[appNumero]')].map((el, i) => {
      el.setAttribute('data-p17n', 'n' + i);
      const oculto = !el.offsetParent && getComputedStyle(el).position !== 'fixed';
      return {
        sel: '[data-p17n="n' + i + '"]',
        perfil: el.getAttribute('appNumero'),
        max: el.max || null,
        salta: oculto || el.disabled || el.readOnly
      };
    }).filter(c => !c.salta);
  });
  if (!numericos.length) {
    caso('Ajustes: hay campos numéricos que barrer', false, 'no se pintó ninguno');
  } else {
    const excedidos = [];
    for (const c of numericos) {
      const v = await teclear(pageN, c.sel, '123456789012345');
      const enteros = String(v).replace('-', '').split('.')[0].replace(/\D/g, '').length;
      const tope = c.max ? String(Math.floor(Math.abs(Number(c.max)))).length : 9;
      if (enteros > tope) { excedidos.push(`${c.perfil}: ${enteros} dígitos > ${tope}`); }
    }
    caso('Ningún campo numérico admite más dígitos de los que caben',
         excedidos.length === 0,
         excedidos.join(' | ') || `${numericos.length} campos comprobados`);
  }
  await pageN.close();

  // ══ 5 · El alta avisa de que la cuenta ya está creada ═════════════════════
  console.log('\n── 5 · El alta avisa de que la cuenta ya existe');
  const pageR = await browser.newPage();
  await pageR.setViewport({ width: 1400, height: 1100 });
  const erroresR = [];
  pageR.on('pageerror', e => erroresR.push(String(e).slice(0, 200)));
  // El localStorage es POR ORIGEN y lo comparten todas las pestañas, así que
  // ésta hereda la sesión del admin y /registro le enseña el aviso de «ya hay
  // una sesión abierta» en lugar del formulario. Se suelta antes de mirar.
  await irA(pageR, '/login', 1200);
  await pageR.evaluate(() => localStorage.clear());
  await irA(pageR, '/registro', 1800);

  await teclear(pageR, '.reg-paso input[placeholder="María"]', 'Prueba');
  await teclear(pageR, '.reg-paso input[placeholder="López"]', 'Diecisiete');
  await pulsar(pageR, /continuar/);

  const alPaso2 = await esperarPaso(pageR, 2);
  caso('Se llega al paso de acceso', alPaso2.ok, alPaso2.error || '');
  if (alPaso2.ok) {
    await teclear(pageR, 'input[placeholder="tucorreo@ejemplo.com"]', NUEVO_CORREO);
    await teclear(pageR, 'input[placeholder="Al menos 8 caracteres"]', NUEVA_CLAVE);
    await teclear(pageR, 'input[placeholder="La misma de arriba"]', NUEVA_CLAVE);
    await pulsar(pageR, /crear mi cuenta/);

    const alPaso3 = await esperarPaso(pageR, 3);
    caso('La cuenta se crea al terminar el paso 2', alPaso3.ok, alPaso3.error || '');

    if (alPaso3.ok) {
      await pausa(900);
      const aviso = await pageR.evaluate(() => {
        const s = document.querySelector('.reg-creada');
        return {
          hay: !!s,
          texto: s?.textContent?.replace(/\s+/g, ' ').trim() || '',
          correo: s?.querySelector('strong')?.textContent?.trim() || '',
          salida: !!s?.querySelector('.creada-salir'),
          vivo: s?.getAttribute('role') === 'status'
        };
      });
      caso('El paso 3 avisa de que la cuenta YA está creada', aviso.hay);
      caso('El aviso lo dice con esas palabras',
           /cuenta ya está creada/i.test(aviso.texto), aviso.texto.slice(0, 90));
      caso('El aviso dice que lo que queda es opcional',
           /opcional/i.test(aviso.texto));
      caso('El aviso nombra el correo con el que se inició sesión',
           aviso.correo === NUEVO_CORREO, aviso.correo);
      caso('El aviso ofrece irse a la tienda sin completar nada', aviso.salida);
      caso('El aviso se anuncia al lector de pantalla', aviso.vivo);

      // Y sigue estando en el paso 4, que también es opcional.
      await pulsar(pageR, /omitir por ahora/);
      const alPaso4 = await esperarPaso(pageR, 4);
      const aviso4 = await pageR.evaluate(() =>
        document.querySelector('.reg-creada')?.textContent?.replace(/\s+/g, ' ').trim() || '');
      caso('Se llega al paso de intereses', alPaso4.ok, alPaso4.error || '');
      caso('El aviso sigue puesto en el paso 4', /cuenta ya está creada/i.test(aviso4));

      // Y desaparece cuando el registro termina: ahí ya no informa de nada.
      await pulsar(pageR, /omitir por ahora/);
      await pausa(1500);
      const alFinal = await pageR.evaluate(() => ({
        listo: !!document.querySelector('.reg-listo'),
        aviso: !!document.querySelector('.reg-creada')
      }));
      caso('Al terminar, el aviso deja paso a la pantalla de «listo»',
           !alFinal.aviso, alFinal.listo ? 'con la pantalla final puesta' : '');
    }
  }
  await pageR.close();

  // ══ Limpieza ══════════════════════════════════════════════════════════════
  console.log('\n── Limpieza');
  if (tkCliente && varianteAnadida) {
    const r = await fetch(`${API}/api/carrito/items/${varianteAnadida}`, {
      method: 'DELETE', headers: { Authorization: 'Bearer ' + tkCliente }
    });
    caso('La línea que se agregó al carrito se retira', r.ok, `HTTP ${r.status}`);
  }

  const tkAdmin = await tokenDe('admin@retailmind.com', CLAVE_ADMIN);
  let bajas = 0;
  if (tkAdmin) {
    const lista = await (await fetch(`${API}/api/auth/usuarios`,
      { headers: { Authorization: 'Bearer ' + tkAdmin } })).json();
    for (const u of lista) {
      if (!String(u.username || '').startsWith('p17.')) { continue; }
      const r = await fetch(`${API}/api/auth/usuarios/${u.id}/activo`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkAdmin },
        body: JSON.stringify({ activo: false })
      });
      if (r.ok) { bajas++; }
    }
  }
  caso('La cuenta creada por la prueba queda dada de baja', bajas >= 1,
       `${bajas} desactivada(s)`);

  // ══ Consola ═══════════════════════════════════════════════════════════════
  console.log('\n── Consola del navegador');
  const todos = [...new Set([...errores, ...erroresC, ...erroresR])];
  caso('Sin errores de aplicación en todo el recorrido', todos.length === 0,
       todos.slice(0, 5).join(' | '));

  await browser.close();

  const fallos = casos.filter(c => !c.ok);
  console.log('\n' + '='.repeat(70));
  console.log(`P17 · MEJORAS DE INTERFAZ: ${casos.length - fallos.length}/${casos.length} casos en verde`);
  if (fallos.length) {
    console.log('\nFALLOS:');
    fallos.forEach(f => console.log(`  · ${f.nombre}${f.detalle ? '  — ' + f.detalle : ''}`));
  }
  process.exit(fallos.length ? 1 : 0);
})();
