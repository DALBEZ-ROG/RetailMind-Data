import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ResenasService } from '../../../core/services/resenas.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { PreguntaProductoRow, ProductoResenaRef } from '../../../core/models/operativo.model';

/** Espeja las transiciones del backend (ResenasService.TRANSICIONES_PREGUNTA). */
const TRANSICIONES: Record<string, string[]> = {
  pendiente: ['publicada', 'rechazada'],
  publicada: ['rechazada'],
  rechazada: ['publicada']
};

@Component({
  selector: 'app-preguntas-producto',
  standalone: true,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './preguntas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class PreguntasComponent implements OnInit {

  // Personal: bandeja completa; cliente: preguntas por producto
  preguntas: PreguntaProductoRow[] = [];
  filtroEstado = '';
  productoSel: number | null = null;

  showForm = false;
  form = { productoId: null as number | null, pregunta: '' };
  respondiendo: PreguntaProductoRow | null = null;
  nuevaRespuesta = '';

  productosRef: ProductoResenaRef[] = [];
  loading = true;
  estados = ['pendiente', 'publicada', 'rechazada'];

  constructor(private servicio: ResenasService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }

  ngOnInit(): void {
    this.servicio.productosRef().subscribe({ next: p => this.productosRef = p, error: () => {} });
    this.cargar();
  }

  cargar(): void {
    this.loading = true;
    if (this.esCliente) {
      if (!this.productoSel) { this.preguntas = []; this.loading = false; return; }
      this.servicio.preguntasProducto(this.productoSel).subscribe({
        next: data => { this.preguntas = data; this.loading = false; },
        error: () => this.loading = false
      });
    } else {
      this.servicio.preguntas(this.filtroEstado || undefined).subscribe({
        next: data => { this.preguntas = data; this.loading = false; },
        error: () => this.loading = false
      });
    }
  }

  transiciones(estado: string): string[] { return TRANSICIONES[estado] || []; }

  guardar(): void {
    if (!this.form.productoId || !this.form.pregunta.trim()) {
      this.snackBar.open('Selecciona el producto y escribe la pregunta', 'Cerrar', { duration: 3000 });
      return;
    }
    this.servicio.crearPregunta(this.form).subscribe({
      next: () => {
        this.snackBar.open('Pregunta enviada: queda pendiente de moderación', 'OK',
          { duration: 3000, panelClass: ['snack-success'] });
        this.showForm = false;
        this.productoSel = this.form.productoId;
        this.form = { productoId: null, pregunta: '' };
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo enviar la pregunta'),
        'Cerrar', { duration: 4000 })
    });
  }

  responder(): void {
    if (!this.respondiendo || !this.nuevaRespuesta.trim()) return;
    this.servicio.responderPregunta(this.respondiendo.id, this.nuevaRespuesta).subscribe({
      next: () => {
        this.snackBar.open('Respuesta publicada', 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.respondiendo = null;
        this.nuevaRespuesta = '';
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo responder la pregunta'),
        'Cerrar', { duration: 4000 })
    });
  }

  moderar(q: PreguntaProductoRow, estado: string): void {
    this.servicio.moderarPregunta(q.id, estado).subscribe({
      next: () => {
        this.snackBar.open(`Pregunta ${estado}`, 'OK', { duration: 2000 });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo moderar la pregunta'),
        'Cerrar', { duration: 4000 })
    });
  }

  claseEstado(estado: string): string {
    if (estado === 'publicada') return 'ok';
    if (estado === 'rechazada') return 'error';
    return '';
  }
}
