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
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { Observable, of, switchMap } from 'rxjs';
import { ResenasService } from '../../../core/services/resenas.service';
import { AuthService } from '../../../core/services/auth.service';
import { ConfirmService } from '../../../core/services/confirm.service';
import {
  SelectBuscableComponent, OpcionBuscable
} from '../../../core/components/select-buscable/select-buscable.component';
import {
  AccionesRegistroComponent
} from '../../../core/components/acciones-registro/acciones-registro.component';
import { mensajeError } from '../../../core/services/api-error.util';
import { PreguntaProductoRow, ProductoResenaRef } from '../../../core/models/operativo.model';
import {
  PreguntaDialogComponent, PreguntaDialogData, PreguntaDialogResultado
} from './pregunta-dialog.component';

/** Espeja las transiciones del backend (ResenasService.TRANSICIONES_PREGUNTA). */
const TRANSICIONES: Record<string, string[]> = {
  pendiente: ['publicada', 'rechazada'],
  publicada: ['rechazada'],
  rechazada: ['publicada']
};

/**
 * Preguntas de productos, alineada al patrón (docs/PATRON_UI.md).
 *
 * Era la única pantalla de gestión SIN tabla: la lista eran tarjetas apiladas
 * y cada tarjeta llevaba sus propios botones («Responder», «Publicar»,
 * «Rechazar», «Publicar respuesta»). Ahora es una GRILLA con la barra de las
 * cuatro opciones, y las tres acciones de moderación caben dentro de
 * «Modificar» —estado y respuesta oficial en el mismo diálogo—, igual que se
 * hizo con el ciclo de vida de Campañas en la Fase 1.
 *
 * Limitación DECLARADA: desde moderación no se crea una pregunta (nace del
 * cliente), así que esa barra oculta «Nuevo».
 */
@Component({
  selector: 'app-preguntas-producto',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatDialogModule, SelectBuscableComponent, AccionesRegistroComponent],
  templateUrl: './preguntas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PreguntasComponent implements OnInit {

  private todas: PreguntaProductoRow[] = [];
  preguntas: PreguntaProductoRow[] = [];

  // Criterios de búsqueda (regla 1)
  filtroEstado = 'todos';
  filtroTexto = '';
  filtroRespondidas = 'todos';        // 'todos' | 'con' | 'sin'
  productoSel: number | null = null;  // criterio obligatorio en la vista de cliente

  filaSeleccionada: PreguntaProductoRow | null = null;

  productosRef: ProductoResenaRef[] = [];
  productosOpc: OpcionBuscable[] = [];
  loading = true;
  estados = ['pendiente', 'publicada', 'rechazada'];

  columnas = ['producto', 'pregunta', 'cliente', 'respuestas', 'estado', 'fecha'];
  columnasCliente = ['pregunta', 'respuestas', 'estado', 'fecha'];

  constructor(private servicio: ResenasService, private auth: AuthService,
              private snackBar: MatSnackBar, private dialog: MatDialog,
              private confirmar: ConfirmService) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }

  get columnasVigentes(): string[] {
    return this.esCliente ? this.columnasCliente : this.columnas;
  }

  ngOnInit(): void {
    this.servicio.productosRef().subscribe({
      next: p => {
        this.productosRef = p;
        this.productosOpc = p.map(x => ({ id: x.id, texto: x.nombre }));
      },
      error: () => {}
    });
    this.cargar();
  }

  // ── Regla 1: la grilla y sus criterios ───────────────────────────────

  cargar(): void {
    this.loading = true;
    if (this.esCliente) {
      if (!this.productoSel) {
        this.todas = []; this.aplicarFiltros(); this.loading = false; return;
      }
      this.servicio.preguntasProducto(this.productoSel).subscribe({
        next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
        error: () => this.loading = false
      });
    } else {
      this.servicio.preguntas().subscribe({
        next: data => { this.todas = data; this.aplicarFiltros(); this.loading = false; },
        error: () => this.loading = false
      });
    }
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.preguntas = this.todas.filter(p => {
      if (this.filtroEstado !== 'todos' && p.estado !== this.filtroEstado) return false;
      const respondida = (p.respuestas?.length ?? 0) > 0;
      if (this.filtroRespondidas === 'con' && !respondida) return false;
      if (this.filtroRespondidas === 'sin' && respondida) return false;
      if (!q) return true;
      return p.pregunta.toLowerCase().includes(q)
          || (p.producto ?? '').toLowerCase().includes(q)
          || (p.cliente ?? '').toLowerCase().includes(q);
    });
    this.resincronizarSeleccion();
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroEstado = 'todos';
    this.filtroRespondidas = 'todos';
    this.aplicarFiltros();
  }

  private resincronizarSeleccion(): void {
    if (!this.filaSeleccionada) return;
    this.filaSeleccionada = this.preguntas.find(p => p.id === this.filaSeleccionada!.id) ?? null;
  }

  transiciones(estado: string): string[] { return TRANSICIONES[estado] || []; }

  // ── Regla 2: selección + las cuatro opciones ─────────────────────────

  seleccionarFila(p: PreguntaProductoRow): void { this.filaSeleccionada = p; }

  nuevaPregunta(): void { this.abrirDialogo('nuevo'); }
  modificarPregunta(): void { this.abrirDialogo('actualizar'); }
  verPregunta(): void { this.abrirDialogo('consulta'); }

  private abrirDialogo(modo: 'nuevo' | 'actualizar' | 'consulta'): void {
    const pregunta = modo === 'nuevo' ? undefined : this.filaSeleccionada ?? undefined;
    if (modo !== 'nuevo' && !pregunta) return;

    const data: PreguntaDialogData = {
      pregunta, modo, esModerador: !this.esCliente, productos: this.productosOpc,
      transiciones: pregunta ? this.transiciones(pregunta.estado) : []
    };
    this.dialog.open(PreguntaDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false })
      .afterClosed().subscribe((res: PreguntaDialogResultado | undefined) => {
        if (!res) return;
        if (modo === 'nuevo') this.crear(res);
        else this.aplicarCambios(pregunta!, res);
      });
  }

  private crear(res: PreguntaDialogResultado): void {
    this.servicio.crearPregunta({
      productoId: res.productoId, pregunta: res.pregunta
    }).subscribe({
      next: () => {
        this.snackBar.open('Pregunta enviada: queda pendiente de moderación', 'OK',
          { duration: 3000, panelClass: ['snack-success'] });
        this.productoSel = res.productoId;
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo enviar la pregunta'),
        'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Modificar puede traer DOS cosas: un cambio de estado y una respuesta
   * oficial nueva. Van encadenadas —igual que `activo` en el resto de
   * pantallas— y solo se llama a lo que de verdad cambió.
   */
  private aplicarCambios(original: PreguntaProductoRow, res: PreguntaDialogResultado): void {
    const cambioEstado = res.estado && res.estado !== original.estado;
    const hayRespuesta = !!res.respuesta.trim();
    if (!cambioEstado && !hayRespuesta) return;

    const primero: Observable<unknown> = hayRespuesta
      ? this.servicio.responderPregunta(original.id, res.respuesta.trim())
      : of(null);

    primero.pipe(
      switchMap(() => cambioEstado
        ? this.servicio.moderarPregunta(original.id, res.estado)
        : of(null))
    ).subscribe({
      next: () => {
        this.snackBar.open('Pregunta actualizada', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo actualizar la pregunta'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Regla 5: eliminar (rechazar) siempre pregunta antes ──────────────

  /**
   * «Eliminar» una pregunta = RECHAZARLA: deja de verse en la ficha del
   * producto. Es reversible desde Modificar y el mensaje lo dice.
   */
  eliminarPregunta(): void {
    const p = this.filaSeleccionada;
    if (!p || p.estado === 'rechazada') return;
    const recorte = p.pregunta.length > 70 ? p.pregunta.slice(0, 70) + '…' : p.pregunta;
    this.confirmar.eliminacion(
      `la pregunta «${recorte}»`,
      'Pasará a estado «rechazada» y dejará de mostrarse en la ficha del producto, ' +
      'junto con las respuestas que ya tenga. No se borra nada: el texto del cliente ' +
      'y las respuestas se conservan, y puedes volver a publicarla eligiendo ' +
      '«publicada» desde Modificar.'
    ).subscribe(ok => {
      if (!ok) return;
      this.servicio.moderarPregunta(p.id, 'rechazada').subscribe({
        next: () => {
          this.snackBar.open('Pregunta rechazada', 'OK', { duration: 3000 });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'No se pudo rechazar la pregunta'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  get motivoNoEliminable(): string {
    return this.filaSeleccionada?.estado === 'rechazada'
      ? 'La pregunta ya está rechazada (oculta). Vuelve a publicarla desde Modificar.'
      : '';
  }

  claseEstado(estado: string): string {
    if (estado === 'publicada') return 'ok';
    if (estado === 'rechazada') return 'error';
    return '';
  }
}
