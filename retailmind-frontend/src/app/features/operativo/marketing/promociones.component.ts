import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { PromocionRow, PromocionDetalle, ProductoRef } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-promociones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './promociones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PromocionesComponent implements OnInit {

  promociones: PromocionRow[] = [];
  productosRef: ProductoRef[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  seleccionada: PromocionDetalle | null = null;
  productoAsociar: number | null = null;

  tiposDescuento = ['porcentaje', 'monto_fijo'];
  columnas = ['nombre', 'tipo', 'valor', 'vigencia', 'productos', 'activo', 'acciones'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void {
    this.cargar();
    this.marketing.productosRef().subscribe(p => this.productosRef = p);
  }

  private formVacio() {
    return { nombre: '', descripcion: '', tipoDescuento: 'porcentaje', valor: 0,
             fechaInicio: '', fechaFin: '', prioridad: 0, acumulable: false };
  }

  cargar(): void {
    this.loading = true;
    this.marketing.promociones().subscribe({
      next: data => { this.promociones = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(p: PromocionRow): void {
    this.editandoId = p.id;
    this.form = {
      nombre: p.nombre, descripcion: p.descripcion || '', tipoDescuento: p.tipo_descuento,
      valor: p.valor, fechaInicio: (p.fecha_inicio || '').substring(0, 16),
      fechaFin: (p.fecha_fin || '').substring(0, 16),
      prioridad: p.prioridad, acumulable: p.acumulable
    };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.nombre || !this.form.fechaInicio) {
      this.snackBar.open('Nombre y fecha de inicio son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.marketing.crearPromocion(this.form)
      : this.marketing.editarPromocion(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Promoción creada' : 'Promoción actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la promoción'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(p: PromocionRow): void {
    this.marketing.activarPromocion(p.id, !p.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  verDetalle(id: number): void {
    this.marketing.promocion(id).subscribe({
      next: p => { this.seleccionada = p; this.productoAsociar = null; },
      error: () => this.snackBar.open('No se pudo cargar la promoción', 'Cerrar', { duration: 3000 })
    });
  }

  asociar(): void {
    if (!this.seleccionada || this.productoAsociar === null) return;
    this.marketing.asociarProducto(this.seleccionada.id, this.productoAsociar).subscribe({
      next: () => {
        this.snackBar.open('Producto asociado', 'OK', { duration: 2000, panelClass: ['snack-success'] });
        this.verDetalle(this.seleccionada!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al asociar producto'),
        'Cerrar', { duration: 4000 })
    });
  }

  quitar(productoId: number): void {
    if (!this.seleccionada) return;
    this.marketing.quitarProducto(this.seleccionada.id, productoId).subscribe({
      next: () => {
        this.snackBar.open('Producto quitado de la promoción', 'OK', { duration: 2000 });
        this.verDetalle(this.seleccionada!.id);
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al quitar producto'),
        'Cerrar', { duration: 4000 })
    });
  }
}
