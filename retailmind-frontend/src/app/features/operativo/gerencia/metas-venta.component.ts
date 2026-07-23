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
import { MetasService } from '../../../core/services/metas.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { MetaVentaRow } from '../../../core/models/operativo.model';

const MESES = ['Enero', 'Febrero', 'Marzo', 'Abril', 'Mayo', 'Junio', 'Julio',
  'Agosto', 'Septiembre', 'Octubre', 'Noviembre', 'Diciembre'];

/**
 * Metas de venta por período (OTD-VEN-15, script 48).
 * ADMIN/GERENTE fijan y editan; VENDEDOR/ANALISTA solo leen el avance.
 * Admite períodos PASADOS a propósito (siembra de metas históricas).
 */
@Component({
  selector: 'app-metas-venta',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './metas-venta.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class MetasVentaComponent implements OnInit {

  metas: MetaVentaRow[] = [];
  loading = true;

  showForm = false;
  editandoId: number | null = null;
  form = this.formVacio();

  // Espeja el CHECK de meta_venta.departamento
  departamentos = ['general', 'ventas', 'compras', 'inventario',
    'logistica', 'soporte', 'marketing'];
  meses = MESES;
  columnas = ['periodo', 'departamento', 'meta', 'avance', 'fijada', 'activo', 'acciones'];

  constructor(private metasSrv: MetasService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  /** Fijar/editar metas: gerencia (espeja SecurityConfig y los GRANTs). */
  get esGestion(): boolean {
    return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE');
  }

  ngOnInit(): void { this.cargar(); }

  private formVacio() {
    const hoy = new Date();
    return { anio: hoy.getFullYear(), mes: hoy.getMonth() + 1,
             departamento: 'ventas', montoMeta: null as number | null, notas: '' };
  }

  cargar(): void {
    this.loading = true;
    this.metasSrv.metas().subscribe({
      next: data => { this.metas = data; this.loading = false; },
      error: () => this.loading = false
    });
  }

  nuevo(): void {
    this.editandoId = null;
    this.form = this.formVacio();
    this.showForm = true;
  }

  editar(m: MetaVentaRow): void {
    this.editandoId = m.id;
    this.form = { anio: m.anio, mes: m.mes, departamento: m.departamento,
                  montoMeta: m.monto_meta, notas: m.notas || '' };
    this.showForm = true;
  }

  guardar(): void {
    if (!this.form.montoMeta || this.form.montoMeta <= 0) {
      this.snackBar.open('El monto de la meta debe ser mayor que cero', 'Cerrar', { duration: 3000 });
      return;
    }
    const peticion = this.editandoId === null
      ? this.metasSrv.crear(this.form)
      : this.metasSrv.editar(this.editandoId, this.form);
    peticion.subscribe({
      next: () => {
        this.snackBar.open(this.editandoId === null ? 'Meta creada' : 'Meta actualizada',
          'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al guardar la meta'),
        'Cerrar', { duration: 4000 })
    });
  }

  toggleActivo(m: MetaVentaRow): void {
    this.metasSrv.activar(m.id, !m.activo).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.cargar(); },
      error: e => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  nombreMes(mes: number): string { return MESES[mes - 1] || String(mes); }

  /** % de cumplimiento (solo metas con venta_real calculada). */
  cumplimiento(m: MetaVentaRow): number | null {
    if (m.venta_real === null || m.venta_real === undefined || !m.monto_meta) return null;
    return Math.round((m.venta_real / m.monto_meta) * 100);
  }
}
