import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ShopService } from './shop.service';
import { ShopUiService } from './shop-ui.service';
import { PaletaCategoria } from './catalogo-visual';
import { VentasService } from '../../core/services/ventas.service';
import { mensajeError } from '../../core/services/api-error.util';

import { CampoTextoDirective } from '../../core/validacion';

/**
 * Checkout ONLINE de la tienda (tipo Amazon): resumen del carrito, dirección
 * de envío, cupón (preparado para la fase de descuentos), método de pago con
 * tarjeta SIMULADA (validación de formato; el backend solo guarda marca +
 * últimos 4, nunca PAN/CVV) y confirmación. El pedido resultante nace PAGADO
 * y entra al ciclo de venta (facturar → despachar → entregar).
 */
@Component({
  selector: 'app-checkout',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink, MatButtonModule,
    MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatSnackBarModule, MatProgressSpinnerModule,
    CampoTextoDirective
  ],
  templateUrl: './checkout.component.html',
  styleUrls: ['./shop-shared.scss', './checkout.component.scss']
})
export class CheckoutComponent implements OnInit {

  readonly IVA = 0.15;

  items: any[] = [];
  loading = true;
  procesando = false;

  // Paso 1 — dirección
  direcciones: any[] = [];
  direccionId: number | null = null;
  mostrarNuevaDireccion = false;
  ciudades: any[] = [];
  guardandoDireccion = false;
  nuevaDireccion = {
    destinatario: '', callePrincipal: '', calleSecundaria: '', numero: '',
    referencia: '', telefono: '', ciudadId: null as number | null,
    alias: '', esPredeterminada: false
  };

  // Paso 2 — cupón: se valida contra el backend y se re-aplica al confirmar
  cupon = '';
  cuponAplicado: any = null;
  cuponError = '';
  validandoCupon = false;

  // Paso 3 — método de pago
  metodos: any[] = [];
  metodoPagoId: number | null = null;
  referenciaTransferencia = '';
  tarjeta = { numero: '', titular: '', vencimiento: '', cvv: '' };

  // Resultado
  exito: any = null;
  // Rechazo del pago simulado (OTD-VEN-12): motivo visible y reintento sin
  // perder el carrito (el backend revirtió todo salvo el rastro del intento)
  errorPago: string | null = null;

  constructor(
    private shop: ShopService,
    public ui: ShopUiService,
    private ventas: VentasService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.ui.cargarCategorias();
    this.shop.getCarrito().subscribe({
      next: items => {
        this.items = items;
        this.loading = false;
        if (!items.length) this.router.navigate(['/shop/carrito']);
      },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudo cargar el carrito'), 'Cerrar', { duration: 4000 });
      }
    });
    this.shop.getDirecciones().subscribe({
      next: dirs => {
        this.direcciones = dirs;
        // Manda la que el cliente eligió en la barra superior; si no eligió
        // ninguna, la predeterminada, y si tampoco la hay, la primera. El
        // orden importa: llegar al pago y encontrar OTRA dirección distinta de
        // la que dice la barra es la forma más rápida de perder la confianza.
        const elegida = this.ui.direccionElegidaId;
        const pred = dirs.find(d => Number(d.id) === elegida)
                  || dirs.find(d => d.esPredeterminada)
                  || dirs[0];
        this.direccionId = pred ? pred.id : null;
        this.mostrarNuevaDireccion = !dirs.length;
      },
      error: () => {}
    });
    this.shop.checkoutMetodos().subscribe({
      next: m => {
        this.metodos = m;
        const tarjeta = m.find(x => x.tipo === 'tarjeta');
        this.metodoPagoId = tarjeta ? tarjeta.id : (m[0]?.id ?? null);
      },
      error: () => {}
    });
    this.shop.getCiudades().subscribe({ next: c => this.ciudades = c, error: () => {} });
  }

  // ── Totales (mismo cálculo que el backend: promos por línea, cupón sobre
  //    el subtotal neto e IVA 15% sobre la base realmente cobrada, es decir
  //    neta de promos Y de cupón — salvo envío gratis, que no toca base) ──
  get subtotal(): number {
    return this.items.reduce((s, i) => s + i.precioUnitario * i.cantidad, 0);
  }
  get descuentoPromos(): number {
    return this.items.reduce((s, i) => s + (i.descuentoPromo || 0), 0);
  }
  get subtotalNeto(): number { return this.subtotal - this.descuentoPromos; }
  get descuentoCupon(): number {
    return this.cuponAplicado ? Number(this.cuponAplicado.descuento) || 0 : 0;
  }
  get impuesto(): number {
    const cuponBase = this.cuponAplicado?.tipoDescuento === 'envio_gratis'
      ? 0 : this.descuentoCupon;
    return Math.max(0, this.subtotalNeto - cuponBase) * this.IVA;
  }
  get total(): number {
    return Math.max(0, this.subtotalNeto - this.descuentoCupon + this.impuesto);
  }

  get metodoSeleccionado(): any {
    return this.metodos.find(m => m.id === this.metodoPagoId) || null;
  }
  get esTarjeta(): boolean { return this.metodoSeleccionado?.tipo === 'tarjeta'; }

  // ── Validaciones de tarjeta (formato; el backend re-valida) ─
  get numeroValido(): boolean {
    return /^\d{16}$/.test(this.tarjeta.numero.replace(/[\s-]/g, ''));
  }
  get vencimientoValido(): boolean {
    if (!/^(0[1-9]|1[0-2])\/\d{2}$/.test(this.tarjeta.vencimiento.trim())) return false;
    const [mm, aa] = this.tarjeta.vencimiento.trim().split('/').map(Number);
    const ahora = new Date();
    return (2000 + aa) > ahora.getFullYear()
      || ((2000 + aa) === ahora.getFullYear() && mm >= ahora.getMonth() + 1);
  }
  get cvvValido(): boolean { return /^\d{3,4}$/.test(this.tarjeta.cvv); }
  get tarjetaValida(): boolean {
    return this.numeroValido && !!this.tarjeta.titular.trim()
      && this.vencimientoValido && this.cvvValido;
  }

  get puedeConfirmar(): boolean {
    return !!this.items.length && !!this.direccionId && !!this.metodoPagoId
      && (!this.esTarjeta || this.tarjetaValida) && !this.procesando;
  }

  /** Marca visual según el prefijo (solo informativa; el backend decide). */
  get marcaTarjeta(): string {
    const n = this.tarjeta.numero.replace(/[\s-]/g, '');
    if (/^4/.test(n)) return 'VISA';
    if (/^(5[1-5]|2[2-7])/.test(n)) return 'Mastercard';
    if (/^3[47]/.test(n)) return 'AMEX';
    if (/^6/.test(n)) return 'Discover';
    return '';
  }

  // El formateo del número de tarjeta y del vencimiento vivía aquí, colgado de
  // dos `(input)` de la plantilla, y NO funcionaba: un manejador de plantilla
  // corre ANTES de que `ngModel` escriba en el modelo, así que leía el valor de
  // la tecla anterior y `ngModel` machacaba después lo que este método hubiera
  // corregido. El tope de 16 dígitos, en particular, no llegaba a aplicarse
  // jamás. Ahora son los perfiles `tarjeta` y `vencimiento` de
  // `core/validacion/perfiles-texto.ts`, que trabajan sobre el DOM y reemiten
  // `input` con el valor ya limpio.

  /** Valida el código contra el backend; el monto lo decide SIEMPRE el servidor. */
  aplicarCupon(): void {
    const codigo = this.cupon.trim();
    if (!codigo || this.validandoCupon) return;
    this.validandoCupon = true;
    this.cuponError = '';
    this.shop.validarCupon(codigo).subscribe({
      next: res => {
        this.validandoCupon = false;
        if (res.valido) {
          this.cuponAplicado = res;
        } else {
          this.cuponAplicado = null;
          this.cuponError = res.motivo || 'El cupón no es válido';
        }
      },
      error: e => {
        this.validandoCupon = false;
        this.cuponAplicado = null;
        this.cuponError = mensajeError(e, 'No se pudo validar el cupón');
      }
    });
  }

  quitarCupon(): void {
    this.cupon = '';
    this.cuponAplicado = null;
    this.cuponError = '';
  }

  guardarDireccion(): void {
    const d = this.nuevaDireccion;
    if (!d.destinatario.trim() || !d.callePrincipal.trim() || !d.ciudadId) {
      this.snackBar.open('Destinatario, calle principal y ciudad son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    this.guardandoDireccion = true;
    this.shop.crearDireccion({ ...d, tipo: 'envio' }).subscribe({
      next: r => {
        this.guardandoDireccion = false;
        this.mostrarNuevaDireccion = false;
        this.snackBar.open('Dirección guardada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.shop.getDirecciones().subscribe({
          next: dirs => { this.direcciones = dirs; this.direccionId = r.id; },
          error: () => {}
        });
        // La barra superior tiene su propia copia de la lista: si no se le
        // avisa, sigue anunciando la dirección vieja mientras el pago usa la
        // nueva.
        this.ui.cargarDirecciones(true);
        this.ui.elegirDireccion(r.id);
      },
      error: e => {
        this.guardandoDireccion = false;
        this.snackBar.open(mensajeError(e, 'No se pudo guardar la dirección'), 'Cerrar', { duration: 4500 });
      }
    });
  }

  confirmar(): void {
    if (!this.puedeConfirmar) return;
    this.procesando = true;
    this.errorPago = null;
    // Solo viaja el CÓDIGO del cupón aplicado: el backend re-valida y
    // recalcula el descuento (nunca se envía el monto desde el cliente).
    const body: any = {
      direccionId: this.direccionId,
      metodoPagoId: this.metodoPagoId,
      cupon: this.cuponAplicado ? this.cuponAplicado.codigo : undefined
    };
    if (this.esTarjeta) {
      body.tarjeta = { ...this.tarjeta, numero: this.tarjeta.numero.replace(/\s/g, '') };
    } else {
      body.referenciaTransferencia = this.referenciaTransferencia.trim() || undefined;
    }
    this.shop.checkout(body).subscribe({
      next: res => {
        this.procesando = false;
        this.exito = res;
        this.items = [];
        this.snackBar.open('¡Pago confirmado! Tu pedido quedó PAGADO y FACTURADO', 'OK',
          { duration: 4500, panelClass: ['snack-success'] });
      },
      error: e => {
        this.procesando = false;
        // El motivo queda visible junto al botón: el carrito y el formulario
        // siguen intactos, así que reintentar es volver a pulsar "Pagar"
        this.errorPago = mensajeError(e, 'No se pudo completar la compra');
        this.snackBar.open(this.errorPago, 'Cerrar',
          { duration: 6000, panelClass: ['snack-error'] });
      }
    });
  }

  /** Descarga la factura emitida automáticamente (RLS: solo la propia). */
  descargarFactura(): void {
    if (!this.exito?.facturaId) return;
    this.ventas.facturaPdf(this.exito.facturaId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      },
      error: e => this.snackBar.open(
        mensajeError(e, 'No se pudo descargar la factura'), 'Cerrar', { duration: 4500 })
    });
  }

  paleta(p: any): PaletaCategoria { return this.ui.paleta(p); }

  volverAlCarrito(): void { this.router.navigate(['/shop/carrito']); }
  seguirComprando(): void { this.router.navigate(['/shop']); }
}
