import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

/** Un punto de la serie que envía el backend: observado O previsto, nunca los dos. */
export interface PuntoPrevision {
  periodo: string;
  real: number | null;
  previsto: number | null;
  limiteInferior: number | null;
  limiteSuperior: number | null;
  truncado: boolean;
}

/**
 * El gráfico de la PREVISIÓN DE DEMANDA — regla 1 de §5.1.9: **la serie
 * histórica y la previsión van en el MISMO gráfico**. «Un número solo, sin la
 * serie que lo produjo, no es interpretable.»
 *
 * <h2>Por qué un componente propio y no el de los tableros</h2>
 * `TableroGraficoComponent` dibuja bloques de tablero y su entrada es un
 * `BloqueTablero`. Aquí hacen falta tres trazos que ningún bloque tiene: una
 * línea continua para lo observado, una DISCONTINUA para lo previsto y un área
 * de banda por debajo. Adaptar aquel componente significaría meterle un tipo de
 * visualización que solo usa esta pantalla, y con él la lógica de la banda, en
 * un archivo que ya sirve a siete tableros.
 *
 * SVG a mano, como en los tableros y por el mismo motivo: se calcula una vez en
 * `ngOnChanges`, no hay canvas que destruir al cambiar de filtro y el resultado
 * es inspeccionable en el DOM. Los rótulos son `<title>` NATIVO y no
 * `matTooltip`: con una directiva por punto el navegador se arrastra.
 *
 * <h2>Tres decisiones de dibujo que son afirmaciones sobre el dato</h2>
 * <ol>
 *   <li><b>El eje vertical arranca en CERO.</b> Recortarlo convierte una
 *       variación del 5 % en un acantilado, y aquí lo que se mira es
 *       precisamente la magnitud de un cambio mensual.</li>
 *   <li><b>La banda arranca en el último punto OBSERVADO</b>, con anchura cero
 *       allí. Es donde la incertidumbre empieza; dibujarla despegada del
 *       histórico haría parecer que el primer mes previsto ya nace con un
 *       error que no depende de lo anterior.</li>
 *   <li><b>El mes truncado se marca y NO se disimula.</b> Va con un punto
 *       hueco y su rótulo. Una caída de fin de serie que es un artefacto del
 *       corte de los datos se lee, si no, como una caída del negocio — que es
 *       exactamente la decisión equivocada que esta pantalla debe evitar.</li>
 * </ol>
 */
@Component({
  selector: 'app-prevision-grafico',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './prevision-grafico.component.html',
  styleUrls: ['./informes.scss']
})
export class PrevisionGraficoComponent implements OnChanges {

  @Input() puntos: PuntoPrevision[] = [];
  /** Nombre de la serie que se está viendo (total o categoría). */
  @Input() serie = '';

  readonly ancho = 1200;
  readonly alto = 340;
  readonly margen = { arriba: 18, derecha: 24, abajo: 52, izquierda: 74 };

  vacio = true;

  ejeX: { etiqueta: string; x: number }[] = [];
  ejeY: { etiqueta: string; y: number }[] = [];

  /** Trazo continuo de lo observado. */
  historico = '';
  /** Trazo discontinuo de lo previsto, enganchado al último observado. */
  prevision = '';
  /** Polígono de la banda del 80 %. */
  banda = '';

  puntosHistoricos: { cx: number; cy: number; titulo: string; truncado: boolean }[] = [];
  puntosPrevistos: { cx: number; cy: number; titulo: string }[] = [];
  /** Rótulo del mes incompleto, cuando lo hay. */
  marcaTruncado: { x: number; y: number } | null = null;

  get areaAncho(): number {
    return this.ancho - this.margen.izquierda - this.margen.derecha;
  }

  get areaAlto(): number {
    return this.alto - this.margen.arriba - this.margen.abajo;
  }

  get base(): number {
    return this.margen.arriba + this.areaAlto;
  }

  ngOnChanges(): void {
    this.reiniciar();
    const puntos = this.puntos ?? [];
    this.vacio = puntos.length < 2;
    if (this.vacio) { return; }

    const maximo = Math.max(
      ...puntos.map(p => Math.max(
        Number(p.real ?? 0), Number(p.previsto ?? 0), Number(p.limiteSuperior ?? 0))),
      1);
    // El eje arranca en cero SIEMPRE (decisión 1 de la cabecera).
    const tope = maximo * 1.08;
    const x = (i: number) => this.margen.izquierda
      + (puntos.length === 1 ? 0 : (i * this.areaAncho) / (puntos.length - 1));
    const y = (v: number) => this.base - (v / tope) * this.areaAlto;

    this.escalaY(tope, y);
    this.escalaX(puntos, x);

    // ── Trazo observado ────────────────────────────────────────────────
    const observados: { i: number; v: number }[] = [];
    puntos.forEach((p, i) => {
      if (p.real !== null && p.real !== undefined) {
        observados.push({ i, v: Number(p.real) });
      }
    });
    this.historico = observados
      .map((o, k) => `${k === 0 ? 'M' : 'L'}${x(o.i).toFixed(1)},${y(o.v).toFixed(1)}`)
      .join(' ');
    observados.forEach(o => this.puntosHistoricos.push({
      cx: x(o.i), cy: y(o.v),
      titulo: `${puntos[o.i].periodo} · ${this.uds(o.v)} observadas`
        + (puntos[o.i].truncado ? ' · MES INCOMPLETO' : ''),
      truncado: !!puntos[o.i].truncado
    }));

    const ultimo = observados.length ? observados[observados.length - 1] : null;
    if (ultimo && puntos[ultimo.i].truncado) {
      this.marcaTruncado = { x: x(ultimo.i), y: y(ultimo.v) };
    }

    // ── Trazo previsto, enganchado al último observado ─────────────────
    const previstos: { i: number; v: number; lo: number; hi: number }[] = [];
    puntos.forEach((p, i) => {
      if (p.previsto !== null && p.previsto !== undefined) {
        previstos.push({
          i, v: Number(p.previsto),
          lo: Number(p.limiteInferior ?? p.previsto),
          hi: Number(p.limiteSuperior ?? p.previsto)
        });
      }
    });
    if (!previstos.length) { return; }

    const arranque = ultimo
      ? [`M${x(ultimo.i).toFixed(1)},${y(ultimo.v).toFixed(1)}`]
      : [];
    this.prevision = arranque
      .concat(previstos.map((p, k) =>
        `${!arranque.length && k === 0 ? 'M' : 'L'}${x(p.i).toFixed(1)},${y(p.v).toFixed(1)}`))
      .join(' ');

    // La banda nace SIN anchura en el último observado (decisión 2).
    const superior = previstos.map(p => `L${x(p.i).toFixed(1)},${y(p.hi).toFixed(1)}`);
    const inferior = [...previstos].reverse()
      .map(p => `L${x(p.i).toFixed(1)},${y(p.lo).toFixed(1)}`);
    const origen = ultimo
      ? `M${x(ultimo.i).toFixed(1)},${y(ultimo.v).toFixed(1)}`
      : `M${x(previstos[0].i).toFixed(1)},${y(previstos[0].v).toFixed(1)}`;
    this.banda = [origen, ...superior, ...inferior, 'Z'].join(' ');

    previstos.forEach(p => this.puntosPrevistos.push({
      cx: x(p.i), cy: y(p.v),
      titulo: `${puntos[p.i].periodo} · previsión ${this.uds(p.v)}`
        + ` · banda 80 % ${this.uds(p.lo)} – ${this.uds(p.hi)}`
    }));
  }

  private escalaY(tope: number, y: (v: number) => number): void {
    for (let k = 0; k <= 4; k++) {
      const valor = (tope * k) / 4;
      this.ejeY.push({ etiqueta: this.uds(valor), y: y(valor) });
    }
  }

  /**
   * Rótulos del eje horizontal: los meses previstos SIEMPRE, y del histórico
   * solo uno de cada N para que no se pisen. Perder el rótulo de un mes
   * previsto sería perder de qué mes habla la cifra que se va a usar.
   */
  private escalaX(puntos: PuntoPrevision[], x: (i: number) => number): void {
    const paso = Math.max(1, Math.ceil(puntos.length / 12));
    puntos.forEach((p, i) => {
      const esPrevision = p.previsto !== null && p.previsto !== undefined;
      if (esPrevision || i % paso === 0 || i === puntos.length - 1) {
        this.ejeX.push({ etiqueta: p.periodo, x: x(i) });
      }
    });
  }

  private uds(valor: number): string {
    return Math.round(valor).toLocaleString('es-EC');
  }

  private reiniciar(): void {
    this.ejeX = [];
    this.ejeY = [];
    this.historico = '';
    this.prevision = '';
    this.banda = '';
    this.puntosHistoricos = [];
    this.puntosPrevistos = [];
    this.marcaTruncado = null;
  }
}
