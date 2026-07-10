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
}

// ── Catálogo admin ───────────────────────────────────────────────────────
export interface ProductoAdmin {
  id: number; nombre: string; slug: string; descripcion_corta: string;
  publicado: boolean; activo: boolean; marca: string | null; variantes: number;
}
export interface ProductoDetalleAdmin {
  id: number; nombre: string; slug: string; marca: string | null;
  publicado: boolean; activo: boolean;
  variantes: VarianteAdmin[];
  categorias: { id: number; nombre: string; es_principal: boolean }[];
}
export interface VarianteAdmin {
  id: number; sku: string; precio: number; costo: number;
  es_predeterminada: boolean; activo: boolean; atributos: string;
}
export interface MarcaAdmin    { id: number; nombre: string; slug: string; activo: boolean; }
export interface CategoriaAdmin { id: number; nombre: string; slug: string; activo: boolean; }

// ── Compras ──────────────────────────────────────────────────────────────
export interface ItemOrdenReq { varianteId: number; cantidad: number; precioUnitario: number; ivaPorcentaje?: number; }
export interface OrdenCompraRow {
  id: number; numero: string; estado: string; fecha_emision: string;
  total: number; proveedor: string; bodega: string;
}
export interface OrdenCompraDetalle extends OrdenCompraRow {
  subtotal: number; monto_impuesto: number; fecha_entrega_esperada: string;
  detalles: {
    id: number; producto_variante_id: number; sku: string; producto: string;
    cantidad: number; precio_unitario: number; subtotal: number;
    monto_impuesto: number; cantidad_recibida: number;
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
  id: number; numero: string; estado: string; total: number;
  fecha_pedido: string; cliente: string;
}
export interface PedidoVentaDetalle extends PedidoVentaRow {
  subtotal: number; monto_impuesto: number; canal: string;
  detalles: {
    id: number; sku: string; nombre_producto: string; cantidad: number;
    precio_unitario: number; subtotal: number;
  }[];
  historial: { estado: string; comentario: string; fecha_creacion: string }[];
  notas: NotaPedidoRow[];
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
export interface EnvioDetalle {
  id: number; numero: string; numero_guia: string; estado: string;
  fecha_despacho: string; direccion_entrega: string; transportista: string;
  metodo_envio: string; numero_pedido: string;
  detalles: { cantidad: number; sku: string; nombre_producto: string }[];
}
export interface SeguimientoRow { estado: string; descripcion: string; ubicacion: string; fecha_evento: string; }
export interface ItemDevolucionReq {
  pedidoDetalleId: number; cantidad: number; estadoProducto?: string; accion?: string;
}
export interface DevolucionDetalle {
  id: number; numero: string; estado: string; monto_total: number;
  descripcion: string; motivo: string; numero_pedido: string;
  detalles: { cantidad: number; estado_producto: string; accion: string; sku: string; nombre_producto: string }[];
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

// ── Soporte ──────────────────────────────────────────────────────────────
export interface CategoriaTicketRow {
  id: number; nombre: string; descripcion: string | null; activo: boolean;
  fecha_creacion: string; tickets: number; faqs: number;
}
export interface CategoriaTicketRef { id: number; nombre: string; }
export interface TicketRow {
  id: number; numero: string; asunto: string; prioridad: string; estado: string;
  pedido_id: number | null; fecha_creacion: string; fecha_cierre: string | null;
  categoria: string | null; mensajes: number;
  // solo en la vista del personal
  cliente_id?: number; cliente?: string | null;
  asignado_usuario_id?: number | null; asignado?: string | null;
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
  mensajes: MensajeTicketRow[];
}
export interface UsuarioSoporteRef { id: number; nombre: string; rol: string; }
export interface PedidoSoporteRef {
  id: number; numero: string; total: number; fecha_pedido: string; estado: string;
}
export interface FaqRow {
  id: number; categoria_ticket_id: number | null; categoria: string | null;
  pregunta: string; respuesta: string; orden: number; activo: boolean; fecha_creacion: string;
}
export interface FaqActiva {
  id: number; categoria: string | null; pregunta: string; respuesta: string; orden: number;
}
