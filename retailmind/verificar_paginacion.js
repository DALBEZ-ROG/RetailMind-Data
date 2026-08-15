/**
 * V1-V7 — Verificación EN NAVEGADOR REAL de la paginación en servidor.
 *
 * Chrome headless (puppeteer). Por cada pantalla: entra con un usuario de
 * verdad, mide el tiempo de apertura, pagina adelante y atrás comprobando que
 * las filas CAMBIAN y NO SE SOLAPAN, lee el `total` del paginador para
 * contrastarlo contra `count(*)`, y ejerce CADA filtro buscando a propósito un
 * valor que NO está en la primera página — que es la única forma de demostrar
 * que el filtro mira el conjunto completo y no la página visible.
 *
 * Las claves llegan por ENTORNO (deuda C-4: no se escriben aquí):
 *   set RETAILMIND_ADMIN_PASS=...   set RETAILMIND_STAFF_PASS=...
 *   node retailmind/verificar_paginacion.js [nombre-de-pantalla ...]
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

const RUIDO = [/favicon/i, /Failed to load resource: net::ERR_/i];

// ── Utilidades de la página ────────────────────────────────────────────

const SEL_FILA = 'table[mat-table] tbody tr.mat-mdc-row, table tbody tr[mat-row]';

async function entrar(page, cred) {
  await page.goto(`${APP}/login`, { waitUntil: 'networkidle2', timeout: 90000 });
  await page.waitForSelector('input[formcontrolname="username"], input[name="username"]',
                             { timeout: 30000 });
  await page.type('input[formcontrolname="username"], input[name="username"]', cred.u);
  await page.type('input[formcontrolname="password"], input[name="password"]', cred.p);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 90000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await new Promise(r => setTimeout(r, 2500));
}

/**
 * Identidad de las filas de la tabla n-ésima: la fila ENTERA, no su primera
 * celda. En la bandeja de reseñas la primera celda es el nombre del producto y
 * se repite entre filas distintas, así que comparar por ella acusaba un solape
 * entre páginas que no existía.
 */
function filasDe(page, tabla = 0) {
  return page.evaluate(sel => {
    const t = window.__tablaDe(sel);
    if (!t) return [];
    return [...t.querySelectorAll('tbody tr')]
      .filter(tr => tr.querySelector('td'))
      .map(tr => tr.innerText.replace(/\s+/g, ' ').trim());
  }, tabla);
}

/**
 * Localiza la tabla POR EL TÍTULO de su tarjeta y no por su posición.
 *
 * El índice no sirve: las tarjetas llevan `*ngIf` sobre el listado, así que
 * mientras carga la tabla lenta no está en el DOM y la rápida ocupa el índice
 * 0. En `devoluciones-proveedor` eso hacía que se midiera —y se paginara— la
 * tabla equivocada, con un «solapan 25 de 25» que era en realidad otra tabla
 * que no se había movido.
 */
const LOCALIZADOR = `
window.__tablaDe = function (sel) {
  const tablas = [...document.querySelectorAll('table')];
  if (typeof sel === 'number') return tablas[sel];
  return tablas.find(t => {
    const card = t.closest('.op-card') || t.parentElement;
    const h = card && card.querySelector('h3');
    return h && h.innerText.toLowerCase().includes(String(sel).toLowerCase());
  });
};
window.__paginadorDe = function (sel) {
  const t = window.__tablaDe(sel);
  const card = t ? (t.closest('.op-card') || t.parentElement) : null;
  return (card && card.querySelector('mat-paginator'))
      || document.querySelector('mat-paginator');
};`;

/** Rótulo «1 – 25 of 26551» del paginador de ESA tabla. */
function rangoDe(page, sel = 0) {
  return page.evaluate(s => {
    const p = window.__paginadorDe(s);
    const l = p && p.querySelector('.mat-mdc-paginator-range-label');
    return l ? l.innerText.trim() : null;
  }, sel);
}

function totalDelRango(rango) {
  if (!rango) return null;
  const m = rango.replace(/ /g, ' ').match(/\b(?:of|de)\s+([\d.,\s]+)$/i);
  return m ? Number(m[1].replace(/[.,\s]/g, '')) : null;
}

/**
 * El 404 de `favicon.ico` es PRE-EXISTENTE y ajeno a esta sesión (el archivo
 * existe en `src/` pero `angular.json` solo copia `src/assets/**`). El mensaje
 * genérico del navegador no dice QUÉ recurso falló, así que solo se descarta
 * si todas las respuestas >=400 observadas son ruido conocido.
 */
function esRuidoGenerico(texto, respuestas) {
  if (!/Failed to load resource/i.test(texto)) return false;
  return respuestas.length > 0 && respuestas.every(u => RUIDO.some(r => r.test(u)));
}

async function clicPaginador(page, direccion, sel = 0) {
  const clase = direccion === 'siguiente'
    ? '.mat-mdc-paginator-navigation-next' : '.mat-mdc-paginator-navigation-previous';
  const ok = await page.evaluate((c, s) => {
    const p = window.__paginadorDe(s);
    const b = p && p.querySelector(c);
    if (!b || b.disabled) return false;
    b.click();
    return true;
  }, clase, sel);
  if (ok) { await new Promise(r => setTimeout(r, 3000)); }
  return ok;
}

/**
 * Escribe en el input cuyo `<mat-label>` contiene `etiqueta`.
 *
 * El borrado NO puede ser `type('')`: seleccionar con triple clic y no teclear
 * nada deja el texto intacto, así que los filtros siguientes se acumulaban
 * sobre una búsqueda que se creía limpia. Se borra con Backspace.
 *
 * La espera es larga a propósito: el buscador de reseñas tiene 350 ms de
 * debounce y su consulta sobre 263.077 filas tarda ~2,6 s; leer antes de eso
 * devuelve el total ANTERIOR y parece que el filtro no hizo nada.
 */
async function escribirEn(page, etiqueta, texto) {
  const handle = await page.evaluateHandle(et => {
    for (const f of document.querySelectorAll('mat-form-field')) {
      const l = f.querySelector('mat-label');
      if (l && l.innerText.toLowerCase().includes(et.toLowerCase())) {
        const i = f.querySelector('input');
        if (i) return i;
      }
    }
    return null;
  }, etiqueta);
  const el = handle.asElement();
  if (!el) return false;
  await el.click({ clickCount: 3 });
  await page.keyboard.press('Backspace');
  if (texto) { await el.type(texto, { delay: 12 }); }
  await new Promise(r => setTimeout(r, 6000));
  return true;
}

/** Elige la opción `valor` del mat-select cuya etiqueta contiene `etiqueta`. */
async function elegirEn(page, etiqueta, valor) {
  const abierto = await page.evaluate(et => {
    for (const f of document.querySelectorAll('mat-form-field')) {
      const l = f.querySelector('mat-label');
      if (l && l.innerText.toLowerCase().includes(et.toLowerCase())) {
        const s = f.querySelector('mat-select');
        if (s) { s.click(); return true; }
      }
    }
    return false;
  }, etiqueta);
  if (!abierto) return false;
  await new Promise(r => setTimeout(r, 700));
  const elegido = await page.evaluate(v => {
    for (const o of document.querySelectorAll('mat-option')) {
      if (o.innerText.trim().toLowerCase() === v.toLowerCase()) { o.click(); return true; }
    }
    for (const o of document.querySelectorAll('mat-option')) {
      if (o.innerText.trim().toLowerCase().includes(v.toLowerCase())) { o.click(); return true; }
    }
    return false;
  }, valor);
  await new Promise(r => setTimeout(r, 2800));
  if (!elegido) { await page.keyboard.press('Escape'); }
  return elegido;
}

/**
 * Cuenta las opciones del `mat-select` de esa etiqueta. Es la prueba de V2:
 * los selectores que se alimentaban filtrando la tabla entera en el navegador
 * tienen que seguir ofreciendo SUS opciones, no las de la primera página.
 */
async function contarOpciones(page, etiqueta) {
  const abierto = await page.evaluate(et => {
    for (const f of document.querySelectorAll('mat-form-field')) {
      const l = f.querySelector('mat-label');
      if (l && l.innerText.toLowerCase().includes(et.toLowerCase())) {
        const s = f.querySelector('mat-select');
        if (s) { s.click(); return true; }
      }
    }
    return false;
  }, etiqueta);
  if (!abierto) return -1;
  await new Promise(r => setTimeout(r, 900));
  const n = await page.evaluate(() => document.querySelectorAll('mat-option').length);
  await page.keyboard.press('Escape');
  await new Promise(r => setTimeout(r, 500));
  return n;
}

/** Pulsa el botón cuyo texto contiene `texto`. */
async function pulsar(page, texto) {
  const ok = await page.evaluate(t => {
    for (const b of document.querySelectorAll('button, mat-button-toggle button')) {
      if ((b.innerText || '').toLowerCase().includes(t.toLowerCase())) { b.click(); return true; }
    }
    return false;
  }, texto);
  if (ok) { await new Promise(r => setTimeout(r, 2600)); }
  return ok;
}

// ── Definición de las pantallas ────────────────────────────────────────

const PANTALLAS = {
  preparacion: {
    ruta: '/operativo/ventas/preparacion', cred: ADMIN,
    tabla: 'Cola de preparación',
    conteo: 'cola de preparación (facturado + en_preparacion)',
    filtros: []                                   // la pantalla no tiene filtros
  },
  devoluciones: {
    ruta: '/operativo/ventas/devoluciones', cred: ADMIN,
    tabla: 'Bandeja de devoluciones',
    conteo: 'devolucion',
    filtros: [{ tipo: 'select', etiqueta: 'Estado', valor: 'cerrada', esperado: 25634 }]
  },
  ordenes: {
    ruta: '/operativo/compras/ordenes', cred: ADMIN,
    tabla: 'Órdenes emitidas',
    conteo: 'orden_compra',
    filtros: []
  },
  facturas: {
    ruta: '/operativo/compras/facturas', cred: ADMIN,
    tabla: 'Cuentas por pagar',
    conteo: 'cuenta_por_pagar',
    filtros: [],
    // Se llenaba filtrando las 134.588 órdenes en el navegador; hoy es
    // `facturables=true` en SQL. Son 4 y ninguna cae en la primera página.
    selectores: [{ etiqueta: 'Orden de compra', esperado: 4 }]
  },
  // Pantalla ya corregida en la sesión anterior; aquí solo se verifica que su
  // selector de cliente —que descargaba los 50.072— sigue sirviendo.
  pedidos: {
    ruta: '/operativo/ventas/pedidos', cred: ADMIN,
    tabla: 'Pedidos registrados',
    conteo: 'pedido',
    filtros: [],
    buscadores: [{ abrirCon: 'Nuevo Pedido', etiqueta: 'Cliente', valor: 'Yolanda Flores' }]
  },
  recepciones: {
    ruta: '/operativo/compras/recepciones', cred: ADMIN,
    tabla: 'Líneas de la orden',
    conteo: '—(pantalla sin listado propio)',
    filtros: [], paginar: false,
    // `recibibles=true`: 72 recibida_parcial + 7 confirmada = 79.
    selectores: [{ etiqueta: 'Orden de compra', esperado: 79 }]
  },
  defectuosos: {
    ruta: '/operativo/compras/devoluciones-proveedor', cred: ADMIN,
    tabla: 'Ítems defectuosos',
    conteo: 'item_defectuoso (pendiente)',
    filtros: [{ tipo: 'select', etiqueta: 'Estado', valor: 'resuelto', reset: 'pendiente' }]
  },
  tickets: {
    ruta: '/operativo/soporte/tickets', cred: ADMIN,
    tabla: 'Tickets',
    conteo: 'ticket_soporte',
    // Los cuatro filtros que vivían en el navegador. «cerrado» es la prueba
    // dura: el ORDER BY manda resuelto/cerrado al FINAL de 179.851 filas, así
    // que ninguno de sus 54.949 tickets está en la primera página.
    filtros: [
      { tipo: 'select', etiqueta: 'Estado',    valor: 'cerrado',    esperado: 54949 },
      { tipo: 'select', etiqueta: 'Prioridad', valor: 'urgente',    esperado: 10, reset: 'Todas' },
      { tipo: 'select', etiqueta: 'Categoría', valor: 'Sugerencia', esperado: 18367, reset: 'Todas' },
      { tipo: 'boton',  etiqueta: 'bandeja',   valor: 'Sin asignar', esperado: 39623, reset: 'Todos' }
    ],
    buscadores: [{ abrirCon: 'Nuevo Ticket', etiqueta: 'Cliente', valor: 'Yolanda Flores' }]
  },
  resenas: {
    ruta: '/operativo/resenas', cred: ADMIN,
    tabla: 'Reseñas',
    conteo: 'resena',
    // «Zapatos» casa con 23 reseñas de 263.077 y NINGUNA está en la primera
    // página (la primera página son las 25 más recientes, todas de otros
    // productos): es la prueba de que el buscador mira el conjunto completo.
    filtros: [
      { tipo: 'texto',  etiqueta: 'Buscar',       valor: 'Zapatos',   esperado: 23 },
      { tipo: 'select', etiqueta: 'Estado',       valor: 'rechazada', esperado: 16220,
        reset: '— Todos —' },
      { tipo: 'select', etiqueta: 'Calificación', valor: '★☆☆☆☆',     esperado: 19232,
        reset: '— Todas —' },
      { tipo: 'select', etiqueta: 'Denuncias',    valor: 'Sin reportes pendientes',
        esperado: 263077, reset: '— Todas —' }
    ]
  }
};

// ── Rutina por pantalla ────────────────────────────────────────────────

async function verificar(browser, nombre, spec) {
  const page = await browser.newPage();
  await page.setViewport({ width: 1500, height: 1000 });
  const errores = [];
  const consola = [];
  const cuatroXX = [];
  page.on('console', m => {
    if (m.type() === 'error' && !RUIDO.some(r => r.test(m.text()))) {
      consola.push(m.text().slice(0, 220));
    }
    if (m.type() === 'warning' && !RUIDO.some(r => r.test(m.text()))) {
      consola.push('WARN ' + m.text().slice(0, 220));
    }
  });
  page.on('pageerror', e => errores.push(`pageerror: ${String(e).slice(0, 220)}`));
  page.on('response', r => {
    if (r.status() >= 400) {
      const u = `HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`;
      cuatroXX.push(u);
      if (!RUIDO.some(x => x.test(r.url()))) { errores.push(u); }
    }
  });

  console.log(`\n${'='.repeat(70)}\n${nombre.toUpperCase()}  ${spec.ruta}`);
  await page.evaluateOnNewDocument(LOCALIZADOR);
  await entrar(page, spec.cred);

  const t0 = Date.now();
  await page.goto(APP + spec.ruta, { waitUntil: 'domcontentloaded', timeout: 300000 });
  // La plantilla pinta el `empty-state` MIENTRAS carga, así que esperar por él
  // mediría el armazón y no el dato: se espera a que haya FILAS de verdad.
  if (spec.paginar !== false) {
    await page.waitForFunction(
      s => {
        const t = window.__tablaDe(s);
        return !!t && t.querySelectorAll('tbody tr td').length > 0;
      },
      { timeout: 300000, polling: 200 }, spec.tabla ?? 0).catch(() => {});
  } else {
    // Esta pantalla no tiene listado: su tabla solo aparece tras elegir una
    // orden. Esperar por ella mediría el timeout, no la apertura.
    await page.waitForSelector('mat-select', { timeout: 120000 }).catch(() => {});
  }
  const apertura = Date.now() - t0;
  await new Promise(r => setTimeout(r, 2500));

  const p0 = await filasDe(page, spec.tabla ?? 0);
  const rango = await rangoDe(page, spec.tabla ?? 0);
  const total = totalDelRango(rango);
  console.log(`  apertura: ${apertura} ms   filas en pantalla: ${p0.length}`);
  console.log(`  paginador: «${rango}»  → total = ${total}   [contrastar con ${spec.conteo}]`);

  // V1 · paginar adelante y atrás (las pantallas sin listado propio no paginan)
  if (spec.paginar === false) {
    console.log('  (pantalla sin listado paginado: solo se comprueba su selector)');
  }
  const avanzo = spec.paginar === false
    ? true : await clicPaginador(page, 'siguiente', spec.tabla ?? 0);
  if (spec.paginar !== false) {
    const p1 = avanzo ? await filasDe(page, spec.tabla ?? 0) : [];
    const solapan = p0.filter(x => p1.includes(x));
    const retrocedio = avanzo
      ? await clicPaginador(page, 'anterior', spec.tabla ?? 0) : false;
    const p0b = retrocedio ? await filasDe(page, spec.tabla ?? 0) : [];
    const vuelveIgual = JSON.stringify(p0) === JSON.stringify(p0b);
    console.log(`  pág 1→2: ${avanzo ? 'sí' : 'NO'}   filas distintas: `
      + `${p1.length && solapan.length === 0 ? 'sí' : 'NO'} (solapadas: ${solapan.length})`);
    console.log(`  pág 2→1: ${retrocedio ? 'sí' : 'NO'}   reproduce la página 1: `
      + `${vuelveIgual ? 'sí' : 'NO'}`);
    if (!avanzo || solapan.length || !vuelveIgual) { errores.push('paginación'); }
  }

  // V1 · cada filtro, buscando algo que NO esté en la primera página
  for (const f of spec.filtros ?? []) {
    let ok = false;
    if (f.tipo === 'texto')   { ok = await escribirEn(page, f.etiqueta, f.valor); }
    if (f.tipo === 'select')  { ok = await elegirEn(page, f.etiqueta, f.valor, f.exacto); }
    if (f.tipo === 'boton')   { ok = await pulsar(page, f.valor); }
    const filas = await filasDe(page, spec.tabla ?? 0);
    const r = await rangoDe(page, spec.tabla ?? 0);
    const t = totalDelRango(r);
    const fueraDeP0 = filas.filter(x => !p0.includes(x)).length;
    console.log(`  filtro ${f.tipo} «${f.etiqueta}» = «${f.valor}»: `
      + `${ok ? 'aplicado' : 'NO SE PUDO APLICAR'}  filas=${filas.length}  total=${t}`
      + `  fuera de la 1ª página original=${fueraDeP0}`
      + (f.esperado != null ? `  [esperado ${f.esperado}]` : ''));
    if (!ok) { errores.push(`filtro ${f.etiqueta}`); }
    if (f.esperado != null && t !== f.esperado) { errores.push(`total de ${f.etiqueta}`); }
    if (f.limpiar !== false) {
      if (f.tipo === 'texto')  { await escribirEn(page, f.etiqueta, ''); }
      if (f.tipo === 'select') { await elegirEn(page, f.etiqueta, f.reset || 'Todos'); }
      if (f.tipo === 'boton' && f.reset) { await pulsar(page, f.reset); }
    }
  }

  // V2 · selectores que BUSCAN en el servidor (autocomplete, no mat-select).
  // Se busca a propósito algo del FINAL del alfabeto: con 50.072 clientes esa
  // opción caía en la última de 1.002 páginas y jamás estaría precargada.
  for (const b of spec.buscadores ?? []) {
    await pulsar(page, b.abrirCon);
    const escrito = await escribirEn(page, b.etiqueta, b.valor);
    const n = await page.evaluate(() => document.querySelectorAll('mat-option').length);
    const textos = await page.evaluate(() =>
      [...document.querySelectorAll('mat-option')].map(o => o.innerText.trim()).slice(0, 3));
    const casa = textos.some(t => t.toLowerCase().includes(b.valor.toLowerCase()));
    console.log(`  buscador «${b.etiqueta}» = «${b.valor}»: `
      + `${escrito ? 'escrito' : 'NO SE PUDO ESCRIBIR'}  opciones=${n}  `
      + `¿aparece?=${casa ? 'sí' : 'NO'}  ej: ${textos[0] || '—'}`);
    if (!escrito || !casa) { errores.push(`buscador ${b.etiqueta}`); }
    await page.keyboard.press('Escape');
  }

  // V2 · los selectores que se llenaban filtrando la tabla entera
  for (const s of spec.selectores ?? []) {
    const n = await contarOpciones(page, s.etiqueta);
    const ok = n === s.esperado;
    console.log(`  selector «${s.etiqueta}»: ${n} opciones  [esperado ${s.esperado}]  `
      + (ok ? 'OK' : 'DISCREPANCIA'));
    if (!ok) { errores.push(`selector ${s.etiqueta}`); }
  }

  consola.forEach(t => {
    if (!esRuidoGenerico(t, cuatroXX)) { errores.push(`consola: ${t}`); }
  });
  const unicos = [...new Set(errores)];
  if (cuatroXX.length) {
    console.log(`  respuestas >=400: ${[...new Set(cuatroXX)].join(', ')}`);
  }
  console.log(unicos.length
    ? `  RESULTADO: ${unicos.length} problema(s)\n     - ` + unicos.slice(0, 10).join('\n     - ')
    : '  RESULTADO: OK — 0 errores y 0 warnings de aplicación');
  await page.close();
  return { nombre, apertura, total, fallos: unicos.length };
}

(async () => {
  const pedidas = process.argv.slice(2).length
    ? process.argv.slice(2) : Object.keys(PANTALLAS);
  const browser = await puppeteer.launch({
    headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const res = [];
  for (const n of pedidas) {
    if (!PANTALLAS[n]) { console.error(`Pantalla desconocida: ${n}`); continue; }
    res.push(await verificar(browser, n, PANTALLAS[n]));
  }
  await browser.close();
  console.log('\n' + '='.repeat(70));
  res.forEach(r => console.log(
    `  ${r.nombre.padEnd(14)} apertura ${String(r.apertura).padStart(7)} ms   `
    + `total ${String(r.total).padStart(8)}   ${r.fallos ? r.fallos + ' FALLO(S)' : 'OK'}`));
  process.exit(res.some(r => r.fallos) ? 1 : 0);
})();
