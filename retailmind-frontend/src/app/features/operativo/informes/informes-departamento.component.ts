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
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

import { InformesService } from '../../../core/services/informes.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ColumnaInforme, DefinicionDepartamento, DefinicionInforme, FiltroInforme,
  FuenteInforme, KpiInforme, TipoValor
} from '../../../core/models/informe.model';
import { etiquetaCodigo } from '../../../core/pipes/etiquetas.pipe';
import { definicionDepartamento } from './definiciones/catalogo-informes';
import { ActualizacionAlmacenComponent } from './actualizacion-almacen.component';
import { PrevisionGraficoComponent, PuntoPrevision } from './prevision-grafico.component';

/**
 * PANTALLA GENÉRICA de informes tácticos — sirve a los seis departamentos.
 *
 * El departamento llega por `data.departamento` de la ruta; de ahí sale su
 * `DefinicionDepartamento` y con ella se pintan el selector de informes, los
 * filtros, el resumen y la tabla. NO hay lógica de negocio aquí: todo lo
 * específico vive en el archivo de definiciones del departamento y en su
 * servicio del backend.
 *
 * Consulta POR PANTALLA: filtros en vivo, tabla paginada y registros
 * visibles. NO hay exportación a PDF (decisión de alcance del nivel táctico;
 * los PDF quedan para documentos operativos).
 *
 * Permisos: el selector solo ofrece los informes cuyo `roles` incluye el rol
 * del usuario — espeja SecurityConfig, que es quien realmente decide, y evita
 * disparar peticiones que la API negaría con 403.
 */
@Component({
  selector: 'app-informes-departamento',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, MatProgressBarModule,
    ActualizacionAlmacenComponent, PrevisionGraficoComponent],
  templateUrl: './informes-departamento.component.html',
  // `informes-piloto.scss` va SOLO aquí. `informes.scss` lo comparte
  // `tablero.component.ts`, así que las reglas del piloto puestas allí
  // viajarían también al paquete de los siete tableros (medido: +6,9 kB de
  // CSS muerto). Ver la cabecera del propio archivo.
  styleUrls: ['../operativo-shared.scss', './informes.scss', './informes-piloto.scss']
})
export class InformesDepartamentoComponent implements OnInit {

  depto?: DefinicionDepartamento;
  /** Informes que el rol actual puede consultar. */
  disponibles: DefinicionInforme[] = [];
  /**
   * Los que se están pintando: `disponibles` recortado por el filtro de tipo.
   *
   * Es un CAMPO y no un getter: un getter devolvería un array nuevo en cada
   * ciclo de detección de cambios y `*ngFor` repintaría el selector entero
   * (trampa §8.6 de `docs/PATRON_UI.md`).
   */
  visibles: DefinicionInforme[] = [];
  /** null = se ven los dos tipos. Lo gobiernan las pastillas del contador. */
  filtroFuente: FuenteInforme | null = null;
  actual?: DefinicionInforme;

  filas: Record<string, any>[] = [];
  resumen: KpiInforme[] = [];
  columnas: string[] = [];
  total = 0;

  /**
   * Columnas de la fila de indicadores. Es un CAMPO recalculado al llegar el
   * sobre y NO un getter (§8.6 del patrón).
   *
   * El número de indicadores varía por informe —medido: de 0 en OTD-VEN-15 a
   * 11 en OTD-VEN-19— y dejar que lo repartiera `auto-fit` producía la fila
   * rota: a 1920 px entraban SIETE columnas, así que los 8 de OTD-VEN-07
   * salían **7 + 1** y los 11 de OTD-VEN-19, **7 + 4**. Con el reparto
   * calculado son 4 + 4 y 4 + 4 + 3.
   */
  colsKpi = 1;

  /**
   * Cuántas columnas reparten `n` cajas en filas PAREJAS sin pasar de
   * `maxCols`. Primero se fija el número de FILAS —el mínimo que cabe— y
   * después se reparte entre ellas; es al revés de como lo hace `flex-wrap` o
   * `auto-fit`, que llenan la primera hasta que no cabe una más.
   *
   * Es la misma función que `tablero.component.ts`, con un tope distinto: allí
   * es 5 y aquí 4, porque la columna del selector se lleva 280 px y con cinco
   * columnas la tarjeta baja a ~200 px, que es justo el ancho con el que
   * «$525.083.889,49» no cabe. El tope es lo que garantiza los repartos
   * pedidos: 8 → 4+4, 7 → 4+3, 11 → 4+4+3.
   */
  private static reparto(n: number, maxCols: number): number {
    if (n <= 0) { return 1; }
    return Math.ceil(n / Math.ceil(n / maxCols));
  }

  /**
   * `total` es un MÍNIMO porque el servidor cortó el conteo en su tope. Solo
   * pasa en los informes que abarcan millones de filas sin filtrar (hoy
   * OTD-VEN-01 sobre los 2.999.993 pedidos): contarlos bajo RLS cuesta 4,5 s
   * en cada apertura. En cuanto se pone un filtro el conteo vuelve a ser
   * exacto y esta bandera se apaga sola.
   */
  totalEsMinimo = false;

  /** Lo que se pinta junto al nombre del informe. */
  get etiquetaTotal(): string {
    const n = this.total.toLocaleString('es-EC');
    return this.totalEsMinimo ? `más de ${n}` : n;
  }
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];
  loading = false;
  /** Aviso cuando el backend recortó el informe (VEN-02 para un vendedor). */
  avisoAlcance = '';

  // ── Informes COMPUESTOS (fuente ClickHouse) ──────────────────────────
  /** Marca de agua «Datos al …»: última carga del ETL. Vacío en los simples. */
  datosAl = '';
  fuente = '';
  /** Aviso de degradación cuando el almacén analítico no responde. */
  avisoAnalitica = '';

  /**
   * Salvedad metodológica del informe (§8.3 del diseño del pipeline). Hoy la
   * envía OTD-INV-09: la valorización del inventario de meses pasados usa el
   * costo VIGENTE porque no existe costo histórico en el sistema.
   */
  salvedad = '';

  /**
   * Serie del gráfico de previsión: meses observados y meses previstos en una
   * sola lista (regla 1 de §5.1.9). Solo la envía OTD-GER-13.
   */
  serie: PuntoPrevision[] = [];
  /** Nombre de la serie que el gráfico está mostrando (total o categoría). */
  previsionDe = '';

  /**
   * Coletilla del título (regla 5 de §5.2.9, la estrena OTD-VEN-19): la fecha
   * ancla contra la que se midió el informe. Sin ella, una pantalla servida por
   * un pipeline detenido se lee como si fuera de hoy.
   */
  sufijoTitulo = '';

  /** Valores actuales de los filtros del informe seleccionado. */
  valores: Record<string, string> = {};
  private texto$ = new Subject<void>();

  constructor(private ruta: ActivatedRoute,
              private srv: InformesService,
              private auth: AuthService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    const nombre = this.ruta.snapshot.data['departamento'] as string;
    this.depto = definicionDepartamento(nombre);
    if (!this.depto) {
      this.snackBar.open(`No hay informes definidos para «${nombre}»`, 'Cerrar', { duration: 5000 });
      return;
    }
    const rol = this.auth.getCurrentUser()?.rol ?? '';
    this.disponibles = this.depto.informes.filter(i => i.roles.includes(rol));

    this.texto$.pipe(debounceTime(350), distinctUntilChanged())
      .subscribe(() => { this.pagina = 0; this.cargar(); });

    // Calcula `visibles` y abre el primero: sustituye al `seleccionar` suelto
    // que había aquí, para no consultar dos veces al entrar.
    this.aplicarFiltroFuente();
  }

  // ── Selección de informe ─────────────────────────────────────────────

  seleccionar(informe: DefinicionInforme): void {
    this.actual = informe;
    this.columnas = informe.columnas.map(c => c.campo);
    this.valores = {};
    informe.filtros.forEach(f => this.valores[f.param] = this.valorInicialDe(f));
    this.pagina = 0;
    this.filas = [];
    this.resumen = [];
    this.total = 0;
    this.cargar();
  }

  esActual(informe: DefinicionInforme): boolean {
    return this.actual?.id === informe.id;
  }

  // ── Presentación: el piloto de Ventas (2026-08-15) ───────────────────
  // Las dos banderas viven en la DEFINICIÓN del departamento y no en la
  // pantalla: así extenderlas a otro departamento es una línea en su archivo
  // de definiciones, y los cinco que no las declaran se pintan como siempre.

  /** Selector en columna a la izquierda en vez de parrilla de tarjetas. */
  get selectorVertical(): boolean { return !!this.depto?.selectorVertical; }

  /** Tarjetas de resumen en vidrio (las de los tableros) en vez de índigo. */
  get kpiVidrio(): boolean { return !!this.depto?.kpiVidrio; }

  /**
   * A ancho reducido la columna se pliega a una cabecera de UNA línea que
   * dice qué informe está abierto, y la lista se despliega al pulsarla.
   *
   * Por qué plegada y no una lista siempre visible: por debajo de 1100 px las
   * dos columnas no caben, así que la lista pasa a ir ENCIMA del contenido —
   * y 17 filas encima de los filtros es el mismo defecto que esta sesión
   * corrige, solo que peor (17 filas en vez de 5). Plegada cuesta cero
   * mientras se lee el informe y un toque cuando se quiere cambiar.
   *
   * La bandera solo la MIRA la hoja por debajo del punto de ruptura; por
   * encima, la lista se ve entera pase lo que pase con este booleano.
   */
  listaAbierta = false;

  alternarLista(): void { this.listaAbierta = !this.listaAbierta; }

  /** Al elegir informe en modo plegado, la lista se cierra sola. */
  seleccionarYPlegar(informe: DefinicionInforme): void {
    this.seleccionar(informe);
    this.listaAbierta = false;
  }

  /** Rótulo de la cabecera plegable: el informe abierto, o el hueco. */
  get resumenSeleccion(): string {
    return this.actual ? `${this.actual.id} · ${this.actual.titulo}`
                       : 'Elige un informe';
  }

  // ── Simples vs. compuestos ───────────────────────────────────────────
  // Los dos contadores se CALCULAN sobre lo que hay pintado; ninguna cifra
  // está escrita a mano. Con el filtro puesto, el tipo excluido queda en 0,
  // que es la lectura correcta: no hay ninguno de ésos en pantalla.

  get conteoSimples(): number {
    return this.visibles.filter(i => i.fuente === 'simple').length;
  }

  get conteoCompuestos(): number {
    return this.visibles.filter(i => i.fuente === 'compuesto').length;
  }

  /** Pulsar el tipo ya filtrado lo suelta: la pastilla es un interruptor. */
  alternarFuente(f: FuenteInforme): void {
    this.filtroFuente = this.filtroFuente === f ? null : f;
    this.aplicarFiltroFuente();
  }

  /**
   * El informe abierto se conserva si sigue visible; si el filtro lo dejó
   * fuera, se abre el primero de la lista nueva —y si no queda ninguno, la
   * pantalla se queda sin tabla en vez de consultar un informe invisible.
   */
  private aplicarFiltroFuente(): void {
    this.visibles = this.filtroFuente
      ? this.disponibles.filter(i => i.fuente === this.filtroFuente)
      : [...this.disponibles];
    if (this.actual && this.visibles.some(i => i.id === this.actual!.id)) { return; }
    if (this.visibles.length) { this.seleccionar(this.visibles[0]); }
    else { this.actual = undefined; }
  }

  /** Texto del `title` de la insignia: el porqué del tipo, no solo su nombre. */
  tituloFuente(informe: DefinicionInforme): string {
    return informe.fuente === 'compuesto'
      ? `${informe.id} · COMPUESTO — recorre histórico o cruza períodos; se sirve del `
        + 'almacén analítico (ClickHouse, retailmind_dwh) y lleva marca de agua «Datos al …».'
      : `${informe.id} · SIMPLE — responde sobre el estado actual; se consulta directo `
        + 'contra PostgreSQL, sin pasar por el almacén analítico.';
  }

  // ── Consulta ─────────────────────────────────────────────────────────

  cargar(): void {
    if (!this.depto || !this.actual) { return; }
    const informe = this.actual;
    this.loading = true;
    this.avisoAlcance = '';
    this.avisoAnalitica = '';
    this.datosAl = '';
    this.fuente = '';
    this.salvedad = '';
    this.serie = [];
    this.previsionDe = '';
    this.sufijoTitulo = '';

    const filtros: Record<string, string | number> = { ...this.valores };
    // El filtro de período se envía descompuesto en año y mes.
    if (filtros['periodo']) {
      const [anio, mes] = String(filtros['periodo']).split('-');
      filtros['anio'] = anio;
      filtros['mes'] = String(Number(mes));
    }
    delete filtros['periodo'];
    if (!informe.sinPaginar) {
      filtros['page'] = this.pagina;
      filtros['size'] = this.tamPagina;
    }

    this.srv.consultar(this.depto.departamento, informe.endpoint, filtros).subscribe({
      next: sobre => {
        this.filas = sobre.items ?? [];
        this.total = sobre.total ?? this.filas.length;
        this.totalEsMinimo = !!sobre['totalEsMinimo'];
        this.resumen = sobre.resumen ?? [];
        this.colsKpi = InformesDepartamentoComponent.reparto(this.resumen.length, 4);
        if (sobre.alcance === 'propio') {
          // El informe puede traer su propio texto: en OTD-VEN-19 el recorte es
          // por CARTERA (a quién ha atendido) y no por autoría de la venta, y
          // decir «tus propias ventas» ahí sería describir mal el filtro que se
          // acaba de aplicar.
          this.avisoAlcance = sobre.avisoAlcance
            ?? 'Estás viendo únicamente tus propias ventas: '
             + 'el detalle del resto del equipo es atribución de Gerencia.';
        }
        this.sufijoTitulo = sobre.sufijoTitulo ?? '';
        // Informes compuestos: marca de agua y degradación. Los simples no
        // envían estos campos y todo queda vacío, así que no se pinta nada.
        this.datosAl = sobre.datosAl ?? '';
        this.fuente = sobre.fuente ?? '';
        // Salvedad metodológica (OTD-INV-09 y el modo valorizado de INV-10):
        // se pinta ENCIMA de la tabla, no debajo, porque es una advertencia
        // sobre cómo leer las cifras y llega tarde después de haberlas leído.
        this.salvedad = sobre.salvedad ?? '';
        // Serie del gráfico de previsión (regla 1 de §5.1.9). Los demás
        // informes no envían `serie` y el gráfico no llega a pintarse.
        this.serie = (sobre['serie'] as PuntoPrevision[]) ?? [];
        this.previsionDe = (sobre['previsionDe'] as string) ?? '';
        if (sobre.analiticaDisponible === false) {
          this.avisoAnalitica = sobre.avisoAnalitica
            ?? 'La analítica no está disponible en este momento.';
        }
        this.loading = false;
      },
      error: e => {
        this.filas = [];
        this.resumen = [];
        this.colsKpi = 1;
        this.total = 0;
        this.loading = false;
        this.snackBar.open(
          mensajeError(e, `No se pudo consultar el informe ${informe.id}`),
          'Cerrar', { duration: 6000 });
      }
    });
  }

  aplicarFiltros(): void { this.pagina = 0; this.cargar(); }

  alEscribir(): void { this.texto$.next(); }

  limpiar(): void {
    this.actual?.filtros.forEach(f => this.valores[f.param] = this.valorInicialDe(f));
    this.pagina = 0;
    this.cargar();
  }

  /** El período arranca SIEMPRE en el mes en curso; el resto, en lo declarado. */
  private valorInicialDe(f: FiltroInforme): string {
    if (f.valorInicial) { return f.valorInicial; }
    return f.tipo === 'periodo' ? this.mesActual() : '';
  }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.cargar();
  }

  // ── Formato de celdas y tarjetas ─────────────────────────────────────

  valor(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    if (crudo === null || crudo === undefined || crudo === '') { return '—'; }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar
      ? texto.slice(0, col.recortar) + '…'
      : texto;
  }

  /** Texto completo para el tooltip de las celdas recortadas. */
  valorCompleto(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    // El sparkline lleva su rótulo dentro del SVG (<title> nativo); devolver
    // aquí un texto añadiría un matTooltip por fila y por celda, que es
    // justamente lo que dejó el navegador colgado en la fase E1-A.
    if (col.tipo === 'sparkline') { return ''; }
    if (crudo === null || crudo === undefined || crudo === '') { return ''; }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar ? texto : '';
  }

  formatear(crudo: any, tipo: TipoValor): string {
    switch (tipo) {
      // Una PÍLDORA de estado es, por definición, un código de catálogo. Varios
      // informes simples proyectan `ep.codigo` en vez de `ep.nombre` (OTD-LOG-01
      // lo hace) y el chip salía con `en_preparacion`. Se traduce aquí, en el
      // único sitio que pinta las celdas, y no informe por informe; la columna
      // que trae su propia `etiqueta` no llega hasta aquí y conserva la suya.
      case 'chip':
        return etiquetaCodigo(String(crudo));
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
      case 'fechaHora':
        return new Date(crudo).toLocaleString('es-EC',
          { day: '2-digit', month: '2-digit', year: '2-digit',
            hour: '2-digit', minute: '2-digit' });
      case 'estrellas':
        return '★'.repeat(Number(crudo)) + '☆'.repeat(Math.max(5 - Number(crudo), 0));
      case 'booleano':
        return crudo === true ? 'Sí' : 'No';
      default:
        return String(crudo);
    }
  }

  claseChip(col: ColumnaInforme, fila: Record<string, any>): string {
    return col.color ? col.color(fila) : 'neutral';
  }

  // ── Un KPI SIN VALOR no es un KPI que vale cero ──────────────────────

  /**
   * ¿La tarjeta trae una cifra de verdad?
   *
   * El backend distingue las dos cosas y envía `valor: null` cuando NO calculó
   * el indicador. Hoy solo pasa en OTD-VEN-01: por encima del tope de conteo
   * (`Paginacion.TOPE_CONTEO` = 200.000) las dos sumas recorrerían los
   * 2.999.991 pedidos en cada apertura, y una suma sobre 200.000 pedidos
   * arbitrarios de tres millones no es «el total aproximado», es un número sin
   * significado — así que el servicio devuelve los tres KPI vacíos y adjunta
   * la `salvedad` que lo explica (`InformesVentasService:128-139`).
   *
   * La pantalla NO respetaba esa distinción: `formatear(null,'numero')` pasa
   * por `Number(null)`, que es 0, y la cabecera acababa diciendo «Pedidos en
   * el filtro: 0» y «$0,00» justo encima de un título que dice «(más de
   * 200.000)». Se leía como un fallo del sistema cuando era una decisión
   * declarada del servidor.
   */
  tieneValor(k: KpiInforme): boolean {
    return k.valor !== null && k.valor !== undefined && k.valor !== '';
  }

  /** El valor ya formateado. Se usa para pintarlo, para medirlo y para el `title`. */
  textoKpi(k: KpiInforme): string {
    return this.formatear(k.valor, k.tipo);
  }

  /**
   * Tamaño de la cifra según lo LARGA que sea.
   *
   * No todos estos indicadores son números. El backend manda también frases —
   * «Ferreteria y Herramienta», «Servilletas Supermaxi Basico Coco x24»,
   * «16347 clientes · el mayor silencio entre ellos, 760 días» (56 caracteres)—
   * y a 27 px/800 se salían de la caja. Los importes tampoco caben:
   * «$525.083.889,49» son 15 dígitos que a 27 px miden ~243 px dentro de una
   * tarjeta de 246 px con 36 px de relleno. Ocho valores desbordaban, medido.
   *
   * La alternativa —ensanchar la tarjeta— rompe el reparto en filas parejas, y
   * la de recortar con puntos suspensivos ESCONDE el dato. Se escala la letra:
   * un número corto conserva el tratamiento de cifra grande, que es lo que
   * hace legible un tablero de un vistazo, y una frase pasa a tamaño de texto,
   * que es lo que de verdad es. La escala baja en pasos de la misma familia
   * tipográfica, no continua, para que dos tarjetas vecinas con valores
   * parecidos no salgan con tamaños distintos por un carácter.
   *
   * Devuelve una CADENA (no un objeto ni un array), así que llamarla desde la
   * plantilla no rompe la detección de cambios como haría un getter que
   * construye una colección nueva (§8.6).
   */
  claseKpi(k: KpiInforme): string {
    const n = this.textoKpi(k).length;
    if (n <= 10) { return ''; }            // 27 px — «2.883.686», «104», «12,4 %»
    if (n <= 14) { return 'kpi-v-medio'; } // 22 px — «$1.234.567,89»
    if (n <= 19) { return 'kpi-v-corto'; } // 18 px — «$525.083.889,49»
    return 'kpi-v-frase';                  // 14 px y hasta tres líneas
  }

  /**
   * Por qué no hay cifra, en el `title` NATIVO de la tarjeta (§18: nada de
   * `matTooltip`). El texto es el que envía el propio informe en su
   * `salvedad`, que es quien conoce el motivo; solo si no lo envía se recurre
   * a una frase genérica, para no inventar una razón que no consta.
   */
  get motivoSinCalcular(): string {
    return this.salvedad
      || 'El informe no calculó este indicador para el filtro actual. '
       + 'Acota la consulta y volverá a mostrarse.';
  }

  // ── Sparkline (regla 2 de §5.2.9, lo estrena OTD-VEN-19) ─────────────

  /**
   * Barras del micro-gráfico de una celda, ya escaladas a la altura del SVG.
   *
   * Se escala contra el MÁXIMO DE LA PROPIA FILA y no contra el de la tabla: la
   * pregunta que responde el trazo es «¿este cliente está bajando?», que es
   * sobre su propia serie. Escalar contra el máximo global dejaría plana la
   * serie de los 60 clientes pequeños y solo se vería la del mayor — que es
   * justo la lectura que el artefacto de la rampa produce.
   */
  barras(fila: Record<string, any>, campo: string): { x: number; y: number; alto: number }[] {
    const serie = (fila[campo] as number[]) ?? [];
    if (!serie.length) { return []; }
    const tope = Math.max(...serie.map(Number), 1);
    const ancho = 4, hueco = 1, alto = 18;
    return serie.map((v, i) => {
      const h = Math.max(Math.round((Number(v) / tope) * alto), Number(v) > 0 ? 1 : 0);
      return { x: i * (ancho + hueco), y: alto - h, alto: h };
    });
  }

  /** Ancho del SVG para que las barras quepan justas. */
  anchoSparkline(fila: Record<string, any>, campo: string): number {
    const serie = (fila[campo] as number[]) ?? [];
    return Math.max(serie.length * 5, 5);
  }

  /**
   * Rótulo del sparkline. Va como `<title>` NATIVO del SVG y no como
   * `matTooltip`: con centenares de directivas de tooltip vivas el navegador
   * deja de responder (lección de la fase E1-A del nivel estratégico).
   */
  rotuloSparkline(fila: Record<string, any>, campo: string): string {
    const serie = (fila[campo] as number[]) ?? [];
    return serie.length
      ? `Compras por mes (últimos ${serie.length} meses): ${serie.join(' · ')}`
      : '';
  }

  /**
   * Barra de avance: solo para los informes que la piden (`barraAvance`) y que
   * traen un porcentaje en el resumen. No basta con que HAYA un porcentaje —
   * una tasa de resolución (SOP-05) o una ocupación media (INV-08) no son un
   * avance sobre una meta, y pintarlas como tal sería un dato falso.
   */
  get avance(): number | null {
    if (!this.actual?.barraAvance) { return null; }
    const k = this.resumen.find(r => r.tipo === 'porcentaje');
    return k ? Math.min(Number(k.valor), 100) : null;
  }

  /** Valor del filtro de período por defecto: el mes en curso (input month). */
  mesActual(): string {
    const hoy = new Date();
    return `${hoy.getFullYear()}-${String(hoy.getMonth() + 1).padStart(2, '0')}`;
  }
}
