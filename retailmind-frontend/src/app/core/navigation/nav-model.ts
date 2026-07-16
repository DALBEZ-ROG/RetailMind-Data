/**
 * Punto único de verdad de la navegación por rol.
 *
 * ROLES_POR_PERMISO espeja exactamente los roleGuard de app.routes.ts y (vía
 * NavPermissionsService) alimenta tanto los getters canX del sidebar como el
 * dashboard de inicio. Si cambia un permiso, se cambia SOLO aquí.
 */

export type PermisoNav =
  | 'admin'            // ADMIN
  | 'cliente'          // CLIENTE
  | 'autenticado'      // cualquier usuario logueado
  | 'catalogo'         // productos y variantes
  | 'compras'          // órdenes / recepciones / facturas de compra
  | 'inventario'       // transferencias de stock
  | 'ajustes'          // ajustes de inventario
  | 'kardex'           // kardex
  | 'ventas'           // pedidos / facturas de venta
  | 'logistica'        // (legado) ventas + despacho
  | 'despachar'        // despachos
  | 'devoluciones'     // RMA / logística inversa (pipeline multi-rol)
  | 'marketing'        // cupones / promos / campañas / banners / newsletter
  | 'tickets'          // tickets de soporte
  | 'categoriasTicket' // categorías de ticket
  | 'faq'              // preguntas frecuentes
  | 'resenas';         // reseñas y preguntas de producto

export const ROLES_POR_PERMISO: Record<PermisoNav, readonly string[]> = {
  admin:            ['ADMIN'],
  cliente:          ['CLIENTE'],
  autenticado:      ['ADMIN', 'GERENTE', 'VENDEDOR', 'COMPRAS', 'BODEGA', 'DESPACHO', 'CLIENTE', 'ANALISTA', 'SOPORTE'],
  catalogo:         ['ADMIN'],
  compras:          ['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'],
  inventario:       ['ADMIN', 'GERENTE', 'BODEGA'],
  ajustes:          ['ADMIN', 'BODEGA'],
  kardex:           ['ADMIN', 'GERENTE', 'BODEGA', 'ANALISTA'],
  ventas:           ['ADMIN', 'GERENTE', 'VENDEDOR'],
  logistica:        ['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO'],
  despachar:        ['ADMIN', 'GERENTE', 'DESPACHO'],
  // RMA: cada rol del pipeline entra al tablero (VENDEDOR solo consulta);
  // el CLIENTE no: su devolución nace y se sigue desde Mis Pedidos
  devoluciones:     ['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO', 'BODEGA', 'SOPORTE'],
  marketing:        ['ADMIN', 'GERENTE'],
  // SOPORTE (9º rol, script 37): bandeja de tickets + FAQ; nada más
  tickets:          ['ADMIN', 'GERENTE', 'CLIENTE', 'SOPORTE'],
  categoriasTicket: ['ADMIN'],
  faq:              ['ADMIN', 'GERENTE', 'ANALISTA', 'CLIENTE', 'SOPORTE'],
  resenas:          ['ADMIN', 'GERENTE', 'CLIENTE']
};

/**
 * Permisos de DATOS (no de navegación): espejan la matriz de GRANTs de la BD
 * (19_privilegios.sql y addenda) y SecurityConfig para los endpoints de
 * referencia/consulta que las pantallas cargan en ngOnInit. Se evalúan ANTES
 * de disparar la petición: si el rol no puede leer esa tabla, el frontend no
 * llama al endpoint y así no ensucia la consola con 403 (la BD igual negaría).
 */
export type PermisoDato =
  | 'refClientes'          // tabla cliente
  | 'refBodegas'           // tabla bodega
  | 'refProveedores'       // tabla proveedor
  | 'refVariantes'         // producto_variante + producto
  | 'refMetodosPago'       // metodo_pago
  | 'refMetodosEnvio'      // metodo_envio
  | 'refTransportistas'    // transportista
  | 'refMotivosDevolucion' // motivo_devolucion
  | 'cuentasPorPagar';     // cuenta_por_pagar (facturas de compra / pagos)

export const ROLES_POR_DATO: Record<PermisoDato, readonly string[]> = {
  refClientes:          ['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO', 'ANALISTA'],
  // VENDEDOR: script 35 le dio bodega (el pedido descuenta stock de una bodega)
  refBodegas:           ['ADMIN', 'GERENTE', 'VENDEDOR', 'COMPRAS', 'BODEGA', 'ANALISTA'],
  refProveedores:       ['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA', 'ANALISTA'],
  refVariantes:         ['ADMIN', 'GERENTE', 'VENDEDOR', 'COMPRAS', 'BODEGA', 'CLIENTE', 'ANALISTA'],
  // COMPRAS: registra el pago a proveedor (INSERT en pago_proveedor) y necesita
  // el catalogo de metodos en la pantalla de Facturas de Compra (grant script 24)
  refMetodosPago:       ['ADMIN', 'GERENTE', 'VENDEDOR', 'COMPRAS', 'CLIENTE', 'ANALISTA'],
  refMetodosEnvio:      ['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO', 'CLIENTE', 'ANALISTA'],
  refTransportistas:    ['ADMIN', 'GERENTE', 'DESPACHO', 'ANALISTA'],
  // Script 38 amplió motivo_devolucion a todo el pipeline RMA + cliente
  refMotivosDevolucion: ['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO', 'BODEGA', 'SOPORTE', 'CLIENTE', 'ANALISTA'],
  cuentasPorPagar:      ['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA']
};

export interface AccionNav {
  titulo: string;
  /** Etiqueta alternativa cuando el usuario es CLIENTE (ej. "Mis Tickets"). */
  tituloCliente?: string;
  descripcion: string;
  icono: string;
  ruta: string;
  permiso: PermisoNav;
}

export interface AreaNav {
  id: string;
  titulo: string;
  descripcion: string;
  icono: string;
  /** Color de acento del área (var CSS --acento en las cards). */
  acento: string;
  gradiente: string;
  acciones: AccionNav[];
}

export const DASHBOARD_AREAS: AreaNav[] = [
  {
    id: 'catalogo',
    titulo: 'Catálogo',
    descripcion: 'Maestro de productos y sus variantes',
    icono: 'inventory_2',
    acento: '#3949ab',
    gradiente: 'linear-gradient(135deg, #1a237e, #5c6bc0)',
    acciones: [
      { titulo: 'Productos y Variantes', descripcion: 'Alta y gestión del catálogo maestro',
        icono: 'inventory_2', ruta: '/operativo/productos', permiso: 'catalogo' },
      { titulo: 'Marcas', descripcion: 'Marcas del catálogo',
        icono: 'sell', ruta: '/operativo/catalogo/marcas', permiso: 'catalogo' },
      { titulo: 'Categorías', descripcion: 'Árbol de categorías del catálogo',
        icono: 'category', ruta: '/operativo/catalogo/categorias', permiso: 'catalogo' }
    ]
  },
  {
    id: 'compras',
    titulo: 'Compras',
    descripcion: 'Ciclo de abastecimiento con proveedores',
    icono: 'shopping_cart_checkout',
    acento: '#00897b',
    gradiente: 'linear-gradient(135deg, #00695c, #26a69a)',
    acciones: [
      { titulo: 'Órdenes de Compra', descripcion: 'Crear y aprobar órdenes a proveedores',
        icono: 'shopping_cart_checkout', ruta: '/operativo/compras/ordenes', permiso: 'compras' },
      { titulo: 'Recepciones', descripcion: 'Registrar la mercancía recibida',
        icono: 'move_to_inbox', ruta: '/operativo/compras/recepciones', permiso: 'compras' },
      { titulo: 'Facturas de Compra', descripcion: 'Facturas de proveedor y pagos',
        icono: 'request_quote', ruta: '/operativo/compras/facturas', permiso: 'compras' }
    ]
  },
  {
    id: 'inventario',
    titulo: 'Inventario',
    descripcion: 'Stock, movimientos y trazabilidad',
    icono: 'warehouse',
    acento: '#f57c00',
    gradiente: 'linear-gradient(135deg, #e65100, #ffa726)',
    acciones: [
      { titulo: 'Transferir Stock', descripcion: 'Mover stock entre almacenes',
        icono: 'swap_horiz', ruta: '/operativo/inventario/transferencias', permiso: 'inventario' },
      { titulo: 'Ajustes de Inventario', descripcion: 'Corregir existencias con ajustes',
        icono: 'tune', ruta: '/operativo/inventario/ajustes', permiso: 'ajustes' },
      { titulo: 'Kardex', descripcion: 'Historial de movimientos por producto',
        icono: 'receipt_long', ruta: '/operativo/inventario/kardex', permiso: 'kardex' }
    ]
  },
  {
    id: 'ventas',
    titulo: 'Ventas',
    descripcion: 'Pedidos y facturación a clientes',
    icono: 'point_of_sale',
    acento: '#43a047',
    gradiente: 'linear-gradient(135deg, #2e7d32, #66bb6a)',
    acciones: [
      { titulo: 'Pedidos de Venta', descripcion: 'Capturar y confirmar pedidos',
        icono: 'point_of_sale', ruta: '/operativo/ventas/pedidos', permiso: 'ventas' },
      { titulo: 'Facturas de Venta', descripcion: 'Facturar pedidos y emitir PDF',
        icono: 'receipt', ruta: '/operativo/ventas/facturas', permiso: 'ventas' }
    ]
  },
  {
    id: 'logistica',
    titulo: 'Logística',
    descripcion: 'Despacho y entrega de pedidos',
    icono: 'local_shipping',
    acento: '#039be5',
    gradiente: 'linear-gradient(135deg, #01579b, #29b6f6)',
    acciones: [
      { titulo: 'Despachos', descripcion: 'Preparar y entregar pedidos facturados',
        icono: 'local_shipping', ruta: '/operativo/ventas/despachos', permiso: 'despachar' }
    ]
  },
  {
    id: 'devoluciones',
    titulo: 'Devoluciones',
    descripcion: 'RMA: retorno, inspección y reembolso',
    icono: 'assignment_return',
    acento: '#8e24aa',
    gradiente: 'linear-gradient(135deg, #6a1b9a, #ab47bc)',
    acciones: [
      { titulo: 'Devoluciones (RMA)', descripcion: 'Solicitudes, guía de retorno, inspección y reembolso',
        icono: 'assignment_return', ruta: '/operativo/ventas/devoluciones', permiso: 'devoluciones' }
    ]
  },
  {
    id: 'marketing',
    titulo: 'Marketing',
    descripcion: 'Cupones, promociones y difusión',
    icono: 'campaign',
    acento: '#d81b60',
    gradiente: 'linear-gradient(135deg, #ad1457, #ec407a)',
    acciones: [
      { titulo: 'Cupones', descripcion: 'Códigos de descuento para clientes',
        icono: 'confirmation_number', ruta: '/operativo/marketing/cupones', permiso: 'marketing' },
      { titulo: 'Promociones', descripcion: 'Ofertas por producto y vigencia',
        icono: 'local_offer', ruta: '/operativo/marketing/promociones', permiso: 'marketing' },
      { titulo: 'Campañas', descripcion: 'Campañas de marketing y presupuesto',
        icono: 'campaign', ruta: '/operativo/marketing/campanas', permiso: 'marketing' },
      { titulo: 'Banners', descripcion: 'Banners visuales de la tienda',
        icono: 'view_carousel', ruta: '/operativo/marketing/banners', permiso: 'marketing' },
      { titulo: 'Newsletter', descripcion: 'Suscriptores y envíos de boletín',
        icono: 'mail', ruta: '/operativo/marketing/newsletter', permiso: 'marketing' }
    ]
  },
  {
    id: 'soporte',
    titulo: 'Soporte',
    descripcion: 'Atención al cliente y ayuda',
    icono: 'support_agent',
    acento: '#00acc1',
    gradiente: 'linear-gradient(135deg, #006064, #26c6da)',
    acciones: [
      { titulo: 'Tickets de Soporte', tituloCliente: 'Mis Tickets',
        descripcion: 'Casos de soporte y su seguimiento',
        icono: 'support_agent', ruta: '/operativo/soporte/tickets', permiso: 'tickets' },
      { titulo: 'Categorías de Ticket', descripcion: 'Clasificación de los tickets',
        icono: 'category', ruta: '/operativo/soporte/categorias', permiso: 'categoriasTicket' },
      { titulo: 'Preguntas Frecuentes', descripcion: 'Base de conocimiento FAQ',
        icono: 'quiz', ruta: '/operativo/soporte/faq', permiso: 'faq' }
    ]
  },
  {
    id: 'opiniones',
    titulo: 'Opiniones',
    descripcion: 'Reseñas y preguntas de productos',
    icono: 'rate_review',
    acento: '#f9a825',
    gradiente: 'linear-gradient(135deg, #f57f17, #ffca28)',
    acciones: [
      { titulo: 'Reseñas de Productos', tituloCliente: 'Mis Reseñas',
        descripcion: 'Valoraciones y moderación',
        icono: 'rate_review', ruta: '/operativo/resenas', permiso: 'resenas' },
      { titulo: 'Preguntas de Productos', descripcion: 'Preguntas y respuestas públicas',
        icono: 'question_answer', ruta: '/operativo/resenas/preguntas', permiso: 'resenas' }
    ]
  },
  {
    id: 'seguridad',
    titulo: 'Seguridad',
    descripcion: 'Control de acceso por horario',
    icono: 'security',
    acento: '#546e7a',
    gradiente: 'linear-gradient(135deg, #37474f, #78909c)',
    acciones: [
      { titulo: 'Horarios de Acceso', descripcion: 'Ventanas horarias por grupo de rol',
        icono: 'schedule', ruta: '/operativo/horarios', permiso: 'admin' }
    ]
  },
  {
    id: 'administracion',
    titulo: 'Administración',
    descripcion: 'Gestión del sistema y datos',
    icono: 'admin_panel_settings',
    acento: '#1a237e',
    gradiente: 'var(--primary-gradient)',
    acciones: [
      { titulo: 'Administración ETL', descripcion: 'Pipelines PocketBase → ClickHouse',
        icono: 'sync', ruta: '/admin-etl', permiso: 'admin' },
      { titulo: 'Gestión de Datos', descripcion: 'Mantenimiento de datos del sistema',
        icono: 'dataset', ruta: '/gestion-datos', permiso: 'admin' },
      { titulo: 'Usuarios', descripcion: 'Cuentas y roles del sistema',
        icono: 'group', ruta: '/admin-usuarios', permiso: 'admin' },
      { titulo: 'Inicialización', descripcion: 'Puesta a punto del sistema',
        icono: 'rocket_launch', ruta: '/inicializacion', permiso: 'admin' },
      { titulo: 'Reportes', descripcion: 'Reportes ejecutivos en Excel',
        icono: 'assessment', ruta: '/admin/reportes', permiso: 'admin' }
    ]
  },
  {
    id: 'analytics',
    titulo: 'Analytics',
    descripcion: 'Métricas de la tienda en ClickHouse',
    icono: 'insights',
    acento: '#5e35b1',
    gradiente: 'linear-gradient(135deg, #4527a0, #7e57c2)',
    acciones: [
      { titulo: 'Dashboard', descripcion: 'KPIs generales de la tienda',
        icono: 'dashboard', ruta: '/dashboard', permiso: 'admin' },
      { titulo: 'Sesiones', descripcion: 'Sesiones de navegación web',
        icono: 'timeline', ruta: '/sesiones', permiso: 'admin' },
      { titulo: 'Conversiones', descripcion: 'Sesiones que terminan en compra',
        icono: 'trending_up', ruta: '/conversiones', permiso: 'admin' },
      { titulo: 'Funnel', descripcion: 'Embudo de conversión por etapa',
        icono: 'filter_alt', ruta: '/funnel', permiso: 'admin' },
      { titulo: 'Región', descripcion: 'Actividad por región geográfica',
        icono: 'public', ruta: '/analytics/region', permiso: 'admin' },
      { titulo: 'Dispositivos', descripcion: 'Uso por tipo de dispositivo',
        icono: 'devices', ruta: '/analytics/dispositivo', permiso: 'admin' },
      { titulo: 'Fuente de Tráfico', descripcion: 'Canales de adquisición',
        icono: 'wifi', ruta: '/analytics/trafico', permiso: 'admin' }
    ]
  },
  {
    id: 'tienda',
    titulo: 'Tienda',
    descripcion: 'Tu experiencia de compra online',
    icono: 'storefront',
    acento: '#00bcd4',
    gradiente: 'var(--accent-gradient)',
    // Tienda del cliente (PostgreSQL): SOLO rol CLIENTE. Los roles operativos
    // no entran a la tienda (evita 500 de carrito/bodega@... y duplicados).
    acciones: [
      { titulo: 'Tienda', descripcion: 'Explorar el catálogo online',
        icono: 'storefront', ruta: '/shop', permiso: 'cliente' },
      { titulo: 'Mi Carrito', descripcion: 'Productos listos para el checkout',
        icono: 'shopping_bag', ruta: '/shop/carrito', permiso: 'cliente' },
      { titulo: 'Mi Wishlist', descripcion: 'Tus productos favoritos',
        icono: 'favorite', ruta: '/wishlist', permiso: 'cliente' },
      { titulo: 'Recomendaciones', descripcion: 'Sugerencias personalizadas',
        icono: 'local_fire_department', ruta: '/recomendaciones', permiso: 'cliente' },
      { titulo: 'Mis Pedidos', descripcion: 'Historial de compras online',
        icono: 'shopping_basket', ruta: '/operativo/ventas/mis-pedidos', permiso: 'cliente' },
      { titulo: 'Mi Perfil', descripcion: 'Datos personales y direcciones',
        icono: 'person', ruta: '/perfil', permiso: 'cliente' }
    ]
  }
];
