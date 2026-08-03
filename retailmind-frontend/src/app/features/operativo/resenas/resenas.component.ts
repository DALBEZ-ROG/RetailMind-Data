import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
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
import {
  ResenaRow, ResenaPublica, ReporteResenaRow, ProductoResenaRef
} from '../../../core/models/operativo.model';
import {
  ResenaDialogComponent, ResenaDialogData, ResenaDialogResultado
} from './resena-dialog.component';
import {
  ReporteDialogComponent, ReporteDialogData, ReporteDialogResultado
} from './reporte-dialog.component';

/** Espeja las transiciones del backend (ResenasService.TRANSICIONES_RESENA). */
const TRANSICIONES: Record<string, string[]> = {
  pendiente: ['aprobada', 'rechazada'],
  aprobada: ['rechazada'],
  rechazada: ['aprobada']
};

/**
 * Transiciones del reporte. «atendido» y «descartado» son AMBOS TERMINALES:
 * el backend no admite ninguna salida desde ellos.
 */
const TRANSICIONES_REPORTE: Record<string, string[]> = {
  pendiente: ['atendido', 'descartado'],
  atendido: [],
  descartado: []
};

/**
 * Reseñas de productos, alineada al patrón (docs/PATRON_UI.md).
 *
 * Dos públicos y, por tanto, dos juegos de grillas:
 * - **Moderación** (personal): bandeja de reseñas + bandeja de reportes de
 *   abuso, cada una con sus criterios y su barra de acciones.
 * - **Cliente**: sus reseñas y las opiniones públicas de un producto.
 *
 * Limitaciones DECLARADAS (el backend no las expone; no se fuerza la regla):
 * - El cliente no puede modificar ni eliminar una reseña ya enviada — `resena`
 *   le concede grants POR COLUMNA y sin UPDATE (script 43). Su barra oculta
 *   esas dos opciones.
 * - El moderador no puede crear una reseña: nace del cliente con compra
 *   verificada. Su barra oculta «Nuevo».
 */
@Component({
  selector: 'app-resenas',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    MatDialogModule, SelectBuscableComponent, AccionesRegistroComponent],
  templateUrl: './resenas.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class ResenasComponent implements OnInit {

  // ── Moderación (personal) ──────────────────────────────────────────────
  private todasResenas: ResenaRow[] = [];
  resenas: ResenaRow[] = [];
  filtroEstado = 'todos';
  filtroTexto = '';
  filtroCalificacion: number | 'todos' = 'todos';
  filtroReportadas = 'todos';           // 'todos' | 'con' | 'sin'
  resenaSeleccionada: ResenaRow | null = null;

  private todosReportes: ReporteResenaRow[] = [];
  reportes: ReporteResenaRow[] = [];
  filtroReporte = 'pendiente';
  filtroMotivo = 'todos';
  reporteSeleccionado: ReporteResenaRow | null = null;

  // ── Cliente ────────────────────────────────────────────────────────────
  private todasMias: ResenaRow[] = [];
  misResenas: ResenaRow[] = [];
  filtroMiEstado = 'todos';
  filtroMiTexto = '';
  miResenaSeleccionada: ResenaRow | null = null;

  publicas: ResenaPublica[] = [];
  publicasFiltradas: ResenaPublica[] = [];
  productoSel: number | null = null;
  filtroPublicaCalificacion: number | 'todos' = 'todos';
  publicaSeleccionada: ResenaPublica | null = null;

  productosRef: ProductoResenaRef[] = [];
  productosOpc: OpcionBuscable[] = [];
  /** Solo productos COMPRADOS (compra verificada): opciones del formulario. */
  compradosOpc: OpcionBuscable[] = [];
  loading = true;

  estados = ['pendiente', 'aprobada', 'rechazada'];
  estadosReporte = ['pendiente', 'atendido', 'descartado'];
  motivos = ['ofensivo', 'spam', 'falso', 'otro'];
  calificaciones = [1, 2, 3, 4, 5];

  columnas = ['producto', 'cliente', 'calificacion', 'titulo', 'estado',
    'votos', 'reportes', 'fecha'];
  columnasMias = ['producto', 'calificacion', 'titulo', 'estado', 'utiles', 'fecha'];
  columnasReportes = ['motivo', 'resena', 'reportadoPor', 'estado', 'fecha'];
  columnasPublicas = ['cliente', 'calificacion', 'opinion', 'votos', 'fecha'];

  constructor(private servicio: ResenasService, private auth: AuthService,
              private route: ActivatedRoute, private snackBar: MatSnackBar,
              private dialog: MatDialog, private confirmar: ConfirmService) {}

  get esCliente(): boolean { return this.auth.hasRole('CLIENTE'); }

  ngOnInit(): void {
    this.servicio.productosRef().subscribe({
      next: p => {
        this.productosRef = p;
        this.productosOpc = p.map(x => ({ id: x.id, texto: x.nombre }));
      },
      error: () => {}
    });
    if (this.esCliente) {
      // El formulario solo ofrece productos COMPRADOS (el backend lo enforza)
      this.servicio.productosComprados().subscribe({
        next: p => this.compradosOpc = p.map(x => ({ id: x.id, texto: x.nombre })),
        error: () => {}
      });
      // Llegada desde Mis Pedidos: ?productoId=N abre el alta ya preseleccionada
      const productoId = Number(this.route.snapshot.queryParamMap.get('productoId'));
      if (productoId) { setTimeout(() => this.nuevaResena(productoId)); }
    }
    this.cargar();
  }

  // ── Regla 1: las grillas y sus criterios ─────────────────────────────

  cargar(): void {
    this.loading = true;
    if (this.esCliente) {
      this.servicio.misResenas().subscribe({
        next: data => { this.todasMias = data; this.aplicarFiltrosMias(); this.loading = false; },
        error: () => this.loading = false
      });
      if (this.productoSel) this.verProducto();
    } else {
      this.servicio.resenas().subscribe({
        next: data => { this.todasResenas = data; this.aplicarFiltros(); this.loading = false; },
        error: () => this.loading = false
      });
      this.cargarReportes();
    }
  }

  cargarReportes(): void {
    this.servicio.reportes().subscribe({
      next: data => { this.todosReportes = data; this.aplicarFiltrosReportes(); },
      error: () => {}
    });
  }

  aplicarFiltros(): void {
    const q = this.filtroTexto.trim().toLowerCase();
    this.resenas = this.todasResenas.filter(r => {
      if (this.filtroEstado !== 'todos' && r.estado !== this.filtroEstado) return false;
      if (this.filtroCalificacion !== 'todos'
          && r.calificacion !== this.filtroCalificacion) return false;
      const conReportes = (r.reportes_pendientes ?? 0) > 0;
      if (this.filtroReportadas === 'con' && !conReportes) return false;
      if (this.filtroReportadas === 'sin' && conReportes) return false;
      if (!q) return true;
      return (r.producto ?? '').toLowerCase().includes(q)
          || (r.cliente ?? '').toLowerCase().includes(q)
          || (r.titulo ?? '').toLowerCase().includes(q)
          || (r.comentario ?? '').toLowerCase().includes(q);
    });
    this.resenaSeleccionada = this.resenas
      .find(r => r.id === this.resenaSeleccionada?.id) ?? null;
  }

  limpiarFiltros(): void {
    this.filtroTexto = '';
    this.filtroEstado = 'todos';
    this.filtroCalificacion = 'todos';
    this.filtroReportadas = 'todos';
    this.aplicarFiltros();
  }

  aplicarFiltrosReportes(): void {
    this.reportes = this.todosReportes.filter(r => {
      if (this.filtroReporte !== 'todos' && r.estado !== this.filtroReporte) return false;
      if (this.filtroMotivo !== 'todos' && r.motivo !== this.filtroMotivo) return false;
      return true;
    });
    this.reporteSeleccionado = this.reportes
      .find(r => r.id === this.reporteSeleccionado?.id) ?? null;
  }

  limpiarFiltrosReportes(): void {
    this.filtroReporte = 'todos';
    this.filtroMotivo = 'todos';
    this.aplicarFiltrosReportes();
  }

  aplicarFiltrosMias(): void {
    const q = this.filtroMiTexto.trim().toLowerCase();
    this.misResenas = this.todasMias.filter(r => {
      if (this.filtroMiEstado !== 'todos' && r.estado !== this.filtroMiEstado) return false;
      if (!q) return true;
      return (r.producto ?? '').toLowerCase().includes(q)
          || (r.titulo ?? '').toLowerCase().includes(q);
    });
    this.miResenaSeleccionada = this.misResenas
      .find(r => r.id === this.miResenaSeleccionada?.id) ?? null;
  }

  limpiarFiltrosMias(): void {
    this.filtroMiTexto = '';
    this.filtroMiEstado = 'todos';
    this.aplicarFiltrosMias();
  }

  aplicarFiltrosPublicas(): void {
    this.publicasFiltradas = this.publicas.filter(r =>
      this.filtroPublicaCalificacion === 'todos'
        || r.calificacion === this.filtroPublicaCalificacion);
    this.publicaSeleccionada = this.publicasFiltradas
      .find(r => r.id === this.publicaSeleccionada?.id) ?? null;
  }

  transiciones(estado: string): string[] { return TRANSICIONES[estado] || []; }

  // ── Regla 2: las cuatro opciones sobre la bandeja de moderación ──────

  seleccionarResena(r: ResenaRow): void { this.resenaSeleccionada = r; }

  modificarResena(): void { this.abrirResena('actualizar'); }
  verResena(): void { this.abrirResena('consulta'); }

  private abrirResena(modo: 'actualizar' | 'consulta'): void {
    const r = this.resenaSeleccionada;
    if (!r) return;
    const data: ResenaDialogData = {
      resena: r, modo, esModerador: true, comprados: [],
      transiciones: this.transiciones(r.estado)
    };
    this.dialog.open(ResenaDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false })
      .afterClosed().subscribe((res: ResenaDialogResultado | undefined) => {
        if (!res || res.estado === r.estado) return;
        this.moderar(r, res.estado);
      });
  }

  private moderar(r: ResenaRow, estado: string): void {
    this.servicio.moderarResena(r.id, estado).subscribe({
      next: () => {
        this.snackBar.open(`Reseña ${estado}`, 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo moderar la reseña'),
        'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Regla 5. «Eliminar» una reseña = RECHAZARLA: deja de verse en la ficha del
   * producto. Es reversible desde Modificar, y el mensaje lo dice.
   */
  eliminarResena(): void {
    const r = this.resenaSeleccionada;
    if (!r || r.estado === 'rechazada') return;
    this.confirmar.eliminacion(
      `la reseña «${r.titulo || '(sin título)'}» de ${r.producto}`,
      'Pasará a estado «rechazada» y dejará de mostrarse en la ficha del producto y ' +
      'en la media de calificación. No se borra nada: el texto del cliente y sus votos ' +
      'de utilidad se conservan, y puedes volver a publicarla eligiendo «aprobada» ' +
      'desde Modificar.'
    ).subscribe(ok => { if (ok) this.moderar(r, 'rechazada'); });
  }

  get motivoResenaNoEliminable(): string {
    return this.resenaSeleccionada?.estado === 'rechazada'
      ? 'La reseña ya está rechazada (oculta). Vuelve a publicarla desde Modificar.'
      : '';
  }

  // ── Regla 2: las cuatro opciones sobre la bandeja de reportes ────────

  seleccionarReporte(r: ReporteResenaRow): void { this.reporteSeleccionado = r; }

  modificarReporte(): void { this.abrirReporte('actualizar'); }
  verReporte(): void { this.abrirReporte('consulta'); }

  private abrirReporte(modo: 'actualizar' | 'consulta'): void {
    const rep = this.reporteSeleccionado;
    if (!rep) return;
    const data: ReporteDialogData = {
      reporte: rep, modo, motivos: this.motivos,
      transiciones: TRANSICIONES_REPORTE[rep.estado] ?? []
    };
    this.dialog.open(ReporteDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false })
      .afterClosed().subscribe((res: ReporteDialogResultado | undefined) => {
        if (!res || res.estado === rep.estado) return;
        this.resolverReporte(rep, res.estado);
      });
  }

  private resolverReporte(rep: ReporteResenaRow, estado: string): void {
    this.servicio.resolverReporte(rep.id, estado).subscribe({
      next: () => {
        this.snackBar.open(`Reporte ${estado}`, 'OK',
          { duration: 2500, panelClass: ['snack-success'] });
        this.cargarReportes();
        this.cargar();
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo resolver el reporte'),
        'Cerrar', { duration: 4000 })
    });
  }

  /**
   * Regla 5. «Eliminar» un reporte = DESCARTARLO. Es un estado TERMINAL: el
   * backend no admite ninguna transición desde él, así que el mensaje NO
   * promete restauración.
   */
  eliminarReporte(): void {
    const rep = this.reporteSeleccionado;
    if (!rep || rep.estado !== 'pendiente') return;
    this.confirmar.eliminacion(
      `el reporte por «${rep.motivo}» sobre la reseña de ${rep.producto}`,
      'Se marcará como «descartado»: la denuncia se da por infundada y la reseña ' +
      'queda como está. ESTA ACCIÓN NO SE PUEDE DESHACER — «descartado» es un estado ' +
      'terminal y el reporte no se puede reabrir. Si la denuncia sí procede, usa ' +
      'Modificar y elige «atendido».'
    ).subscribe(ok => { if (ok) this.resolverReporte(rep, 'descartado'); });
  }

  get motivoReporteNoEliminable(): string {
    const rep = this.reporteSeleccionado;
    if (!rep || rep.estado === 'pendiente') return '';
    return `El reporte ya está «${rep.estado}», que es un estado terminal.`;
  }

  // ── Cliente: sus reseñas ───────────────────────────────────────────────

  seleccionarMia(r: ResenaRow): void { this.miResenaSeleccionada = r; }

  nuevaResena(productoId?: number): void {
    const data: ResenaDialogData = {
      modo: 'nuevo', esModerador: false, comprados: this.compradosOpc, transiciones: []
    };
    const ref = this.dialog.open(ResenaDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false });
    if (productoId) ref.componentInstance.form.productoId = productoId;
    ref.afterClosed().subscribe((res: ResenaDialogResultado | undefined) => {
      if (!res) return;
      this.servicio.crearResena({
        productoId: res.productoId, calificacion: res.calificacion,
        titulo: res.titulo, comentario: res.comentario
      }).subscribe({
        next: () => {
          this.snackBar.open('Reseña enviada: queda pendiente de aprobación', 'OK',
            { duration: 3000, panelClass: ['snack-success'] });
          this.cargar();
        },
        error: e => this.snackBar.open(mensajeError(e, 'No se pudo crear la reseña'),
          'Cerrar', { duration: 4000 })
      });
    });
  }

  verMiResena(): void {
    const r = this.miResenaSeleccionada;
    if (!r) return;
    const data: ResenaDialogData = {
      resena: r, modo: 'consulta', esModerador: false, comprados: [], transiciones: []
    };
    this.dialog.open(ResenaDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false });
  }

  // ── Cliente: opiniones públicas de un producto ────────────────────────

  verProducto(): void {
    if (!this.productoSel) { this.publicas = []; this.publicasFiltradas = []; return; }
    this.servicio.resenasProducto(this.productoSel).subscribe({
      next: data => { this.publicas = data; this.aplicarFiltrosPublicas(); },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudieron cargar las reseñas'),
        'Cerrar', { duration: 4000 })
    });
  }

  seleccionarPublica(r: ResenaPublica): void { this.publicaSeleccionada = r; }

  verPublica(): void {
    const r = this.publicaSeleccionada;
    if (!r) return;
    const data: ResenaDialogData = {
      resena: {
        id: r.id, producto_id: this.productoSel ?? 0,
        producto: this.nombreProducto(this.productoSel), calificacion: r.calificacion,
        titulo: r.titulo, comentario: r.comentario,
        compra_verificada: r.compra_verificada, estado: 'aprobada',
        fecha_creacion: r.fecha_creacion, utiles: r.utiles, cliente: r.cliente
      },
      modo: 'consulta', esModerador: true, comprados: [], transiciones: []
    };
    this.dialog.open(ResenaDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false });
  }

  /**
   * Voto de utilidad. NO es una operación de mantenimiento sobre un registro
   * propio —es la señal de la tienda sobre la opinión de otro cliente—, por
   * eso vive junto a la grilla y no en el diálogo de las cuatro opciones.
   */
  votar(esUtil: boolean): void {
    const r = this.publicaSeleccionada;
    if (!r || r.es_mia || r.mi_voto !== null) return;
    this.servicio.votar(r.id, esUtil).subscribe({
      next: c => {
        r.utiles = c.utiles;
        r.no_utiles = c.no_utiles;
        r.mi_voto = esUtil;
        this.snackBar.open('Voto registrado', 'OK',
          { duration: 2000, panelClass: ['snack-success'] });
      },
      error: e => this.snackBar.open(mensajeError(e, 'No se pudo registrar el voto'),
        'Cerrar', { duration: 4000 })
    });
  }

  reportar(): void {
    const r = this.publicaSeleccionada;
    if (!r || r.es_mia) return;
    const data: ReporteDialogData = {
      modo: 'nuevo', motivos: this.motivos, transiciones: [],
      resenaResumen: `${this.nombreProducto(this.productoSel)} · ${this.estrellas(r.calificacion)}`
                   + ` — ${r.titulo || '(sin título)'}`
                   + (r.comentario ? `\n${r.comentario}` : '')
    };
    this.dialog.open(ReporteDialogComponent, { data, panelClass: 'dubai-dialog', autoFocus: false })
      .afterClosed().subscribe((res: ReporteDialogResultado | undefined) => {
        if (!res) return;
        this.servicio.reportar(r.id, res.motivo, res.comentario).subscribe({
          next: () => this.snackBar.open('Reseña reportada: el equipo la revisará', 'OK',
            { duration: 3000, panelClass: ['snack-success'] }),
          error: e => this.snackBar.open(mensajeError(e, 'No se pudo reportar la reseña'),
            'Cerrar', { duration: 4000 })
        });
      });
  }

  get puedeVotar(): boolean {
    const r = this.publicaSeleccionada;
    return !!r && !r.es_mia && r.mi_voto === null;
  }
  get puedeReportar(): boolean {
    return !!this.publicaSeleccionada && !this.publicaSeleccionada.es_mia;
  }

  nombreProducto(id: number | null): string {
    return this.productosRef.find(p => p.id === id)?.nombre ?? '—';
  }

  estrellas(n: number): string { return '★'.repeat(n) + '☆'.repeat(5 - n); }

  claseEstado(estado: string): string {
    if (estado === 'aprobada' || estado === 'atendido') return 'ok';
    if (estado === 'rechazada' || estado === 'descartado') return 'error';
    return '';
  }
}
