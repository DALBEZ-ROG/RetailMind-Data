import { CommonModule } from '@angular/common';
import {
  AfterViewInit, Component, ElementRef, NgZone, OnDestroy, OnInit, ViewChild
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {
  BarController, BarElement,
  CategoryScale, Chart, ChartConfiguration,
  Filler, LinearScale,
  LineController, LineElement, PointElement,
  Tooltip
} from 'chart.js';

import {
  BloquePanorama, EstadoAlmacen, KpiPanorama, Panorama
} from '../../../core/models/panorama.model';
import { PanoramaService } from '../../../core/services/panorama.service';
import { mensajeError } from '../../../core/services/api-error.util';

// Sólo lo que se usa, igual que en la analítica legada. `Legend` queda FUERA a
// propósito: los seis gráficos son de UNA serie y una caja de leyenda con un
// solo color repite el título y gasta espacio.
Chart.register(
  BarController, BarElement,
  LineController, LineElement, PointElement,
  CategoryScale, LinearScale,
  Tooltip, Filler
);

/** Un indicador ya FORMATEADO. Se calcula una vez, no en un getter (§8.6). */
interface KpiVista {
  etiqueta: string;
  valor: string;
  /** El número entero y sin abreviar, para el `title` nativo (§18). */
  exacto: string;
  nota: string;
}

/**
 * PANORAMA DEL NEGOCIO — la foto de conjunto del comercio, sobre el almacén.
 *
 * <h2>Qué pantalla es esta y cuál NO es</h2>
 * No es un octavo tablero. Los siete tableros responden por su ámbito y se
 * filtran; los 73 informes son de detalle. Faltaba la primera pregunta:
 * <b>¿de qué tamaño es este comercio y está sano?</b> Por eso aquí NO hay
 * filtros: la ventana es la década completa, y cada gráfico responde UNA
 * pregunta escrita encima de él.
 *
 * <h2>Por qué las cinco reglas del patrón CRUD no aplican</h2>
 * {@code docs/UNIFORMIDAD_CIERRE.md} («Fuera de la tabla») excluye
 * explícitamente los tableros y toda la analítica de ClickHouse: son de SOLO
 * LECTURA —no crean, no editan, no borran— y un patrón de formularios de
 * gestión no les aplica. Aquí no hay grilla de mantenimiento, ni barra de
 * acciones, ni chip de modo, ni confirmación: no habría sobre qué.
 *
 * <h2>Decisiones de dibujo</h2>
 * <ul>
 *   <li><b>Una serie por gráfico y un solo tono</b> (#3949ab). Con una sola
 *       serie el color NO porta información, así que §9.bis.25 se cumple por
 *       construcción: lo que distingue cada barra es su etiqueta del eje.</li>
 *   <li><b>Rótulos con `title` NATIVO</b>, nunca `matTooltip` (§18): en una
 *       pantalla con 120 puntos por serie, poblarla de directivas vivas es lo
 *       que dejó de responder al navegador en los tableros.</li>
 *   <li><b>El eje vertical arranca en cero</b> salvo en las dos series de
 *       porcentaje, que se acotan a su rango real y lo DECLARAN en la tarjeta:
 *       recortar el eje de una serie de dinero convierte un 2 % en un
 *       acantilado.</li>
 *   <li><b>Nada se recalcula en un getter</b> (§8.6): los indicadores y las
 *       cifras del almacén se formatean una vez, en el `next` de la carga.</li>
 * </ul>
 */
@Component({
  selector: 'app-panorama',
  standalone: true,
  imports: [
    CommonModule, MatIconModule, MatButtonModule,
    MatProgressSpinnerModule, MatSnackBarModule
  ],
  templateUrl: './panorama.component.html',
  styleUrls: ['../tableros/tableros.scss', './panorama.scss']
})
export class PanoramaComponent implements OnInit, AfterViewInit, OnDestroy {

  @ViewChild('cvVenta')        cvVenta?:        ElementRef<HTMLCanvasElement>;
  @ViewChild('cvFlujos')       cvFlujos?:       ElementRef<HTMLCanvasElement>;
  @ViewChild('cvCategorias')   cvCategorias?:   ElementRef<HTMLCanvasElement>;
  @ViewChild('cvMargen')       cvMargen?:       ElementRef<HTMLCanvasElement>;
  @ViewChild('cvPuntualidad')  cvPuntualidad?:  ElementRef<HTMLCanvasElement>;
  @ViewChild('cvKardex')       cvKardex?:       ElementRef<HTMLCanvasElement>;

  /** Un solo tono para las seis. El color no distingue nada aquí. */
  private readonly SERIE = '#3949ab';
  private readonly RELLENO = 'rgba(57, 73, 171, 0.12)';
  private readonly REJILLA = 'rgba(26, 35, 126, 0.08)';

  private graficos: Chart[] = [];
  private vistaLista = false;
  /** Listener de `resize` propio — ver `escucharRedimensionado()`. */
  private alRedimensionar?: () => void;
  private temporizadorResize?: ReturnType<typeof setTimeout>;
  private temporizadorPintado?: ReturnType<typeof setTimeout>;
  /** Cierra la puerta a los `next` que llegan después de salir de la pantalla. */
  private destruido = false;

  cargando = true;
  datos: Panorama | null = null;
  kpis: KpiVista[] = [];
  almacen: EstadoAlmacen | null = null;
  /** true si la última corrida validó los 49 controles sin fallos. */
  almacenSano = false;
  filasPublicadas = '';

  /**
   * Las preguntas. Van en el código y no en el backend porque son de
   * PRESENTACIÓN: el backend manda el dato y su denominador; la pantalla dice
   * para qué se mira. Un bloque sin pregunta no se pinta.
   */
  private readonly PREGUNTAS: Record<string, string> = {
    venta_mensual:       '¿Cómo ha crecido la venta en diez años?',
    flujos_dinero:       '¿Qué tamaño tiene cada flujo de dinero del comercio?',
    categorias:          '¿Qué categorías sostienen la venta?',
    margen_mensual:      '¿El margen aguanta el crecimiento?',
    puntualidad_mensual: '¿La operación sigue cumpliendo la promesa al crecer?',
    kardex_mensual:      '¿Cuánta mercancía mueve físicamente el almacén?'
  };

  constructor(private servicio: PanoramaService,
              private snack: MatSnackBar,
              private zona: NgZone) {}

  ngOnInit(): void { this.cargar(); }

  ngAfterViewInit(): void {
    this.vistaLista = true;
    this.escucharRedimensionado();
    this.pintar();
  }

  ngOnDestroy(): void {
    this.destruido = true;
    if (this.alRedimensionar) {
      window.removeEventListener('resize', this.alRedimensionar);
      this.alRedimensionar = undefined;
    }
    clearTimeout(this.temporizadorResize);
    clearTimeout(this.temporizadorPintado);
    this.destruir();
  }

  /**
   * Re-dibuja los gráficos cuando cambia el tamaño de la ventana.
   *
   * <h3>Por qué hace falta si `responsive: true` ya está puesto</h3>
   * Medido: al estrechar el contenedor de 1.841 a 600 px, el ancho CSS del
   * canvas sigue al contenedor —eso lo hace `canvas { max-width: 100% }`— pero
   * el MAPA DE BITS se queda en 1.841. Es decir, la imagen se ESCALA y el
   * gráfico se ve aplastado en horizontal hasta que se recarga la página; no se
   * vuelve a maquetar. El observador propio de Chart.js no lo corrige aquí, y
   * la analítica legada tiene exactamente el mismo comportamiento (verificado:
   * su mapa de bits también se queda en 858 mientras el CSS baja a 600). Esto
   * pone a esta pantalla por encima de esa paridad, y SOLO en esta pantalla:
   * no se toca el `/dashboard` legado ni los siete tableros.
   *
   * <h3>Tres cuidados</h3>
   * <ol>
   *   <li><b>Fuera de la zona de Angular</b>: `resize` se dispara decenas de
   *       veces por segundo y dentro de la zona lanzaría una detección de
   *       cambios por evento, que es peor que el problema que arregla.</li>
   *   <li><b>Con retardo de 150 ms</b>: se re-dibuja al soltar, no durante el
   *       arrastre — seis gráficos por evento sería un tirón continuo.</li>
   *   <li><b>Sobre el array VIVO</b>: `pintar()` destruye y recrea, así que se
   *       recorre `this.graficos` en el momento de ejecutarse y nunca una copia
   *       capturada antes; llamar a `resize()` sobre un gráfico destruido
   *       reventaría.</li>
   * </ol>
   */
  private escucharRedimensionado(): void {
    this.zona.runOutsideAngular(() => {
      this.alRedimensionar = () => {
        clearTimeout(this.temporizadorResize);
        this.temporizadorResize = setTimeout(() => {
          this.graficos.forEach(g => g.resize());
        }, 150);
      };
      window.addEventListener('resize', this.alRedimensionar);
    });
  }

  cargar(): void {
    this.cargando = true;
    this.servicio.obtener().subscribe({
      next: d => {
        this.datos = d;
        this.kpis = (d.kpis ?? []).map(k => this.formatearKpi(k));
        this.almacen = d.almacen ?? null;
        this.almacenSano = !!this.almacen
          && this.almacen.validacion === 'exito'
          && this.almacen.tablasConFallo === 0;
        this.filasPublicadas = this.almacen
          ? this.numero(this.almacen.filasPublicadas) : '';
        this.cargando = false;
        this.pintar();
      },
      error: e => {
        this.cargando = false;
        this.snack.open(
          mensajeError(e, 'No se pudo cargar el panorama del negocio.'),
          'Cerrar', { duration: 6000 });
      }
    });
  }

  bloque(id: string): BloquePanorama | undefined {
    return this.datos?.bloques?.find(b => b.id === id);
  }

  pregunta(id: string): string { return this.PREGUNTAS[id] ?? ''; }

  // ── Formateo ────────────────────────────────────────────────────────────

  private formatearKpi(k: KpiPanorama): KpiVista {
    const n = Number(k.valor ?? 0);
    let valor: string;
    let exacto: string;
    if (k.tipo === 'moneda') {
      exacto = this.moneda(n);
      // Por encima del millón se abrevia: a 27 px un importe de doce dígitos
      // se sale de la tarjeta, y la fila entera perdería su línea de base.
      // El número exacto no se pierde — va en el `title`.
      valor = Math.abs(n) >= 1_000_000
        ? '$' + (n / 1_000_000).toLocaleString('es-EC', {
            minimumFractionDigits: 1, maximumFractionDigits: 1 }) + ' M'
        : exacto;
    } else if (k.tipo === 'porcentaje') {
      valor = n.toLocaleString('es-EC', {
        minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' %';
      exacto = valor;
    } else {
      valor = this.numero(n);
      exacto = valor;
    }
    return { etiqueta: k.etiqueta, valor, exacto, nota: k.nota };
  }

  private moneda(n: number): string {
    return '$' + n.toLocaleString('es-EC', {
      minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }

  private numero(n: number | string): string {
    return Number(n ?? 0).toLocaleString('es-EC');
  }

  // ── Gráficos ────────────────────────────────────────────────────────────

  /**
   * Se llama desde el `next` y desde `ngAfterViewInit`. El primero que ocurra
   * encuentra los canvas detrás del `*ngIf="!cargando"` y no hace nada; el
   * segundo ya los tiene. El `setTimeout(0)` deja que Angular pinte el DOM
   * antes de que Chart.js mida el contenedor — sin él, mide 0 y el gráfico
   * sale de un píxel de alto.
   */
  private pintar(): void {
    if (this.destruido || !this.vistaLista || this.cargando || !this.datos) return;
    clearTimeout(this.temporizadorPintado);
    this.temporizadorPintado = setTimeout(() => {
      // Se puede haber salido de la pantalla entre que se programó el pintado y
      // que corre: la petición del panorama tarda segundos y no se cancela al
      // salir. Sin esta guarda se creaban SEIS gráficos sobre canvas ya
      // desprendidos del documento y, con `ngOnDestroy` pasado, no los destruía
      // nadie.
      if (this.destruido) return;
      this.destruir();
      this.serieVenta();
      this.barrasFlujos();
      this.barrasCategorias();
      this.seriePorcentaje(this.cvMargen, 'margen_mensual', 'pct_margen', 'Margen');
      this.seriePorcentaje(this.cvPuntualidad, 'puntualidad_mensual', 'pct', 'A tiempo');
      this.barrasKardex();
    }, 0);
  }

  private destruir(): void {
    this.graficos.forEach(g => g.destroy());
    this.graficos = [];
  }

  private crear(cv: HTMLCanvasElement, cfg: ChartConfiguration): void {
    this.graficos.push(new Chart(cv, cfg));
  }

  /** Ejes comunes: rejilla tenue, sin borde, etiquetas que no se amontonan. */
  private ejeCategoria(maxTicks: number) {
    return {
      grid: { display: false },
      border: { display: false },
      ticks: {
        autoSkip: true, maxTicksLimit: maxTicks, maxRotation: 0,
        color: '#5c6bc0', font: { size: 10 }
      }
    };
  }

  private ejeValor(formato: (v: any) => string, empiezaEnCero = true) {
    return {
      beginAtZero: empiezaEnCero,
      grid: { color: this.REJILLA },
      border: { display: false },
      ticks: { color: '#5c6bc0', font: { size: 10 }, callback: formato }
    };
  }

  /** G1 — la historia de la década: 120 puntos, sin marcadores. */
  private serieVenta(): void {
    const b = this.bloque('venta_mensual');
    const cv = this.cvVenta?.nativeElement;
    if (!cv || !b?.items?.length) return;
    this.crear(cv, {
      type: 'line',
      data: {
        labels: b.items.map(i => String(i['periodo'])),
        datasets: [{
          label: 'Venta',
          data: b.items.map(i => Number(i['venta'])),
          borderColor: this.SERIE,
          backgroundColor: this.RELLENO,
          borderWidth: 2,
          // Con 120 puntos los marcadores se tocan y forman una banda sólida;
          // aparecen sólo al pasar por encima.
          pointRadius: 0,
          pointHoverRadius: 4,
          pointBackgroundColor: this.SERIE,
          fill: true,
          tension: 0.25
        }]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => {
                const fila = b.items[it.dataIndex];
                return `${this.moneda(Number(it.raw))} · `
                     + `${this.numero(fila['pedidos'])} pedidos`;
              }
            }
          }
        },
        scales: {
          x: this.ejeCategoria(12),
          y: this.ejeValor(v => '$' + (Number(v) / 1_000_000).toFixed(1) + ' M')
        }
      }
    });
  }

  /** G2 — cuatro barras horizontales: la escala relativa se lee de un vistazo. */
  private barrasFlujos(): void {
    const b = this.bloque('flujos_dinero');
    const cv = this.cvFlujos?.nativeElement;
    if (!cv || !b?.items?.length) return;
    this.crear(cv, {
      type: 'bar',
      data: {
        labels: b.items.map(i => String(i['concepto'])),
        datasets: [{
          label: 'Monto',
          data: b.items.map(i => Number(i['monto'])),
          backgroundColor: this.SERIE,
          borderRadius: 4,
          maxBarThickness: 26
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true, maintainAspectRatio: false,
        layout: { padding: { right: 16 } },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => {
                const fila = b.items[it.dataIndex];
                return `${this.moneda(Number(it.raw))} · ${fila['origen']}`;
              }
            }
          }
        },
        scales: {
          x: this.ejeValor(v => '$' + (Number(v) / 1_000_000).toFixed(0) + ' M'),
          y: { grid: { display: false }, border: { display: false },
               ticks: { color: '#5c6bc0', font: { size: 11 } } }
        }
      }
    });
  }

  /** G3 — ranking de 10 categorías. */
  private barrasCategorias(): void {
    const b = this.bloque('categorias');
    const cv = this.cvCategorias?.nativeElement;
    if (!cv || !b?.items?.length) return;
    this.crear(cv, {
      type: 'bar',
      data: {
        labels: b.items.map(i => String(i['categoria'])),
        datasets: [{
          label: 'Venta neta',
          data: b.items.map(i => Number(i['venta'])),
          backgroundColor: this.SERIE,
          borderRadius: 4,
          maxBarThickness: 20
        }]
      },
      options: {
        indexAxis: 'y',
        responsive: true, maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => {
                const fila = b.items[it.dataIndex];
                return `${this.moneda(Number(it.raw))} · `
                     + `${this.numero(fila['unidades'])} uds · `
                     + `margen ${fila['pct_margen']} %`;
              }
            }
          }
        },
        scales: {
          x: this.ejeValor(v => '$' + (Number(v) / 1_000_000).toFixed(0) + ' M'),
          y: { grid: { display: false }, border: { display: false },
               ticks: { color: '#5c6bc0', font: { size: 10 } } }
        }
      }
    });
  }

  /**
   * G4 y G5 — las dos series de porcentaje. Son el ÚNICO sitio donde el eje no
   * arranca en cero, y la tarjeta lo dice: con 0-100 una variación de dos
   * puntos sobre veinte se vuelve una línea plana que no informa de nada. El
   * rango se calcula de los datos con un margen, nunca a ojo.
   */
  private seriePorcentaje(ref: ElementRef<HTMLCanvasElement> | undefined,
                          idBloque: string, campo: string, etiqueta: string): void {
    const b = this.bloque(idBloque);
    const cv = ref?.nativeElement;
    if (!cv || !b?.items?.length) return;
    const valores = b.items.map(i => Number(i[campo]));
    const min = Math.min(...valores);
    const max = Math.max(...valores);
    const holgura = Math.max(1, (max - min) * 0.25);
    this.crear(cv, {
      type: 'line',
      data: {
        labels: b.items.map(i => String(i['periodo'])),
        datasets: [{
          label: etiqueta,
          data: valores,
          borderColor: this.SERIE,
          backgroundColor: this.RELLENO,
          borderWidth: 2,
          pointRadius: 0,
          pointHoverRadius: 4,
          pointBackgroundColor: this.SERIE,
          fill: true,
          tension: 0.25
        }]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => {
                const fila = b.items[it.dataIndex];
                const base = fila['medibles'] !== undefined
                  ? ` · ${this.numero(fila['medibles'])} envíos medidos` : '';
                return `${etiqueta}: ${Number(it.raw).toFixed(2)} %${base}`;
              }
            }
          }
        },
        scales: {
          x: this.ejeCategoria(12),
          y: {
            ...this.ejeValor(v => Number(v).toFixed(0) + ' %', false),
            suggestedMin: Math.max(0, min - holgura),
            suggestedMax: max + holgura
          }
        }
      }
    });
  }

  /** G6 — el pulso físico del almacén: 8,0 M de movimientos resumidos por mes. */
  private barrasKardex(): void {
    const b = this.bloque('kardex_mensual');
    const cv = this.cvKardex?.nativeElement;
    if (!cv || !b?.items?.length) return;
    this.crear(cv, {
      type: 'bar',
      data: {
        labels: b.items.map(i => String(i['periodo'])),
        datasets: [{
          label: 'Unidades',
          data: b.items.map(i => Number(i['unidades'])),
          backgroundColor: this.SERIE,
          borderRadius: 2,
          maxBarThickness: 14
        }]
      },
      options: {
        responsive: true, maintainAspectRatio: false,
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (it: any) => {
                const fila = b.items[it.dataIndex];
                return `${this.numero(it.raw)} uds · `
                     + `${this.numero(fila['movimientos'])} movimientos`;
              }
            }
          }
        },
        scales: {
          x: this.ejeCategoria(12),
          y: this.ejeValor(v => (Number(v) / 1000).toFixed(0) + ' k')
        }
      }
    });
  }
}
