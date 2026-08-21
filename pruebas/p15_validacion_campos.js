/**
 * p15_validacion_campos.js — Validación de los campos escribibles (suite P15).
 *
 * Comprueba, TECLEANDO DE VERDAD en el navegador, que ningún campo de la
 * aplicación acepta lo que no le corresponde: que un campo de número solo
 * admite números, que un teléfono no admite letras, que un correo no admite
 * espacios y que ningún campo se traga `<` ni `>`.
 *
 * Tres razones para que esto sea una prueba de NAVEGADOR y no de API:
 *
 *   1. `<input type="number">` **no impide escribir basura por sí solo**. El
 *      navegador acepta `e`, `E`, `+` y `-` en cualquier posición, y cuando lo
 *      tecleado no se puede leer como número devuelve **cadena vacía** con
 *      `validity.badInput` en true. Desde fuera del navegador ese estado no
 *      existe: el campo «está vacío» y no hay nada que medir.
 *   2. La primera capa de defensa es `keydown`, o sea la TECLA. Solo se puede
 *      comprobar tecleando; `page.type` manda eventos de teclado reales por CDP,
 *      así que un `preventDefault` que no funcione se ve aquí y en ningún otro
 *      sitio.
 *   3. La segunda capa —lo que entra sin pasar por el teclado: pegar, arrastrar,
 *      autocompletar— se prueba con `keyboard.sendCharacter`, que usa
 *      `Input.insertText` y **se salta el `keydown` a propósito**. Si las dos
 *      capas se probaran con el mismo gesto, la segunda podría no existir y la
 *      suite seguiría en verde.
 *
 * El oráculo NO es la tabla de perfiles del frontend: es una lista de caracteres
 * escrita aquí, a mano y aparte. Contrastar la implementación consigo misma
 * daría verde con las dos mitades equivocadas.
 *
 *   export RETAILMIND_ADMIN_PASS='…'      # la del admin
 *   export RETAILMIND_CLIENTE_PASS='…'    # la de los clientes demo
 *   node pruebas/p15_validacion_campos.js
 *   RETAILMIND_WEB=http://localhost:4200 node pruebas/p15_validacion_campos.js
 *
 * Requiere puppeteer, que ya está en `retailmind-frontend/node_modules`.
 */

const path = require('path');

const RAIZ = path.resolve(__dirname, '..');
const puppeteer = require(path.join(RAIZ, 'retailmind-frontend', 'node_modules', 'puppeteer'));

const WEB = (process.env.RETAILMIND_WEB || 'http://localhost:4200').replace(/\/$/, '');
const ADMIN = process.env.RETAILMIND_USER || 'admin@retailmind.com';
const CLAVE_ADMIN = process.env.RETAILMIND_ADMIN_PASS;
const CLIENTE = process.env.RETAILMIND_CLIENTE || 'maria.lopez@demo.com';
const CLAVE_CLIENTE = process.env.RETAILMIND_CLIENTE_PASS;

if (!CLAVE_ADMIN || !CLAVE_CLIENTE) {
  console.error('FALTAN RETAILMIND_ADMIN_PASS y/o RETAILMIND_CLIENTE_PASS.\n'
    + '  Las credenciales de demostración no se escriben en el repo (deuda C-4).\n'
    + '  Están en la sección «Credenciales de desarrollo» de CLAUDE.md.');
  process.exit(2);
}

// ── Oráculo ──────────────────────────────────────────────────────────────────

/**
 * Lo que NUNCA puede quedar escrito en un campo de cada clase, expresado como
 * la expresión que NO debe casar con el valor final.
 *
 * Está escrito al revés que la implementación (allí se enumera lo prohibido por
 * perfil; aquí, la forma que el valor tiene que tener) para que un error de
 * transcripción en una de las dos no se cancele con el de la otra.
 */
const FORMA = {
  nombre:       /^[A-Za-z0-9À-ÖØ-öø-ſ .,'&()/-]*$/,
  libre:        /^[^<>]*$/,
  alfanumerico: /^[A-Za-z0-9 _-]*$/,
  codigo:       /^[A-Z0-9_-]*$/,
  sku:          /^[A-Z0-9._-]*$/,
  slug:         /^[a-z0-9-]*$/,
  telefono:     /^[0-9+() -]*$/,
  digitos:      /^[0-9]*$/,
  ruc:          /^[0-9]*$/,
  postal:       /^[A-Z0-9-]*$/,
  numeroCasa:   /^[A-Za-z0-9 -]*$/,
  email:        /^[a-z0-9@._+-]*$/,
  url:          /^[A-Za-z0-9:/?#@!$&*+,;=._~%-]*$/,
  referencia:   /^[A-Za-z0-9 ._-]*$/
};

/** Un número y nada más: signo opcional, dígitos, y a lo sumo un punto. */
const FORMA_NUMERO = /^-?\d*(\.\d+)?$/;

/**
 * Lo que se teclea para intentar romper un campo. Lleva a propósito las cuatro
 * familias que aparecen en un formulario real mal usado: marcado (`<script>`),
 * comillas, signos de puntuación de teclado y notación científica.
 */
const SUCIO_TEXTO = 'Ab1 <script>alert(1)</script> ' + String.fromCharCode(34)
  + "'" + String.fromCharCode(96) + '#$%^&*|' + String.fromCharCode(92) + '{}[]~;:?=+';
const SUCIO_NUMERO = '12e5-3.75abc!@#' + String.fromCharCode(34);

let casos = [];

function caso(nombre, ok, detalle) {
  casos.push({ nombre, ok: !!ok, detalle: detalle || '' });
  console.log(`  [${ok ? 'OK  ' : 'FALLA'}] ${nombre}${detalle ? '  — ' + detalle : ''}`);
}

const pausa = ms => new Promise(r => setTimeout(r, ms));

// ── Utilidades de página ─────────────────────────────────────────────────────

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

async function irA(page, ruta) {
  await page.goto(WEB + ruta, { waitUntil: 'networkidle2', timeout: 60000 });
  await pausa(1200);
}

/** Vacía el campo sin pasar por el teclado, para partir siempre de lo mismo. */
async function vaciar(page, sel) {
  await page.evaluate(s => {
    const el = document.querySelector(s);
    const proto = el instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, sel);
}

/** Teclea (capa 1: pasa por `keydown`). */
async function teclear(page, sel, texto) {
  await page.focus(sel);
  await vaciar(page, sel);
  await page.focus(sel);
  await page.type(sel, texto, { delay: 5 });
  await page.evaluate(s => document.querySelector(s).blur(), sel);
  await pausa(120);
  return page.$eval(sel, el => el.value);
}

/** Inserta sin teclado (capa 2: `Input.insertText`, se salta el `keydown`). */
async function insertar(page, sel, texto) {
  await page.focus(sel);
  await vaciar(page, sel);
  await page.focus(sel);
  for (const ch of texto) { await page.keyboard.sendCharacter(ch); }
  await page.evaluate(s => document.querySelector(s).blur(), sel);
  await pausa(120);
  return page.$eval(sel, el => el.value);
}

/** El aviso que pinta la directiva bajo el campo, si lo hay. */
async function avisoDe(page, sel) {
  return page.evaluate(s => {
    const el = document.querySelector(s);
    const campo = el.closest('.mat-mdc-form-field') || el.parentElement;
    return campo?.querySelector('.rm-campo-error')?.textContent?.trim() || '';
  }, sel);
}

// ── Barrido genérico de una pantalla ─────────────────────────────────────────

/**
 * Recorre TODOS los campos validados que haya pintados y comprueba la forma del
 * valor tras teclear basura. Es lo que da cobertura: enumerar los campos a mano
 * dejaría fuera justo el que se añada mañana.
 */
async function barrer(page, pantalla) {
  const campos = await page.evaluate(() => {
    const vivos = [...document.querySelectorAll('[appTexto], [appNumero]')];
    return vivos.map((el, i) => {
      el.setAttribute('data-p15', 'c' + i);
      const oculto = !el.offsetParent && getComputedStyle(el).position !== 'fixed';
      return {
        sel: '[data-p15="c' + i + '"]',
        clase: el.getAttribute('appTexto') || null,
        numero: el.hasAttribute('appNumero') ? el.getAttribute('appNumero') : null,
        etiqueta: el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim()
          || el.getAttribute('placeholder') || el.getAttribute('aria-label') || '(sin etiqueta)',
        min: el.min || null,
        max: el.max || null,
        salta: oculto || el.disabled || el.readOnly
      };
    });
  });

  const utiles = campos.filter(c => !c.salta);
  if (!utiles.length) {
    caso(`${pantalla}: hay campos validados que barrer`, false, 'no se pintó ninguno');
    return;
  }

  const malText = [], malNum = [], malPeg = [];

  for (const c of utiles) {
    if (c.numero) {
      const tecleado = await teclear(page, c.sel, SUCIO_NUMERO);
      const pegado = await insertar(page, c.sel, SUCIO_NUMERO);
      for (const [via, v, bolsa] of [['teclado', tecleado, malNum], ['inserción', pegado, malPeg]]) {
        let motivo = null;
        if (!FORMA_NUMERO.test(v)) { motivo = 'no es un número'; }
        else if (c.numero === 'entero' && v.includes('.')) { motivo = 'admitió decimales'; }
        else if (v !== '' && c.min != null && Number(v) < Number(c.min)) { motivo = 'por debajo del mínimo'; }
        else if (v !== '' && c.max != null && Number(v) > Number(c.max)) { motivo = 'por encima del máximo'; }
        if (motivo) { bolsa.push(`${c.etiqueta} [${via}] «${v}» ${motivo}`); }
      }
    } else {
      const forma = FORMA[c.clase] || FORMA.libre;
      const tecleado = await teclear(page, c.sel, SUCIO_TEXTO);
      if (!forma.test(tecleado)) { malText.push(`${c.etiqueta} (${c.clase}) → «${tecleado}»`); }
      const pegado = await insertar(page, c.sel, SUCIO_TEXTO);
      if (!forma.test(pegado)) { malPeg.push(`${c.etiqueta} (${c.clase}) → «${pegado}»`); }
    }
    await vaciar(page, c.sel);
  }

  const nT = utiles.filter(c => !c.numero).length;
  const nN = utiles.length - nT;
  if (nT) {
    caso(`${pantalla}: los ${nT} campos de texto rechazan lo que no les toca`,
         malText.length === 0, malText.slice(0, 3).join(' | '));
  }
  if (nN) {
    caso(`${pantalla}: los ${nN} campos de número solo admiten números`,
         malNum.length === 0, malNum.slice(0, 3).join(' | '));
  }
  caso(`${pantalla}: lo que entra SIN teclado (pegar/autocompletar) también se limpia`,
       malPeg.length === 0, malPeg.slice(0, 3).join(' | '));
}

/** Abre el formulario de alta de las pantallas con la barra de acciones estándar. */
async function pulsarNuevo(page) {
  const ok = await page.evaluate(() => {
    const b = document.querySelector('app-acciones-registro button.btn-aplicar');
    if (!b) { return false; }
    b.click();
    return true;
  });
  await pausa(1400);
  return ok;
}

// ── Recorrido ────────────────────────────────────────────────────────────────

/** Pantallas de ADMIN con diálogo de alta detrás de la barra de acciones. */
const CON_DIALOGO = [
  ['Productos · alta', '/operativo/productos'],
  ['Marcas · alta', '/operativo/catalogo/marcas'],
  ['Categorías · alta', '/operativo/catalogo/categorias'],
  ['Cupones · alta', '/operativo/marketing/cupones'],
  ['Promociones · alta', '/operativo/marketing/promociones'],
  ['Campañas · alta', '/operativo/marketing/campanas'],
  ['Banners · alta', '/operativo/marketing/banners'],
  ['Metas · alta', '/operativo/gerencia/metas'],
  ['Usuarios · alta', '/admin-usuarios'],
  ['FAQ · alta', '/operativo/soporte/faq'],
  ['Categorías de ticket · alta', '/operativo/soporte/categorias']
];

/** Pulsa el primer botón visible cuyo rótulo case con el patrón. */
async function pulsarPorTexto(page, patron) {
  const ok = await page.evaluate(re => {
    const b = [...document.querySelectorAll('button')]
      .find(e => e.offsetParent && new RegExp(re, 'i').test(e.textContent));
    if (!b) { return false; }
    b.click();
    return true;
  }, patron.source);
  await pausa(1000);
  return ok;
}

/**
 * Pantallas cuyos campos están a la vista, sin diálogo. Tres de ellas llevan
 * `abrir`: sus formularios están plegados o cuelgan de una fila seleccionada, y
 * sin abrirlos la pantalla no pinta un solo campo — un barrido limitado a lo
 * visible daría verde por no haber mirado.
 */
const SIN_DIALOGO = [
  ['Ajustes de inventario', '/operativo/inventario/ajustes', null],
  ['Transferencias', '/operativo/inventario/transferencias', null],
  ['Órdenes de compra', '/operativo/compras/ordenes',
    page => pulsarPorTexto(page, /nueva orden/)],
  ['Proveedores', '/operativo/compras/proveedores', async page => {
    // El formulario de condiciones cuelga del proveedor SELECCIONADO, y la
    // selección se hace con el botón «Ver productos que ofrece» de la fila: en
    // esta pantalla la fila entera NO es pulsable.
    const hay = await page.evaluate(() => {
      const b = document.querySelector('table tbody tr button[mattooltip*="productos que ofrece"]')
        || document.querySelector('table tbody tr button');
      if (!b) { return false; }
      b.click();
      return true;
    });
    if (!hay) { return false; }
    await pausa(1600);
    return pulsarPorTexto(page, /asociar producto/);
  }],
  ['Tickets de soporte', '/operativo/soporte/tickets',
    page => pulsarPorTexto(page, /nuevo ticket/)],
  ['Permisos del motor', '/operativo/seguridad/permisos', async page => {
    // Los tres filtros viven en pestañas distintas; se barre la de «Permisos»,
    // que es la única con el campo suelto y sin depender de un rol elegido.
    const ok = await page.evaluate(() => {
      const t = [...document.querySelectorAll('.mat-mdc-tab')]
        .find(e => /^permisos$/i.test(e.textContent.trim()));
      if (!t) { return false; }
      t.click();
      return true;
    });
    await pausa(1800);
    return ok;
  }],
  ['Accesos', '/operativo/seguridad/accesos', null]
];

/** Las cinco pestañas de la red logística, cada una con su formulario. */
const PESTANAS_RED = ['Bodegas', 'Transportistas', 'Métodos', 'Zonas', 'Tarifas'];

async function abrirPestanaRed(page, etiqueta) {
  const ok = await page.evaluate(t => {
    const tab = [...document.querySelectorAll('.mat-mdc-tab')]
      .find(e => e.textContent.trim().toLowerCase().startsWith(t.toLowerCase().slice(0, 6)));
    if (!tab) { return false; }
    tab.click();
    return true;
  }, etiqueta);
  await pausa(900);
  if (!ok) { return false; }
  const alta = await page.evaluate(() => {
    const b = [...document.querySelectorAll('button')]
      .find(e => e.offsetParent && /nuev/i.test(e.textContent));
    if (!b) { return false; }
    b.click();
    return true;
  });
  await pausa(900);
  return alta;
}

// ── Casos con expectativa ESCRITA, no solo de forma ──────────────────────────

/**
 * Los de arriba comprueban que no entra lo prohibido; éstos comprueban que lo
 * que SÍ entra es exactamente lo esperado, y que un valor legítimo NO se toca.
 * Sin esta segunda mitad, una directiva que borrase el campo entero pasaría el
 * barrido con sobresaliente.
 */
async function casosConcretos(page) {
  console.log('\n── Casos con valor esperado (red logística)');
  await irA(page, '/operativo/red');

  // Bodegas: código y teléfono
  if (await abrirPestanaRed(page, 'Bodegas')) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appTexto], input[appNumero]').forEach((el, i) => {
        el.setAttribute('data-p15b', 'b' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15b="b' + i + '"]'; }
      });
      return m;
    });

    if (sels['Código']) {
      const v = await teclear(page, sels['Código'], 'bod ega-01!*');
      caso('Bodega · Código pasa a mayúsculas y suelta espacios y signos',
           v === 'BODEGA-01', `«bod ega-01!*» → «${v}»`);
    }
    if (sels['Teléfono']) {
      const v = await teclear(page, sels['Teléfono'], '09ab9-123 4567xyz');
      caso('Bodega · Teléfono se queda solo con dígitos y signos de formato',
           v === '099-123 4567', `«09ab9-123 4567xyz» → «${v}»`);
      const corto = await teclear(page, sels['Teléfono'], '0991');
      const aviso = await avisoDe(page, sels['Teléfono']);
      caso('Bodega · Un teléfono demasiado corto se AVISA, no se borra',
           corto === '0991' && /7 d/i.test(aviso), `«${corto}» + «${aviso}»`);
    }
    if (sels['Nombre']) {
      const v = await teclear(page, sels['Nombre'], 'Bodega Central Norte');
      caso('Bodega · Un nombre legítimo NO se toca', v === 'Bodega Central Norte', `«${v}»`);
    }
  }

  // Transportistas: RUC, correo y sitio web
  if (await abrirPestanaRed(page, 'Transportistas')) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appTexto]').forEach((el, i) => {
        el.setAttribute('data-p15t', 't' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15t="t' + i + '"]'; }
      });
      return m;
    });

    if (sels['RUC']) {
      const v = await teclear(page, sels['RUC'], '17-99.123456001');
      caso('Transportista · RUC se queda en dígitos', v === '1799123456001', `→ «${v}»`);
      const medio = await teclear(page, sels['RUC'], '12345');
      const aviso = await avisoDe(page, sels['RUC']);
      caso('Transportista · Un RUC de largo imposible se avisa',
           medio === '12345' && /10 d/i.test(aviso), `«${medio}» + «${aviso}»`);
    }
    if (sels['Email']) {
      const v = await teclear(page, sels['Email'], 'Juan Perez@Ejemplo.COM');
      caso('Transportista · El correo pierde el espacio y baja a minúsculas',
           v === 'juanperez@ejemplo.com', `→ «${v}»`);
      const roto = await teclear(page, sels['Email'], 'juan@sinpunto');
      const aviso = await avisoDe(page, sels['Email']);
      caso('Transportista · Un correo sin dominio se avisa',
           /nombre@dominio/i.test(aviso), `«${roto}» + «${aviso}»`);
    }
    const web = sels['Sitio web'] || sels['Sitio Web'];
    if (web) {
      const v = await teclear(page, web, 'javascript:alert(1) <x>');
      const aviso = await avisoDe(page, web);
      caso('Transportista · Una dirección que no es http(s) se avisa',
           !v.includes('<') && !v.includes(' ') && /http/i.test(aviso), `«${v}» + «${aviso}»`);
      const buena = await teclear(page, web, 'https://servientrega.com.ec/rastreo?guia=1');
      caso('Transportista · Una dirección web legítima NO se toca',
           buena === 'https://servientrega.com.ec/rastreo?guia=1', `«${buena}»`);
    }
  }

  // Métodos de envío: días de entrega (enteros, sin negativos)
  if (await abrirPestanaRed(page, 'Métodos')) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appNumero]').forEach((el, i) => {
        el.setAttribute('data-p15m', 'm' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15m="m' + i + '"]'; }
      });
      return m;
    });
    const dias = Object.keys(sels).find(k => /d[íi]as/i.test(k));
    if (dias) {
      const v = await teclear(page, sels[dias], '-3.7');
      caso('Método · Los días de entrega no admiten ni signo ni coma',
           v === '37', `«-3.7» → «${v}»`);
    }
  }

  // Tarifas: dinero con dos decimales y mínimo respetado
  if (await abrirPestanaRed(page, 'Tarifas')) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appNumero]').forEach((el, i) => {
        el.setAttribute('data-p15f', 'f' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15f="f' + i + '"]'; }
      });
      return m;
    });
    const base = Object.keys(sels).find(k => /costo base/i.test(k));
    if (base) {
      const v = await teclear(page, sels[base], '12.9999');
      caso('Tarifa · El dinero se queda en dos decimales, sin redondear',
           v === '12.99', `«12.9999» → «${v}»`);
      const cero = await teclear(page, sels[base], '4.50');
      caso('Tarifa · Un importe legítimo NO se toca', cero === '4.50', `«${cero}»`);
    }
    const peso = Object.keys(sels).find(k => /peso m[íi]n/i.test(k));
    if (peso) {
      const v = await teclear(page, sels[peso], '0.5005');
      caso('Tarifa · El peso conserva tres decimales', v === '0.500', `«0.5005» → «${v}»`);
    }
  }
}

/** Diálogos con reglas propias: slug del producto, código y usos del cupón, año de la meta. */
async function casosDialogos(page) {
  console.log('\n── Casos con valor esperado (diálogos)');

  await irA(page, '/operativo/catalogo/marcas');
  if (await pulsarNuevo(page)) {
    const sel = await page.evaluate(() => {
      const els = [...document.querySelectorAll('input[appTexto="slug"]')];
      if (!els.length) { return null; }
      els[0].setAttribute('data-p15s', 's');
      return '[data-p15s="s"]';
    });
    if (sel) {
      const v = await teclear(page, sel, 'Zapatos De Cuero!! Ñ');
      caso('Marca · El slug baja a minúsculas y cambia los espacios por guiones',
           v === 'zapatos-de-cuero-', `«Zapatos De Cuero!! Ñ» → «${v}»`);
      const aviso = await avisoDe(page, sel);
      caso('Marca · Un slug acabado en guion se avisa', /guion/i.test(aviso), `«${aviso}»`);
    }
    await page.keyboard.press('Escape');
    await pausa(600);
  }

  await irA(page, '/operativo/marketing/cupones');
  if (await pulsarNuevo(page)) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appTexto], input[appNumero]').forEach((el, i) => {
        el.setAttribute('data-p15c', 'c' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15c="c' + i + '"]'; }
      });
      return m;
    });
    const cod = Object.keys(sels).find(k => /c[óo]digo/i.test(k));
    if (cod) {
      const v = await teclear(page, sels[cod], 'desc uento-25%');
      caso('Cupón · El código sube a mayúsculas de verdad, no solo en pantalla',
           v === 'DESCUENTO-25', `«desc uento-25%» → «${v}»`);
    }
    const usos = Object.keys(sels).find(k => /usos m[áa]x/i.test(k));
    if (usos) {
      const v = await teclear(page, sels[usos], '2.5');
      caso('Cupón · Los usos máximos no admiten decimales', v === '25', `«2.5» → «${v}»`);
      const bajo = await teclear(page, sels[usos], '0');
      caso('Cupón · Un valor por debajo del mínimo se ajusta al salir del campo',
           bajo === '1', `«0» → «${bajo}» (min=1)`);
    }
    await page.keyboard.press('Escape');
    await pausa(600);
  }

  await irA(page, '/operativo/gerencia/metas');
  if (await pulsarNuevo(page)) {
    const sels = await page.evaluate(() => {
      const m = {};
      document.querySelectorAll('input[appNumero]').forEach((el, i) => {
        el.setAttribute('data-p15g', 'g' + i);
        const et = el.closest('.mat-mdc-form-field')?.querySelector('mat-label')?.textContent?.trim();
        if (et) { m[et] = '[data-p15g="g' + i + '"]'; }
      });
      return m;
    });
    const anio = Object.keys(sels).find(k => /a[ñn]o/i.test(k));
    if (anio) {
      const v = await teclear(page, sels[anio], '1999');
      caso('Meta · Un año fuera de rango se ajusta al mínimo declarado',
           v === '2000', `«1999» → «${v}» (min=2000)`);
    }
    const monto = Object.keys(sels).find(k => /monto/i.test(k));
    if (monto) {
      const v = await teclear(page, sels[monto], '3650000.999');
      caso('Meta · El monto se queda en dos decimales', v === '3650000.99', `→ «${v}»`);
    }
    await page.keyboard.press('Escape');
    await pausa(600);
  }
}

// ── Programa ─────────────────────────────────────────────────────────────────

(async () => {
  console.log('='.repeat(70));
  console.log('P15 · VALIDACIÓN DE CAMPOS  ·  ' + WEB);
  console.log('='.repeat(70));

  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1600,1000']
  });

  const errores = [];
  const RUIDO = [/favicon/i, /fonts\.(googleapis|gstatic)\.com/i, /net::ERR_(INTERNET|NAME_NOT)/i];

  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });
  page.on('console', m => {
    if (m.type() !== 'error') { return; }
    const t = m.text();
    if (!RUIDO.some(r => r.test(t))) { errores.push(t); }
  });
  page.on('pageerror', e => errores.push('pageerror: ' + e.message));

  await entrar(page, ADMIN, CLAVE_ADMIN);

  console.log('\n── Barrido: pantallas con diálogo de alta');
  for (const [nombre, ruta] of CON_DIALOGO) {
    await irA(page, ruta);
    if (!(await pulsarNuevo(page))) {
      caso(`${nombre}: se abre el formulario`, false, 'no se encontró el botón Nuevo');
      continue;
    }
    await barrer(page, nombre);
    await page.keyboard.press('Escape');
    await pausa(500);
  }

  console.log('\n── Barrido: pantallas sin diálogo');
  for (const [nombre, ruta, abrir] of SIN_DIALOGO) {
    await irA(page, ruta);
    if (abrir && !(await abrir(page))) {
      caso(`${nombre}: se abre el formulario`, false, 'no se pudo desplegar');
      continue;
    }
    await barrer(page, nombre);
  }

  console.log('\n── Barrido: red logística, pestaña a pestaña');
  await irA(page, '/operativo/red');
  for (const p of PESTANAS_RED) {
    if (await abrirPestanaRed(page, p)) {
      await barrer(page, 'Red · ' + p);
    } else {
      caso(`Red · ${p}: se abre el formulario`, false, 'no se encontró la pestaña o el alta');
    }
  }

  await casosConcretos(page);
  await casosDialogos(page);

  // ── Cliente: tienda y perfil ───────────────────────────────────────────────
  console.log('\n── Barrido: la tienda y el perfil, con el rol CLIENTE');
  const pageC = await browser.newPage();
  await pageC.setViewport({ width: 1600, height: 1000 });
  pageC.on('console', m => {
    if (m.type() !== 'error') { return; }
    const t = m.text();
    if (!RUIDO.some(r => r.test(t))) { errores.push(t); }
  });
  pageC.on('pageerror', e => errores.push('pageerror: ' + e.message));

  await entrar(pageC, CLIENTE, CLAVE_CLIENTE);

  await irA(pageC, '/shop');
  // El panel de precio se despliega SOLO si no está ya abierto: pulsarlo a
  // ciegas lo cerraría y el barrido daría verde sin haber tocado los dos campos
  // de importe, que son los únicos numéricos de la tienda.
  await pageC.evaluate(() => {
    if (document.querySelector('.rango-manual input')) { return; }
    const b = [...document.querySelectorAll('.grupo-titulo')]
      .find(e => /precio/i.test(e.textContent));
    b?.click();
  });
  await pausa(700);
  await barrer(pageC, 'Tienda · catálogo');

  await irA(pageC, '/perfil');
  await pageC.evaluate(() => {
    const b = [...document.querySelectorAll('button')]
      .find(e => e.offsetParent && /(nueva|añadir|agregar).*direcci/i.test(e.textContent));
    b?.click();
  });
  await pausa(800);
  await barrer(pageC, 'Perfil del cliente');

  const selTel = await pageC.evaluate(() => {
    const el = document.querySelector('input[appTexto="telefono"]');
    if (!el) { return null; }
    el.setAttribute('data-p15p', 'p');
    return '[data-p15p="p"]';
  });
  if (selTel) {
    const v = await teclear(pageC, selTel, '09 letras 8877665');
    caso('Perfil · El teléfono del cliente no admite letras',
         /^[0-9+() -]*$/.test(v), `→ «${v}»`);
  }

  await irA(pageC, '/shop/checkout');
  await barrer(pageC, 'Tienda · checkout');

  // ── Consola ────────────────────────────────────────────────────────────────
  console.log('\n── Consola del navegador');
  const unicos = [...new Set(errores)];
  caso('Sin errores de aplicación en todo el recorrido', unicos.length === 0,
       unicos.slice(0, 5).join(' | '));

  await browser.close();

  const fallos = casos.filter(c => !c.ok);
  console.log('\n' + '='.repeat(70));
  console.log(`P15 · VALIDACIÓN DE CAMPOS: ${casos.length - fallos.length}/${casos.length} casos en verde`);
  if (fallos.length) {
    console.log('\nFALLOS:');
    fallos.forEach(f => console.log(`  · ${f.nombre}${f.detalle ? '  — ' + f.detalle : ''}`));
  }
  process.exit(fallos.length ? 1 : 0);
})();
