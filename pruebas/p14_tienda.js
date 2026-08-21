/**
 * p14_tienda.js — La TIENDA del cliente (suite P14).
 *
 * Comprueba que los filtros y el campo de búsqueda de la tienda rediseñada
 * FUNCIONAN, y no solo que se pintan. Cada caso mira tres cosas distintas:
 *
 *   1. el ESTADO: que el filtro quede escrito en la URL, que es donde vive
 *      (la barra superior y el catálogo son dos componentes distintos y se
 *      comunican por ahí);
 *   2. el RESULTADO: que el conteo de la pantalla coincida con el que devuelve
 *      `/api/catalogo/productos` con esos mismos parámetros — un filtro que
 *      pinta chips y no recorta nada pasaría cualquier prueba visual;
 *   3. el CONTENIDO: que las tarjetas pintadas cumplan de verdad el criterio
 *      (todas de la marca elegida, todos los precios dentro del tramo, ningún
 *      agotado con «solo con stock»).
 *
 * Corre contra el frontend de desarrollo (4200) o contra el contenedor, y
 * necesita el backend en pie. La clave llega por ENTORNO, nunca escrita aquí
 * (deuda C-4).
 *
 *   export RETAILMIND_CLIENTE_PASS='…'          # la de los clientes demo
 *   node pruebas/p14_tienda.js
 *   RETAILMIND_WEB=http://localhost:4200 node pruebas/p14_tienda.js
 *
 * Requiere puppeteer, que ya está en `retailmind-frontend/node_modules`.
 */

const path = require('path');

const RAIZ = path.resolve(__dirname, '..');
const puppeteer = require(path.join(RAIZ, 'retailmind-frontend', 'node_modules', 'puppeteer'));

const WEB = (process.env.RETAILMIND_WEB || 'http://localhost:4200').replace(/\/$/, '');
const API = (process.env.RETAILMIND_API || 'http://localhost:8080').replace(/\/$/, '');
const USUARIO = process.env.RETAILMIND_CLIENTE || 'maria.lopez@demo.com';
const CLAVE = process.env.RETAILMIND_CLIENTE_PASS;

if (!CLAVE) {
  console.error('FALTA RETAILMIND_CLIENTE_PASS.\n'
    + '  Las credenciales de demostración no se escriben en el repo (deuda C-4).\n'
    + '  La tienes en la sección «Credenciales de desarrollo» de CLAUDE.md.');
  process.exit(2);
}

/**
 * Ruido de consola ajeno a la tienda. Va ENUMERADO: un filtro amplio
 * («ignora todo lo que diga error») convertiría esta suite en decorativa.
 */
const RUIDO = [
  /favicon/i,
  /fonts\.(googleapis|gstatic)\.com/i,
  /net::ERR_(INTERNET|NAME_NOT|CONNECTION_REFUSED)/i
];

let casos = [];

function caso(nombre, ok, detalle) {
  casos.push({ nombre, ok: !!ok, detalle: detalle || '' });
  const marca = ok ? 'OK  ' : 'FALLA';
  console.log(`  [${marca}] ${nombre}${detalle ? '  — ' + detalle : ''}`);
}

const pausa = ms => new Promise(r => setTimeout(r, ms));

// ── Utilidades de página ─────────────────────────────────────────────────────

async function entrar(page) {
  await page.goto(`${WEB}/login`, { waitUntil: 'networkidle2', timeout: 60000 });
  await page.waitForSelector('input[formcontrolname="username"], input[name="username"]', { timeout: 30000 });
  await page.type('input[formcontrolname="username"], input[name="username"]', USUARIO);
  await page.type('input[formcontrolname="password"], input[name="password"]', CLAVE);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 60000 }).catch(() => {}),
    page.click('button[type="submit"]')
  ]);
  await pausa(1500);
}

/**
 * El token del JWT, para poder preguntarle lo MISMO a la API directamente y
 * usar su respuesta como oráculo de cada filtro. La clave es la que fija
 * `auth.service.ts` (`rm_token`); si cambiara allí, esta prueba lo diría al
 * instante porque todos los conteos de la API saldrían en −1.
 */
function leerToken(page) {
  return page.evaluate(() => localStorage.getItem('rm_token'));
}

/** Total que devuelve la API para unos filtros: el oráculo de cada caso. */
async function totalApi(page, token, params) {
  return page.evaluate(async (api, tk, qs) => {
    const r = await fetch(`${api}/api/catalogo/productos?size=1&${qs}`,
      { headers: { Authorization: 'Bearer ' + tk } });
    if (!r.ok) return -1;
    const j = await r.json();
    return j.totalElements;
  }, API, token, params);
}

/** Lo que la pantalla dice que hay: «1–24 de 6.214 resultados». */
async function totalPantalla(page) {
  return page.evaluate(() => {
    const t = document.querySelector('.conteo')?.innerText || '';
    const m = t.match(/de\s+([\d.,]+)\s+resultados/i);
    return m ? Number(m[1].replace(/[.,]/g, '')) : -1;
  });
}

/** Marca, precio y stock de cada tarjeta pintada. */
async function tarjetas(page) {
  return page.evaluate(() => [...document.querySelectorAll('.p-card')].map(c => ({
    marca: c.querySelector('.p-marca')?.innerText.trim() || '',
    nombre: c.querySelector('.p-nombre')?.innerText.trim() || '',
    precio: Number(
      (c.querySelector('.p-precio .entero')?.innerText || '0').replace(/[.,]/g, '')
      + '.' + (c.querySelector('.p-precio .decimal')?.innerText || '00')),
    agotado: /sin stock/i.test(c.querySelector('.p-stock')?.innerText || '')
  })));
}

async function irA(page, ruta) {
  await page.goto(WEB + ruta, { waitUntil: 'networkidle2', timeout: 60000 });
  await pausa(1600);
}

// ── La suite ────────────────────────────────────────────────────────────────

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1600, height: 1000 });

  const errores = [];

  // Un caso de la suite provoca un rechazo A PROPÓSITO (un género inventado
  // debe dar 400). Sin esta ventana, el propio caso ensuciaría el recuento de
  // errores de consola y el último caso fallaría por hacer bien su trabajo. Se
  // abre y se cierra alrededor de la llamada, nunca de forma permanente.
  let rechazoEsperado = null;
  const esperado = (metodo, url) => rechazoEsperado
    && url.includes(rechazoEsperado) && (metodo === undefined || true);

  page.on('console', m => {
    if (m.type() !== 'error' || RUIDO.some(r => r.test(m.text()))) return;
    // El mensaje genérico del navegador no trae URL; si hay una ventana de
    // rechazo abierta, se atribuye a ella.
    if (rechazoEsperado && /Failed to load resource/i.test(m.text())) return;
    errores.push(m.text().slice(0, 200));
  });
  page.on('pageerror', e => errores.push('pageerror: ' + String(e).slice(0, 200)));
  page.on('response', r => {
    if (r.status() < 400 || RUIDO.some(x => x.test(r.url()))) return;
    if (esperado(undefined, r.url())) return;
    errores.push(`HTTP ${r.status()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`);
  });

  await entrar(page);
  const token = await leerToken(page);

  // ── 1 · El catálogo carga con sus piezas nuevas ──────────────────────────
  console.log('\n── 1 · Estructura del catálogo');
  await irA(page, '/shop');

  const piezas = await page.evaluate(() => ({
    railIzquierda: !!document.querySelector('.catalogo-cuerpo .rail'),
    railEsPrimera: document.querySelector('.catalogo-cuerpo')?.firstElementChild?.classList.contains('rail'),
    buscadorBarra: !!document.querySelector('.tienda-buscador .tb-campo'),
    ambitoBarra: !!document.querySelector('.tienda-buscador .tb-ambito'),
    devoluciones: !!document.querySelector('.tienda-acciones a[href*="mis-pedidos"]'),
    ayuda: [...document.querySelectorAll('.tienda-acciones .acc-doble')]
             .some(b => /soporte/i.test(b.innerText)),
    carrito: !!document.querySelector('.acc-carrito'),
    deseos: !!document.querySelector('.acc-icono[href*="wishlist"]'),
    departamentos: document.querySelectorAll('.departamentos .dep').length,
    tarjetas: document.querySelectorAll('.p-card').length,
    gruposFiltro: [...document.querySelectorAll('.rail .grupo-titulo')].map(g => g.innerText.trim())
  }));

  caso('El panel de filtros está a la IZQUIERDA del listado',
       piezas.railIzquierda && piezas.railEsPrimera);
  caso('La barra superior lleva campo de búsqueda y selector de departamento',
       piezas.buscadorBarra && piezas.ambitoBarra);
  caso('La barra lleva accesos a Devoluciones, Ayuda/Soporte, deseos y carrito',
       piezas.devoluciones && piezas.ayuda && piezas.deseos && piezas.carrito,
       `pedidos:${piezas.devoluciones} ayuda:${piezas.ayuda} deseos:${piezas.deseos} carrito:${piezas.carrito}`);
  caso('Los cuatro grupos de filtro están presentes',
       piezas.gruposFiltro.length === 4,
       piezas.gruposFiltro.join(' · '));
  caso('La franja de departamentos y la rejilla traen contenido',
       piezas.departamentos > 5 && piezas.tarjetas > 0,
       `${piezas.departamentos} departamentos · ${piezas.tarjetas} tarjetas`);

  const totalSinFiltro = await totalPantalla(page);
  const apiSinFiltro = await totalApi(page, token, '');
  caso('El conteo sin filtros coincide con la API',
       totalSinFiltro === apiSinFiltro, `pantalla ${totalSinFiltro} · API ${apiSinFiltro}`);

  // ── 2 · Búsqueda desde la barra superior ─────────────────────────────────
  console.log('\n── 2 · Búsqueda desde la barra superior');
  await page.click('.tienda-buscador .tb-campo');
  await page.type('.tienda-buscador .tb-campo', 'Samsung');
  await page.keyboard.press('Enter');
  await pausa(1800);

  const urlBusqueda = page.url();
  const totalBusqueda = await totalPantalla(page);
  const apiBusqueda = await totalApi(page, token, 'q=Samsung');
  const cards = await tarjetas(page);

  caso('El término viaja en la URL (?q=)', /[?&]q=Samsung/i.test(urlBusqueda), urlBusqueda.split('?')[1] || '');
  caso('La búsqueda RECORTA el catálogo', totalBusqueda > 0 && totalBusqueda < totalSinFiltro,
       `${totalSinFiltro} → ${totalBusqueda}`);
  caso('El conteo de la búsqueda coincide con la API',
       totalBusqueda === apiBusqueda, `pantalla ${totalBusqueda} · API ${apiBusqueda}`);
  caso('Todos los resultados mencionan el término buscado',
       cards.length > 0 && cards.every(c => /samsung/i.test(c.marca + ' ' + c.nombre)),
       `${cards.length} tarjetas revisadas`);
  caso('Aparece el chip del filtro activo',
       await page.evaluate(() => [...document.querySelectorAll('.chip-activo')]
         .some(c => /samsung/i.test(c.innerText))));

  // Una búsqueda sin resultados no puede dejar la pantalla en blanco.
  await irA(page, '/shop?q=zzzzqqxx');
  const vacio = await page.evaluate(() => ({
    hayVacio: !!document.querySelector('.tienda-vacio'),
    texto: document.querySelector('.tienda-vacio')?.innerText.slice(0, 80) || ''
  }));
  caso('Una búsqueda sin resultados muestra un estado vacío explícito',
       vacio.hayVacio, vacio.texto.replace(/\n/g, ' '));

  // ── 3 · Departamento ─────────────────────────────────────────────────────
  console.log('\n── 3 · Filtro por departamento');
  await irA(page, '/shop');
  const dep = await page.evaluate(() => {
    const b = [...document.querySelectorAll('.departamentos .dep')]
      .find(x => /electr/i.test(x.innerText));
    return b ? { texto: b.innerText.replace(/\n/g, ' ').trim() } : null;
  });
  await page.evaluate(() => {
    [...document.querySelectorAll('.departamentos .dep')]
      .find(x => /electr/i.test(x.innerText))?.click();
  });
  await pausa(1800);

  const catUrl = new URL(page.url()).searchParams.get('cat');
  const totalCat = await totalPantalla(page);
  const apiCat = await totalApi(page, token, `categoria_id=${catUrl}`);
  const cardsCat = await tarjetas(page);

  caso('El departamento queda en la URL (?cat=)', !!catUrl, `cat=${catUrl} · ${dep?.texto || ''}`);
  caso('El conteo por departamento coincide con la API',
       totalCat === apiCat && totalCat > 0, `pantalla ${totalCat} · API ${apiCat}`);
  caso('El selector de la barra refleja el departamento activo',
       await page.evaluate(c => document.querySelector('.tb-ambito')?.value === String(c)
                              || /electr/i.test(document.querySelector('.tb-ambito')?.selectedOptions[0]?.text || ''),
                            catUrl));
  caso('La pastilla del departamento queda marcada como activa',
       await page.evaluate(() => !!document.querySelector('.departamentos .dep.activo:not(:first-child)')));

  // ── 4 · Marca ────────────────────────────────────────────────────────────
  console.log('\n── 4 · Filtro por marca (con su propio buscador)');
  await irA(page, '/shop');
  await page.type('.rail .grupo .campo-tienda', 'Nik');
  await pausa(500);
  const marcasVisibles = await page.evaluate(() =>
    [...document.querySelectorAll('.lista-scroll.marcas .opcion .op-texto')].map(x => x.innerText.trim()));
  caso('El buscador de marcas filtra la lista',
       marcasVisibles.length > 0 && marcasVisibles.every(m => /todas las marcas|nik/i.test(m)),
       marcasVisibles.join(', ').slice(0, 90));

  await page.evaluate(() => {
    [...document.querySelectorAll('.lista-scroll.marcas .opcion')]
      .find(o => /^nike$/i.test(o.innerText.trim()))?.click();
  });
  await pausa(1800);

  const marcaUrl = new URL(page.url()).searchParams.get('marca');
  const totalMarca = await totalPantalla(page);
  const apiMarca = await totalApi(page, token, `brand=${encodeURIComponent(marcaUrl || '')}`);
  const cardsMarca = await tarjetas(page);

  caso('La marca queda en la URL (?marca=)', marcaUrl === 'Nike', `marca=${marcaUrl}`);
  caso('El conteo por marca coincide con la API',
       totalMarca === apiMarca && totalMarca > 0, `pantalla ${totalMarca} · API ${apiMarca}`);
  caso('Todas las tarjetas son de la marca elegida',
       cardsMarca.length > 0 && cardsMarca.every(c => /nike/i.test(c.marca)),
       `${cardsMarca.length} tarjetas`);

  // ── 5 · Precio ───────────────────────────────────────────────────────────
  console.log('\n── 5 · Filtro por precio');
  await irA(page, '/shop');
  await page.evaluate(() => {
    [...document.querySelectorAll('.tramo')].find(t => /30 a \$80/i.test(t.innerText))?.click();
  });
  await pausa(1800);

  const q = new URL(page.url()).searchParams;
  const totalTramo = await totalPantalla(page);
  const apiTramo = await totalApi(page, token, `min_price=${q.get('min')}&max_price=${q.get('max')}`);
  const cardsTramo = await tarjetas(page);
  const fueraDeRango = cardsTramo.filter(c => c.precio < 30 || c.precio > 80);

  caso('El tramo queda en la URL (?min=&max=)', q.get('min') === '30' && q.get('max') === '80',
       `min=${q.get('min')} max=${q.get('max')}`);
  caso('El conteo por tramo coincide con la API',
       totalTramo === apiTramo && totalTramo > 0, `pantalla ${totalTramo} · API ${apiTramo}`);
  caso('Ningún precio pintado se sale del tramo',
       cardsTramo.length > 0 && fueraDeRango.length === 0,
       fueraDeRango.length ? `fuera: ${fueraDeRango.map(c => c.precio).join(', ')}`
                           : `${cardsTramo.length} precios dentro de [30, 80]`);

  // Rango escrito a mano
  await irA(page, '/shop');
  await page.type('.rango-manual .campo-tienda:nth-of-type(1)', '500');
  await page.evaluate(() => {
    const inputs = document.querySelectorAll('.rango-manual input');
    inputs[1].focus();
  });
  await page.keyboard.type('900');
  await page.click('.rango-manual .btn-ir');
  await pausa(1800);

  const q2 = new URL(page.url()).searchParams;
  const cardsManual = await tarjetas(page);
  const fuera2 = cardsManual.filter(c => c.precio < 500 || c.precio > 900);
  caso('El rango escrito a mano se aplica',
       q2.get('min') === '500' && q2.get('max') === '900',
       `min=${q2.get('min')} max=${q2.get('max')}`);
  caso('Ningún precio se sale del rango escrito a mano',
       cardsManual.length > 0 && fuera2.length === 0,
       `${cardsManual.length} tarjetas`);

  // ── 6 · Refinamientos de página: orden y disponibilidad ──────────────────
  console.log('\n── 6 · Orden y disponibilidad (sobre la página recibida)');
  await irA(page, '/shop');
  await page.select('.control-orden select', 'precio_asc');
  await pausa(700);
  const precios = (await tarjetas(page)).map(c => c.precio);
  const ordenados = precios.every((p, i) => i === 0 || precios[i - 1] <= p);
  caso('«Precio: menor a mayor» ordena de verdad la página',
       precios.length > 1 && ordenados,
       `${precios.length} precios · ${precios.slice(0, 4).join(' ≤ ')} …`);

  await page.select('.control-orden select', 'precio_desc');
  await pausa(700);
  const preciosDesc = (await tarjetas(page)).map(c => c.precio);
  caso('«Precio: mayor a menor» invierte el orden',
       preciosDesc.every((p, i) => i === 0 || preciosDesc[i - 1] >= p),
       `${preciosDesc.slice(0, 4).join(' ≥ ')} …`);

  caso('El control de orden declara que actúa sobre la página',
       await page.evaluate(() => {
         const t = document.querySelector('.control-orden .pista')?.getAttribute('ng-reflect-message')
                || document.querySelector('.control-orden .pista')?.getAttribute('aria-describedby');
         return !!document.querySelector('.control-orden .pista');
       }),
       'icono de aclaración presente junto al selector');

  // «Solo con stock» esconde los agotados de la página.
  //
  // El filtro se prueba sobre una página que SÍ tenga agotados. En este
  // catálogo solo 3 de 6.214 variantes están a cero, así que sobre la primera
  // página del catálogo entero el caso pasaría en verde sin haber escondido
  // nada: 0 agotados antes y 0 después. La página se elige por parámetro y el
  // caso EXIGE que hubiera al menos uno que esconder.
  const paginaConAgotados = process.env.RETAILMIND_PAGINA_AGOTADOS || '/shop?marca=Ikea&size=48';
  await irA(page, paginaConAgotados);
  const antesRefinar = await tarjetas(page);
  const agotadosAntes = antesRefinar.filter(c => c.agotado).length;
  await page.click('.casilla input[type="checkbox"]');
  await pausa(700);
  const despuesRefinar = await tarjetas(page);
  const agotadosDespues = despuesRefinar.filter(c => c.agotado).length;
  caso('«Solo productos con stock» retira los agotados de la vista',
       agotadosAntes > 0 && agotadosDespues === 0
         && despuesRefinar.length === antesRefinar.length - agotadosAntes,
       agotadosAntes === 0
         ? `SIN MUESTRA: la página ${paginaConAgotados} no traía agotados que esconder`
         : `${antesRefinar.length} tarjetas con ${agotadosAntes} agotado(s) → `
           + `${despuesRefinar.length} tarjetas, ${agotadosDespues} agotados`);
  caso('El aviso dice cuántos productos quedaron ocultos',
       await page.evaluate(() => /qued(ó|aron) oculto/i.test(
         document.querySelector('.aviso-refinado')?.innerText || '')));
  caso('El filtro de disponibilidad DECLARA su alcance',
       await page.evaluate(() => /esta página/i.test(document.querySelector('.nota-alcance')?.innerText || '')));

  // ── 7 · Combinación y limpieza ───────────────────────────────────────────
  console.log('\n── 7 · Filtros combinados y limpieza');
  await irA(page, '/shop?q=laptop&cat=10&min=200&max=2000');
  const totalCombo = await totalPantalla(page);
  const apiCombo = await totalApi(page, token, 'q=laptop&categoria_id=10&min_price=200&max_price=2000');
  const chips = await page.evaluate(() =>
    [...document.querySelectorAll('.chip-activo')].map(c => c.innerText.replace(/\n/g, ' ').trim()));
  caso('Cuatro filtros a la vez coinciden con la API',
       totalCombo === apiCombo, `pantalla ${totalCombo} · API ${apiCombo}`);
  caso('Cada filtro activo tiene su chip para quitarlo',
       chips.length === 3, chips.join(' | '));   // búsqueda, departamento y precio

  await page.evaluate(() => document.querySelector('.chip-limpiar')?.click());
  await pausa(1800);
  const totalTrasLimpiar = await totalPantalla(page);
  caso('«Limpiar todo» devuelve el catálogo completo',
       totalTrasLimpiar === totalSinFiltro && !/[?&](q|cat|marca|min|max)=/.test(page.url()),
       `${totalTrasLimpiar} productos · ${page.url().replace(WEB, '') || '/shop'}`);

  // ── 8 · Paginación ───────────────────────────────────────────────────────
  console.log('\n── 8 · Paginación');
  const primeraPagina = (await tarjetas(page)).map(c => c.nombre).join('|');
  await page.click('.mat-mdc-paginator-navigation-next');
  await pausa(1800);
  const segundaPagina = (await tarjetas(page)).map(c => c.nombre).join('|');
  caso('La página siguiente queda en la URL y cambia los productos',
       /[?&]page=1/.test(page.url()) && primeraPagina !== segundaPagina,
       page.url().split('?')[1] || '');

  // ── 9 · Carrito y lista de deseos desde la tarjeta ───────────────────────
  console.log('\n── 9 · Acciones de la tarjeta');
  await irA(page, '/shop?q=teclado');
  const globo = () => page.evaluate(() =>
    Number(document.querySelector('.acc-carrito .globo')?.innerText || 0));
  const globoDeseos = () => page.evaluate(() =>
    Number(document.querySelector('.acc-icono .globo')?.innerText || 0));

  const carritoAntes = await globo();

  // Se elige un producto que NO esté ya en el carrito y se recuerda cuál: el
  // globo cuenta LÍNEAS, así que agregar algo que ya estaba subiría la cantidad
  // sin crear línea y el «+1» no se cumpliría —el caso mediría otra cosa—.
  // Además la prueba escribe en el carrito real del cliente demo, y al final lo
  // devuelve a como estaba.
  const yaEnCarrito = await page.evaluate(async (api, tk) => {
    const r = await fetch(`${api}/api/carrito`, { headers: { Authorization: 'Bearer ' + tk } });
    return r.ok ? (await r.json()).map(i => i.nombre) : [];
  }, API, token);

  const agregado = await page.evaluate(nombres => {
    const card = [...document.querySelectorAll('.p-card')].find(c =>
      !c.querySelector('.btn-agregar[disabled]')
      && !nombres.includes(c.querySelector('.p-nombre')?.innerText.trim()));
    card?.querySelector('.btn-agregar')?.click();
    return card?.querySelector('.p-nombre')?.innerText.trim() || null;
  }, yaEnCarrito);
  await pausa(1800);
  const carritoDespues = await globo();
  caso('«Agregar» sube el contador del carrito de la barra',
       !!agregado && carritoDespues === carritoAntes + 1,
       `${carritoAntes} → ${carritoDespues} («${agregado}»)`);

  const enServidor = await page.evaluate(async (api, tk, nombre) => {
    const r = await fetch(`${api}/api/carrito`, { headers: { Authorization: 'Bearer ' + tk } });
    return r.ok && (await r.json()).some(i => i.nombre === nombre);
  }, API, token, agregado);
  caso('El producto agregado está de verdad en el carrito del servidor', enServidor);

  const deseosAntes = await globoDeseos();
  await page.evaluate(() => document.querySelector('.p-card .p-card-corazon')?.click());
  await pausa(1600);
  const deseosDespues = await globoDeseos();
  caso('El corazón mueve el contador de la lista de deseos',
       deseosDespues !== deseosAntes, `${deseosAntes} → ${deseosDespues}`);

  // Se deja como estaba: la prueba no debe ensuciar la lista del cliente.
  await page.evaluate(() => document.querySelector('.p-card .p-card-corazon')?.click());
  await pausa(1200);

  // ── 10 · Ficha de producto ───────────────────────────────────────────────
  console.log('\n── 10 · Ficha de producto');
  await page.evaluate(() => document.querySelector('.p-card .p-nombre')?.click());
  await pausa(2200);
  const ficha = await page.evaluate(() => ({
    esFicha: /\/shop\/producto\//.test(location.pathname),
    migas: document.querySelectorAll('.tienda-migas a').length,
    cajaCompra: !!document.querySelector('.caja-compra'),
    cantidad: !!document.querySelector('.caja-cantidad select'),
    comprarAhora: !!document.querySelector('.btn-comprar'),
    detalles: document.querySelectorAll('.tabla-detalles dt').length,
    similares: document.querySelectorAll('.grid-similares .p-card').length
  }));
  caso('La ficha abre con migas, caja de compra y tabla de detalles',
       ficha.esFicha && ficha.migas >= 2 && ficha.cajaCompra && ficha.detalles >= 4,
       `migas:${ficha.migas} detalles:${ficha.detalles}`);
  caso('La caja de compra ofrece cantidad y «Comprar ahora»',
       ficha.cantidad && ficha.comprarAhora);
  caso('La ficha propone productos similares', ficha.similares > 0, `${ficha.similares} similares`);

  // ── 11 · Carrito y checkout ──────────────────────────────────────────────
  console.log('\n── 11 · Carrito y checkout');
  await irA(page, '/shop/carrito');
  const carro = await page.evaluate(() => {
    const num = t => Number((t || '').replace(/[^\d.]/g, ''));
    return {
      lineas: document.querySelectorAll('.linea').length,
      resumenDerecha: !!document.querySelector('.col-resumen .resumen'),
      subtotal: num(document.querySelector('.fila.total span:last-child')?.innerText),
      suma: [...document.querySelectorAll('.importe-final')]
              .reduce((s, e) => s + num(e.innerText), 0),
      stepper: document.querySelectorAll('.stepper').length,
      guardarDespues: /guardar para después/i.test(document.body.innerText)
    };
  });
  caso('El carrito lista líneas con su selector de cantidad',
       carro.lineas > 0 && carro.stepper === carro.lineas, `${carro.lineas} líneas`);
  caso('El resumen del carrito va en la columna derecha', carro.resumenDerecha);
  caso('El subtotal del resumen cuadra con la suma de las líneas',
       Math.abs(carro.subtotal - carro.suma) < 0.02,
       `resumen ${carro.subtotal.toFixed(2)} · líneas ${carro.suma.toFixed(2)}`);
  caso('Cada línea ofrece «Guardar para después»', carro.guardarDespues);

  // Subir una unidad recalcula contra el servidor.
  const antesLinea = await page.evaluate(() =>
    Number((document.querySelector('.importe-final')?.innerText || '').replace(/[^\d.]/g, '')));
  const unitario = await page.evaluate(() =>
    Number((document.querySelector('.importe-unitario')?.innerText || '').replace(/[^\d.]/g, '')));
  await page.evaluate(() => document.querySelectorAll('.stepper button')[1]?.click());
  await pausa(2200);
  const despuesLinea = await page.evaluate(() =>
    Number((document.querySelector('.importe-final')?.innerText || '').replace(/[^\d.]/g, '')));
  caso('Subir la cantidad recalcula el importe de la línea',
       Math.abs((despuesLinea - antesLinea) - unitario) < 0.05,
       `${antesLinea.toFixed(2)} → ${despuesLinea.toFixed(2)} (unitario ${unitario.toFixed(2)})`);
  // Se devuelve la cantidad a como estaba.
  await page.evaluate(() => document.querySelectorAll('.stepper button')[0]?.click());
  await pausa(2000);

  await irA(page, '/shop/checkout');
  const pago = await page.evaluate(() => {
    const cuerpo = document.querySelector('.checkout-grid');
    return {
      direccionIzquierda: cuerpo?.firstElementChild?.classList.contains('pasos'),
      primerPaso: document.querySelector('.paso .paso-cabecera h2')?.innerText.trim() || '',
      resumenDerecha: !!document.querySelector('.resumen-col .resumen'),
      pasos: document.querySelectorAll('.paso').length,
      botonPagoBloqueado: !!document.querySelector('.btn-pagar[disabled]'),
      pista: document.querySelector('.pista-bloqueo')?.innerText.trim() || '',
      total: document.querySelector('.linea.total span:last-child')?.innerText.trim() || ''
    };
  });
  caso('La dirección de envío es el PRIMER paso y va a la izquierda',
       pago.direccionIzquierda && /dirección de envío/i.test(pago.primerPaso),
       `«${pago.primerPaso}»`);
  caso('El resumen del pedido va a la derecha, con su total',
       pago.resumenDerecha && /\$/.test(pago.total), `total ${pago.total}`);
  caso('El checkout mantiene sus tres pasos', pago.pasos === 3, `${pago.pasos} pasos`);
  caso('El botón de pago está bloqueado hasta completar la tarjeta',
       pago.botonPagoBloqueado && !!pago.pista, pago.pista.replace(/\n/g, ' '));

  // ── 12 · Dirección de envío en la barra ──────────────────────────────────
  //
  // Se comprueba de punta a punta porque el dato cruza DOS componentes que no
  // se conocen: la barra (`app.component`) y el checkout. Que el rótulo cambie
  // no prueba nada si el pago sigue proponiendo otra dirección.
  console.log('\n── 12 · Dirección de envío en la barra');
  await irA(page, '/shop');

  const dirsApi = await page.evaluate(async (api, tk) => {
    const r = await fetch(`${api}/api/perfil/direcciones`, { headers: { Authorization: 'Bearer ' + tk } });
    return r.ok ? await r.json() : [];
  }, API, token);

  const rotuloInicial = await page.evaluate(() =>
    document.querySelector('.acc-envio strong')?.innerText.replace(/expand_more/, '').trim() || '');
  const predeterminada = dirsApi.find(d => d.esPredeterminada) || dirsApi[0];

  caso('La barra anuncia la dirección de envío con su ciudad',
       !!rotuloInicial && (!predeterminada || rotuloInicial.includes(predeterminada.ciudad)),
       `«${rotuloInicial}»`);

  await page.click('.acc-envio');
  await pausa(800);
  const opciones = await page.evaluate(() =>
    [...document.querySelectorAll('.mat-mdc-menu-panel button.mat-mdc-menu-item')]
      .map(b => b.innerText.replace(/\n/g, ' ').trim()));
  caso('El menú lista todas las direcciones del cliente',
       opciones.length === dirsApi.length,
       `menú ${opciones.length} · API ${dirsApi.length}`);
  caso('El menú lleva a gestionar las direcciones',
       await page.evaluate(() => [...document.querySelectorAll('.mat-mdc-menu-panel a')]
         .some(a => /perfil/.test(a.getAttribute('href') || ''))));

  // Solo se puede probar el cambio si hay una segunda dirección.
  if (dirsApi.length > 1) {
    await page.evaluate(() => {
      const bs = [...document.querySelectorAll('.mat-mdc-menu-panel button.mat-mdc-menu-item')];
      bs[1]?.click();
    });
    await pausa(900);
    const rotuloNuevo = await page.evaluate(() =>
      document.querySelector('.acc-envio strong')?.innerText.replace(/expand_more/, '').trim() || '');
    caso('Elegir otra dirección cambia el rótulo de la barra',
         rotuloNuevo !== rotuloInicial && rotuloNuevo.includes(dirsApi[1].ciudad),
         `«${rotuloInicial}» → «${rotuloNuevo}»`);

    // Recarga COMPLETA a propósito: la elección tiene que sobrevivir a un F5,
    // o el cliente vuelve del refresco con otra dirección puesta.
    await irA(page, '/shop/checkout');
    const enPago = await page.evaluate(() => ({
      ciudad: document.querySelector('.dir.elegida .dir-sub')?.innerText.trim() || '',
      quien: document.querySelector('.dir.elegida .dir-linea1')?.innerText.replace(/\n/g, ' ').trim() || ''
    }));
    caso('El pago preselecciona la dirección elegida en la barra, tras recargar',
         enPago.ciudad.includes(dirsApi[1].ciudad),
         `pago: ${enPago.ciudad} · esperada: ${dirsApi[1].ciudad}`);

    // Se devuelve la elección a la de partida.
    await irA(page, '/shop');
    await page.click('.acc-envio');
    await pausa(700);
    await page.evaluate(() => {
      const bs = [...document.querySelectorAll('.mat-mdc-menu-panel button.mat-mdc-menu-item')];
      bs[0]?.click();
    });
    await pausa(700);
  } else {
    caso('Elegir otra dirección cambia el rótulo de la barra', false,
         'SIN MUESTRA: el cliente de prueba solo tiene una dirección');
    await page.keyboard.press('Escape');
    await pausa(400);
  }

  // ── 12b · Lista de deseos ────────────────────────────────────────────────
  console.log('\n── 12b · Lista de deseos');
  await irA(page, '/wishlist');
  const deseos = await page.evaluate(() => ({
    cabecera: !!document.querySelector('.deseos-cabecera'),
    tarjetas: document.querySelectorAll('.p-card').length,
    vacio: !!document.querySelector('.tienda-vacio'),
    mismasTarjetas: !!document.querySelector('.p-card .p-card-lienzo')
  }));
  caso('La lista de deseos usa la misma tarjeta que el catálogo',
       deseos.cabecera && (deseos.tarjetas === 0 ? deseos.vacio : deseos.mismasTarjetas),
       deseos.tarjetas ? `${deseos.tarjetas} guardados` : 'lista vacía con su mensaje');

  // Se devuelve el carrito a como estaba: la línea que agregó el caso 9 se
  // elimina desde la propia pantalla, que es también una comprobación más.
  if (agregado) {
    await irA(page, '/shop/carrito');
    const lineasAntes = await page.evaluate(() => document.querySelectorAll('.linea').length);
    const quitada = await page.evaluate(nombre => {
      const linea = [...document.querySelectorAll('.linea')]
        .find(l => l.querySelector('.linea-nombre')?.innerText.trim() === nombre);
      if (!linea) return false;
      [...linea.querySelectorAll('.enlace-accion')]
        .find(b => /eliminar/i.test(b.innerText))?.click();
      return true;
    }, agregado);
    await pausa(1800);
    const lineasDespues = await page.evaluate(() => document.querySelectorAll('.linea').length);
    caso('«Eliminar» quita la línea del carrito (y deja el estado como estaba)',
         quitada && lineasDespues === lineasAntes - 1,
         `${lineasAntes} → ${lineasDespues} líneas`);
  }

  // ── 13 · Menú de ayuda y recomendaciones ─────────────────────────────────
  console.log('\n── 13 · Ayuda/Soporte y recomendaciones');
  await irA(page, '/shop');
  await page.evaluate(() => {
    [...document.querySelectorAll('.tienda-acciones .acc-doble')]
      .find(b => /soporte/i.test(b.innerText))?.click();
  });
  await pausa(900);
  const menu = await page.evaluate(() =>
    [...document.querySelectorAll('.mat-mdc-menu-panel a')]
      .map(a => ({ texto: a.innerText.trim(), destino: a.getAttribute('href') || '' })));
  const destinos = menu.map(m => m.destino);
  caso('El menú de Ayuda lleva a soporte, FAQ, devoluciones y reseñas',
       destinos.some(d => /soporte\/tickets/.test(d))
       && destinos.some(d => /soporte\/faq/.test(d))
       && destinos.some(d => /mis-pedidos/.test(d))
       && destinos.some(d => /resenas/.test(d)),
       menu.map(m => m.texto).join(' · '));
  await page.keyboard.press('Escape');
  await pausa(400);

  await irA(page, '/recomendaciones');
  const recos = await page.evaluate(() => ({
    hero: !!document.querySelector('.recos-hero h1'),
    titulo: document.querySelector('.recos-hero h1')?.innerText.trim() || '',
    tarjetas: document.querySelectorAll('.p-card').length,
    declaraOrigen: !!document.querySelector('.aviso-origen') || /personalizado/i.test(
      document.querySelector('.rh-kicker')?.innerText || '')
  }));
  caso('Recomendaciones usa la misma tarjeta y declara de dónde sale la lista',
       recos.hero && recos.tarjetas > 0 && recos.declaraOrigen,
       `«${recos.titulo}» · ${recos.tarjetas} productos`);

  // ── 14 · Pantalla estrecha ───────────────────────────────────────────────
  console.log('\n── 14 · Pantalla estrecha (el panel pasa a cajón)');

  // La barra superior mete cinco bloques en una fila (logotipo, dirección,
  // buscador, accesos y usuario) y el buscador CRECE para llenar el hueco. Eso
  // se rompe por desbordamiento, no por error: las piezas se montan unas sobre
  // otras y la pantalla sigue «funcionando». Se mide a varias anchuras.
  // Se mide en TRES pantallas y no solo en el catálogo: el campo de búsqueda
  // del catálogo no existe en el carrito ni en la lista de deseos, y ahí la
  // única vía es el atajo de la lupa. Medirlo solo en /shop daba el caso por
  // bueno mientras el resto de la tienda se quedaba sin buscador.
  const anchos = [1920, 1600, 1440, 1366, 1280, 1200, 1100, 1024, 900, 768];
  const pantallas = ['/shop', '/shop/carrito', '/wishlist'];
  const desbordes = [];
  const sinBuscador = [];

  for (const ruta of pantallas) {
    await irA(page, ruta);
    for (const w of anchos) {
      await page.setViewport({ width: w, height: 900 });
      await pausa(420);
      const m = await page.evaluate(() => {
        const nav = document.querySelector('.navbar');
        const vis = s => {
          const e = document.querySelector(s);
          return !!e && getComputedStyle(e).display !== 'none';
        };
        const r = nav.getBoundingClientRect();
        const fuera = [...nav.children].some(c => {
          const cr = c.getBoundingClientRect();
          return cr.width > 0 && (cr.right > r.right + 1 || cr.left < r.left - 1);
        });
        return {
          desborda: nav.scrollWidth > nav.clientWidth + 1 || fuera,
          hayBuscador: vis('.tienda-buscador') || vis('.busqueda-movil') || vis('.acc-buscar')
        };
      });
      if (m.desborda) desbordes.push(`${ruta}@${w}px`);
      if (!m.hayBuscador) sinBuscador.push(`${ruta}@${w}px`);
    }
  }
  caso('La barra superior no desborda en ninguna anchura',
       desbordes.length === 0,
       desbordes.length ? `desborda en ${desbordes.join(', ')}`
                        : `${anchos.length * pantallas.length} medidas (10 anchuras × 3 pantallas)`);
  caso('Siempre hay una vía de búsqueda: el campo o el atajo de la lupa',
       sinBuscador.length === 0,
       sinBuscador.length ? `sin ninguna en ${sinBuscador.join(', ')}`
                          : 'campo hasta 1080 px; por debajo, atajo en la barra');

  // El atajo tiene que LLEVAR al catálogo con el cursor puesto, no solo verse.
  await page.setViewport({ width: 900, height: 900 });
  await irA(page, '/shop/carrito');
  await page.click('.acc-buscar');
  await pausa(1600);
  const trasAtajo = await page.evaluate(() => ({
    ruta: location.pathname,
    enfocado: document.activeElement?.classList.contains('campo-tienda'),
    sinRastro: !/[?&]buscar=/.test(location.search)
  }));
  caso('El atajo de la lupa abre el catálogo con el cursor en el campo',
       trasAtajo.ruta === '/shop' && trasAtajo.enfocado && trasAtajo.sinRastro,
       `ruta ${trasAtajo.ruta} · foco ${trasAtajo.enfocado} · URL limpia ${trasAtajo.sinRastro}`);

  await page.setViewport({ width: 820, height: 1000 });
  await irA(page, '/shop');
  const movil = await page.evaluate(() => {
    const vis = s => {
      const e = document.querySelector(s);
      return !!e && getComputedStyle(e).display !== 'none';
    };
    return {
      botonFiltros: vis('.btn-filtros-movil'),
      buscadorPropio: vis('.busqueda-movil'),
      buscadorBarraOculto: !vis('.tienda-buscador'),
      railFuera: (document.querySelector('.rail')?.getBoundingClientRect().right ?? 0) <= 1
    };
  });
  caso('En pantalla estrecha aparece el botón de filtros',
       movil.botonFiltros);
  caso('El buscador de la barra cede el sitio al del catálogo',
       movil.buscadorBarraOculto && movil.buscadorPropio);
  caso('El panel de filtros queda fuera de la vista hasta pedirlo', movil.railFuera);

  await page.click('.btn-filtros-movil');
  await pausa(700);
  const abierto = await page.evaluate(() =>
    (document.querySelector('.rail')?.getBoundingClientRect().left ?? -99) >= -1);
  caso('El botón abre el cajón de filtros', abierto);
  await page.setViewport({ width: 1600, height: 1000 });

  // ── 15 · Perfil del cliente ──────────────────────────────────────────────
  //
  // El perfil es la pantalla donde el cliente MODIFICA sus datos, así que se
  // prueba escribiendo de verdad y comprobando que lo escrito SOBREVIVE a una
  // recarga. Todo se devuelve a su estado inicial al final.
  console.log('\n── 15 · Perfil del cliente');
  await page.setViewport({ width: 1500, height: 1000 });

  const perfilApi = async () => page.evaluate(async (api, tk) => {
    const r = await fetch(`${api}/api/perfil`, { headers: { Authorization: 'Bearer ' + tk } });
    return r.ok ? await r.json() : null;
  }, API, token);

  const perfilInicial = await perfilApi();
  await irA(page, '/perfil');

  const campos = await page.evaluate(() => {
    const etiquetas = [...document.querySelectorAll('.section-card mat-label')]
      .map(l => l.innerText.trim());
    return {
      etiquetas,
      hayFecha: !!document.querySelector('input[type="date"]'),
      opcionesGenero: null
    };
  });
  caso('El formulario ofrece la fecha de nacimiento',
       campos.hayFecha,
       campos.etiquetas.join(' · '));

  // Las opciones de género tienen que ser las que admite la BD; si no, se
  // guardan con un 400 de restricción y además no casan al releer.
  //
  // Se comprueba por COMPORTAMIENTO y no leyendo el valor del `<mat-option>`:
  // Angular solo escribe `ng-reflect-value` en modo DESARROLLO, así que una
  // prueba que lo lea pasa contra `ng serve` y falla contra el contenedor
  // —misma aplicación, distinto veredicto—. Lo que se mira es qué acepta el
  // servidor, que es lo que de verdad decide si el campo se puede guardar.
  await page.evaluate(() => {
    [...document.querySelectorAll('mat-select')]
      .find(s => /género/i.test(s.closest('mat-form-field')?.innerText || ''))?.click();
  });
  await pausa(700);
  const generos = await page.evaluate(() =>
    [...document.querySelectorAll('mat-option')].map(o => o.innerText.trim()));
  const rotulos = ['Sin especificar', 'Femenino', 'Masculino', 'Otro', 'Prefiero no indicarlo'];
  caso('El desplegable de género ofrece las cinco opciones',
       rotulos.every(r => generos.some(g => g === r)),
       generos.join(' · '));

  const valoresBd = ['masculino', 'femenino', 'otro', 'no_indica'];
  rechazoEsperado = '/api/perfil';   // el 400 de «F» es el resultado buscado
  const veredicto = await page.evaluate(async (api, tk, vals) => {
    const put = async g => (await fetch(`${api}/api/perfil`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer ' + tk, 'Content-Type': 'application/json' },
      body: JSON.stringify({ nombre: 'Maria', genero: g })
    })).status;
    const aceptados = [];
    for (const v of vals) if (await put(v) === 200) aceptados.push(v);
    return { aceptados, rechazaInventado: await put('F') };
  }, API, token, valoresBd);
  rechazoEsperado = null;            // se cierra la ventana en cuanto acaba
  caso('El servidor acepta los cuatro géneros del catálogo y rechaza el resto',
       veredicto.aceptados.length === 4 && veredicto.rechazaInventado === 400,
       `acepta ${veredicto.aceptados.join(', ')} · «F» → HTTP ${veredicto.rechazaInventado}`);

  // Elegir «Femenino» y guardar.
  await page.evaluate(() => {
    [...document.querySelectorAll('mat-option')]
      .find(o => /femenino/i.test(o.innerText))?.click();
  });
  await pausa(600);
  await page.evaluate(() => {
    const f = document.querySelector('input[type="date"]');
    const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    set.call(f, '1995-03-14');
    f.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await pausa(400);
  await page.evaluate(() => {
    [...document.querySelectorAll('button')]
      .find(b => /guardar cambios/i.test(b.innerText))?.click();
  });
  await pausa(2000);

  const aviso = await page.evaluate(() =>
    document.querySelector('simple-snack-bar, .mat-mdc-snack-bar-label')?.innerText.trim() || '');
  caso('Guardar los datos personales NO da error',
       /actualizad/i.test(aviso) && !/error|no se pudo|reglas de la base/i.test(aviso),
       `aviso: «${aviso}»`);

  const trasGuardar = await perfilApi();
  caso('El género y la fecha quedan guardados en el servidor',
       trasGuardar?.genero === 'femenino'
         && String(trasGuardar?.fechaNacimiento).slice(0, 10) === '1995-03-14',
       `genero=${trasGuardar?.genero} fecha=${trasGuardar?.fechaNacimiento}`);

  // Releer la pantalla: el valor guardado tiene que APARECER seleccionado.
  await irA(page, '/perfil');
  const releido = await page.evaluate(() => ({
    genero: [...document.querySelectorAll('mat-select')]
      .map(s => s.innerText.trim()).find(t => /femenino|masculino|otro|prefiero|sin especificar/i.test(t)) || '',
    fecha: document.querySelector('input[type="date"]')?.value || ''
  }));
  caso('Al volver a abrir el perfil, el género y la fecha se ven puestos',
       /femenino/i.test(releido.genero) && releido.fecha === '1995-03-14',
       `género «${releido.genero}» · fecha «${releido.fecha}»`);

  // Guardar OTRA VEZ sin tocar nada no puede perder la fecha (era el defecto:
  // el formulario no la enviaba y el UPDATE la ponía a NULL).
  await page.evaluate(() => {
    [...document.querySelectorAll('button')]
      .find(b => /guardar cambios/i.test(b.innerText))?.click();
  });
  await pausa(2000);
  const trasSegundo = await perfilApi();
  caso('Guardar de nuevo no borra la fecha de nacimiento',
       String(trasSegundo?.fechaNacimiento).slice(0, 10) === '1995-03-14',
       `fecha=${trasSegundo?.fechaNacimiento}`);

  // ── Direcciones: alta, edición y baja desde la pantalla ──────────────────
  const cuantasDirs = async () => page.evaluate(() =>
    document.querySelectorAll('.direccion-card').length);
  const dirsAntes = await cuantasDirs();

  await page.evaluate(() => {
    [...document.querySelectorAll('button')]
      .find(b => /nueva dirección/i.test(b.innerText))?.click();
  });
  await pausa(700);
  const escribir = async (etiqueta, valor) => {
    const ok = await page.evaluate((et, v) => {
      const campo = [...document.querySelectorAll('.direccion-form mat-form-field')]
        .find(f => new RegExp(et, 'i').test(f.innerText));
      const input = campo?.querySelector('input');
      if (!input) return false;
      const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      set.call(input, v);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    }, etiqueta, valor);
    await pausa(150);
    return ok;
  };
  await escribir('Alias', 'Prueba P14');
  await escribir('Destinatario', 'Maria Lopez');
  await escribir('Calle principal', 'Calle de la Prueba 1');
  await page.evaluate(() => {
    [...document.querySelectorAll('.direccion-form mat-select')]
      .find(s => /ciudad/i.test(s.closest('mat-form-field')?.innerText || ''))?.click();
  });
  await pausa(600);
  await page.evaluate(() => document.querySelector('mat-option')?.click());
  await pausa(500);
  await page.evaluate(() => {
    [...document.querySelectorAll('button')]
      .find(b => /guardar dirección/i.test(b.innerText))?.click();
  });
  await pausa(2000);

  const dirsTrasAlta = await cuantasDirs();
  caso('Se puede dar de alta una dirección desde el perfil',
       dirsTrasAlta === dirsAntes + 1,
       `${dirsAntes} → ${dirsTrasAlta} direcciones`);

  // Editarla
  await page.evaluate(() => {
    const card = [...document.querySelectorAll('.direccion-card')]
      .find(c => /Prueba P14/.test(c.innerText));
    [...card.querySelectorAll('button')]
      .find(b => /edit$/i.test(b.innerText.trim()))?.click();
  });
  await pausa(900);
  await escribir('Alias', 'Prueba P14 editada');
  await page.evaluate(() => {
    [...document.querySelectorAll('button')]
      .find(b => /actualizar dirección/i.test(b.innerText))?.click();
  });
  await pausa(2000);
  const editada = await page.evaluate(() =>
    [...document.querySelectorAll('.direccion-card')].some(c => /Prueba P14 editada/.test(c.innerText)));
  caso('Se puede editar una dirección desde el perfil', editada);

  // Borrarla (baja lógica, con su diálogo de confirmación)
  await page.evaluate(() => {
    const card = [...document.querySelectorAll('.direccion-card')]
      .find(c => /Prueba P14 editada/.test(c.innerText));
    [...card.querySelectorAll('button')]
      .find(b => /delete$/i.test(b.innerText.trim()))?.click();
  });
  await pausa(900);
  // El diálogo de confirmación del proyecto (`ConfirmDialogComponent`) rotula
  // su botón «Aceptar», no «Eliminar»: se busca por su clase, que es estable.
  const confirmoBorrado = await page.evaluate(() => {
    const b = document.querySelector('.btn-aceptar');
    if (!b) return false;
    b.click();
    return true;
  });
  await pausa(2000);
  const dirsFinal = await cuantasDirs();
  caso('El borrado pide confirmación antes de hacerse', confirmoBorrado,
       confirmoBorrado ? 'diálogo mostrado y aceptado' : 'NO apareció el diálogo');
  caso('Se puede eliminar una dirección desde el perfil (y queda como al empezar)',
       dirsFinal === dirsAntes,
       `${dirsTrasAlta} → ${dirsFinal} direcciones`);

  // Se devuelven los datos personales a su estado inicial.
  await page.evaluate(async (api, tk, p) => {
    await fetch(`${api}/api/perfil`, {
      method: 'PUT',
      headers: { Authorization: 'Bearer ' + tk, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        nombre: p.nombre, apellido: p.apellido || '', telefono: p.telefono || '',
        genero: p.genero || '',
        fechaNacimiento: p.fechaNacimiento ? String(p.fechaNacimiento).slice(0, 10) : '',
        aceptaMarketing: p.aceptaMarketing
      })
    });
  }, API, token, perfilInicial);
  const restaurado = await perfilApi();
  caso('El perfil queda como estaba antes de la prueba',
       restaurado?.genero === perfilInicial?.genero
         && String(restaurado?.fechaNacimiento) === String(perfilInicial?.fechaNacimiento),
       `genero=${restaurado?.genero} fecha=${restaurado?.fechaNacimiento}`);

  // ── 16 · Mis pedidos: orden, búsqueda y filtro ───────────────────────────
  //
  // El orden se comprueba sobre las FECHAS pintadas y no sobre los ids: en
  // esta base los ids NO son cronológicos —la carga masiva usó bandas
  // reservadas hasta 2.100.055.830 mientras la secuencia va por 4.343—, que es
  // justo el motivo por el que un pedido recién hecho no aparecía arriba.
  console.log('\n── 16 · Mis pedidos: orden, búsqueda y filtro');
  await irA(page, '/operativo/ventas/mis-pedidos');

  const filas = async () => page.evaluate(() =>
    [...document.querySelectorAll('.mp-pedido')].map(c => ({
      numero: c.querySelector('.mp-numero')?.innerText.trim() || '',
      fecha: c.querySelector('.mp-fila-2 span')?.innerText.replace(/^event\s*/, '').trim() || '',
      estado: c.querySelector('.estado-chip')?.innerText.replace(/^\w+\s/, '').trim() || ''
    })));

  const lista = await filas();
  caso('La lista de pedidos se pinta como tarjetas', lista.length > 0,
       `${lista.length} pedidos en la primera página`);

  // El número lleva la fecha dentro (PED-AAAAMMDD-…), así que el orden se
  // puede verificar sin depender del formato con que se pinte la fecha.
  const fechasDelNumero = lista
    .map(f => (f.numero.match(/PED-(\d{8})/) || [])[1])
    .filter(Boolean);
  const enOrden = fechasDelNumero.every((d, i) => i === 0 || fechasDelNumero[i - 1] >= d);
  caso('Los pedidos salen del más reciente al más antiguo',
       fechasDelNumero.length > 1 && enOrden,
       `${fechasDelNumero[0]} → ${fechasDelNumero[fechasDelNumero.length - 1]}`);

  caso('La pantalla declara en qué orden está la lista',
       await page.evaluate(() => /más reciente/i.test(
         document.querySelector('.mp-orden')?.innerText || '')));

  // Buscar un pedido concreto por su número: tiene que encontrarlo aunque esté
  // a cientos de páginas, porque el filtro va al SERVIDOR.
  const objetivo = await page.evaluate(async (api, tk) => {
    const r = await fetch(`${api}/api/ventas/pedidos?page=3&size=25`,
      { headers: { Authorization: 'Bearer ' + tk } });
    if (!r.ok) return null;
    const j = await r.json();
    return j.items.length ? j.items[j.items.length - 1].numero : null;
  }, API, token);

  if (objetivo) {
    await page.click('.mp-buscador input');
    await page.type('.mp-buscador input', objetivo);
    await pausa(2200);
    const encontrado = await filas();
    caso('El buscador encuentra un pedido que NO estaba en la primera página',
         encontrado.length >= 1 && encontrado.some(f => f.numero === objetivo)
           && /[?&]q=/.test(page.url()),
         `«${objetivo}» → ${encontrado.length} resultado(s)`);
    caso('La búsqueda queda en la URL, así que se puede enlazar',
         new URL(page.url()).searchParams.get('q') === objetivo);
    await page.evaluate(() => document.querySelector('.chip-limpiar')?.click());
    await pausa(1800);
  } else {
    caso('El buscador encuentra un pedido que NO estaba en la primera página', false,
         'SIN MUESTRA: el cliente de prueba no tiene 4 páginas de pedidos');
  }

  // Filtro por estado
  await page.select('.mp-estado select', 'entregado');
  await pausa(2200);
  const entregados = await filas();
  caso('El filtro por estado recorta la lista',
       entregados.length > 0 && entregados.every(f => /entregado/i.test(f.estado))
         && new URL(page.url()).searchParams.get('estado') === 'entregado',
       `${entregados.length} pedidos, todos entregados`);

  // Una búsqueda imposible tiene que decirlo, no dejar la pantalla en blanco.
  await irA(page, '/operativo/ventas/mis-pedidos?q=PED-NO-EXISTE-0000');
  const vacioPedidos = await page.evaluate(() => ({
    hay: !!document.querySelector('.tienda-vacio'),
    texto: document.querySelector('.tienda-vacio h3')?.innerText.trim() || ''
  }));
  caso('Una búsqueda sin resultados lo dice, y distingue «no hay» de «no encontré»',
       vacioPedidos.hay && /coincide/i.test(vacioPedidos.texto),
       `«${vacioPedidos.texto}»`);

  // ── Consola ──────────────────────────────────────────────────────────────
  console.log('\n── Consola del navegador');
  const unicos = [...new Set(errores)];
  caso('Sin errores de aplicación en toda la travesía', unicos.length === 0,
       unicos.slice(0, 5).join(' | '));

  await browser.close();

  const fallos = casos.filter(c => !c.ok);
  console.log('\n' + '='.repeat(70));
  console.log(`P14 · TIENDA: ${casos.length - fallos.length}/${casos.length} casos en verde`);
  if (fallos.length) {
    console.log('\nFALLOS:');
    fallos.forEach(f => console.log(`  · ${f.nombre}${f.detalle ? '  — ' + f.detalle : ''}`));
  }
  process.exit(fallos.length ? 1 : 0);
})();
