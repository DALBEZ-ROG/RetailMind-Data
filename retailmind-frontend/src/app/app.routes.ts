import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { adminGuard, roleGuard } from './core/guards/role.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: '',
    redirectTo: 'inicio',
    pathMatch: 'full'
  },
  // Dashboard de inicio (landing post-login): nivel 1 áreas, nivel 2 acciones.
  // Sin roleGuard: el propio componente filtra áreas/acciones con la misma
  // matriz de permisos (NavPermissionsService) que protege cada ruta destino.
  {
    path: 'inicio',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/inicio/inicio.component').then(m => m.InicioComponent)
  },
  {
    path: 'inicio/:area',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/inicio/inicio.component').then(m => m.InicioComponent)
  },
  // ── Tienda del cliente (PostgreSQL; solo rol CLIENTE) ───────────────────
  {
    path: 'shop',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/shop/shop.component').then(m => m.ShopComponent)
  },
  {
    path: 'shop/producto/:id',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/shop/producto-detalle.component').then(m => m.ProductoDetalleComponent)
  },
  {
    path: 'shop/carrito',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/shop/carrito.component').then(m => m.CarritoComponent)
  },
  {
    path: 'shop/checkout',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/shop/checkout.component').then(m => m.CheckoutComponent)
  },
  {
    path: 'wishlist',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/wishlist/wishlist.component').then(m => m.WishlistComponent)
  },
  // 'Mis Pedidos' del cliente es /operativo/ventas/mis-pedidos (PostgreSQL);
  // las rutas legacy /mis-pedidos y /admin-pedidos (ClickHouse) se eliminaron.
  {
    path: 'admin-usuarios',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/usuarios/admin-usuarios.component').then(m => m.AdminUsuariosComponent)
  },
  {
    path: 'funnel',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/funnel/funnel.component').then(m => m.FunnelComponent)
  },
  {
    path: 'dashboard',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'sesiones',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/sesiones/sesiones-list.component').then(m => m.SesionesListComponent)
  },
  {
    path: 'conversiones',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/conversiones/conversiones-list.component').then(m => m.ConversionesListComponent)
  },
  {
    path: 'admin-etl',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/etl/admin-etl.component').then(m => m.AdminEtlComponent)
  },
  {
    path: 'gestion-datos',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/gestion/gestion-datos.component').then(m => m.GestionDatosComponent)
  },
  {
    path: 'inicializacion',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/inicializacion/inicializacion.component').then(m => m.InicializacionComponent)
  },
  {
    path: 'analytics/region',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/region/region.component').then(m => m.RegionComponent)
  },
  {
    path: 'analytics/dispositivo',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/dispositivo/dispositivo.component').then(m => m.DispositivoComponent)
  },
  {
    path: 'analytics/trafico',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/analytics/trafico/trafico.component').then(m => m.TraficoComponent)
  },
  {
    path: 'admin/reportes',
    canActivate: [authGuard, adminGuard],
    loadComponent: () =>
      import('./features/admin/reportes/reportes.component').then(m => m.ReportesComponent)
  },
  {
    path: 'perfil',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/perfil/perfil.component').then(m => m.PerfilComponent)
  },
  {
    path: 'recomendaciones',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/recomendaciones/recomendaciones.component').then(m => m.RecomendacionesComponent)
  },
  // ── Módulo operativo (casos de uso 1-10) ────────────────────────────────
  {
    path: 'operativo/productos',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/catalogo/productos-admin.component').then(m => m.ProductosAdminComponent)
  },
  {
    path: 'operativo/catalogo/marcas',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/catalogo/marcas-admin.component').then(m => m.MarcasAdminComponent)
  },
  {
    path: 'operativo/catalogo/categorias',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/catalogo/categorias-admin.component').then(m => m.CategoriasAdminComponent)
  },
  {
    path: 'operativo/compras/ordenes',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/ordenes-compra.component').then(m => m.OrdenesCompraComponent)
  },
  {
    path: 'operativo/compras/recepciones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/recepciones.component').then(m => m.RecepcionesComponent)
  },
  {
    // Devolución a proveedor (script 45): BODEGA identifica el defectuoso,
    // COMPRAS gestiona el ciclo; GERENTE supervisa
    path: 'operativo/compras/devoluciones-proveedor',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/compras/devoluciones-proveedor.component').then(m => m.DevolucionesProveedorComponent)
  },
  {
    // Ficha del proveedor + catálogo proveedor-producto (OTD-COM-10, script 51):
    // contiene costo — BODEGA fuera (segregación financiera)
    path: 'operativo/compras/proveedores',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS'])],
    loadComponent: () =>
      import('./features/operativo/compras/proveedores.component').then(m => m.ProveedoresComponent)
  },
  {
    // Facturas de compra: documento financiero — BODEGA fuera (segregación)
    path: 'operativo/compras/facturas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS'])],
    loadComponent: () =>
      import('./features/operativo/compras/facturas-compra.component').then(m => m.FacturasCompraComponent)
  },
  {
    path: 'operativo/inventario/transferencias',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/transferencias.component').then(m => m.TransferenciasComponent)
  },
  {
    path: 'operativo/inventario/ajustes',
    canActivate: [authGuard, roleGuard(['ADMIN', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/ajustes.component').then(m => m.AjustesComponent)
  },
  {
    path: 'operativo/inventario/kardex',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'BODEGA', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/inventario/kardex.component').then(m => m.KardexComponent)
  },
  {
    path: 'operativo/ventas/pedidos',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR'])],
    loadComponent: () =>
      import('./features/operativo/ventas/pedidos-venta.component').then(m => m.PedidosVentaComponent)
  },
  {
    path: 'operativo/ventas/mis-pedidos',
    canActivate: [authGuard, roleGuard(['CLIENTE'])],
    loadComponent: () =>
      import('./features/operativo/ventas/mis-pedidos-tienda.component').then(m => m.MisPedidosTiendaComponent)
  },
  {
    path: 'operativo/ventas/facturas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR'])],
    loadComponent: () =>
      import('./features/operativo/ventas/facturas-venta.component').then(m => m.FacturasVentaComponent)
  },
  // Preparación de pedidos (picking de bodega, script 39)
  {
    path: 'operativo/ventas/preparacion',
    canActivate: [authGuard, roleGuard(['ADMIN', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/ventas/preparacion-pedidos.component').then(m => m.PreparacionPedidosComponent)
  },
  {
    path: 'operativo/ventas/despachos',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'DESPACHO'])],
    loadComponent: () =>
      import('./features/operativo/ventas/despachos.component').then(m => m.DespachosComponent)
  },
  // RMA / logística inversa: todo el pipeline (soporte valida, despacho mueve,
  // bodega inspecciona, gerente reembolsa; vendedor consulta). El CLIENTE
  // solicita y sigue su devolución desde /operativo/ventas/mis-pedidos.
  {
    path: 'operativo/ventas/devoluciones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR', 'DESPACHO', 'BODEGA', 'SOPORTE'])],
    loadComponent: () =>
      import('./features/operativo/ventas/devoluciones.component').then(m => m.DevolucionesComponent)
  },
  // ── Módulo gerencia: metas de venta (OTD-VEN-15, script 48) — fija
  //    GERENTE/ADMIN; VENDEDOR/ANALISTA leen el avance (espeja SecurityConfig)
  {
    path: 'operativo/gerencia/metas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/gerencia/metas-venta.component').then(m => m.MetasVentaComponent)
  },
  // ── Informes tácticos por departamento (docs/tactico/PATRON_INFORMES.md).
  //    UNA sola pantalla genérica parametrizada por `data.departamento`; los
  //    informes y sus filtros salen del archivo de definiciones del área.
  //    Ventas: espeja SecurityConfig (/api/informes/ventas/**) — BODEGA y
  //    DESPACHO fuera por segregación financiera. El ANALISTA entra solo por
  //    OTD-VEN-16 (participación por canal), el único informe de Ventas que le
  //    corresponde; la pantalla le oculta los otros cinco.
  {
    path: 'operativo/informes/ventas',
    data: { departamento: 'ventas' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'VENDEDOR', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  //    Inventario: espeja SecurityConfig (/api/informes/inventario/**). La lista
  //    es la UNIÓN de quien ve al menos un informe — BODEGA sí entra, porque seis
  //    de los siete son de cantidades; el de valorización (OTD-INV-07, dinero) se
  //    le oculta dentro de la pantalla y su ruta REST se lo niega igualmente.
  {
    path: 'operativo/informes/inventario',
    data: { departamento: 'inventario' },
    canActivate: [authGuard,
      roleGuard(['ADMIN', 'GERENTE', 'BODEGA', 'COMPRAS', 'VENDEDOR', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  //    Compras: espeja SecurityConfig (/api/informes/compras/**). BODEGA entra a
  //    la pantalla por los TRES informes que el catálogo le da «en cantidades,
  //    sin montos» —OTD-COM-08, COM-07 y COM-11—; el resto lleva monto y su ruta
  //    REST se lo niega. ANALISTA entra por los cuatro compuestos en que el
  //    catálogo lo incluye (COM-03, COM-04, COM-06 y COM-12) y la API le niega
  //    los demás, COM-05 incluido.
  {
    path: 'operativo/informes/compras',
    data: { departamento: 'compras' },
    canActivate: [authGuard,
      roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'BODEGA', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  //    Logística: espeja SecurityConfig (/api/informes/logistica/**). DESPACHO es
  //    el destinatario de los tres informes de estados y cantidades y queda fuera
  //    de OTD-LOG-11 (costo del envío, dinero), que su ruta REST le niega.
  {
    path: 'operativo/informes/logistica',
    data: { departamento: 'logistica' },
    canActivate: [authGuard,
      roleGuard(['ADMIN', 'GERENTE', 'DESPACHO', 'SOPORTE', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  //    Soporte: espeja SecurityConfig (/api/informes/soporte/**). Ningún informe
  //    de la mesa de ayuda lleva dinero, así que no hay corte financiero: los
  //    destinatarios son el propio SOPORTE y la jefatura.
  {
    path: 'operativo/informes/soporte',
    data: { departamento: 'soporte' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'SOPORTE'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  //    Gerencia: espeja SecurityConfig (/api/informes/gerencia/**). Los cinco son
  //    de dirección y dos de ellos (OTD-GER-08 auditoría y OTD-GER-09 accesos)
  //    son DATOS SENSIBLES DE SEGURIDAD, el corte más estricto del sistema: el
  //    departamento entero queda en ADMIN + GERENTE.
  {
    path: 'operativo/informes/gerencia',
    data: { departamento: 'gerencia' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/informes/informes-departamento.component')
        .then(m => m.InformesDepartamentoComponent)
  },
  // ── Tableros de DIRECCIÓN (nivel estratégico, fase E1-A:
  //    docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md §4).
  //    UNA sola pantalla genérica parametrizada por `data.tablero`; los bloques
  //    y su presentación salen del archivo de definiciones.
  //
  //    Los TRES llevan dinero, así que BODEGA y DESPACHO quedan fuera —igual
  //    que en SecurityConfig, que es quien realmente decide—. En ClickHouse la
  //    segregación no la respalda el motor: no hay GRANT por columna, y la
  //    única barrera es la ruta.
  {
    path: 'operativo/tableros/omnicanal',
    data: { tablero: 'omnicanal' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  {
    path: 'operativo/tableros/rentabilidad',
    data: { tablero: 'rentabilidad' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  //    T-3 suma SOPORTE, y solo él: entra por el bloque de tickets y
  //    devoluciones. El recorte de los demás bloques no lo hace el guard —es
  //    DENTRO del mismo tablero— sino la consulta del backend, que no los
  //    ejecuta y declara en el sobre cuáles omitió.
  {
    path: 'operativo/tableros/cliente-posventa',
    data: { tablero: 'cliente-posventa' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'ANALISTA', 'SOPORTE'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  //    Fase E1-B. T-4 es el UNICO tablero SIN dinero y el unico al que Despacho
  //    y Bodega pueden entrar: su consulta no selecciona un solo importe. T-5 es
  //    su gemelo CON dinero y existe separado precisamente por eso.
  {
    path: 'operativo/tableros/operacion',
    data: { tablero: 'operacion' },
    canActivate: [authGuard,
      roleGuard(['ADMIN', 'GERENTE', 'ANALISTA', 'DESPACHO', 'BODEGA'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  {
    path: 'operativo/tableros/costo-operacion',
    data: { tablero: 'costo-operacion' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  //    T-6 suma COMPRAS: es su objetivo (OE-11) y su centro de costo.
  {
    path: 'operativo/tableros/abastecimiento',
    data: { tablero: 'abastecimiento' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'COMPRAS', 'ANALISTA'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  //    T-7 es DATO SENSIBLE: el corte mas estricto del sistema. El ANALISTA
  //    queda fuera aunque entre en los otros seis, y en la auditoria la barrera
  //    real es la RUTA y no el motor (grp_analista SI lee log_auditoria).
  {
    path: 'operativo/tableros/gobierno-dato',
    data: { tablero: 'gobierno-dato' },
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/tableros/tablero.component')
        .then(m => m.TableroComponent)
  },
  // ── Seguridad: intentos de acceso al sistema (OTD-GER-09, script 53) —
  //    informe solo ADMIN/GERENTE (espeja SecurityConfig y los GRANTs)
  {
    path: 'operativo/seguridad/accesos',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/seguridad/accesos.component').then(m => m.AccesosComponent)
  },
  // ── Módulo marketing (lectura ADMIN/GERENTE; escrituras solo ADMIN, espeja SecurityConfig)
  {
    path: 'operativo/marketing/cupones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/cupones.component').then(m => m.CuponesComponent)
  },
  {
    path: 'operativo/marketing/promociones',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/promociones.component').then(m => m.PromocionesComponent)
  },
  {
    path: 'operativo/marketing/campanas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/campanas.component').then(m => m.CampanasComponent)
  },
  {
    path: 'operativo/marketing/banners',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/banners.component').then(m => m.BannersComponent)
  },
  {
    path: 'operativo/marketing/newsletter',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE'])],
    loadComponent: () =>
      import('./features/operativo/marketing/newsletter.component').then(m => m.NewsletterComponent)
  },
  // ── Módulo soporte (tickets ADMIN/GERENTE/CLIENTE; categorías solo ADMIN;
  //    FAQ: gestión ADMIN, lectura para roles con SELECT en la BD)
  {
    path: 'operativo/soporte/tickets',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'CLIENTE', 'SOPORTE'])],
    loadComponent: () =>
      import('./features/operativo/soporte/tickets.component').then(m => m.TicketsComponent)
  },
  {
    path: 'operativo/soporte/categorias',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/soporte/categorias-ticket.component').then(m => m.CategoriasTicketComponent)
  },
  {
    path: 'operativo/soporte/faq',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'ANALISTA', 'CLIENTE', 'SOPORTE'])],
    loadComponent: () =>
      import('./features/operativo/soporte/faq.component').then(m => m.FaqComponent)
  },
  // ── Módulo reseñas (cliente crea/vota/reporta/pregunta; ADMIN/GERENTE modera)
  {
    path: 'operativo/resenas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'CLIENTE'])],
    loadComponent: () =>
      import('./features/operativo/resenas/resenas.component').then(m => m.ResenasComponent)
  },
  {
    path: 'operativo/resenas/preguntas',
    canActivate: [authGuard, roleGuard(['ADMIN', 'GERENTE', 'CLIENTE'])],
    loadComponent: () =>
      import('./features/operativo/resenas/preguntas.component').then(m => m.PreguntasComponent)
  },
  {
    path: 'operativo/horarios',
    canActivate: [authGuard, roleGuard(['ADMIN'])],
    loadComponent: () =>
      import('./features/operativo/horarios/horarios.component').then(m => m.HorariosComponent)
  },
  {
    path: '**',
    redirectTo: 'inicio'
  }
];
