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
import { ResenasService } from '../../../core/services/resenas.service';
import { AuthService } from '../../../core/services/auth.service';
import { SelectBuscableComponent, OpcionBuscable } from '../../../core/components/select-buscable/select-buscable.component';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ResenaRow, ResenaPublica, ReporteResenaRow, ProductoResenaRef
} from '../../../core/models/operativo.model';

/** Espeja las transiciones del backend (ResenasService.TRANSICIONES_RESENA). */
const TRANSICIONES: Record<string, string[]> = {
  pendiente: ['aprobada', 'rechazada'],
  aprobada: ['rechazada'],
  rechazada: ['aprobada']
};

@Component({
  selector: 'app-resenas',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    SelectBuscableComponent],
  templateUrl: './resenas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class ResenasComponent implements OnInit {

  // Moderación (personal)
  resenas: ResenaRow[] = [];
  filtroEstado = '';
  reportes: ReporteResenaRow[] = [];
  filtroReporte = 'pendiente';

  // Cliente
  misResenas: ResenaRow[] = [];
  publicas: ResenaPublica[] = [];
  productoSel: number | null = null;
  showForm = false;
  form = this.formVacio();
  reportando: ResenaPublica | null = null;
  reporteForm = { motivo: 'ofensivo', comentario: '' };

  productosRef: ProductoResenaRef[] = [];
  productosOpc: OpcionBuscable[] = [];
  loading = true;

  estados = ['pendiente', 'aprobada', 'rechazada'];
  estadosReporte = ['pendiente', 'atendido', 'descartado'];
  motivos = ['ofensivo', 'spam', 'falso', 'otro'];
  calificaciones = [1, 2, 3, 4, 5];

  columnas = ['producto', 'cliente', 'calificacion', 'titulo', 'estado',
    'votos', 'reportes', 'fecha', 'acciones'];
  columnasMias = ['producto', 'calificacion', 'titulo', 'estado', 'utiles', 'fecha'];

  constructor(private servicio: ResenasService, private auth: AuthService,
              private snackBar: MatSnackBar) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }

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

  private formVacio() {
    return { productoId: null as number | null, calificacion: 5, titulo: '', comentario: '' };
  }

  cargar(): void {
    this.loading = true;
    if (this.esCliente) {
      this.servicio.misResenas().subscribe({
        next: data => { this.misResenas = data; this.loading = false; },
        error: () => this.loading = false
      });
      if (this.productoSel) this.verProducto();
    } else {
      this.servicio.resenas(this.filtroEstado || undefined).subscribe({
        next: data => { this.resenas = data; this.loading = false; },
        error: () => this.loading = false
      });
      this.cargarReportes();
    }
  }

  cargarReportes(): void {
    this.servicio.reportes(this.filtroReporte || undefined).subscribe({
      next: data => this.reportes = data, error: () => {}
    });
  }

  transiciones(estado: string): string[] { return TRANSICIONES[estado] || []; }

  moderar(r: ResenaRow, estado: string): void {
    this.servicio.moderarResena(r.id, estado).subscribe({
      next: () => {
        this.snackBar.open(`Reseña ${estado}`, 'OK', { duration: 2000, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo moderar la reseña'),
        'Cerrar', { duration: 4000 })
    });
  }

  resolverReporte(rep: ReporteResenaRow, estado: string): void {
    this.servicio.resolverReporte(rep.id, estado).subscribe({
      next: () => {
        this.snackBar.open(`Reporte ${estado}`, 'OK', { duration: 2000 });
        this.cargarReportes();
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo resolver el reporte'),
        'Cerrar', { duration: 4000 })
    });
  }

  // ── Cliente ────────────────────────────────────────────────────────────

  guardar(): void {
    if (!this.form.productoId) {
      this.snackBar.open('Selecciona el producto a reseñar', 'Cerrar', { duration: 3000 });
      return;
    }
    this.servicio.crearResena(this.form).subscribe({
      next: () => {
        this.snackBar.open('Reseña enviada: queda pendiente de aprobación', 'OK',
          { duration: 3000, panelClass: ['snack-success'] });
        this.showForm = false;
        this.form = this.formVacio();
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo crear la reseña'),
        'Cerrar', { duration: 4000 })
    });
  }

  verProducto(): void {
    if (!this.productoSel) { this.publicas = []; return; }
    this.servicio.resenasProducto(this.productoSel).subscribe({
      next: data => this.publicas = data,
      error: e => this.snackBar.open(mensajeError(e, 'No se pudieron cargar las reseñas'),
        'Cerrar', { duration: 4000 })
    });
  }

  votar(r: ResenaPublica, esUtil: boolean): void {
    this.servicio.votar(r.id, esUtil).subscribe({
      next: c => {
        r.utiles = c.utiles;
        r.no_utiles = c.no_utiles;
        r.mi_voto = esUtil;
        this.snackBar.open('Voto registrado', 'OK', { duration: 2000, panelClass: ['snack-success'] });
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo registrar el voto'),
        'Cerrar', { duration: 4000 })
    });
  }

  reportar(): void {
    if (!this.reportando) return;
    this.servicio.reportar(this.reportando.id, this.reporteForm.motivo,
      this.reporteForm.comentario).subscribe({
      next: () => {
        this.snackBar.open('Reseña reportada: el equipo la revisará', 'OK',
          { duration: 3000, panelClass: ['snack-success'] });
        this.reportando = null;
        this.reporteForm = { motivo: 'ofensivo', comentario: '' };
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo reportar la reseña'),
        'Cerrar', { duration: 4000 })
    });
  }

  estrellas(n: number): string { return '★'.repeat(n) + '☆'.repeat(5 - n); }

  claseEstado(estado: string): string {
    if (estado === 'aprobada' || estado === 'atendido') return 'ok';
    if (estado === 'rechazada' || estado === 'descartado') return 'error';
    return '';
  }
}
