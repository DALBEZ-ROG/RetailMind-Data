/**
 * p16_tienda_publica.js — El escaparate público y el alta de cliente (suite P16).
 *
 * Recorre lo que puede hacer alguien que llega a la tienda SIN cuenta: mirar el
 * catálogo, abrir una ficha, toparse con el muro al querer comprar, y crearse
 * una cuenta desde ahí en cuatro pasos.
 *
 * Tres cosas que solo se ven probándolo en el navegador:
 *
 *   1. **Que al visitante no lo eche nadie.** Escribir `/shop` sin sesión tiene
 *      que dejarte en `/shop`. Un guard olvidado en la ruta redirige al login y
 *      desde la API eso no se nota: los endpoints responderían 200 igual.
 *   2. **Que el visitante no dispare peticiones que no puede hacer.** El
 *      catálogo pedía carrito, lista de deseos y recomendaciones al arrancar, y
 *      los tres `error: () => {}` hacían que los 403 no se vieran EN PANTALLA
 *      mientras ensuciaban la consola y el registro del servidor. La suite mira
 *      las respuestas HTTP, no lo pintado.
 *   3. **Que el muro distinga quién entra.** El login es el mismo para todos y
 *      tiene que serlo; lo que cambia es a dónde se va cada uno después. Con un
 *      GERENTE se comprueba que acaba en el sistema interno y no en el carrito.
 *
 * La cuenta que se crea queda DESACTIVADA al terminar (baja lógica por
 * `PATCH /api/auth/usuarios/{id}/activo`, el mismo camino que la pantalla de
 * administración). No se borra: un usuario tiene rastro en `log_acceso` y
 * borrarlo obligaría a tocar la base por fuera de la aplicación.
 *
 *   export RETAILMIND_ADMIN_PASS='…'      # la del admin
 *   export RETAILMIND_STAFF_PASS='…'      # la del resto de roles
 *   node pruebas/p16_tienda_publica.js
 */

const path = require('path');

const RAIZ = path.resolve(__dirname, '..');
const puppeteer = require(path.join(RAIZ, 'retailmind-frontend', 'node_modules', 'puppeteer'));

const WEB = (process.env.RETAILMIND_WEB || 'http://localhost:4200').replace(/\/$/, '');
const API = (process.env.RETAILMIND_API || 'http://localhost:8080').replace(/\/$/, '');
const CLAVE_ADMIN = process.env.RETAILMIND_ADMIN_PASS;
const CLAVE_STAFF = process.env.RETAILMIND_STAFF_PASS;

if (!CLAVE_ADMIN || !CLAVE_STAFF) {
  console.error('FALTAN RETAILMIND_ADMIN_PASS y/o RETAILMIND_STAFF_PASS.\n'
    + '  Las credenciales de demostración no se escriben en el repo (deuda C-4).');
  process.exit(2);
}

/** Correo con marca de tiempo: la suite tiene que poder correr dos veces seguidas. */
const SELLO = new Date().toISOString().replace(/\D/g, '').slice(2, 14);
const NUEVO_CORREO = `p16.${SELLO}@demo.com`;
const NUEVA_CLAVE = 'PruebaP16.2026';
/**
 * La identificacion tambien tiene que cambiar en cada corrida:
 * `uq_cliente_identificacion` es UNIQUE sobre (tipo, numero), asi que una
 * cedula escrita a mano hace que la suite pase la primera vez y falle la
 * segunda — que es exactamente como se descubrio que ese choque devolvia el
 * mensaje generico del motor.
 */
const NUEVA_CEDULA = SELLO.slice(-10);

const RUIDO = [/favicon/i, /fonts\.(googleapis|gstatic)\.com/i];

let casos = [];
function caso(nombre, ok, detalle) {
  casos.push({ nombre, ok: !!ok, detalle: detalle || '' });
  console.log(`  [${ok ? 'OK  ' : 'FALLA'}] ${nombre}${detalle ? '  — ' + detalle : ''}`);
}
const pausa = ms => new Promise(r => setTimeout(r, ms));

async function irA(page, ruta) {
  await page.goto(WEB + ruta, { waitUntil: 'networkidle2', timeout: 60000 });
  await pausa(1600);
}

/** Escribe en un campo por selector, vaciándolo antes. */
async function escribir(page, sel, texto) {
  await page.focus(sel);
  await page.evaluate(s => {
    const el = document.querySelector(s);
    const proto = el instanceof HTMLTextAreaElement
      ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, '');
    el.dispatchEvent(new Event('input', { bubbles: true }));
  }, sel);
  await page.type(sel, texto, { delay: 8 });
}

/** Pulsa el botón visible cuyo rótulo case con el patrón. */
async function pulsar(page, patron) {
  const ok = await page.evaluate(re => {
    const b = [...document.querySelectorAll('button, a')]
      .find(e => e.offsetParent && new RegExp(re, 'i').test(e.textContent));
    if (!b) { return false; }
    b.click();
    return true;
  }, patron.source);
  await pausa(1200);
  return ok;
}

/**
 * Espera a que el registro llegue al paso pedido.
 *
 * Con una pausa fija esto era inestable y por una razon concreta: crear la
 * cuenta cifra la clave con BCrypt y acto seguido inicia sesion, y ese par
 * tarda entre medio segundo y casi tres segun como ande la maquina. Un
 * `pausa(2600)` pasa unas veces y falla otras, y cuando falla acusa al sistema
 * de algo que no ha hecho.
 */
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
  return { ok: false, error: 'no llego al paso ' + numero + ' a tiempo' };
}

async function tokenDe(usuario, clave) {
  const r = await fetch(`${API}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: usuario, password: clave })
  });
  if (!r.ok) { return null; }
  return (await r.json()).token;
}

(async () => {
  console.log('='.repeat(70));
  console.log('P16 · TIENDA PÚBLICA Y ALTA DE CLIENTE  ·  ' + WEB);
  console.log('='.repeat(70));

  const errores = [];
  const fallosRed = [];
  // La fase en curso. Un 403 no significa lo mismo en cada tramo: mientras se
  // mira la tienda sin cuenta es un defecto, y con la sesión de un GERENTE
  // puesta en el sistema interno puede ser el comportamiento correcto. Sin
  // atribuirlo, la suite acusa a una fase de lo que pasó en otra.
  let fase = 'escaparate';
  const browser = await puppeteer.launch({
    headless: 'new', args: ['--no-sandbox', '--disable-dev-shm-usage', '--window-size=1500,950']
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1500, height: 950 });
  page.on('console', m => {
    if (m.type() !== 'error') { return; }
    const t = m.text();
    if (!RUIDO.some(r => r.test(t))) { errores.push(t); }
  });
  page.on('pageerror', e => errores.push('pageerror: ' + e.message));
  page.on('response', r => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      fallosRed.push(`[${fase}] ${r.status()} ${r.url().replace(API, '').replace(WEB, '')}`);
    }
  });

  // ══ 1 · El escaparate ═════════════════════════════════════════════════════
  console.log('\n── 1 · El escaparate sin cuenta');
  await irA(page, '/shop');

  caso('Escribir /shop sin sesión NO redirige al login',
       new URL(page.url()).pathname === '/shop', page.url().replace(WEB, ''));

  const tarjetas = await page.$$eval('.p-card', e => e.length).catch(() => 0);
  caso('El catálogo pinta productos para el visitante', tarjetas > 0, `${tarjetas} tarjetas`);

  const barra = await page.evaluate(() => ({
    entrar: !!document.querySelector('.acc-entrar'),
    crear: !!document.querySelector('.acc-crear'),
    carrito: !!document.querySelector('.acc-carrito'),
    deseos: !!document.querySelector('.acc-icono[href*="wishlist"]'),
    pedidos: !!document.querySelector('a[href*="mis-pedidos"]'),
    buscador: !!document.querySelector('.tienda-buscador'),
    departamentos: document.querySelectorAll('.tienda-buscador .tb-ambito option').length
  }));
  caso('La barra ofrece «Iniciar sesión» y «Crea tu cuenta»', barra.entrar && barra.crear);
  caso('La barra NO enseña carrito, deseos ni pedidos a quien no tiene cuenta',
       !barra.carrito && !barra.deseos && !barra.pedidos);
  caso('El buscador y sus departamentos siguen ahí', barra.buscador && barra.departamentos > 1,
       `${barra.departamentos} departamentos`);
  caso('El visitante no dispara ni una petición prohibida',
       fallosRed.length === 0, [...new Set(fallosRed)].slice(0, 4).join(' | '));

  // Filtrar sin cuenta.
  await irA(page, '/shop?q=zapato');
  const filtrados = await page.$$eval('.p-card', e => e.length).catch(() => 0);
  caso('Se puede buscar en el catálogo sin cuenta', filtrados > 0, `${filtrados} resultados`);

  // La ficha del producto también es pública.
  const idProducto = await page.evaluate(() => {
    const c = document.querySelector('.p-card');
    c?.click();
    return true;
  });
  await pausa(2200);
  caso('La ficha de un producto se abre sin cuenta',
       /\/shop\/producto\//.test(page.url()), page.url().replace(WEB, ''));

  // ══ 2 · El muro ═══════════════════════════════════════════════════════════
  console.log('\n── 2 · El muro de sesión');
  await irA(page, '/shop');
  await page.evaluate(() => {
    const b = [...document.querySelectorAll('.p-card button')]
      .find(e => /agregar|carrito/i.test(e.textContent + e.getAttribute('aria-label')));
    (b || document.querySelector('.p-card-acciones button'))?.click();
  });
  await pausa(1800);

  const muro = await page.evaluate(() => {
    const d = document.querySelector('app-sesion-requerida');
    const telon = document.querySelector('.cdk-overlay-backdrop.muro-sesion-telon');
    return {
      abierto: !!d,
      titulo: d?.querySelector('h2')?.textContent?.trim() || '',
      motivo: d?.querySelector('.muro-cabecera p')?.textContent?.trim() || '',
      difuminado: telon ? getComputedStyle(telon).backdropFilter : '',
      crear: !!d?.querySelector('.muro-crear')
    };
  });
  caso('Agregar al carrito sin cuenta abre el muro', muro.abierto, muro.titulo);
  caso('El muro difumina lo de atrás', /blur/.test(muro.difuminado), muro.difuminado);
  caso('El muro dice para qué hace falta la cuenta', /carrito/i.test(muro.motivo), muro.motivo);
  caso('El muro ofrece crear una cuenta', muro.crear);

  // El corazón de la lista de deseos también lo levanta, con OTRO motivo.
  await page.keyboard.press('Escape');
  await pausa(900);
  await page.evaluate(() => document.querySelector('.p-card-corazon')?.click());
  await pausa(1600);
  const motivoDeseos = await page.evaluate(() =>
    document.querySelector('app-sesion-requerida .muro-cabecera p')?.textContent?.trim() || '');
  caso('El corazón levanta el muro con su propio motivo',
       /deseos/i.test(motivoDeseos), motivoDeseos);

  // ══ 3 · Quien no es cliente acaba en el sistema interno ═══════════════════
  console.log('\n── 3 · El muro distingue quién entra');
  fase = 'gerente';
  await escribir(page, 'app-sesion-requerida input[name="correo"]', 'gerente@retailmind.com');
  await escribir(page, 'app-sesion-requerida input[name="clave"]', CLAVE_STAFF);
  await page.evaluate(() => document.querySelector('app-sesion-requerida .muro-entrar').click());
  await pausa(3200);
  caso('Un GERENTE que entra por el muro va al sistema interno, no al carrito',
       new URL(page.url()).pathname === '/inicio', page.url().replace(WEB, ''));
  await page.evaluate(() => localStorage.clear());

  // ══ 4 · El alta, paso a paso ══════════════════════════════════════════════
  console.log('\n── 4 · Crear la cuenta en cuatro pasos');
  fase = 'registro';
  await irA(page, '/login');
  const enLogin = await page.evaluate(() => ({
    crear: document.querySelector('.login-crear')?.textContent?.trim() || '',
    mirar: document.querySelector('.login-mirar')?.textContent?.trim() || ''
  }));
  caso('El login ofrece crear una cuenta de cliente', /crea tu cuenta/i.test(enLogin.crear));
  caso('El login deja entrar a mirar la tienda sin cuenta', /ver la tienda/i.test(enLogin.mirar));

  await page.evaluate(() => document.querySelector('.login-crear').click());
  await pausa(1800);
  caso('El enlace lleva al registro', new URL(page.url()).pathname === '/registro');

  const sinBarra = await page.evaluate(() => !document.querySelector('nav.navbar'));
  caso('El registro se pinta a pantalla completa, sin la barra del sistema', sinBarra);

  // — Paso 1
  await escribir(page, '.reg-paso input[placeholder="María"]', 'Sofía');
  await escribir(page, '.reg-paso input[placeholder="López"]', 'Ramírez');
  await escribir(page, '.reg-paso input[placeholder="0991234567"]', '0987654321');
  await page.evaluate(() => {
    const s = document.querySelectorAll('.reg-paso select');
    s[0].value = 'cedula'; s[0].dispatchEvent(new Event('change', { bubbles: true }));
    s[1].value = 'femenino'; s[1].dispatchEvent(new Event('change', { bubbles: true }));
  });
  await escribir(page, '.reg-paso input[placeholder="1712345678"]', NUEVA_CEDULA);

  const paso1Listo = await page.evaluate(() =>
    !document.querySelector('.reg-acciones .primario').disabled);
  caso('Con el nombre puesto, «Continuar» se habilita', paso1Listo);
  await pulsar(page, /continuar/);

  // — Paso 2
  const alPaso2 = await esperarPaso(page, 2);
  caso('Se avanza al paso de acceso', alPaso2.ok, alPaso2.error || '');

  await escribir(page, 'input[placeholder="tucorreo@ejemplo.com"]', NUEVO_CORREO);
  await escribir(page, 'input[placeholder="Al menos 8 caracteres"]', NUEVA_CLAVE);
  await escribir(page, 'input[placeholder="La misma de arriba"]', 'otra-distinta');
  const bloqueado = await page.evaluate(() =>
    document.querySelector('.reg-acciones .primario').disabled);
  caso('Con las contraseñas distintas NO deja crear la cuenta', bloqueado);

  await escribir(page, 'input[placeholder="La misma de arriba"]', NUEVA_CLAVE);
  await pulsar(page, /crear mi cuenta/);

  // — Paso 3
  const alPaso3 = await esperarPaso(page, 3);
  await pausa(900);   // margen para que lleguen las ciudades
  const enPaso3 = await page.evaluate(() => ({
    opcional: /opcional/i.test(document.querySelector('.reg-subtitle')?.textContent || ''),
    ciudades: document.querySelectorAll('.reg-paso select option').length,
    sinAtras: !document.querySelector('.reg-acciones .plano')
      || !/atrás/i.test(document.querySelector('.reg-acciones .plano').textContent)
  }));
  caso('La cuenta se crea al terminar el paso 2 y se pasa a la dirección',
       alPaso3.ok, alPaso3.error || '');
  if (!alPaso3.ok) {
    console.log('\n  Sin cuenta creada no hay nada más que recorrer.');
    await browser.close();
    process.exit(1);
  }
  caso('El paso de dirección se declara OPCIONAL en pantalla', enPaso3.opcional);
  caso('Ya con sesión, el paso 3 carga las ciudades', enPaso3.ciudades > 1,
       `${enPaso3.ciudades} opciones`);
  caso('Después de crear la cuenta ya no se puede volver a los datos', enPaso3.sinAtras);

  // La dirección se rellena de verdad (destinatario y teléfono vienen precargados).
  await escribir(page, 'input[placeholder="Av. Quito"]', 'Av. Walter Andrade');
  // La ciudad se elige por INDICE y no por el atributo `value`: con [ngValue],
  // Angular escribe ahi "0: null" para el marcador de posicion, que es una
  // cadena NO vacia — buscar la primera opcion con `value` truthy elige justo
  // «— Elige tu ciudad —» y el formulario se queda sin ciudad.
  await page.evaluate(() => {
    const s = [...document.querySelectorAll('.reg-paso select')].pop();
    if (s && s.options.length > 1) {
      s.selectedIndex = 1;
      s.dispatchEvent(new Event('change', { bubbles: true }));
    }
  });
  await pulsar(page, /continuar/);

  // — Paso 4
  const alPaso4 = await esperarPaso(page, 4);
  await pausa(900);
  const enPaso4 = await page.evaluate(() => ({
    intereses: document.querySelectorAll('.interes').length
  }));
  caso('Se llega al paso de intereses', alPaso4.ok, alPaso4.error || '');
  caso('Los intereses ofrecen los departamentos del catálogo', enPaso4.intereses > 1,
       `${enPaso4.intereses} departamentos`);

  await page.evaluate(() => {
    const b = document.querySelectorAll('.interes');
    b[0]?.click(); b[1]?.click();
  });
  await pausa(400);
  const elegidos = await page.$$eval('.interes.elegida', e => e.length);
  caso('Se pueden marcar intereses', elegidos === 2, `${elegidos} marcados`);

  await pulsar(page, /finalizar/);
  await esperarPaso(page, 5);

  const hecho = await page.evaluate(() =>
    document.querySelector('.reg-listo h2')?.textContent?.trim() || '');
  caso('El registro termina con una confirmación', /lista/i.test(hecho), hecho);

  await pausa(2400);
  caso('Al terminar se vuelve a la tienda, ya con sesión',
       new URL(page.url()).pathname.startsWith('/shop'), page.url().replace(WEB, ''));

  const yaCliente = await page.evaluate(() => ({
    carrito: !!document.querySelector('.acc-carrito'),
    entrar: !!document.querySelector('.acc-entrar'),
    nombre: document.querySelector('.user-btn span')?.textContent?.trim() || ''
  }));
  caso('La barra pasa a modo cliente: aparece el carrito y desaparece «Iniciar sesión»',
       yaCliente.carrito && !yaCliente.entrar, yaCliente.nombre);

  // ══ 5 · Lo que quedó guardado ═════════════════════════════════════════════
  console.log('\n── 5 · La cuenta creada es una cuenta de verdad');
  const tk = await tokenDe(NUEVO_CORREO, NUEVA_CLAVE);
  caso('La cuenta nueva puede iniciar sesión por su cuenta', !!tk);

  if (tk) {
    const pedir = async r => {
      const res = await fetch(API + r, { headers: { Authorization: 'Bearer ' + tk } });
      return { estado: res.status, cuerpo: res.ok ? await res.json() : null };
    };
    const perfil = await pedir('/api/perfil');
    caso('El perfil la reconoce como cliente de la tienda',
         perfil.cuerpo?.esCliente === true, `esCliente=${perfil.cuerpo?.esCliente}`);

    const dirs = await pedir('/api/perfil/direcciones');
    caso('La dirección del paso 3 quedó guardada',
         Array.isArray(dirs.cuerpo) && dirs.cuerpo.length === 1,
         dirs.cuerpo?.[0]?.callePrincipal || '');

    const ints = await pedir('/api/perfil/intereses');
    const marcados = (ints.cuerpo || []).filter(i => i.elegida);
    caso('Los intereses del paso 4 quedaron guardados', marcados.length === 2,
         marcados.map(i => i.nombre).join(', '));

    const pedidos = await pedir('/api/ventas/pedidos?page=0&size=1');
    caso('La cuenta nace VACÍA: cero pedidos', pedidos.cuerpo?.total === 0,
         `total=${pedidos.cuerpo?.total}`);

    const carrito = await pedir('/api/carrito');
    caso('El carrito nace vacío', Array.isArray(carrito.cuerpo) && carrito.cuerpo.length === 0);
  }

  // ══ 6 · El rol no se puede elegir ═════════════════════════════════════════
  console.log('\n── 6 · El alta pública solo crea CLIENTES');
  const intruso = `p16.intruso.${SELLO}@demo.com`;
  const rIntruso = await fetch(`${API}/api/auth/registro-cliente`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: intruso, password: NUEVA_CLAVE, nombre: 'Intruso',
                           rol: 'ADMIN', rolCodigo: 'ADMIN', authorities: ['ADMIN'] })
  });
  caso('El alta acepta la petición aunque venga con «rol: ADMIN» en el cuerpo',
       rIntruso.status === 201, `HTTP ${rIntruso.status}`);
  const tkIntruso = await tokenDe(intruso, NUEVA_CLAVE);
  if (tkIntruso) {
    const carga = JSON.parse(Buffer.from(tkIntruso.split('.')[1], 'base64').toString());
    caso('…pero el rol que le queda es CLIENTE, no el que pidió', carga.rol === 'CLIENTE',
         `rol=${carga.rol}`);
    const admin = await fetch(`${API}/api/auth/usuarios`, {
      headers: { Authorization: 'Bearer ' + tkIntruso } });
    caso('Y con ese token no entra en la gestión de usuarios', admin.status === 403,
         `HTTP ${admin.status}`);
  }

  const dup = await fetch(`${API}/api/auth/registro-cliente`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: NUEVO_CORREO, password: NUEVA_CLAVE, nombre: 'Repetida' })
  });
  caso('Un correo ya registrado da 409 y no un 500', dup.status === 409, `HTTP ${dup.status}`);

  const identRepe = await fetch(`${API}/api/auth/registro-cliente`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `p16.ident.${SELLO}@demo.com`, password: NUEVA_CLAVE,
                           nombre: 'Cedula repetida', tipoIdentificacion: 'cedula',
                           numeroIdentificacion: NUEVA_CEDULA })
  });
  const textoIdent = await identRepe.text();
  caso('Una identificación ya registrada da 409 y DICE que es la identificación',
       identRepe.status === 409 && /identificaci/i.test(textoIdent),
       `HTTP ${identRepe.status}`);

  const flojo = await fetch(`${API}/api/auth/registro-cliente`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: `p16.flojo.${SELLO}@demo.com`, password: '123', nombre: 'Corta' })
  });
  caso('Una contraseña corta da 400 con el motivo', flojo.status === 400, `HTTP ${flojo.status}`);

  const sinArroba = await fetch(`${API}/api/auth/registro-cliente`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'no-es-un-correo', password: NUEVA_CLAVE, nombre: 'Mala' })
  });
  caso('Un correo mal formado da 400', sinArroba.status === 400, `HTTP ${sinArroba.status}`);

  // ══ Limpieza ══════════════════════════════════════════════════════════════
  console.log('\n── Limpieza');
  const tkAdmin = await tokenDe('admin@retailmind.com', CLAVE_ADMIN);
  let bajas = 0;
  if (tkAdmin) {
    const lista = await (await fetch(`${API}/api/auth/usuarios`,
      { headers: { Authorization: 'Bearer ' + tkAdmin } })).json();
    for (const u of lista) {
      // El correo llega como `username` y no como `email`: es el campo que el
      // frontend espera desde siempre porque el login se hace con el correo.
      if (!String(u.username || '').startsWith('p16.')) { continue; }
      const r = await fetch(`${API}/api/auth/usuarios/${u.id}/activo`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkAdmin },
        body: JSON.stringify({ activo: false })
      });
      if (r.ok) { bajas++; }
    }
  }
  caso('Las cuentas de la prueba quedan dadas de baja', bajas >= 2, `${bajas} desactivadas`);

  // ══ Consola ═══════════════════════════════════════════════════════════════
  console.log('\n── Consola del navegador');
  const redTienda = [...new Set(fallosRed.filter(f => !f.startsWith('[gerente]')))];
  caso('Ni una petición fallida mirando la tienda o creando la cuenta',
       redTienda.length === 0, redTienda.slice(0, 4).join(' | '));

  const redGerente = [...new Set(fallosRed.filter(f => f.startsWith('[gerente]')))];
  if (redGerente.length) {
    console.log('  (informativo) el sistema interno con rol GERENTE devolvió: '
                + redGerente.slice(0, 4).join(' | '));
  }

  // Los errores de JAVASCRIPT sí se exigen limpios en todo el recorrido: un
  // 403 puede ser correcto, una excepción sin capturar nunca lo es.
  const unicos = [...new Set(errores)].filter(t => !/status of 40[13]/.test(t));
  caso('Sin errores de aplicación en todo el recorrido', unicos.length === 0,
       unicos.slice(0, 4).join(' | '));

  await browser.close();

  const fallos = casos.filter(c => !c.ok);
  console.log('\n' + '='.repeat(70));
  console.log(`P16 · TIENDA PÚBLICA: ${casos.length - fallos.length}/${casos.length} casos en verde`);
  if (fallos.length) {
    console.log('\nFALLOS:');
    fallos.forEach(f => console.log(`  · ${f.nombre}${f.detalle ? '  — ' + f.detalle : ''}`));
  }
  process.exit(fallos.length ? 1 : 0);
})();
