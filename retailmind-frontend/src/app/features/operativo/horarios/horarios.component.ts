import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HorariosService } from '../../../core/services/horarios.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { VentanaHoraria } from '../../../core/models/operativo.model';

@Component({
  selector: 'app-horarios',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSlideToggleModule,
    MatSnackBarModule, MatTooltipModule],
  templateUrl: './horarios.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class HorariosComponent implements OnInit {

  readonly dias = ['Domingo', 'Lunes', 'Martes', 'MiÃ©rcoles', 'Jueves', 'Viernes', 'SÃ¡bado'];
  readonly roles = ['grp_gerente', 'grp_vendedor', 'grp_compras', 'grp_bodega',
                    'grp_despacho', 'grp_cliente', 'grp_analista'];

  ventanas: VentanaHoraria[] = [];
  editando: { [id: number]: boolean } = {};
  loading = true;

  showForm = false;
  nueva = { rolGrupo: 'grp_vendedor', diaSemana: 1, horaInicio: '08:00', horaFin: '18:00', activo: true };

  columnas = ['rol', 'dia', 'inicio', 'fin', 'activo', 'acciones'];

  constructor(private horarios: HorariosService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargar(); }

  cargar(): void {
    this.loading = true;
    this.horarios.listar().subscribe({
      next: v => { this.ventanas = v; this.editando = {}; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  guardar(v: VentanaHoraria): void {
    this.horarios.editar(v.id, {
      horaInicio: v.hora_inicio, horaFin: v.hora_fin, activo: v.activo
    }).subscribe({
      next: () => {
        this.snackBar.open('Ventana horaria actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.editando[v.id] = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar'), 'Cerrar', { duration: 4000 })
    });
  }

  crear(): void {
    if (!this.nueva.horaInicio || !this.nueva.horaFin || this.nueva.horaInicio >= this.nueva.horaFin) {
      this.snackBar.open('La hora de inicio debe ser menor que la de fin', 'Cerrar', { duration: 3500 });
      return;
    }
    this.horarios.crear(this.nueva).subscribe({
      next: () => {
        this.snackBar.open('Ventana horaria creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.showForm = false;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear'), 'Cerrar', { duration: 4000 })
    });
  }
}
