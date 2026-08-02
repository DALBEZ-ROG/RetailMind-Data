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
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

import { TablerosService } from '../../../core/services/tableros.service';
import { InformesService } from '../../../core/services/informes.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { ColumnaInforme, FiltroInforme, TipoValor } from '../../../core/models/informe.model';
import {
  BloqueExterno, BloqueTablero, DefinicionTablero, EstadoExterno, PresentacionBloque,
  SobreTablero
} from '../../../core/models/tablero.model';
import { definicionTablero } from './definiciones/tableros';
import { TableroGraficoComponent } from './tablero-grafico.component';

/**
 * PANTALLA GENÉRICA de los TABLEROS DE DIRECCIÓN — nivel estratégico.
 *
 * Sirve a los tres tableros de la fase E1-A (y a los cuatro de la E1-B sin
 * tocar una línea): el tablero llega por `data.tablero` de la ruta, de ahí sale
 * su `DefinicionTablero`, y con ella se pintan los filtros, las tarjetas de
 * cabecera y los bloques. NO hay lógica de negocio aquí.
 *
 * <h2>Una llamada para el tablero, y una por bloque externo</h2>
 * El tablero entero viene en UN sobre (ver `TableroServiceBase` en el backend:
 * los bloques comparten filtros, marca de agua y decisión de degradación). Los
 * bloques que NO salen del almacén —carrito abandonado en T-1, sobre-stock en
 * T-2— se piden aparte a los informes SIMPLES de PostgreSQL que ya existen, y
 * por eso **siguen vivos cuando el almacén está apagado**: la pantalla los pide
 * igual y los pinta igual.
 *
 * <h2>Lo que esta pantalla se obliga a mostrar</h2>
 * <ul>
 *   <li>La <b>marca de agua</b> «Datos al …», que es la carga MÁS REZAGADA de
 *       las tablas que sirven el tablero.</li>
 *   <li>El <b>denominador</b> de cada bloque, siempre, en la propia tarjeta.</li>
 *   <li>La <b>salvedad</b> de cada bloque ENCIMA de sus cifras: una advertencia
 *       sobre cómo leer un número llega tarde si se lee después del número.</li>
 *   <li>Los <b>bloques omitidos</b> por el alcance del rol, con su motivo. Un
 *       tablero que se recorta en silencio se lee como un tablero completo.</li>
 * </ul>
 */
@Component({
  selector: 'app-tablero',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    TableroGraficoComponent],
  templateUrl: './tablero.component.html',
  styleUrls: ['../operativo-shared.scss', '../informes/informes.scss', './tableros.scss']
})
export class TableroComponent implements OnInit {

  def?: DefinicionTablero;
  sobre?: SobreTablero;
  loading = false;
  avisoAnalitica = '';

  /** Valores actuales de los filtros. */
  valores: Record<string, string> = {};
  private texto$ = new Subject<void>();

  /** Respuesta de cada bloque servido desde PostgreSQL, por id. */
  externos: Record<string, EstadoExterno> = {};

  /** Bloques del sobre indexados para casarlos con su presentación. */
  private porId: Record<string, BloqueTablero> = {};

  constructor(private ruta: ActivatedRoute,
              private srv: TablerosService,
              private informes: InformesService,
              private auth: AuthService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    const clave = this.ruta.snapshot.data['tablero'] as string;
    this.def = definicionTablero(clave);
    if (!this.def) {
      this.snackBar.open(`No hay ningún tablero definido para «${clave}»`, 'Cerrar',
        { duration: 5000 });
      return;
    }
    this.def.filtros.forEach(f => this.valores[f.param] = f.valorInicial ?? '');
    this.texto$.pipe(debounceTime(400), distinctUntilChanged())
      .subscribe(() => this.cargar());
    this.cargar();
  }

  // ── Consulta ─────────────────────────────────────────────────────────

  cargar(): void {
    if (!this.def) { return; }
    const def = this.def;
    this.loading = true;
    this.avisoAnalitica = '';

    this.srv.consultar(def.clave, { ...this.valores }).subscribe({
      next: sobre => {
        this.sobre = sobre;
        this.porId = {};
        (sobre.bloques ?? []).forEach(b => this.porId[b.id] = b);
        if (sobre.analiticaDisponible === false) {
          this.avisoAnalitica = sobre.avisoAnalitica
            ?? 'El almacén analítico no está disponible en este momento.';
        }
        this.loading = false;
      },
      error: e => {
        this.sobre = undefined;
        this.porId = {};
        this.loading = false;
        this.snackBar.open(
          mensajeError(e, `No se pudo consultar el tablero ${def.id}`),
          'Cerrar', { duration: 6000 });
      }
    });

    // Los bloques externos se piden EN PARALELO y con su propio ciclo: no
    // dependen del almacén y no deben esperar a que responda ni caerse con él.
    (def.externos ?? []).forEach(x => this.cargarExterno(x));
  }

  private cargarExterno(x: BloqueExterno): void {
    if (!x.roles.includes(this.rol)) {
      // Su informe simple no es de este rol. No se dispara la llamada: la API
      // la negaría igual y solo ensuciaría la consola con un 403.
      this.externos[x.id] = { filas: [], total: 0, resumen: [], cargando: false, error: '' };
      return;
    }
    this.externos[x.id] = { filas: [], total: 0, resumen: [], cargando: true, error: '' };
    this.informes.consultar(x.departamento, x.endpoint, x.filtros).subscribe({
      next: s => this.externos[x.id] = {
        filas: s.items ?? [], total: s.total ?? 0, resumen: s.resumen ?? [],
        cargando: false, error: ''
      },
      error: e => this.externos[x.id] = {
        filas: [], total: 0, resumen: [], cargando: false,
        error: mensajeError(e, 'No se pudo consultar este bloque.')
      }
    });
  }

  aplicarFiltros(): void { this.cargar(); }

  alEscribir(): void { this.texto$.next(); }

  limpiar(): void {
    this.def?.filtros.forEach(f => this.valores[f.param] = f.valorInicial ?? '');
    this.cargar();
  }

  // ── Acceso a los datos desde la plantilla ────────────────────────────

  get rol(): string {
    return this.auth.getCurrentUser()?.rol ?? '';
  }

  bloque(pres: PresentacionBloque): BloqueTablero | undefined {
    return this.porId[pres.id];
  }

  /** Presentaciones que el sobre trajo de verdad, en el orden del backend. */
  get bloquesVisibles(): PresentacionBloque[] {
    if (!this.def || !this.sobre) { return []; }
    const orden = (this.sobre.bloques ?? []).map(b => b.id);
    return this.def.bloques
      .filter(p => orden.includes(p.id))
      .sort((a, b) => orden.indexOf(a.id) - orden.indexOf(b.id));
  }

  columnasDe(pres: PresentacionBloque): string[] {
    return pres.columnas.map(c => c.campo);
  }

  /**
   * Filas que se pintan en la tabla del bloque. Recortar es decisión de
   * pantalla —387 productos hueso no caben— y el pie declara cuántas hay.
   */
  filasDe(pres: PresentacionBloque): Record<string, any>[] {
    const b = this.bloque(pres);
    if (!b) { return []; }
    return pres.topFilas && b.items.length > pres.topFilas
      ? b.items.slice(0, pres.topFilas)
      : b.items;
  }

  hayRecorte(pres: PresentacionBloque): boolean {
    const b = this.bloque(pres);
    return !!b && !!pres.topFilas && b.filas > pres.topFilas;
  }

  externo(id: string): EstadoExterno {
    return this.externos[id]
        ?? { filas: [], total: 0, resumen: [], cargando: false, error: '' };
  }

  columnasExternas(x: BloqueExterno): string[] {
    return x.columnas.map(c => c.campo);
  }

  filasExternas(x: BloqueExterno): Record<string, any>[] {
    const e = this.externo(x.id);
    return x.topFilas && e.filas.length > x.topFilas ? e.filas.slice(0, x.topFilas) : e.filas;
  }

  // ── Formato ──────────────────────────────────────────────────────────

  valor(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    if (crudo === null || crudo === undefined || crudo === '') {
      return col.etiqueta ? col.etiqueta(crudo, fila) : '—';
    }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar
      ? texto.slice(0, col.recortar) + '…'
      : texto;
  }

  valorCompleto(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    if (crudo === null || crudo === undefined || crudo === '') { return ''; }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar ? texto : '';
  }

  claseChip(col: ColumnaInforme, fila: Record<string, any>): string {
    return col.color ? col.color(fila) : 'neutral';
  }

  formatear(crudo: any, tipo: TipoValor): string {
    if (crudo === null || crudo === undefined || crudo === '') { return '—'; }
    switch (tipo) {
      case 'moneda':
        return '$' + Number(crudo).toLocaleString('es-EC',
          { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      case 'numero':
        return Number(crudo).toLocaleString('es-EC');
      case 'porcentaje':
        return Number(crudo).toLocaleString('es-EC',
          { minimumFractionDigits: 1, maximumFractionDigits: 2 }) + ' %';
      case 'dias': {
        const n = Number(crudo);
        return n === 1 ? '1 día' : `${n.toLocaleString('es-EC')} días`;
      }
      case 'fecha':
        return new Date(crudo).toLocaleDateString('es-EC',
          { day: '2-digit', month: '2-digit', year: '2-digit' });
      case 'estrellas':
        return '★'.repeat(Number(crudo)) + '☆'.repeat(Math.max(5 - Number(crudo), 0));
      case 'booleano':
        return crudo === true ? 'Sí' : 'No';
      default:
        return String(crudo);
    }
  }

  /** Para el `*ngFor` de los filtros con tipado estricto de plantilla. */
  esFiltro(f: FiltroInforme): FiltroInforme { return f; }
}
