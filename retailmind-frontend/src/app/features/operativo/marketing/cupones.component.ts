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
import { MarketingService } from '../../../core/services/marketing.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { CuponRow, UsoCuponRow } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-cupones',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './cupones.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class CuponesComponent implements OnInit {

  cupones: CuponRow[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  usosDe: CuponRow | null = null;
  usos: UsoCuponRow[] = [];

  tiposDescuento = ['porcentaje', 'monto_fijo', 'envio_gratis'];
  columnas = ['codigo', 'tipo', 'valor', 'vigencia', 'usos', 'activo', 'acciones'];

  constructor(private marketing: MarketingService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    return { codigo: '', descripcion: '', tipoDescuento: 'porcentaje',
             valor: 0, montoMinimoPedido: 0, usosMaximos: null as number | null,
             usosPorCliente: 1, fechaInicio: '', fechaFin: '' };
  }

  cargar(): void {
    this.loading = true;
    this.marketing.cupones().subscribe({
      next: data => { this.cupones = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(c: CuponRow): void {
    this.editandoId = c.id;
    this.form = {
      codigo: c.codigo, descripcion: c.descripcion || '', tipoDescuento: c.tipo_descuento,
      valor: c.valor, montoMinimoPedido: c.monto_minimo_pedido, usosMaximos: c.usos_maximos,
      usosPorCliente: c.usos_por_cliente,
      fechaInicio: (c.fecha_inicio || '').substring(0, 16),
      fechaFin: (c.fecha_fin || '').substring(0, 16)
    };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.codigo || !this.form.fechaInicio) {
      this.snackBar.open('Código y fecha de inicio son requeridos', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.marketing.crearCupon(this.form)
      : this.marketing.editarCupon(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Cupón creado' : 'Cupón actualizado',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar el cupón'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(c: CuponRow): void {
    this.marketing.activarCupon(c.id, !c.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  verUsos(c: CuponRow): void {
    this.usosDe = c;
    this.marketing.usosCupon(c.id).subscribe({
      next: u => this.usos = u,
      error: () => this.snackBar.open('No se pudieron cargar los usos', 'Cerrar', { duration: 3000 })
    });
  }
}
