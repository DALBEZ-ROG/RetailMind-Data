import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTableModule } from '@angular/material/table';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatCardModule } from '@angular/material/card';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDividerModule } from '@angular/material/divider';
import { MatChipsModule } from '@angular/material/chips';
import { GestionDatosService } from './gestion-datos.service';

@Component({
  selector: 'app-gestion-datos',
  standalone: true,
  imports: [
    CommonModule, FormsModule, MatTabsModule, MatTableModule,
    MatButtonModule, MatIconModule, MatPaginatorModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatCardModule, MatSnackBarModule,
    MatTooltipModule, MatDividerModule, MatChipsModule
  ],
  templateUrl: './gestion-datos.component.html',
  styleUrl: './gestion-datos.component.scss'
})
export class GestionDatosComponent implements OnInit {

  // ── Fact Eventos ───────────────────────────────────────────────────────────
  factEventos: any[] = [];
  factTotal = 0;
  factPage = 0;
  factSize = 20;
  factSemana: number | null = null;
  factLoading = false;
  factTime = 0;

  // ── Edición de evento ──────────────────────────────────────────────────────
  editingEvento: any = null;
  editForm: any = {};

  // ── Dimensiones ────────────────────────────────────────────────────────────
  canales: any[] = [];
  regiones: any[] = [];
  dispositivos: any[] = [];
  categorias: any[] = [];
  fuentes: any[] = [];

  canalesTime = 0;
  regionesTime = 0;
  dispositivosTime = 0;
  categoriasTime = 0;
  fuentesTime = 0;

  // ── Productos ──────────────────────────────────────────────────────────────
  productos: any[] = [];
  prodTotal = 0;
  prodPage = 0;
  prodSize = 20;
  prodTime = 0;

  // ── Usuarios ───────────────────────────────────────────────────────────────
  usuarios: any[] = [];
  userTotal = 0;
  userPage = 0;
  userSize = 20;
  userTime = 0;

  // ── Nuevo item dimensión ───────────────────────────────────────────────────
  newDimId = 0;
  newDimNombre = '';

  constructor(
    private service: GestionDatosService,
    private snackBar: MatSnackBar
  ) {}

  ngOnInit(): void {
    this.loadFactEventos();
  }

  // ══════════════════════════════════════════════════════════════════════════
  // FACT EVENTOS
  // ══════════════════════════════════════════════════════════════════════════

  loadFactEventos(): void {
    this.factLoading = true;
    const start = performance.now();
    this.service.getFactEventos(this.factPage, this.factSize, this.factSemana || undefined).subscribe({
      next: (res) => {
        this.factTime = Math.round(performance.now() - start);
        this.factEventos = res.content;
        this.factTotal = res.totalElements;
        this.factLoading = false;
      },
      error: () => { this.factLoading = false; this.factEventos = []; this.factTime = 0; }
    });
  }

  onFactPageChange(e: PageEvent): void {
    this.factPage = e.pageIndex;
    this.factSize = e.pageSize;
    this.loadFactEventos();
  }

  filtrarPorSemana(): void {
    this.factPage = 0;
    this.loadFactEventos();
  }

  editEvento(row: any): void {
    this.editingEvento = row;
    this.editForm = {
      user_action: row.userAction,
      channel: row.channel,
      price: row.price,
      time_spent_sec: row.timeSpentSec,
      session_length: row.sessionLength,
      interaction_count: row.interactionCount,
      is_conversion: row.isConversion,
      drop_off_flag: row.dropOffFlag,
      semana: row.semana
    };
  }

  saveEvento(): void {
    if (!this.editingEvento) return;
    this.service.updateFactEvento(this.editingEvento.eventPk, this.editForm).subscribe({
      next: () => { this.msgOk('Evento actualizado correctamente'); this.editingEvento = null; this.loadFactEventos(); },
      error: (e) => this.msgErr(e)
    });
  }

  cancelEdit(): void { this.editingEvento = null; }

  deleteEvento(eventPk: number): void {
    if (!confirm('¿Eliminar evento #' + eventPk + '? Esta accion no se puede deshacer.')) return;
    this.service.deleteFactEvento(eventPk).subscribe({
      next: () => { this.msgOk('Evento eliminado'); this.loadFactEventos(); },
      error: (e) => this.msgErr(e)
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // DIMENSIONES
  // ══════════════════════════════════════════════════════════════════════════

  loadDimension(tabla: string): void {
    const start = performance.now();
    this.service.getDimension(tabla).subscribe({
      next: (data) => {
        const elapsed = Math.round(performance.now() - start);
        switch (tabla) {
          case 'dim-canal': this.canales = data; this.canalesTime = elapsed; break;
          case 'dim-region': this.regiones = data; this.regionesTime = elapsed; break;
          case 'dim-dispositivo': this.dispositivos = data; this.dispositivosTime = elapsed; break;
          case 'dim-categoria': this.categorias = data; this.categoriasTime = elapsed; break;
          case 'dim-fuente-trafico': this.fuentes = data; this.fuentesTime = elapsed; break;
        }
      },
      error: () => {}
    });
  }

  addDimension(tabla: string): void {
    if (!this.newDimNombre.trim() || this.newDimId <= 0) {
      this.msgErr({ error: { error: 'ID y Nombre son requeridos' } });
      return;
    }
    this.service.createDimension(tabla, { id: this.newDimId, nombre: this.newDimNombre }).subscribe({
      next: () => { this.msgOk('Registro creado'); this.newDimId = 0; this.newDimNombre = ''; this.loadDimension(tabla); },
      error: (e) => this.msgErr(e)
    });
  }

  deleteDim(tabla: string, id: number): void {
    if (!confirm('¿Eliminar registro #' + id + '?')) return;
    this.service.deleteDimension(tabla, id).subscribe({
      next: () => { this.msgOk('Registro eliminado'); this.loadDimension(tabla); },
      error: (e) => this.msgErr(e)
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // PRODUCTOS
  // ══════════════════════════════════════════════════════════════════════════

  loadProductos(): void {
    const start = performance.now();
    this.service.getProductos(this.prodPage, this.prodSize).subscribe({
      next: (res) => { this.productos = res.content; this.prodTotal = res.totalElements; this.prodTime = Math.round(performance.now() - start); },
      error: () => { this.productos = []; }
    });
  }

  onProdPageChange(e: PageEvent): void {
    this.prodPage = e.pageIndex;
    this.prodSize = e.pageSize;
    this.loadProductos();
  }

  deleteProducto(id: string): void {
    if (!confirm('¿Eliminar producto ' + id + '?')) return;
    this.service.deleteProducto(id).subscribe({
      next: () => { this.msgOk('Producto eliminado'); this.loadProductos(); },
      error: (e) => this.msgErr(e)
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // USUARIOS
  // ══════════════════════════════════════════════════════════════════════════

  loadUsuarios(): void {
    const start = performance.now();
    this.service.getUsuarios(this.userPage, this.userSize).subscribe({
      next: (res) => { this.usuarios = res.content; this.userTotal = res.totalElements; this.userTime = Math.round(performance.now() - start); },
      error: () => { this.usuarios = []; }
    });
  }

  onUserPageChange(e: PageEvent): void {
    this.userPage = e.pageIndex;
    this.userSize = e.pageSize;
    this.loadUsuarios();
  }

  deleteUsuario(id: string): void {
    if (!confirm('¿Eliminar usuario ' + id + '?')) return;
    this.service.deleteUsuario(id).subscribe({
      next: () => { this.msgOk('Usuario eliminado'); this.loadUsuarios(); },
      error: (e) => this.msgErr(e)
    });
  }

  // ══════════════════════════════════════════════════════════════════════════
  // TAB CHANGE
  // ══════════════════════════════════════════════════════════════════════════

  onTabChange(index: number): void {
    switch (index) {
      case 0: this.loadFactEventos(); break;
      case 1: this.loadDimension('dim-canal'); break;
      case 2: this.loadDimension('dim-region'); break;
      case 3: this.loadDimension('dim-dispositivo'); break;
      case 4: this.loadDimension('dim-categoria'); break;
      case 5: this.loadDimension('dim-fuente-trafico'); break;
      case 6: this.loadProductos(); break;
      case 7: this.loadUsuarios(); break;
    }
  }

  // ── Helpers ────────────────────────────────────────────────────────────────
  getTimeClass(ms: number): string {
    if (ms === 0) return 'time-neutral';
    if (ms < 200) return 'time-fast';
    if (ms <= 500) return 'time-medium';
    return 'time-slow';
  }

  private msgOk(text: string): void {
    this.snackBar.open('✓ ' + text, 'OK', { duration: 2500, panelClass: ['snack-success'] });
  }

  private msgErr(e: any): void {
    const msg = e?.error?.error || e?.message || 'Error desconocido';
    this.snackBar.open('✗ ' + msg, 'Cerrar', { duration: 4000, panelClass: ['snack-error'] });
  }
}
