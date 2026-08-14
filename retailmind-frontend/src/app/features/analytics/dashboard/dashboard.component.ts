import {
  Component,
  OnInit,
  OnDestroy,
  ViewChild,
  ElementRef
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatIconModule } from '@angular/material/icon';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  Chart, ChartConfiguration, Plugin,
  BarController, BarElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Filler
} from 'chart.js';

import { DashboardService } from '../../../core/services/dashboard.service';
import { SesionService }    from '../../../core/services/sesion.service';
import {
  DashboardResumen, DashboardSeries, GrupoConteo, PuntoSemanal
} from '../../../core/models/dashboard.model';
import { Sesion }      from '../../../core/models/sesion.model';
import { environment } from '../../../../environments/environment';

// Sólo lo que se usa. Se retiraron `DoughnutController`/`ArcElement` —el donut
// desapareció, ver más abajo— y `Legend`: los siete gráficos son de UNA serie,
// y una caja de leyenda con un solo color repite el título y gasta espacio.
Chart.register(
  BarController, BarElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Filler
);

interface KpiCard {
  title: string;
  value: string;
  tooltip: string;
}

/**
 * Analítica web sobre la base LEGADA de ClickHouse (2,93 M de eventos).
 *
 * ═══ POR QUÉ ESTA PANTALLA CAMBIÓ DE ASPECTO ════════════════════════════════
 *
 * 1. LOS KPIs HEREDAN EL VIDRIO DE LOS TABLEROS. Ocho tarjetas blancas con un
 *    chip de color cada una —azul, cian, verde, naranja, rojo, morado— en una
 *    paleta que no es la del sistema. El color no encodaba NADA: verde para
 *    conversiones y rojo para abandonos insinuaban bueno/malo (tokens de
 *    estado) sobre métricas que sólo son recuentos, y el mismo naranja servía
 *    para «Tasa Conversión» y para «Total Eventos», que no tienen relación.
 *    Se retiran los chips y las tarjetas pasan al lavado índigo del 7 % + punto
 *    de cian de `tableros.scss`. Queda un icono de ayuda monocromo —el mismo
 *    `kpi-info` de los tableros— que sólo porta el texto explicativo.
 *
 * 2. LOS DESGLOSES MIDEN EVENTOS, NO SESIONES. `session_id` no identifica una
 *    sesión aquí: el generador sortea `user_id` y `channel` en cada fila, así
 *    que el 87 % de las sesiones tocan 2-3 canales y «sesiones por canal»
 *    sumaba 1.098.845 sobre 474.637 reales (231 %). El donut de dispositivo
 *    era el caso extremo: sus cuatro porciones sumaban 306 % y por tanto no
 *    eran partes de ningún todo. Con eventos cada fila tiene un canal, un
 *    dispositivo y una región: suman 100 %.
 *
 * 3. EL DONUT DESAPARECIÓ, y no sólo por (2): sus cuatro porciones valen
 *    25,4 / 25,0 / 24,9 / 24,7 % — un donut es la peor forma posible para
 *    comparar valores casi iguales. Es una barra horizontal, donde 0,7 puntos
 *    de diferencia se ven.
 *
 * 4. UNA SOLA SERIE, UN SOLO TONO. Las tres barras de canal iban en índigo,
 *    rosa y cian: tres colores de identidad para UNA magnitud, gastando el
 *    canal del color en repetir lo que ya dice la longitud de la barra. Ahora
 *    todas las barras de una misma serie van en `--primary-light` (#3949ab,
 *    7,73:1 sobre blanco). El único gráfico con rampa es el de duración,
 *    porque sus tramos SÍ tienen orden (escala ordinal, validada).
 *
 * 5. APARECE EL TIEMPO. La tabla tiene 28 semanas y la pantalla no mostraba
 *    ninguna. Los dos gráficos nuevos de arriba son la serie semanal.
 *
 * §9.bis.25: no queda ninguna distinción portada por el color solo — cada
 * gráfico es de una serie y cada barra lleva su etiqueta y su cifra.
 * ════════════════════════════════════════════════════════════════════════════
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    MatIconModule,
    MatTableModule,
    MatPaginatorModule,
    MatSnackBarModule,
    MatButtonModule,
    MatChipsModule,
    MatTooltipModule
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit, OnDestroy {

  /** Cierra la puerta a los `next` que llegan después de salir de la pantalla. */
  private destruido = false;
  private temporizadorPintado?: ReturnType<typeof setTimeout>;

  @ViewChild('volumenCanvas')    volumenCanvas?:    ElementRef<HTMLCanvasElement>;
  @ViewChild('conversionCanvas') conversionCanvas?: ElementRef<HTMLCanvasElement>;
  @ViewChild('accionesCanvas')   accionesCanvas?:   ElementRef<HTMLCanvasElement>;
  @ViewChild('duracionCanvas')   duracionCanvas?:   ElementRef<HTMLCanvasElement>;
  @ViewChild('canalCanvas')      canalCanvas?:      ElementRef<HTMLCanvasElement>;
  @ViewChild('dispositivoCanvas')dispositivoCanvas?:ElementRef<HTMLCanvasElement>;
  @ViewChild('regionCanvas')     regionCanvas?:     ElementRef<HTMLCanvasElement>;

  loading   = true;
  resumen!:   DashboardResumen;
  series:     DashboardSeries | null = null;
  kpiCards:   KpiCard[] = [];

  responseTimeMs:  number | null = null;
  lastUpdated:     Date | null   = null;
  refreshingViews  = false;
  hasError         = false;
  promedioEventos  = '0.0';

  // Salvedades que se PINTAN, no que se callan.
  semanasNoMedibles: PuntoSemanal[] = [];
  minSesionesTasa    = 0;

  get currentUser(): string {
    const raw = localStorage.getItem('rm_user');
    if (raw) {
      const u = JSON.parse(raw);
      return u.nombre || u.username;
    }
    return '';
  }

  // Tabla de sesiones recientes
  sesiones:      Sesion[] = [];
  totalSesiones  = 0;
  pageSize       = 20;
  pageIndex      = 0;
  loadingTable   = false;
  displayedCols  = ['sessionId', 'usuario', 'timestampUtc', 'canal', 'interactionCount'];

  private charts: Chart[] = [];

  // ── Paleta: SOLO tonos del sistema Dubai, y validados ────────────────────
  // #3949ab (--primary-light) 7,73:1 sobre blanco; #546e7a (--text-secondary)
  // 5,40:1 para el texto de los ejes. La rejilla va en un gris casi
  // imperceptible: es cromo, no dato.
  private readonly SERIE      = '#3949ab';
  private readonly SERIE_WASH = 'rgba(57, 73, 171, 0.10)';   // relleno de área al 10 %
  private readonly EJE_TEXTO  = '#546e7a';
  private readonly REJILLA    = 'rgba(38, 50, 56, 0.08)';
  private readonly SUPERFICIE = '#ffffff';

  // Rampa ORDINAL de la familia índigo, la única escala con orden de la
  // pantalla. Validada con la herramienta de la skill: L monótona, ΔL ≥ 0,06
  // en todos los saltos, extremo claro a 2,25:1 y un solo tono (6° de
  // dispersión). Nunca se usa para categorías sin orden.
  private readonly RAMPA_ORDINAL = ['#9fa8da', '#7986cb', '#5c6bc0', '#3949ab', '#283593'];

  /**
   * Escribe la cifra en la punta de la barra. Es el canal de lectura que
   * evita que el valor quede detrás del ratón: la skill prohíbe que un
   * tooltip sea la ÚNICA forma de leer un dato. Sólo se aplica a los
   * gráficos de pocas barras (3 a 9); en la serie de 28 semanas los valores
   * los llevan el eje y el tooltip, porque una cifra por barra sería ruido.
   */
  private readonly valorEnPunta: Plugin<'bar'> = {
    id: 'valorEnPunta',
    afterDatasetsDraw: (chart) => {
      const { ctx } = chart;
      const meta = chart.getDatasetMeta(0);
      const horizontal = (chart.options as any)?.indexAxis === 'y';
      ctx.save();
      ctx.font = '600 11px Roboto, "Helvetica Neue", sans-serif';
      ctx.fillStyle = this.EJE_TEXTO;
      ctx.textBaseline = 'middle';
      meta.data.forEach((barra, i) => {
        const valor = chart.data.datasets[0].data[i] as number;
        if (valor == null) return;
        const texto = valor.toLocaleString('es-EC');
        const p = barra.getProps(['x', 'y'], true) as any;
        if (horizontal) {
          ctx.textAlign = 'left';
          ctx.fillText(texto, p.x + 8, p.y);
        } else {
          ctx.textAlign = 'center';
          ctx.fillText(texto, p.x, p.y - 10);
        }
      });
      ctx.restore();
    }
  };

  constructor(
    private dashboardService: DashboardService,
    private sesionService:    SesionService,
    private snackBar:         MatSnackBar,
    private http:             HttpClient
  ) {}

  ngOnInit(): void {
    this.loadDashboard();
    this.loadSesiones(0, this.pageSize);
  }

  ngOnDestroy(): void {
    this.destruido = true;
    clearTimeout(this.temporizadorPintado);
    this.destruirGraficos();
  }

  // ── Carga ─────────────────────────────────────────────────────────────────

  /**
   * Las dos peticiones salen A LA VEZ. Encadenarlas habría sumado sus
   * latencias (~470 ms + ~250 ms); en paralelo el reloj lo marca la más lenta.
   */
  loadDashboard(): void {
    this.loading  = true;
    this.hasError = false;
    const start = Date.now();

    this.dashboardService.getSeries().subscribe({
      next: s => {
        this.series = s?.disponible ? s : null;
        this.minSesionesTasa   = s?.minSesionesTasa ?? 0;
        this.semanasNoMedibles = (s?.semanal ?? []).filter(p => !p.medible);
        this.pintarGraficos();
      },
      error: () => { this.series = null; }
    });

    this.dashboardService.getResumen().subscribe({
      next: data => {
        this.responseTimeMs = Date.now() - start;
        this.lastUpdated    = new Date();
        this.resumen        = data;
        this.buildKpiCards(data);
        this.loading = false;
        this.pintarGraficos();
      },
      error: () => {
        this.responseTimeMs = Date.now() - start;
        this.loading  = false;
        this.hasError = true;
        this.snackBar.open(
          'Error al cargar el dashboard. Verifica que el backend este corriendo.',
          'Cerrar',
          { duration: 5000, panelClass: 'snack-error' }
        );
      }
    });
  }

  loadSesiones(page: number, size: number): void {
    this.loadingTable = true;
    this.sesionService.getAll(page, size).subscribe({
      next: result => {
        this.sesiones      = result.content;
        this.totalSesiones = result.totalElements;
        this.pageIndex     = result.number;
        this.loadingTable  = false;
      },
      error: () => { this.loadingTable = false; }
    });
  }

  onPageChange(event: PageEvent): void {
    this.loadSesiones(event.pageIndex, event.pageSize);
  }

  refrescarVistas(): void {
    this.refreshingViews = true;
    this.http.post<{success: boolean; mensaje: string; duracionMs: number}>(
      `${environment.apiUrl}/api/dashboard/refrescar-vistas`, {}
    ).subscribe({
      next: res => {
        this.refreshingViews = false;
        this.snackBar.open(res.mensaje + ` (${res.duracionMs}ms)`, 'OK', { duration: 4000 });
        this.loadDashboard();
      },
      error: () => {
        this.refreshingViews = false;
        this.snackBar.open('Error al refrescar vistas.', 'Cerrar', { duration: 3000 });
      }
    });
  }

  // ── KPIs ──────────────────────────────────────────────────────────────────
  // Mismas ocho cifras y mismo cálculo que antes: esto es un cambio de
  // PRESENTACIÓN. Lo que se va es el `icon` y el `color` de cada tarjeta.

  private buildKpiCards(d: DashboardResumen): void {
    const totalEventos    = d.totalEventos ?? 0;
    const semanasCargadas = d.semanasCargadas ?? 0;

    this.promedioEventos = d.totalSesiones > 0
      ? (totalEventos / d.totalSesiones).toFixed(1)
      : '0.0';

    this.kpiCards = [
      { title: 'Total sesiones',    value: d.totalSesiones.toLocaleString('es-EC'),     tooltip: 'Identificadores de sesión distintos en fact_eventos' },
      { title: 'Total usuarios',    value: d.totalUsuarios.toLocaleString('es-EC'),     tooltip: 'Usuarios únicos que han interactuado' },
      { title: 'Conversiones',      value: d.totalConversiones.toLocaleString('es-EC'), tooltip: 'Eventos marcados con is_conversion = 1' },
      { title: 'Tasa de conversión',value: d.tasaConversion.toFixed(2) + '%',           tooltip: 'Conversiones sobre el total de sesiones' },
      { title: 'Abandonos',         value: d.totalAbandonos.toLocaleString('es-EC'),    tooltip: 'Eventos marcados con drop_off_flag = 1' },
      { title: 'Total eventos',     value: totalEventos.toLocaleString('es-EC'),        tooltip: 'Filas de fact_eventos: el volumen completo del almacén' },
      { title: 'Semanas cargadas',  value: semanasCargadas.toLocaleString('es-EC'),     tooltip: 'Valores distintos de la columna semana' },
      { title: 'Eventos por sesión',value: this.promedioEventos,                        tooltip: 'Total de eventos dividido entre el total de sesiones' }
    ];
  }

  // ── Gráficos ──────────────────────────────────────────────────────────────

  /**
   * Se llama desde los DOS `next`. El primero que llegue encuentra los canvas
   * aún sin renderizar (están detrás del `*ngIf="!loading"`) y no hace nada;
   * el segundo ya los tiene. El `setTimeout(0)` deja que Angular pinte el DOM
   * antes de que Chart.js mida el contenedor.
   */
  private pintarGraficos(): void {
    if (this.destruido || this.loading || !this.series) return;
    clearTimeout(this.temporizadorPintado);
    this.temporizadorPintado = setTimeout(() => {
      // La pantalla puede haberse abandonado entre que se programó el pintado y
      // que le toca correr: las peticiones de analítica tardan segundos y no se
      // cancelan al salir, así que su `next` sigue llegando a un componente ya
      // destruido. Sin esta guarda se creaban SIETE gráficos sobre canvas que
      // ya no están en el documento, y como `ngOnDestroy` ya pasó, nadie los
      // destruye nunca: cada visita interrumpida dejaba siete instancias de
      // Chart.js vivas con sus observadores y sus datos.
      if (this.destruido) return;
      this.destruirGraficos();
      const s = this.series!;
      this.graficoVolumenSemanal(s.semanal);
      this.graficoConversionSemanal(s.semanal);
      this.graficoBarras(this.accionesCanvas,    s.acciones,              'y');
      this.graficoDuracion(s.duracion);
      this.graficoBarras(this.canalCanvas,       s.eventosPorCanal,       'y');
      this.graficoBarras(this.dispositivoCanvas, s.eventosPorDispositivo, 'y');
      this.graficoBarras(this.regionCanvas,      s.eventosPorRegion,      'y');
    }, 0);
  }

  private destruirGraficos(): void {
    this.charts.forEach(c => c.destroy());
    this.charts = [];
  }

  /** Cromo común: rejilla de un pelo, sólida, y ejes en texto secundario. */
  private ejeY(titulo?: string): any {
    return {
      beginAtZero: true,
      grid: { color: this.REJILLA, drawTicks: false },
      border: { display: false },
      title: titulo ? { display: true, text: titulo, color: this.EJE_TEXTO, font: { size: 11 } } : undefined,
      ticks: {
        color: this.EJE_TEXTO,
        font: { size: 11 },
        padding: 8,
        callback: (v: any) => Number(v).toLocaleString('es-EC')
      }
    };
  }

  private ejeX(): any {
    return {
      grid: { display: false },
      border: { color: this.REJILLA },
      // `autoSkip` es lo que hace que el eje AGUANTE EL CRECIMIENTO: con 28
      // semanas caben todas las etiquetas; con 52 Chart.js las va saltando
      // solo. No hay ningún número de semanas escrito en el código.
      ticks: { color: this.EJE_TEXTO, font: { size: 10 }, autoSkip: true, maxRotation: 0 }
    };
  }

  /**
   * GRÁFICO 1 — «¿Cuánto ha crecido el almacén y cómo se reparte por semana?»
   * Columnas, una serie, un tono. Es el gráfico donde la semana 27 (19 filas,
   * eventos reales de la tienda) se ve como el hueco que es.
   */
  private graficoVolumenSemanal(serie: PuntoSemanal[]): void {
    const cv = this.volumenCanvas?.nativeElement;
    if (!cv || !serie.length) return;

    this.crear(cv, {
      type: 'bar',
      data: {
        labels: serie.map(p => 'S' + p.semana),
        datasets: [{
          label: 'Eventos',
          data: serie.map(p => p.eventos),
          backgroundColor: this.SERIE,
          borderRadius: 4,
          borderSkipped: 'start',
          maxBarThickness: 24,
          categoryPercentage: 0.86,
          barPercentage: 0.9
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (it: any) => 'Semana ' + serie[it[0].dataIndex].semana,
              label: (it: any) => {
                const p = serie[it.dataIndex];
                return [
                  `${p.eventos.toLocaleString('es-EC')} eventos`,
                  `${p.sesiones.toLocaleString('es-EC')} sesiones`
                ];
              }
            }
          }
        },
        scales: { x: this.ejeX(), y: this.ejeY('Eventos') }
      }
    });
  }

  /**
   * GRÁFICO 2 — «¿La conversión se mantiene estable semana a semana?»
   * Línea de una serie. REEMPLAZA al gráfico que estaba fabricado.
   *
   * Dos decisiones que lo hacen legible:
   *  · el eje NO va de 0 a 100 —la razón por la que el anterior se veía
   *    vacío— sino a un dominio ajustado a los datos con un margen de 2
   *    puntos, así que se ve la variación real de 21,6 % a 29,4 %;
   *  · las semanas que no llegan al mínimo de sesiones entran como `null` y
   *    la línea las salta (`spanGaps`). Pintar el 21,05 % de la semana 27,
   *    que sale de 19 sesiones, junto a semanas de ~18.000 sería un bache
   *    inventado. La salvedad se pinta bajo el título.
   */
  private graficoConversionSemanal(serie: PuntoSemanal[]): void {
    const cv = this.conversionCanvas?.nativeElement;
    if (!cv || !serie.length) return;

    const medibles = serie.filter(p => p.medible).map(p => p.tasa);
    if (!medibles.length) return;
    const min = Math.max(0, Math.floor(Math.min(...medibles) - 2));
    const max = Math.ceil(Math.max(...medibles) + 2);

    this.crear(cv, {
      type: 'line',
      data: {
        labels: serie.map(p => 'S' + p.semana),
        datasets: [{
          label: 'Tasa de conversión',
          data: serie.map(p => (p.medible ? p.tasa : null)),
          borderColor: this.SERIE,
          backgroundColor: this.SERIE_WASH,
          borderWidth: 2,
          tension: 0.3,
          fill: true,
          spanGaps: true,
          pointRadius: 4,
          pointHoverRadius: 6,
          pointBackgroundColor: this.SERIE,
          // Anillo de 2 px del color de la superficie: el punto sigue
          // legible donde la línea lo cruza.
          pointBorderColor: this.SUPERFICIE,
          pointBorderWidth: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              title: (it: any) => 'Semana ' + serie[it[0].dataIndex].semana,
              label: (it: any) => {
                const p = serie[it.dataIndex];
                if (!p.medible) {
                  return `Sin base suficiente (${p.sesiones.toLocaleString('es-EC')} sesiones)`;
                }
                return [
                  `${p.tasa.toFixed(2)} % de conversión`,
                  `${p.conversiones.toLocaleString('es-EC')} de ${p.sesiones.toLocaleString('es-EC')} sesiones`
                ];
              }
            }
          }
        },
        scales: {
          x: this.ejeX(),
          y: {
            ...this.ejeY('% de sesiones'),
            beginAtZero: false,
            min, max,
            ticks: {
              color: this.EJE_TEXTO,
              font: { size: 11 },
              padding: 8,
              callback: (v: any) => Number(v).toFixed(0) + ' %'
            }
          }
        }
      }
    });
  }

  /**
   * GRÁFICOS 3, 5, 6 y 7 — los desgloses. Barras HORIZONTALES porque las
   * etiquetas son palabras («add_to_cart», «desktop»), y en vertical se
   * girarían. Una serie, un tono, y la cifra escrita en la punta.
   */
  private graficoBarras(
    ref: ElementRef<HTMLCanvasElement> | undefined,
    datos: GrupoConteo[],
    eje: 'x' | 'y'
  ): void {
    const cv = ref?.nativeElement;
    if (!cv || !datos?.length) return;

    this.crear(cv, {
      type: 'bar',
      data: {
        labels: datos.map(g => g.nombre),
        datasets: [{
          label: 'Eventos',
          data: datos.map(g => g.total),
          backgroundColor: this.SERIE,
          borderRadius: 4,
          borderSkipped: 'start',
          maxBarThickness: 24,
          categoryPercentage: 0.8,
          barPercentage: 0.9
        }]
      },
      options: {
        indexAxis: eje,
        responsive: true,
        maintainAspectRatio: false,
        // Sitio a la derecha para la cifra de la punta.
        layout: { padding: { right: 64 } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => `${Number(it.raw).toLocaleString('es-EC')} eventos`
            }
          }
        },
        scales: {
          x: { display: false, beginAtZero: true },
          y: {
            grid: { display: false },
            border: { display: false },
            ticks: { color: this.EJE_TEXTO, font: { size: 11 } }
          }
        }
      }
    }, [this.valorEnPunta]);
  }

  /**
   * GRÁFICO 4 — «¿Cuánto duran los eventos?»
   * El ÚNICO con rampa: los cinco tramos tienen orden, y barajarlos cambiaría
   * el significado. Por eso van de claro a oscuro y NO se ordenan por tamaño:
   * ordenarlos por volumen convertiría una escala en un ranking.
   */
  private graficoDuracion(datos: GrupoConteo[]): void {
    const cv = this.duracionCanvas?.nativeElement;
    if (!cv || !datos?.length) return;

    this.crear(cv, {
      type: 'bar',
      data: {
        labels: datos.map(g => g.nombre),
        datasets: [{
          label: 'Eventos',
          data: datos.map(g => g.total),
          backgroundColor: datos.map((_, i) => this.RAMPA_ORDINAL[i] ?? this.SERIE),
          borderRadius: 4,
          borderSkipped: 'start',
          maxBarThickness: 28,
          categoryPercentage: 0.82,
          barPercentage: 0.9
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        layout: { padding: { top: 20 } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => `${Number(it.raw).toLocaleString('es-EC')} eventos`
            }
          }
        },
        scales: {
          x: {
            grid: { display: false },
            border: { color: this.REJILLA },
            ticks: { color: this.EJE_TEXTO, font: { size: 10 }, maxRotation: 0, autoSkip: false }
          },
          y: { display: false, beginAtZero: true }
        }
      }
    }, [this.valorEnPunta]);
  }

  private crear(cv: HTMLCanvasElement, cfg: ChartConfiguration, plugins: Plugin<any>[] = []): void {
    this.charts.push(new Chart(cv, { ...cfg, plugins } as ChartConfiguration));
  }

  // ── Ayudas de plantilla ───────────────────────────────────────────────────

  /** Texto de la salvedad de la serie de conversión. Vacío si no hay ninguna. */
  get avisoSemanas(): string {
    if (!this.semanasNoMedibles.length) return '';
    const lista = this.semanasNoMedibles
      .map(p => `S${p.semana} (${p.sesiones.toLocaleString('es-EC')} sesiones)`)
      .join(', ');
    return `Fuera de la línea por base insuficiente (mínimo ${this.minSesionesTasa.toLocaleString('es-EC')} sesiones): ${lista}.`;
  }

  get totalSemanas(): number {
    return this.series?.semanal?.length ?? 0;
  }
}
