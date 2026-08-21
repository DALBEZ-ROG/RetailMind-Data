import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { PaginaServidor } from '../../../core/services/pagina-servidor.util';
import { ComprasService } from '../../../core/services/compras.service';
import { ReferenciasService } from '../../../core/services/referencias.service';
import { NavPermissionsService } from '../../../core/navigation/nav-permissions.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  OrdenCompraRow, FacturaCompra, CuentaPorPagarRow, CatalogoRef
} from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

@Component({
  selector: 'app-facturas-compra',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatPaginatorModule,
    CampoNumeroDirective, CampoTextoDirective
  ],
  templateUrl: './facturas-compra.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FacturasCompraComponent implements OnInit {

  /** Órdenes FACTURABLES para el selector. Hoy son 4 de 134.588. */
  ordenes: OrdenCompraRow[] = [];

  /**
   * La página de cuentas por pagar que devuelve el SERVIDOR. Antes llegaban las
   * 134.558 (26,11 MB) y el recorte era del navegador. Esta tabla no tiene
   * filtros propios.
   */
  readonly pag = new PaginaServidor<CuentaPorPagarRow>();

  /**
   * Estaba escrito como literal DENTRO de `matRowDef`, y una expresión de
   * plantilla se reevalúa en cada ciclo de detección de cambios: el array era
   * nuevo cada vez y `MatTable` rehacía las celdas (§8.6).
   */
  readonly columnasCuentas =
    ['factura', 'proveedor', 'monto', 'saldo', 'vence', 'estado', 'acciones'];
  metodosPago: CatalogoRef[] = [];

  ordenId: number | null = null;
  factura: FacturaCompra | null = null;
  procesando = false;

  // Pago a proveedor (UI del endpoint POST /cuentas-por-pagar/{id}/pagos)
  cuentaPago: CuentaPorPagarRow | null = null;
  montoPago: number | null = null;
  metodoPagoId: number | null = null;
  referenciaPago = '';
  pagando = false;

  constructor(private compras: ComprasService, private referencias: ReferenciasService,
              private nav: NavPermissionsService, private snackBar: MatSnackBar) {}

  // BODEGA puede registrar facturas pero NO leer cuenta_por_pagar ni
  // metodo_pago en la BD: no se disparan esas peticiones (evita 403 en consola).
  get puedeVerCuentas(): boolean { return this.nav.canDato('cuentasPorPagar'); }

  ngOnInit(): void {
    this.cargarOrdenes();
    if (this.nav.canDato('refMetodosPago')) {
      this.referencias.metodosPago().subscribe(m => this.metodosPago = m);
    }
    this.cargarCuentas();
  }

  cargarCuentas(conTotal = true): void {
    if (!this.puedeVerCuentas) return;
    this.compras.cuentasPorPagar({
      page: this.pag.pagina, size: this.pag.tam, conTotal
    }).subscribe(pg => this.pag.aplicar(pg));
  }

  /** Cambiar de página NO recuenta: el conjunto es el mismo. */
  alPaginar(e: PageEvent): void {
    this.pag.alPaginar(e);
    this.cargarCuentas(false);
  }

  /**
   * Facturables: recibidas COMPLETAS y sin factura previa (compuertas del
   * backend). **El filtro se movió a SQL** (`facturables=true`).
   *
   * Antes se descargaban las 134.588 órdenes y se filtraban aquí con
   * `o.filter(...)`. Al paginar el endpoint, ese filter habría mirado las 25
   * filas de la primera página y el selector habría salido VACÍO **sin un solo
   * error**: las órdenes facturables son 4 y el listado va por `id DESC`, así
   * que ninguna está entre las 25 primeras. Es exactamente el fallo silencioso
   * que ya se corrigió en `ventas/pedidos`.
   *
   * Se piden 200 —el tope de `Paginacion`— porque el selector no pagina; el
   * conteo real viaja en `total` y se guarda para poder decirlo en pantalla si
   * algún día pasara de 200.
   */
  totalFacturables = 0;

  cargarOrdenes(): void {
    this.compras.ordenes({ facturables: true, size: 200 }).subscribe(pg => {
      this.ordenes = pg.items;
      this.totalFacturables = pg.total;
    });
  }

  abrirPago(c: CuentaPorPagarRow): void {
    this.cuentaPago = c;
    this.montoPago = Number(c.saldo_pendiente);
    this.metodoPagoId = null;
    this.referenciaPago = '';
  }

  registrarPago(): void {
    if (!this.cuentaPago || !this.montoPago || this.montoPago <= 0 || !this.metodoPagoId) {
      this.snackBar.open('Monto (> 0) y método de pago son requeridos', 'Cerrar', { duration: 3500 });
      return;
    }
    this.pagando = true;
    this.compras.registrarPago(this.cuentaPago.id, {
      monto: this.montoPago, metodoPagoId: this.metodoPagoId, referencia: this.referenciaPago
    }).subscribe({
      next: r => {
        this.pagando = false;
        this.snackBar.open(
          `Pago #${r.pagoId} registrado — saldo ${r.saldoPendiente} (${r.estadoCuenta})`, 'OK',
          { duration: 4000, panelClass: ['snack-success'] });
        this.cuentaPago = null;
        this.cargarCuentas();
      },
      error: e => {
        this.pagando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar el pago'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  registrarFactura(): void {
    if (!this.ordenId) {
      this.snackBar.open('Selecciona la orden de compra a facturar', 'Cerrar', { duration: 3500 });
      return;
    }
    this.procesando = true;
    this.compras.registrarFactura(this.ordenId).subscribe({
      next: f => {
        this.procesando = false;
        this.factura = f;
        this.snackBar.open(`Factura ${f.numero_factura} registrada — total ${f.total}`, 'OK',
          { duration: 3500, panelClass: ['snack-success'] });
        this.ordenId = null; // la orden facturada deja de ser facturable
        this.cargarOrdenes();
        this.cargarCuentas();
      },
      error: e => {
        this.procesando = false;
        this.snackBar.open(mensajeError(e, 'No se pudo registrar la factura'), 'Cerrar', { duration: 5000 });
      }
    });
  }

  /**
   * El PDF exige el Bearer token, así que se descarga por HttpClient como blob
   * y se abre con una object URL (un href directo no llevaría el token).
   */
  verPdf(facturaId: number): void {
    this.compras.facturaPdf(facturaId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.snackBar.open('No se pudo generar el PDF', 'Cerrar', { duration: 3000 })
    });
  }
}
