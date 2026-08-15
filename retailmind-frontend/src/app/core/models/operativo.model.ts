// Modelos del módulo operativo (catálogo, compras, inventario, ventas).
// Las claves reflejan el snake_case que devuelve el backend (queryForList).

// ── Referencias (selects) ────────────────────────────────────────────────
export interface ProveedorRef   { id: number; razon_social: string; dias_credito: number; }
export interface BodegaRef      { id: number; codigo: string; nombre: string; }
export interface ClienteRef     { id: number; nombre: string; email: string; }
export interface CatalogoRef    { id: number; codigo?: string; nombre: string; }
export interface VarianteRef    { id: number; sku: string; producto: string; precio: number; costo: number; }
export interface StockRow {
  producto_variante_id: number; sku: string; producto: string;
  bodega_id: number; bodega: string; stock_actual: number;
  stock_minimo: number; stock_maximo: number | null;
}

// ── Catálogo admin ───────────────────────────────────────────────────────
export interface ProductoAdmin {
  id: number; nombre: string; slug: string; descripcion_corta: string;
  publicado: boolean; activo: boolean; marca: string | null; variantes: number;
}
export interface ProductoDetalleAdmin {
  id: number; nombre: string; slug: string; marca: string | null; marca_id: number | null;
  descripcion_corta: string | null; descripcion: string | null;
  publicado: boolean; destacado: boolean; activo: boolean;
  variantes: VarianteAdmin[];
  categorias: { id: number; nombre: string; es_principal: boolean }[];
}
export interface PaginaProductos {
  items: ProductoAdmin[]; total: number; page: number; size: number;
}
export interface VarianteAdmin {
  id: number; sku: string; precio: number; costo: number;
  es_predeterminada: boolean; activo: boolean; atributos: string;
}
export interface MarcaAdmin {
  id: number; nombre: string; slug: string; descripcion: string | null; activo: boolean;
}
export interface CategoriaAdmin {
  id: number; nombre: string; slug: string; descripcion: string | null;
  categoria_padre_id: number | null; orden: number; activo: boolean;
}

// ── Compras ──────────────────────────────────────────────────────────────
export interface ItemOrdenReq { varianteId: number; cantidad: number; precioUnitario: number; ivaPorcentaje?: number; }
export interface OrdenCompraRow {
  id: number; numero: string; estado: string; fecha_emision: string;
  // montos ausentes para BODEGA (segregación financiera)
  total?: number; proveedor: string; bodega: string; tiene_factura?: boolean;
}
export interface OrdenCompraDetalle extends OrdenCompraRow {
  subtotal?: number; monto_impuesto?: number; fecha_entrega_esperada: string;
  detalles: {
    id: number; producto_variante_id: number; sku: string; producto: string;
    cantidad: number; precio_unitario?: number; subtotal?: number;
    monto_impuesto?: number; cantidad_recibida: number;
  }[];
}
export interface ItemRecepcionReq {
  ordenCompraDetalleId: number; cantidadRecibida: number;
  cantidadRechazada?: number; motivoRechazo?: string;
}
export interface FacturaCompra {
  id: number; numero_factura: string; estado: string; fecha_emision: string;
  fecha_vencimiento: string; subtotal: number; monto_impuesto: number; total: number;
  orden_compra_id: number; proveedor: string;
  cuenta_por_pagar_id: number | null; saldo_pendiente: number | null; estado_cxp: string | null;
  detalles: { sku: string; producto: string; cantidad: number; precio_unitario: number; subtotal: number }[];
}
export interface CuentaPorPagarRow {
  id: number; monto_original: number; saldo_pendiente: number; estado: string;
  fecha_vencimiento: string; numero_factura: string; proveedor: string;
}

// ── Inventario ───────────────────────────────────────────────────────────
export interface TransferenciaRow {
  [key: string]: any; // el backend devuelve el detalle completo de la transferencia
}
export interface AjusteRow {
  id: number; tipo: string; estado: string; motivo: string;
  fecha_aplicacion: string; bodega: string;
}
export interface AjusteResultado {
  id: number; sku: string; tipo: string; cantidad: number;
  stockResultante: number; estado: string;
}
export interface KardexRow {
  id: number; fecha_creacion: string; sku: string; producto: string;
  bodega: string; tipo_movimiento: string; naturaleza: string;
  cantidad: number; stock_anterior: number; stock_nuevo: number;
  costo_unitario: number | null; referencia_tipo: string | null;
  referencia_id: number | null; referencia: string; observacion: string | null;
}

// ── Ventas ───────────────────────────────────────────────────────────────
export interface ItemPedidoReq { varianteId: number; cantidad: number; }
export interface PedidoVentaRow {
  // total ausente para BODEGA/DESPACHO (segregación financiera)
  id: number; numero: string; estado: string; total?: number;
  fecha_pedido: string; cliente: string; tiene_factura?: boolean;
  // 'web' = tienda online (nace pagado+facturado en el checkout); 'tienda'/'telefono' = interno
  canal: string;
  // Transportista ASIGNADO por zona (script 39); despacho puede cambiarlo
  transportista?: string | null;
}
export interface PedidoVentaDetalle extends PedidoVentaRow {
  subtotal: number; monto_impuesto: number;
  // Fase de descuentos (script 40): cupón de cabecera y promo por línea
  monto_descuento: number;
  cupon: { codigo: string; monto_descontado: number } | null;
  metodo_envio?: string | null;
  dias_entrega_min?: number | null; dias_entrega_max?: number | null;
  detalles: {
    id: number; sku: string; nombre_producto: string; cantidad: number;
    precio_unitario: number; subtotal: number; monto_descuento: number;
    // producto del catálogo (para "Reseñar" desde Mis Pedidos)
    producto_id: number;
  }[];
  historial: { estado: string; comentario: string; fecha_creacion: string }[];
  notas: NotaPedidoRow[];
  // Proceso encadenado (pago -> factura -> envío)
  factura: { id: number; numero: string; estado: string } | null;
  envio: {
    id: number; numero: string; numero_guia: string; estado: string;
    fecha_despacho: string; fecha_entrega_real: string | null;
  } | null;
  pagos: PagoVentaRow[];
  total_pagado?: number; saldo_pendiente?: number; // solo personal
}
export interface PagoVentaRow {
  id: number; monto: number; estado: string; referencia_externa: string | null;
  fecha_pago: string; metodo: string;
}
export interface PagoClienteRes {
  pagoId: number; totalPagado: number; saldoPendiente: number; estadoPedido: string;
}
export interface NotaPedidoRow {
  id: number; nota: string; fecha_creacion: string;
  // solo en la vista del personal
  es_visible_cliente?: boolean; autor?: string | null;
}
export interface FacturaVenta {
  id: number; numero: string; estado: string; fecha_emision: string;
  razon_social: string; identificacion: string; pedido_id: number;
  subtotal: number; monto_impuesto: number; total: number; numero_pedido: string;
  detalles: { descripcion: string; cantidad: number; precio_unitario: number; subtotal: number }[];
}
export interface FacturaVentaRow {
  id: number; numero: string; estado: string; fecha_emision: string;
  total: number; cliente: string; pedido_id: number; numero_pedido: string;
}
export interface PaginaFacturasVenta {
  items: FacturaVentaRow[]; total: number; page: number; size: number;
  /** Ver `Pagina.totalEsMinimo`: 2.855.378 facturas no se cuentan enteras. */
  totalEsMinimo?: boolean;
}
/**
 * Sobre del listado de pedidos. Dejó de ser un array porque el endpoint
 * devolvía los 2.999.993 pedidos y tumbaba el backend con OutOfMemoryError.
 * `total` es el conteo REAL del conjunto filtrado, no el de la página.
 */
export interface PaginaPedidosVenta {
  items: PedidoVentaRow[]; total: number; page: number; size: number;
  /** Ver `Pagina.totalEsMinimo`: 2.999.993 pedidos no se cuentan enteros. */
  totalEsMinimo?: boolean;
}
export interface EnvioDetalle {
  id: number; numero: string; numero_guia: string; estado: string;
  fecha_despacho: string; direccion_entrega: string; transportista: string;
  metodo_envio: string; numero_pedido: string;
  detalles: { cantidad: number; sku: string; nombre_producto: string }[];
}
export interface SeguimientoRow { estado: string; descripcion: string; ubicacion: string; fecha_evento: string; }
// ── Tramo de salida: preparación de bodega y despacho con detalle (script 39) ─
// Sin montos: son vistas OPERATIVAS de bodega/despacho (segregación financiera)
/**
 * Sobre estándar de todo listado paginado EN EL SERVIDOR: espeja
 * `comun.Paginacion` del backend. `total` es el conteo del conjunto FILTRADO
 * (no el de la página) y vale -1 cuando el servidor no lo recalculó.
 */
export interface Pagina<T> {
  items: T[]; total: number; page: number; size: number;
  /**
   * true = `total` es un MÍNIMO, no el conteo exacto: la consulta llegó al
   * tope de `comun.Paginacion.TOPE_CONTEO` y se cortó ahí a propósito, porque
   * contar bajo RLS cuesta una llamada a `esta_en_horario()` por fila. La
   * pantalla DEBE decirlo («más de N»): un total que miente sin avisar es peor
   * que uno lento.
   */
  totalEsMinimo?: boolean;
}
export interface PreparacionRow {
  id: number; numero: string; estado: string; canal: string; fecha_pedido: string;
  cliente: string; factura: string | null;
  transportista: string | null; metodo_envio: string | null;
  items: number; unidades: number;
}
export interface DetalleLogistico {
  id: number; numero: string; estado: string; canal: string; fecha_pedido: string;
  cliente: string; cliente_telefono: string | null;
  transportista_id: number | null; transportista: string | null;
  metodo_envio_id: number | null; metodo_envio: string | null;
  dias_entrega_min: number | null; dias_entrega_max: number | null;
  factura: string | null; direccion_entrega: string;
  detalles: {
    id: number; sku: string; nombre_producto: string; cantidad: number;
  }[];
}
// ── Novedades / incidencias de envío (script 44) ─────────────────────────────
// registrado_por/resuelto_por solo llegan al personal (el cliente no lee usuario)
export interface NovedadEnvioRow {
  id: number; tipo: string; descripcion: string | null; intento_numero: number;
  estado: 'abierta' | 'resuelta'; accion: 'reprogramada' | 'devuelto_almacen' | null;
  fecha_registro: string; fecha_resolucion: string | null;
  registrado_por?: string; resuelto_por?: string | null;
}
export interface NovedadesEnvioRes {
  pedido_id: number;
  envio: { id: number; numero: string; numero_guia: string; estado: string;
           fecha_entrega_estimada: string | null } | null;
  intentos: number; max_intentos?: number;
  novedades: NovedadEnvioRow[];
}
// ── Devoluciones RMA / logística inversa (script 38) ─────────────────────
export interface ItemDevolucionReq {
  pedidoDetalleId: number; cantidad: number; estadoProducto?: string;
}
export interface DevolucionRow {
  id: number; numero: string; estado: string;
  // Ausentes para BODEGA/DESPACHO (segregación financiera)
  monto_total?: number;
  monto_reembolsado?: number | null; guia_retorno: string | null;
  fecha_creacion: string; ticket_soporte_id: number | null;
  motivo: string; numero_pedido: string; cliente?: string;
  transportista?: string | null;
}
export interface DevolucionItemRma {
  id: number; cantidad: number; estado_producto: string; accion: string;
  resultado_inspeccion: string | null; nota_inspeccion: string | null;
  // precio ausente para BODEGA/DESPACHO (segregación financiera)
  sku: string; nombre_producto: string; precio_unitario?: number;
}
export interface HistorialDevolucionRow {
  estado: string; comentario: string; fecha_creacion: string; autor: string;
}
export interface DevolucionRma extends DevolucionRow {
  descripcion: string | null; metodo_reembolso: string | null;
  fecha_reembolso: string | null; motivo_rechazo: string | null;
  pedido_id: number; cliente_email?: string; bodega?: string | null;
  bodega_direccion?: string | null; ticket_numero?: string | null;
  detalles: DevolucionItemRma[]; historial: HistorialDevolucionRow[];
}
export interface ItemElegibleDevolucion {
  pedido_detalle_id: number; sku: string; nombre_producto: string;
  comprada: number; devuelta: number; disponible: number; precio_unitario: number;
}
export interface ElegibilidadDevolucion {
  pedido_id: number; numero_pedido: string; estado_pedido: string;
  fecha_entrega: string | null; plazo_dias: number; dias_restantes: number;
  elegible: boolean; items: ItemElegibleDevolucion[];
}

// ── Horarios de acceso ───────────────────────────────────────────────────
export interface VentanaHoraria {
  id: number; rol_grupo: string; dia_semana: number;
  hora_inicio: string; hora_fin: string; activo: boolean;
}

// ── Marketing ────────────────────────────────────────────────────────────
export interface CuponRow {
  id: number; codigo: string; descripcion: string | null; tipo_descuento: string;
  valor: number; monto_minimo_pedido: number; usos_maximos: number | null;
  usos_por_cliente: number; usos_actuales: number;
  fecha_inicio: string; fecha_fin: string | null; activo: boolean; fecha_creacion: string;
}
export interface UsoCuponRow {
  id: number; pedido_id: number; cliente_id: number | null; cliente: string | null;
  monto_descontado: number; fecha_creacion: string;
}
export interface PromocionRow {
  id: number; nombre: string; descripcion: string | null; tipo_descuento: string;
  valor: number; fecha_inicio: string; fecha_fin: string | null;
  prioridad: number; acumulable: boolean; activo: boolean;
  fecha_creacion: string; productos: number;
}
export interface PromocionDetalle {
  id: number; nombre: string; descripcion: string | null; tipo_descuento: string;
  valor: number; fecha_inicio: string; fecha_fin: string | null;
  prioridad: number; acumulable: boolean; activo: boolean;
  productos: { id: number; producto_id: number; producto: string; producto_activo: boolean }[];
}
export interface ProductoRef { id: number; nombre: string; }
export interface CampanaRow {
  id: number; nombre: string; descripcion: string | null; canal: string;
  presupuesto: number | null; estado: string;
  fecha_inicio: string | null; fecha_fin: string | null;
  fecha_creacion: string; banners: number;
}
export interface BannerRow {
  id: number; campana_id: number | null; campana: string | null; titulo: string;
  imagen_url: string; url_destino: string | null; posicion: string; orden: number;
  fecha_inicio: string | null; fecha_fin: string | null;
  activo: boolean; fecha_creacion: string;
}
export interface SuscriptorRow {
  id: number; email: string; cliente_id: number | null; cliente: string | null;
  confirmado: boolean; fecha_suscripcion: string; fecha_baja: string | null; activo: boolean;
}

// ── Catálogo proveedor-producto (OTD-COM-10, script 51) ──────────────────
export interface ProveedorFichaRow {
  id: number; ruc: string; razon_social: string; nombre_comercial: string | null;
  email: string | null; telefono: string | null; dias_credito: number;
  activo: boolean; productos: number;
}
export interface ProductoProveedorRow {
  id: number; producto_variante_id: number; producto: string; sku: string;
  codigo_proveedor: string | null; costo: number; tiempo_entrega_dias: number | null;
  cantidad_minima: number; es_preferido: boolean; activo: boolean;
  fecha_creacion: string; fecha_actualizacion: string | null;
}
/** Resultado del buscador de producto para asociar (id = variante). */
export interface ProductoCompraRef { id: number; nombre: string; sku: string; }

// ── Gerencia: metas de venta (OTD-VEN-15, script 48) ─────────────────────
export interface MetaVentaRow {
  id: number; anio: number; mes: number; departamento: string;
  monto_meta: number; notas: string | null; activo: boolean;
  fecha_creacion: string; fecha_actualizacion: string | null;
  fijada_por: string | null;
  venta_real: number | null; // solo metas 'general'/'ventas' (facturado del mes)
}

// ── Seguridad: intentos de acceso (OTD-GER-09, script 53) ────────────────
export interface LogAccesoRow {
  id: number;
  fecha_creacion: string;
  email_intentado: string | null;
  exitoso: boolean;
  motivo_fallo: string | null;
  ip_origen: string | null;
  user_agent: string | null;
  usuario_id: number | null;
  usuario: string | null; // nombre del usuario si se identificó
}
export interface LogAccesoPage {
  items: LogAccesoRow[]; total: number; page: number; size: number;
}

// ── Soporte ──────────────────────────────────────────────────────────────
export interface CategoriaTicketRow {
  id: number; nombre: string; descripcion: string | null; activo: boolean;
  fecha_creacion: string; tickets: number; faqs: number;
  prioridad_defecto: string; // la prioridad del ticket nace de aquí (script 37)
}
export interface CategoriaTicketRef { id: number; nombre: string; descripcion?: string | null; }
export interface TicketRow {
  id: number; numero: string; asunto: string; prioridad: string; estado: string;
  pedido_id: number | null; fecha_creacion: string; fecha_cierre: string | null;
  categoria: string | null; mensajes: number;
  // solo en la vista del personal
  cliente_id?: number; cliente?: string | null;
  asignado_usuario_id?: number | null; asignado?: string | null;
  asignado_a_mi?: boolean;
  // SLA persistido en BD (script 49): ticket_soporte.fecha_limite
  fecha_limite?: string; sla_vencido?: boolean;
}
export interface MensajeTicketRow {
  id: number; mensaje: string; fecha_creacion: string; de_cliente: boolean;
  autor: string | null; es_interno?: boolean;
}
export interface TicketDetalle {
  id: number; numero: string; asunto: string; descripcion: string | null;
  prioridad: string; estado: string; pedido_id: number | null;
  fecha_creacion: string; fecha_cierre: string | null; categoria: string | null;
  cliente_id?: number; cliente?: string | null;
  asignado_usuario_id?: number | null; asignado?: string | null;
  fecha_limite?: string; sla_vencido?: boolean; // columna de BD (script 49)
  // Producto del reclamo (script 50, opcional)
  producto_variante_id?: number | null; producto?: string | null;
  mensajes: MensajeTicketRow[];
}
export interface UsuarioSoporteRef { id: number; nombre: string; rol: string; }
export interface PedidoSoporteRef {
  id: number; numero: string; total: number; fecha_pedido: string; estado: string;
}
/** Resultado del buscador de producto del ticket (script 50; id = variante). */
export interface ProductoTicketRef { id: number; nombre: string; sku: string; }
export interface FaqRow {
  id: number; categoria_ticket_id: number | null; categoria: string | null;
  pregunta: string; respuesta: string; orden: number; activo: boolean; fecha_creacion: string;
}
export interface FaqActiva {
  id: number; categoria: string | null; pregunta: string; respuesta: string; orden: number;
}

// ── Reseñas y preguntas de producto ──────────────────────────────────────
export interface ProductoResenaRef { id: number; nombre: string; }
export interface ResenaRow {
  id: number; producto_id: number; producto: string; calificacion: number;
  titulo: string | null; comentario: string | null; compra_verificada: boolean;
  estado: string; fecha_creacion: string; utiles: number;
  cliente_id?: number; cliente?: string | null;
  no_utiles?: number; reportes_pendientes?: number;
}
export interface ResenaPublica {
  id: number; calificacion: number; titulo: string | null; comentario: string | null;
  compra_verificada: boolean; fecha_creacion: string; cliente: string;
  es_mia: boolean | null; utiles: number; no_utiles: number; mi_voto: boolean | null;
}
export interface ReporteResenaRow {
  id: number; resena_id: number; motivo: string; comentario: string | null;
  estado: string; fecha_creacion: string; reportado_por: string | null;
  resena_titulo: string | null; resena_comentario: string | null;
  calificacion: number; resena_estado: string; producto: string;
}
export interface RespuestaPreguntaRow {
  id: number; respuesta: string; es_oficial: boolean; fecha_creacion: string;
  autor: string | null;
}
export interface PreguntaProductoRow {
  id: number; pregunta: string; estado: string; fecha_creacion: string;
  respuestas: RespuestaPreguntaRow[];
  producto_id?: number; producto?: string;
  cliente_id?: number; cliente?: string | null; es_mia?: boolean;
}

// ── Administración de usuarios (/admin-usuarios) ─────────────────────────
export interface UsuarioAdminRow {
  id: number;
  username: string;          // el email: es la credencial de login
  nombre: string;            // nombre + apellido ya concatenados (columna de la grilla)
  soloNombre: string;        // nombre suelto, para el formulario
  apellido: string | null;
  telefono: string | null;
  rol: string | null;
  activo: boolean;
  clienteId: number | null;
  fechaCreacion: string | null;
  ultimoAcceso: string | null;
}
export interface RolRef { codigo: string; nombre: string; }
