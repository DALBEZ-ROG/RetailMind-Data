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
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

import { TablerosService } from '../../../core/services/tableros.service';
import { InformesService } from '../../../core/services/informes.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { etiquetaCodigo } from '../../../core/pipes/etiquetas.pipe';
import { ColumnaInforme, FiltroInforme, TipoValor } from '../../../core/models/informe.model';
import {
  BloqueExterno, BloqueTablero, DefinicionTablero, EstadoExterno, KpiTablero,
  PresentacionBloque, SobreTablero
} from '../../../core/models/tablero.model';
import { definicionTablero } from './definiciones/tableros';
import { TableroGraficoComponent } from './tablero-grafico.component';

import { CampoTextoDirective } from '../../../core/validacion';

/**
 * Una TARJETA del selector: un elemento del tablero que se puede elegir.
 *
 * Reúne en un solo tipo los DOS orígenes que el tablero mezcla —el bloque que
 * llega en el sobre del almacén y el que se resuelve con una segunda llamada a
 * un informe SIMPLE de PostgreSQL— para que el selector sea UNO. El origen no
 * se disimula: viaja en `origen` y se pinta con las tres señales del §9.bis.25
 * del patrón (color, forma de la marca y palabra escrita).
 */
export interface TarjetaBloque {
  id: string;
  titulo: string;
  /** 'almacen' = viene en el sobre (ClickHouse) · 'postgres' = segunda llamada. */
  origen: 'almacen' | 'postgres';
  /** Icono del tipo de dibujo, para reconocer la tarjeta sin leerla. */
  icono: string;
  /** Nombre del tipo de dibujo; ocupa el hueco del `chip-id` del molde. */
  clase: string;
  /** Filas que trae el bloque del almacén (los externos las saben al llegar). */
  filas?: number;
  pres?: PresentacionBloque;
  externo?: BloqueExterno;
}

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
 * <h2>Un elemento a la vez</h2>
 * La pantalla tiene una zona FIJA —cabecera, marca de agua, filtros, tarjetas
 * de KPI, salvedades y alcance— y debajo un SELECTOR de tarjetas con un
 * elemento por bloque. Solo el elegido se pinta. Apilar los ocho bloques de
 * T-6 obligaba a recorrer media pantalla para llegar al siguiente, y además
 * mantenía vivos ocho SVG y ocho `mat-table` a la vez.
 *
 * Consecuencia que hay que respetar al tocar esto: los <b>filtros gobiernan el
 * tablero entero</b>, no el bloque visible. Un cambio de filtro recarga el
 * sobre completo, así que al cambiar de tarjeta se ve el bloque YA filtrado.
 * Por eso el filtro NO va dentro de la tarjeta seleccionada.
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
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule, MatTooltipModule,
    TableroGraficoComponent,
    CampoTextoDirective
  ],
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

  // ── Selector de elemento ─────────────────────────────────────────────
  /**
   * Tarjetas del selector, en el orden que fijó el backend. Es un CAMPO y no un
   * getter (§8.6 del patrón): un getter devuelve un array nuevo en cada ciclo
   * de detección y repinta el selector entero con cada movimiento del ratón.
   */
  tarjetas: TarjetaBloque[] = [];
  /** Id de la tarjeta elegida. Sobrevive a un cambio de filtro. */
  seleccion: string | null = null;
  actual: TarjetaBloque | null = null;

  // ── Reparto de las dos rejillas ──────────────────────────────────────
  /**
   * Columnas del selector y de la fila de tarjetas de cabecera. Son CAMPOS
   * —recalculados al llegar el sobre— y no getters (§8.6 del patrón).
   *
   * El número de elementos varía por tablero (de 4 a 8 tarjetas, de 3 a 6
   * indicadores), y dejar que el ancho de cada caja decidiera el reparto es lo
   * que producía la fila rota: 7 tarjetas entraban 5 + 2 y quedaban tres huecos
   * a la derecha. Con el reparto calculado son 4 + 3.
   */
  colsSelector = 1;
  colsKpi = 1;

  // ── Notas de los indicadores ─────────────────────────────────────────
  /**
   * Indicadores que traen nota, para el pie desplegable. Campo y no getter, por
   * lo de siempre: un `filter()` en la plantilla devuelve un array nuevo en
   * cada ciclo de detección de cambios.
   */
  notasKpi: KpiTablero[] = [];
  notasAbiertas = false;

  /**
   * Todo lo que pinta el elemento visible, RESUELTO al elegirlo. Por la misma
   * razón que arriba: `[dataSource]="filasDe(pres)"` y `columnas(pres)` eran
   * llamadas de plantilla que devolvían un array nuevo por ciclo, y `mat-table`
   * volvía a montar sus filas cada vez.
   */
  bloqueActual?: BloqueTablero;
  colsActual: ColumnaInforme[] = [];
  columnasActual: string[] = [];
  filasActual: Record<string, any>[] = [];
  /** Total de filas cuando la tabla se recortó; 0 cuando se pintan todas. */
  recorteTotal = 0;

  /** Externos ya pedidos en esta carga: la llamada no se repite al volver. */
  private externosPedidos = new Set<string>();

  /**
   * Opciones de los desplegables que se leen de los propios bloques, por
   * `param`. Son CAMPOS y no un getter, por lo de siempre (§8.6): un array
   * nuevo por ciclo de detección reconstruiría las `<mat-option>` en cada
   * movimiento del ratón.
   */
  opcionesPorParam: Record<string, { valor: string; etiqueta: string }[]> = {};
  /** Referencia estable para «este filtro todavía no tiene opciones». */
  private static readonly SIN_OPCIONES: { valor: string; etiqueta: string }[] = [];

  /**
   * Icono y nombre del tipo de dibujo de cada visualización. Sale del catálogo
   * de `Visualizacion` del modelo; un tipo no previsto cae en «Tabla» en vez de
   * dejar la tarjeta sin icono. Los nombres son de Material Icons CLÁSICO, que
   * es la familia que carga `index.html`.
   */
  private static readonly DIBUJO: Record<string, { icono: string; clase: string }> = {
    serie:            { icono: 'show_chart',           clase: 'Serie' },
    serie_apilada:    { icono: 'equalizer',            clase: 'Reparto 100 %' },
    barras:           { icono: 'bar_chart',            clase: 'Barras' },
    barras_apiladas:  { icono: 'equalizer',            clase: 'Barras apiladas' },
    areas_apiladas:   { icono: 'layers',               clase: 'Áreas apiladas' },
    doble_eje:        { icono: 'multiline_chart',      clase: 'Doble eje' },
    curva_acumulada:  { icono: 'timeline',             clase: 'Pareto' },
    embudo:           { icono: 'filter_alt',           clase: 'Embudo' },
    dispersion:       { icono: 'scatter_plot',         clase: 'Dispersión' },
    caja_bigotes:     { icono: 'import_export',        clase: 'Distribución' },
    matriz:           { icono: 'grid_on',              clase: 'Mapa de calor' },
    semaforo:         { icono: 'traffic',              clase: 'Semáforo' },
    semaforo_tabla:   { icono: 'playlist_add_check',   clase: 'Semáforo' },
    ranking:          { icono: 'format_list_numbered', clase: 'Ranking' },
    tabla:            { icono: 'table_chart',          clase: 'Tabla' }
  };

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

    // Los externos se invalidan CON el resto: «Actualizar» tiene que refrescar
    // lo que se está mirando, no servir la copia de hace media hora.
    this.externos = {};
    this.externosPedidos.clear();

    this.srv.consultar(def.clave, { ...this.valores }).subscribe({
      next: sobre => {
        this.sobre = sobre;
        this.porId = {};
        (sobre.bloques ?? []).forEach(b => this.porId[b.id] = b);
        this.notasKpi = (sobre.kpis ?? []).filter(k => !!k.nota);
        this.colsKpi = TableroComponent.reparto((sobre.kpis ?? []).length, 5);
        if (sobre.analiticaDisponible === false) {
          this.avisoAnalitica = sobre.avisoAnalitica
            ?? 'El almacén analítico no está disponible en este momento.';
        }
        this.loading = false;
        this.refrescarOpciones();
        this.recomponer();
      },
      error: e => {
        this.sobre = undefined;
        this.porId = {};
        this.notasKpi = [];
        this.colsKpi = 1;
        this.loading = false;
        this.recomponer();
        this.snackBar.open(
          mensajeError(e, `No se pudo consultar el tablero ${def.id}`),
          'Cerrar', { duration: 6000 });
      }
    });
  }

  /**
   * Rehace las listas de los desplegables con los valores que traen los bloques.
   *
   * <h2>La regla que evita que la lista se coma a sí misma</h2>
   * Un filtro solo se REHACE cuando su propio valor está vacío. Si estuviera
   * puesto —transportista = «Servientrega»—, la respuesta ya viene recortada a
   * ese transportista, la lista se quedaría con una sola opción y el usuario no
   * podría cambiar de transportista ni volver atrás: el desplegable se
   * convertiría en una trampa de la que solo se sale con «Limpiar filtros».
   *
   * Los demás ejes (fechas, canal…) sí la refrescan a propósito: si en el rango
   * elegido no hubo envíos de un transportista, ofrecerlo sería ofrecer una
   * opción que devuelve una pantalla vacía.
   */
  private refrescarOpciones(): void {
    const def = this.def;
    if (!def) { return; }
    for (const f of def.filtros) {
      const fuente = f.opcionesDe;
      if (!fuente) { continue; }
      if ((this.valores[f.param] ?? '') !== '' && this.opcionesPorParam[f.param]) { continue; }

      const vistos = new Set<string>();
      for (const idBloque of fuente.bloques) {
        for (const fila of this.porId[idBloque]?.items ?? []) {
          const v = fila[fuente.campo];
          if (v !== null && v !== undefined && String(v) !== '') { vistos.add(String(v)); }
        }
      }
      // El valor que está puesto se conserva SIEMPRE aunque el sobre ya no lo
      // traiga: si no, el desplegable se quedaría mostrando un hueco sobre una
      // pantalla que sí está filtrada por él.
      const puesto = this.valores[f.param];
      if (puesto) { vistos.add(puesto); }

      this.opcionesPorParam[f.param] = [
        { valor: '', etiqueta: fuente.todos },
        ...[...vistos].sort((a, b) => a.localeCompare(b, 'es'))
          .map(v => ({ valor: v, etiqueta: etiquetaCodigo(v) }))
      ];
    }
  }

  /** Opciones que pinta un `select`: las escritas en la definición o las leídas. */
  opcionesDe(f: FiltroInforme): { valor: string; etiqueta: string }[] {
    return f.opciones ?? this.opcionesPorParam[f.param] ?? TableroComponent.SIN_OPCIONES;
  }

  /**
   * Rehace el selector con lo que trajo el sobre y CONSERVA la tarjeta elegida.
   *
   * Que la selección sobreviva al cambio de filtro no es un detalle: si se
   * volviera al primer bloque en cada filtro, el usuario perdería el elemento
   * que estaba mirando justo cuando acaba de acotarlo.
   */
  /**
   * Cuántas columnas reparten `n` cajas en filas PAREJAS sin pasar de `maxCols`.
   *
   * Primero se fija el número de filas —el mínimo que cabe— y después se
   * reparte entre ellas, que es al revés de como lo hace un `flex-wrap`: éste
   * llena la primera fila hasta que no cabe una más y tira el resto abajo, y
   * por eso 7 salían 5 + 2. Con las filas fijadas primero, 7 salen 4 + 3, 5
   * salen 3 + 2 y 6 salen 3 + 3; el hueco de la última fila nunca pasa de UNA
   * columna.
   */
  private static reparto(n: number, maxCols: number): number {
    if (n <= 0) { return 1; }
    return Math.ceil(n / Math.ceil(n / maxCols));
  }

  private recomponer(): void {
    const def = this.def;
    if (!def) { this.tarjetas = []; this.colsSelector = 1; this.enfocar(null); return; }

    const orden = (this.sobre?.bloques ?? []).map(b => b.id);
    const delAlmacen: TarjetaBloque[] = def.bloques
      .filter(p => orden.includes(p.id))
      .sort((a, b) => orden.indexOf(a.id) - orden.indexOf(b.id))
      .map(p => {
        const b = this.porId[p.id];
        const d = TableroComponent.DIBUJO[b.visualizacion] ?? TableroComponent.DIBUJO['tabla'];
        return { id: p.id, titulo: b.titulo, origen: 'almacen' as const,
                 icono: d.icono, clase: d.clase, filas: b.filas, pres: p };
      });

    // Los externos van SIEMPRE en el selector, incluso cuando el rol no puede
    // consultarlos: la tarjeta explica por qué no hay dato. Un elemento que
    // desaparece se lee como un tablero que no lo tiene.
    const dePostgres: TarjetaBloque[] = (def.externos ?? []).map(x => ({
      id: x.id, titulo: x.titulo, origen: 'postgres' as const,
      icono: 'storage', clase: 'Tabla', externo: x
    }));

    this.tarjetas = [...delAlmacen, ...dePostgres];
    this.colsSelector = TableroComponent.reparto(this.tarjetas.length, 4);
    const previa = this.tarjetas.find(t => t.id === this.seleccion);
    this.enfocar(previa ?? this.tarjetas[0] ?? null);
  }

  seleccionar(t: TarjetaBloque): void {
    if (t.id !== this.seleccion) { this.enfocar(t); }
  }

  /** Resuelve TODO lo que pinta el elemento visible. Se llama al elegirlo. */
  private enfocar(t: TarjetaBloque | null): void {
    this.actual = t;
    this.seleccion = t?.id ?? null;
    this.bloqueActual = undefined;
    this.colsActual = [];
    this.columnasActual = [];
    this.filasActual = [];
    this.recorteTotal = 0;
    if (!t) { return; }

    if (t.origen === 'almacen' && t.pres) {
      const b = this.porId[t.id];
      this.bloqueActual = b;
      this.colsActual = t.pres.columnas;
      this.columnasActual = t.pres.columnas.map(c => c.campo);
      if (b) {
        const tope = t.pres.topFilas;
        this.filasActual = tope && b.items.length > tope ? b.items.slice(0, tope) : b.items;
        this.recorteTotal = tope && b.filas > tope ? b.filas : 0;
      }
    } else if (t.externo) {
      this.colsActual = t.externo.columnas;
      this.columnasActual = t.externo.columnas.map(c => c.campo);
      this.cargarExterno(t.externo);
    }
  }

  /**
   * Pide el bloque servido desde PostgreSQL. Se dispara al ELEGIR su tarjeta y
   * no al abrir el tablero: es una consulta a otra base que solo hace falta si
   * se va a mirar. La caché se vacía en `cargar()`, así que «Actualizar» y
   * cualquier cambio de filtro la vuelven a pedir.
   */
  private cargarExterno(x: BloqueExterno): void {
    if (this.externosPedidos.has(x.id)) { this.recortarExterno(x); return; }
    this.externosPedidos.add(x.id);

    if (!x.roles.includes(this.rol)) {
      // Su informe simple no es de este rol. No se dispara la llamada: la API
      // la negaría igual y solo ensuciaría la consola con un 403.
      this.externos[x.id] = { filas: [], total: 0, resumen: [], cargando: false, error: '' };
      return;
    }
    this.externos[x.id] = { filas: [], total: 0, resumen: [], cargando: true, error: '' };
    this.informes.consultar(x.departamento, x.endpoint, x.filtros).subscribe({
      next: s => {
        this.externos[x.id] = {
          filas: s.items ?? [], total: s.total ?? 0, resumen: s.resumen ?? [],
          cargando: false, error: ''
        };
        if (this.seleccion === x.id) { this.recortarExterno(x); }
      },
      error: e => {
        this.externos[x.id] = {
          filas: [], total: 0, resumen: [], cargando: false,
          error: mensajeError(e, 'No se pudo consultar este bloque.')
        };
        if (this.seleccion === x.id) { this.filasActual = []; this.recorteTotal = 0; }
      }
    });
  }

  private recortarExterno(x: BloqueExterno): void {
    const e = this.externo(x.id);
    this.filasActual = x.topFilas && e.filas.length > x.topFilas
      ? e.filas.slice(0, x.topFilas) : e.filas;
    this.recorteTotal = x.topFilas && e.total > x.topFilas ? e.total : 0;
  }

  aplicarFiltros(): void { this.cargar(); }

  /** Abre o cierra el pie con las notas de los indicadores. */
  alternarNotas(): void { this.notasAbiertas = !this.notasAbiertas; }

  alEscribir(): void { this.texto$.next(); }

  limpiar(): void {
    this.def?.filtros.forEach(f => this.valores[f.param] = f.valorInicial ?? '');
    this.cargar();
  }

  // ── Acceso a los datos desde la plantilla ────────────────────────────

  get rol(): string {
    return this.auth.getCurrentUser()?.rol ?? '';
  }

  externo(id: string): EstadoExterno {
    return this.externos[id]
        ?? { filas: [], total: 0, resumen: [], cargando: false, error: '' };
  }

  /** Rótulo de la insignia de origen de una tarjeta (la PALABRA, §9.bis.25). */
  palabraOrigen(t: TarjetaBloque): string {
    return t.origen === 'postgres' ? 'PostgreSQL' : 'Almacén';
  }

  /** Ayuda de la tarjeta: qué se va a ver y de dónde sale. */
  tituloTarjeta(t: TarjetaBloque): string {
    return t.origen === 'postgres'
      ? `${t.titulo} — se sirve de un informe de PostgreSQL, así que sigue vivo con el `
        + 'almacén analítico apagado.'
      : `${t.titulo} — ${t.clase.toLowerCase()}, ${(t.filas ?? 0).toLocaleString('es-EC')} `
        + 'filas; sale del almacén analítico.';
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
