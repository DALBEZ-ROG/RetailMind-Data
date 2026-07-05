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
