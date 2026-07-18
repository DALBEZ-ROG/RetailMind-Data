import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ShopService } from './shop.service';
import { VentasService } from '../../core/services/ventas.service';
import { mensajeError } from '../../core/services/api-error.util';

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
    CommonModule, FormsModule, RouterLink, MatCardModule, MatButtonModule,
    MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatRadioModule, MatDividerModule, MatSnackBarModule, MatProgressSpinnerModule
  ],
  templateUrl: './checkout.component.html',
  styleUrl: './checkout.component.scss'
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

  constructor(
    private shop: ShopService,
    private ventas: VentasService,
    private router: Router,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
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
        const pred = dirs.find(d => d.esPredeterminada) || dirs[0];
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

  // ── Totales (mismo cálculo que el backend: promos por línea, IVA 15%
  //    sobre la base rebajada, cupón sobre el subtotal neto) ─────────────
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
  get impuesto(): number { return this.subtotalNeto * this.IVA; }
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

  formatearNumero(): void {
    const digitos = this.tarjeta.numero.replace(/\D/g, '').slice(0, 16);
    this.tarjeta.numero = digitos.replace(/(\d{4})(?=\d)/g, '$1 ');
  }

  formatearVencimiento(): void {
    let v = this.tarjeta.vencimiento.replace(/[^\d/]/g, '');
    if (/^\d{3,}$/.test(v)) v = v.slice(0, 2) + '/' + v.slice(2, 4);
    this.tarjeta.vencimiento = v.slice(0, 5);
  }

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
        this.snackBar.open(mensajeError(e, 'No se pudo completar la compra'), 'Cerrar',
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

  volverAlCarrito(): void { this.router.navigate(['/shop/carrito']); }
  seguirComprando(): void { this.router.navigate(['/shop']); }
}
