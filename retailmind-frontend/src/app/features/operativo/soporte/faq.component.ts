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
import { MatExpansionModule } from '@angular/material/expansion';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { of, switchMap } from 'rxjs';
import { SoporteService } from '../../../core/services/soporte.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { FaqRow, FaqActiva, CategoriaTicketRef } from '../../../core/models/operativo.model';
import {
  FaqDialogComponent, FaqDialogData, FaqDialogResultado
} from './faq-dialog.component';

import { CampoTextoDirective } from '../../../core/validacion';

type FiltroEstado = 'todos' | 'activos' | 'eliminados';

/**
 * FAQ con dos caras: la GESTIÓN (ADMIN edita, GERENTE consulta la lista
 * completa), alineada al patrón, y el CENTRO DE AYUDA de solo lectura
 * (CLIENTE/ANALISTA/SOPORTE ven las FAQ activas en un acordeón), que no es
 * una pantalla de mantenimiento y por tanto no lleva grilla ni acciones.
 *
 * «Eliminar» es la BAJA LÓGICA de siempre (PATCH .../activo con false).
 */
@Component({
  selector: 'app-faq',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatExpansionModule, MatDialogModule, AccionesRegistroComponent,
    CampoTextoDirective
  ],
  templateUrl: './faq.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class FaqComponent implements OnInit {

  private todas: FaqRow[] = [];
  faqs: FaqRow[] = [];
  faqsActivas: FaqActiva[] = [];
  categoriasRef: CategoriaTicketRef[] = [];
  loading = true;

  // Criterios de búsqueda (regla 1)
  filtroTexto = '';
  filtroCategoriaId: number | null | 'todos' = 'todos';
  filtroEstado: FiltroEstado = 'todos';

  filaSeleccionada: FaqRow | null = null;

  columnas = ['pregunta', 'categoria', 'orden', 'activo'];

  constructor(private soporte: SoporteService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esAdmin(): boolean { return this.auth.hasRole('ADMIN'); }
  get esGestion(): boolean { return this.auth.hasRole('ADMIN') || this.auth.hasRole('GERENTE'); }

  ngOnInit(): void {
    this.cargar();
    if (this.esGestion) {
      this.soporte.categoriasRef().subscribe({ next: c => this.categoriasRef = c, error: () => {} });
    }
  }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    if (this.esGestion) {
      this.soporte.faqs().subscribe({
        next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
        error: () => this.loading = false
      });
    } else {
      this.soporte.faqsActivas().subscribe({
        next: data => { this.faqsActivas = data; this.loading = false; },
        error: () => this.loading = false
      });
    }
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.faqs = this.todas.filter(f => {
      if (this.filtroEstado === 'activos' && !f.activo) return false;
      if (this.filtroEstado === 'eliminados' && f.activo) return false;
      if (this.filtroCategoriaId !== 'todos'
          && f.categoria_ticket_id !== this.filtroCategoriaId) return false;
      if (!q) return true;
      return f.pregunta.toLowerCase().includes(q)
          || f.respuesta.toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroCategoriaId = 'todos';
    this.filtroEstado = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.faqs.find(f => f.id === this.filaSeleccionada!.id) ?? null;
  }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(f: FaqRow): void { this.filaSeleccionada = f; }

  nuevaFaq(): void { this.abrirDialogo('nuevo'); }
  modificarFaq(): void { this.abrirDialogo('actualizar'); }
  verFaq(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const faq = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !faq) return;

    const data: FaqDialogData = { faq, categorias: this.categoriasRef, modo };
    this.dialog.open(FaqDialogComponent, { data, panelClass: 'dubai-dialog' })
      .afterClosed().subscribe((res: FaqDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.guardar(faq!, res);
      });
  }

  private crear(res: FaqDialogResultado): void {
    const { activo, ...body } = res;
    this.soporte.crearFaq(body).subscribe({
      next: () => {
        this.snackBar.open('FAQ creada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al crear la FAQ'),
        'Cerrar', { duration: 4000 })
    });
  }

  private guardar(original: FaqRow, res: FaqDialogResultado): void {
    const { activo, ...cambios } = res;
    this.soporte.editarFaq(original.id, cambios).pipe(
      switchMap(() => activo === original.activo
        ? of(null)
        : this.soporte.activarFaq(original.id, activo))
    ).subscribe({
      next: () => {
        this.snackBar.open('FAQ actualizada', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'Error al actualizar la FAQ'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (baja lógica) siempre pregunta antes ───────────

  eliminarFaq(): void {
    const f = this.filaSeleccionada;
    if (!f || !f.activo) return;
    this.confirmar.eliminacion(
      `la pregunta «${f.pregunta}»`,
      'La pregunta dejará de aparecer en el centro de ayuda que ven los clientes. ' +
      'No se borra nada: su texto y su respuesta se conservan, y puedes restaurarla ' +
      'marcando «Activa» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.soporte.activarFaq(f.id, false).subscribe({
        next: () => {
          this.snackBar.open('Pregunta frecuente eliminada', 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'Error al eliminar la FAQ'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada && !this.filaSeleccionada.activo
      ? 'La pregunta ya está eliminada (inactiva). Restáurala desde Modificar.'
      : '';
  }
}
