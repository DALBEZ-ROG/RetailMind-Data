import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

import { BloqueTablero, PresentacionBloque } from '../../../core/models/tablero.model';

/**
 * Dibuja UN bloque de tablero como SVG en línea.
 *
 * <h2>Por qué SVG a mano y no una librería</h2>
 * El proyecto ya trae `chart.js` (lo usa la analítica legada), pero aquí no
 * encaja bien: los bloques se pintan dentro de un `*ngFor` sobre una definición
 * declarativa, y cada gráfico de canvas exigiría una referencia, un ciclo de
 * vida y una destrucción propios. El SVG se calcula una vez en `ngOnChanges` y
 * lo pinta la plantilla; no hay estado que sincronizar, no hay canvas que
 * limpiar al cambiar de filtro, y el resultado es inspeccionable en el DOM.
 * Tampoco entra una dependencia nueva.
 *
 * <h2>Lo que este componente NO decide</h2>
 * Ni el denominador, ni la salvedad, ni qué filas hay: eso lo manda el backend
 * y lo pinta la tarjeta que envuelve al gráfico. Aquí solo hay geometría. Un
 * gráfico que decidiera por su cuenta qué filas enseña sería otra forma de la
 * cifra plausible y equivocada.
 *
 * <h2>Escala</h2>
 * El eje vertical arranca SIEMPRE en cero salvo en el doble eje, donde cada
 * medida tiene su propia escala y se declara en la leyenda. Recortar el eje
 * inferior de una serie de dinero convierte una variación del 2 % en un
 * acantilado, que es el engaño visual más barato que existe.
 */
@Component({
  selector: 'app-tablero-grafico',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './tablero-grafico.component.html',
  styleUrls: ['./tableros.scss']
})
export class TableroGraficoComponent implements OnChanges {

  @Input() bloque!: BloqueTablero;
  @Input() pres!: PresentacionBloque;

  /** Paleta categórica estable: el mismo canal es el mismo color entre bloques. */
  static readonly PALETA = [
    '#1e88e5', '#43a047', '#fb8c00', '#8e24aa', '#e53935',
    '#00acc1', '#fdd835', '#6d4c41', '#3949ab', '#7cb342',
    '#d81b60', '#00897b'
  ];

  // El viewBox se elige con una proporcion cercana a la del contenedor real
  // (~1.335 x 340 en la pantalla de trabajo). Con `preserveAspectRatio` en
  // `meet`, una proporcion mas estrecha que la caja NO estira el dibujo: lo
  // centra y deja franjas vacias a los lados, que fue justo lo que paso con
  // 960x300 dentro de una tarjeta de 1.335 px.
  readonly ancho = 1200;
  readonly alto = 305;
  readonly margen = { arriba: 16, derecha: 56, abajo: 44, izquierda: 74 };

  /**
   * Márgenes propios de las barras HORIZONTALES (embudo y ranking).
   *
   * Son mucho más anchos que los del resto porque en estos dos gráficos el
   * texto vive FUERA de la barra: el nombre del paso a la izquierda y el valor
   * con su tasa a la derecha. Con los márgenes normales, «Pedido creado» salía
   * cortado por la izquierda y «3.907 · 95,7 % del paso anterior» se salía del
   * viewBox por la derecha — un rótulo truncado en un embudo es exactamente la
   * mitad de la información que hace falta para decidir dónde invertir.
   */
  readonly margenPasos = { izquierda: 190, derecha: 300 };

  /**
   * La caja y bigotes necesita MÁS sitio a la izquierda que el embudo: su
   * rótulo compone dos dimensiones («Speed Mail Ecuador · Ecuador (nacional)»)
   * y con el margen del embudo el nombre del transportista sale cortado por
   * delante — justo la parte que identifica la fila.
   */
  readonly margenCajas = 270;

  tipo = 'tabla';
  vacio = true;

  /**
   * Altura del viewBox. Es variable porque un ranking de 25 filas no cabe en
   * la misma caja que un embudo de 5: comprimiéndolo, cada barra quedaría en
   * cuatro píxeles y los nombres se pisarían unos a otros.
   */
  altoVista = 305;

  /** Etiquetas del eje horizontal, en el orden en que aparecen. */
  ejeX: { etiqueta: string; x: number }[] = [];
  /** Marcas del eje vertical izquierdo. */
  ejeY: { etiqueta: string; y: number }[] = [];
  ejeY2: { etiqueta: string; y: number }[] = [];

  lineas: { nombre: string; color: string; d: string;
            puntos: { cx: number; cy: number; titulo: string }[] }[] = [];
  barras: { x: number; y: number; w: number; h: number; color: string;
            nombre: string; titulo: string }[] = [];
  pasos: { etiqueta: string; x: number; y: number; w: number; h: number;
           color: string; valor: string; tasa: string; titulo: string }[] = [];
  puntos: { cx: number; cy: number; r: number; color: string; titulo: string }[] = [];
  cruz: { x: number | null; y: number | null } = { x: null, y: null };
  leyenda: { nombre: string; color: string }[] = [];

  /** `caja_bigotes`: una caja por fila, con sus bigotes y su mediana. */
  cajas: { etiqueta: string; y: number; alto: number;
           bigoteIzq: number; bigoteDer: number;
           x: number; ancho: number; mediana: number; media: number | null;
           color: string; titulo: string }[] = [];

  /** `matriz`: celdas de un mapa de calor, con sus rótulos de fila y columna. */
  celdas: { x: number; y: number; w: number; h: number; color: string;
            texto: string; textoColor: string; titulo: string }[] = [];
  rotulosFila: { etiqueta: string; y: number }[] = [];
  rotulosColumna: { etiqueta: string; x: number; y: number }[] = [];

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
    const filas = this.filasVisibles();
    this.vacio = !filas.length;
    if (this.vacio) { return; }

    this.tipo = this.bloque.visualizacion;
    switch (this.tipo) {
      case 'serie':            this.dibujarSerie(filas); break;
      case 'doble_eje':        this.dibujarDobleEje(filas); break;
      case 'serie_apilada':
      case 'barras_apiladas':
      case 'areas_apiladas':   this.dibujarApiladas(filas); break;
      case 'barras':           this.dibujarBarras(filas); break;
      case 'curva_acumulada':  this.dibujarPareto(filas); break;
      case 'embudo':           this.dibujarEmbudo(filas); break;
      case 'dispersion':       this.dibujarDispersion(filas); break;
      case 'caja_bigotes':     this.dibujarCajas(filas); break;
      case 'matriz':           this.dibujarMatriz(filas); break;
      case 'ranking':          this.dibujarRanking(filas); break;
      // 'semaforo', 'semaforo_tabla' y 'tabla' no dibujan: son la tabla sola.
      default:                 this.vacio = true; break;
    }
  }

  private reiniciar(): void {
    this.ejeX = []; this.ejeY = []; this.ejeY2 = [];
    this.lineas = []; this.barras = []; this.pasos = []; this.puntos = [];
    this.leyenda = []; this.cruz = { x: null, y: null };
    this.cajas = []; this.celdas = [];
    this.rotulosFila = []; this.rotulosColumna = [];
    this.altoVista = this.alto;
  }

  /** Barras horizontales: una fila por elemento, con su alto fijo. */
  private disponerPasos(filas: Record<string, any>[], altoPaso: number): number {
    this.altoVista = this.margen.arriba + filas.length * altoPaso + this.margen.abajo;
    return this.ancho - this.margenPasos.izquierda - this.margenPasos.derecha;
  }

  /**
   * El gráfico puede pintar MENOS filas que las que trae el bloque (un ranking
   * de 387 productos no cabe en una caja), pero nunca otras: siempre son las
   * primeras del orden que fijó el backend, y la tarjeta dice cuántas hay.
   *
   * <b>La DISPERSIÓN es la excepción y se queda con la nube entera.</b> Recortar
   * una nube de puntos no la simplifica: la falsea. La cruz de los cuadrantes
   * es la MEDIANA de todo el conjunto, así que dibujando solo las 40 primeras
   * variantes por venta —todas grandes— la cruz aparece pegada al borde
   * izquierdo y los cuadrantes dejan de corresponder con lo que se ve. El tope
   * de filas sigue valiendo para la TABLA, donde recortar es solo paginar.
   */
  private filasVisibles(): Record<string, any>[] {
    const filas = this.bloque?.items ?? [];
    if (this.bloque?.visualizacion === 'dispersion') {
      return filas;
    }
    const tope = this.pres?.topFilas;
    return tope && filas.length > tope ? filas.slice(0, tope) : filas;
  }

  // ── Series y agrupación ──────────────────────────────────────────────

  private categoriasX(filas: Record<string, any>[], campo: string): string[] {
    const vistas: string[] = [];
    filas.forEach(f => {
      const v = String(f[campo] ?? '');
      if (!vistas.includes(v)) { vistas.push(v); }
    });
    return vistas;
  }

  private color(i: number): string {
    return TableroGraficoComponent.PALETA[i % TableroGraficoComponent.PALETA.length];
  }

  private escalaX(cats: string[]): (i: number) => number {
    const paso = this.areaAncho / Math.max(cats.length, 1);
    return (i: number) => this.margen.izquierda + paso * i + paso / 2;
  }

  private marcasY(max: number, derecha = false): void {
    const marcas = 4;
    const destino = derecha ? this.ejeY2 : this.ejeY;
    for (let i = 0; i <= marcas; i++) {
      const valor = (max / marcas) * i;
      destino.push({
        etiqueta: this.corto(valor),
        y: this.base - (this.areaAlto * i) / marcas
      });
    }
  }

  private marcasX(cats: string[], escala: (i: number) => number): void {
    // Con muchas categorías se rotula una de cada n: 19 meses caben, 40
    // productos no, y unas etiquetas superpuestas no son un eje.
    const salto = Math.ceil(cats.length / 12);
    cats.forEach((c, i) => {
      if (i % salto === 0) {
        this.ejeX.push({ etiqueta: this.recortar(c, 12), x: escala(i) });
      }
    });
  }

  // ── Trazados ─────────────────────────────────────────────────────────

  private dibujarSerie(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const campoV = this.pres.valor!;
    const campoS = this.pres.serie;
    const cats = this.categoriasX(filas, campoX);
    const escala = this.escalaX(cats);
    const series = campoS ? this.categoriasX(filas, campoS) : ['(única)'];

    const max = Math.max(...filas.map(f => this.n(f[campoV])), 0) || 1;
    const y = (v: number) => this.base - (v / max) * this.areaAlto;

    series.forEach((nombre, si) => {
      const propias = filas.filter(f => !campoS || String(f[campoS]) === nombre);
      const puntos = propias.map(f => {
        const i = cats.indexOf(String(f[campoX] ?? ''));
        return {
          cx: escala(i),
          cy: y(this.n(f[campoV])),
          titulo: `${nombre} · ${f[campoX]}: ${this.formato(f[campoV])}`
        };
      });
      if (!puntos.length) { return; }
      this.lineas.push({
        nombre, color: this.color(si), puntos,
        d: puntos.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.cx.toFixed(1)},${p.cy.toFixed(1)}`)
                 .join(' ')
      });
      this.leyenda.push({ nombre, color: this.color(si) });
    });
    this.marcasY(max);
    this.marcasX(cats, escala);
  }

  /**
   * Dos medidas con escalas independientes. Es el único gráfico donde el eje
   * derecho existe, y la leyenda dice qué medida va en cada lado: dos curvas
   * sin esa aclaración invitan a leer un cruce que no significa nada.
   */
  private dibujarDobleEje(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const cats = this.categoriasX(filas, campoX);
    const escala = this.escalaX(cats);

    const medidas = [
      { campo: this.pres.valor!, nombre: this.pres.valorEtiqueta ?? this.pres.valor!, der: false },
      { campo: this.pres.valor2!, nombre: this.pres.valor2Etiqueta ?? this.pres.valor2!, der: true }
    ];

    medidas.forEach((m, mi) => {
      const max = Math.max(...filas.map(f => this.n(f[m.campo])), 0) || 1;
      const y = (v: number) => this.base - (v / max) * this.areaAlto;
      const puntos = filas.map(f => ({
        cx: escala(cats.indexOf(String(f[campoX] ?? ''))),
        cy: y(this.n(f[m.campo])),
        titulo: `${m.nombre} · ${f[campoX]}: ${this.formato(f[m.campo])}`
      }));
      this.lineas.push({
        nombre: m.nombre + (m.der ? ' (eje derecho)' : ' (eje izquierdo)'),
        color: this.color(mi), puntos,
        d: puntos.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.cx.toFixed(1)},${p.cy.toFixed(1)}`)
                 .join(' ')
      });
      this.leyenda.push({
        nombre: m.nombre + (m.der ? ' →' : ' ←'), color: this.color(mi)
      });
      this.marcasY(max, m.der);
    });
    this.marcasX(cats, escala);
  }

  private dibujarApiladas(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const campoV = this.pres.valor!;
    const campoS = this.pres.serie!;
    const cats = this.categoriasX(filas, campoX);
    const series = this.categoriasX(filas, campoS);
    const paso = this.areaAncho / Math.max(cats.length, 1);
    const anchoBarra = Math.max(paso * 0.68, 2);

    // El máximo es el de la PILA, no el de un segmento: con el máximo del
    // segmento, cualquier barra completa se saldría del área de dibujo.
    const totales = cats.map(c => filas
      .filter(f => String(f[campoX]) === c)
      .reduce((a, f) => a + this.n(f[campoV]), 0));
    const max = Math.max(...totales, 0) || 1;

    cats.forEach((c, i) => {
      let acumulado = 0;
      series.forEach((s, si) => {
        const fila = filas.find(f => String(f[campoX]) === c && String(f[campoS]) === s);
        if (!fila) { return; }
        const v = this.n(fila[campoV]);
        if (v <= 0) { return; }
        const h = (v / max) * this.areaAlto;
        acumulado += h;
        this.barras.push({
          x: this.margen.izquierda + paso * i + (paso - anchoBarra) / 2,
          y: this.base - acumulado,
          w: anchoBarra, h, color: this.color(si), nombre: s,
          titulo: `${s} · ${c}: ${this.formato(fila[campoV])}`
        });
      });
    });
    series.forEach((s, si) => this.leyenda.push({ nombre: s, color: this.color(si) }));
    this.marcasY(max);
    this.marcasX(cats, this.escalaX(cats));
  }

  private dibujarBarras(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const campoV = this.pres.valor!;
    const cats = this.categoriasX(filas, campoX);
    const paso = this.areaAncho / Math.max(cats.length, 1);
    const anchoBarra = Math.max(paso * 0.6, 2);
    const max = Math.max(...filas.map(f => this.n(f[campoV])), 0) || 1;

    cats.forEach((c, i) => {
      const fila = filas.find(f => String(f[campoX]) === c);
      if (!fila) { return; }
      const h = (this.n(fila[campoV]) / max) * this.areaAlto;
      this.barras.push({
        x: this.margen.izquierda + paso * i + (paso - anchoBarra) / 2,
        y: this.base - h, w: anchoBarra, h, color: this.color(0), nombre: c,
        titulo: `${c}: ${this.formato(fila[campoV])}`
      });
    });

    if (this.pres.valor2) {
      const max2 = Math.max(...filas.map(f => this.n(f[this.pres.valor2!])), 0) || 1;
      const escala = this.escalaX(cats);
      const puntos = cats.map((c, i) => {
        const fila = filas.find(f => String(f[campoX]) === c);
        return {
          cx: escala(i),
          cy: this.base - (this.n(fila?.[this.pres.valor2!]) / max2) * this.areaAlto,
          titulo: `${this.pres.valor2Etiqueta} · ${c}: `
                + `${this.formato(fila?.[this.pres.valor2!])}`
        };
      });
      this.lineas.push({
        nombre: this.pres.valor2Etiqueta ?? this.pres.valor2, color: this.color(3), puntos,
        d: puntos.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.cx.toFixed(1)},${p.cy.toFixed(1)}`)
                 .join(' ')
      });
      this.leyenda.push({ nombre: this.pres.valorEtiqueta ?? 'Valor', color: this.color(0) });
      this.leyenda.push({
        nombre: (this.pres.valor2Etiqueta ?? 'Segunda medida') + ' →', color: this.color(3)
      });
      this.marcasY(max2, true);
    }
    this.marcasY(max);
    this.marcasX(cats, this.escalaX(cats));
  }

  /** Pareto: barra por elemento y curva del acumulado sobre el eje derecho 0-100. */
  private dibujarPareto(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const campoV = this.pres.valor!;
    const campoA = this.pres.acumulado!;
    const paso = this.areaAncho / Math.max(filas.length, 1);
    const anchoBarra = Math.max(paso * 0.7, 1.5);
    const max = Math.max(...filas.map(f => this.n(f[campoV])), 0) || 1;

    filas.forEach((f, i) => {
      const h = (this.n(f[campoV]) / max) * this.areaAlto;
      this.barras.push({
        x: this.margen.izquierda + paso * i + (paso - anchoBarra) / 2,
        y: this.base - h, w: anchoBarra, h, color: this.color(0),
        nombre: String(f[campoX]),
        titulo: `${f[campoX]}: ${this.formato(f[campoV])}`
      });
    });

    const escala = this.escalaX(filas.map(f => String(f[campoX])));
    const puntos = filas.map((f, i) => ({
      cx: escala(i),
      cy: this.base - (Math.min(this.n(f[campoA]), 100) / 100) * this.areaAlto,
      titulo: `${f[campoX]}: ${this.n(f[campoA]).toFixed(2)} % acumulado`
    }));
    this.lineas.push({
      nombre: 'Acumulado', color: this.color(4), puntos,
      d: puntos.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.cx.toFixed(1)},${p.cy.toFixed(1)}`)
               .join(' ')
    });
    this.leyenda.push({ nombre: this.pres.valorEtiqueta ?? 'Venta', color: this.color(0) });
    this.leyenda.push({ nombre: 'Acumulado % →', color: this.color(4) });
    this.marcasY(max);
    for (let i = 0; i <= 4; i++) {
      this.ejeY2.push({ etiqueta: `${i * 25} %`, y: this.base - (this.areaAlto * i) / 4 });
    }
    this.marcasX(filas.map(f => String(f[campoX])), escala);
  }

  /**
   * Embudo horizontal. El ancho de cada paso es proporcional a sus pedidos y
   * la etiqueta lleva la conversión CONTRA EL PASO ANTERIOR, que es el dato con
   * el que se decide dónde invertir. La tasa contra el origen está en la tabla.
   */
  private dibujarEmbudo(filas: Record<string, any>[]): void {
    const max = Math.max(...filas.map(f => this.n(f['pedidos'])), 0) || 1;
    const altoPaso = 52;
    const anchoUtil = this.disponerPasos(filas, altoPaso);
    filas.forEach((f, i) => {
      const w = (this.n(f['pedidos']) / max) * anchoUtil;
      this.pasos.push({
        etiqueta: String(f['paso']),
        x: this.margenPasos.izquierda,
        y: this.margen.arriba + i * altoPaso,
        w: Math.max(w, 2),
        h: altoPaso - 8,
        color: this.color(i),
        valor: this.miles(this.n(f['pedidos'])),
        tasa: i === 0 ? '' : `${this.n(f['tasa_paso_pct']).toFixed(1)} % del paso anterior`,
        titulo: `${f['paso']}: ${this.miles(this.n(f['pedidos']))} de `
              + `${this.miles(this.n(f['denominador']))} · ${f['nota']}`
      });
    });
  }

  /**
   * Nube de puntos con la cruz de corte cuando el bloque la trae. La cruz NO se
   * inventa aquí: si el backend no manda `corteRotacion`/`corteMargen`, no se
   * pinta ninguna, porque un umbral dibujado por la pantalla no corresponde a
   * la clasificación con la que se calcularon los cuadrantes.
   */
  private dibujarDispersion(filas: Record<string, any>[]): void {
    const cx = this.pres.x!;
    const cy = this.pres.y!;
    const maxX = Math.max(...filas.map(f => this.n(f[cx])), 0) || 1;
    const maxY = Math.max(...filas.map(f => this.n(f[cy])), 0) || 1;
    const px = (v: number) => this.margen.izquierda + (v / maxX) * this.areaAncho;
    const py = (v: number) => this.base - (v / maxY) * this.areaAlto;

    const grupos = this.pres.grupo ? this.categoriasX(filas, this.pres.grupo) : [];
    filas.forEach(f => {
      if (f[cx] === null || f[cx] === undefined) { return; }
      const gi = this.pres.grupo ? grupos.indexOf(String(f[this.pres.grupo])) : 0;
      this.puntos.push({
        cx: px(this.n(f[cx])), cy: py(this.n(f[cy])), r: 5, color: this.color(gi),
        titulo: `${f[this.pres.punto ?? 'producto']} · ${this.pres.xEtiqueta}: `
              + `${this.n(f[cx])} · ${this.pres.yEtiqueta}: ${this.n(f[cy])}`
      });
    });
    grupos.forEach((g, i) => this.leyenda.push({ nombre: g, color: this.color(i) }));

    const corteX = this.bloque['corteRotacion'];
    const corteY = this.bloque['corteMargen'];
    if (corteX !== undefined && corteX !== null) { this.cruz.x = px(this.n(corteX)); }
    if (corteY !== undefined && corteY !== null) { this.cruz.y = py(this.n(corteY)); }

    this.marcasY(maxY);
    for (let i = 0; i <= 4; i++) {
      this.ejeX.push({
        etiqueta: this.corto((maxX / 4) * i),
        x: this.margen.izquierda + (this.areaAncho * i) / 4
      });
    }
  }

  /**
   * Caja y bigotes: la DISTRIBUCIÓN, no el promedio.
   *
   * Es la única forma de que dos transportistas con la misma media se vean
   * distintos, y esa diferencia es la decisión: uno entrega siempre en cuatro
   * días y el otro entre cero y nueve. La media va también, como punto, pero
   * encima de la caja y no en su lugar.
   */
  private dibujarCajas(filas: Record<string, any>[]): void {
    const c = this.pres.caja ?? { minimo: 'minimo', q1: 'q1', mediana: 'mediana',
                                  q3: 'q3', maximo: 'maximo', media: 'media' };
    const alto = 30;
    this.altoVista = this.margen.arriba + filas.length * alto + this.margen.abajo;
    const izq = this.margenCajas;
    const anchoUtil = this.ancho - izq - 90;

    // La escala es COMÚN a todas las cajas: una escala por fila haría que dos
    // distribuciones muy distintas se dibujaran igual de anchas.
    const max = Math.max(...filas.map(f => this.n(f[c.maximo])), 1);
    const x = (v: number) => izq + (v / max) * anchoUtil;

    filas.forEach((f, i) => {
      const q1 = this.n(f[c.q1]);
      const q3 = this.n(f[c.q3]);
      const etiqueta = this.pres.serie
        ? `${f[this.pres.ejeX!]} · ${f[this.pres.serie]}`
        : String(f[this.pres.ejeX!] ?? '');
      this.cajas.push({
        etiqueta: this.recortar(etiqueta, 40),
        y: this.margen.arriba + i * alto,
        alto: alto - 12,
        bigoteIzq: x(this.n(f[c.minimo])),
        bigoteDer: x(this.n(f[c.maximo])),
        x: x(q1),
        ancho: Math.max(x(q3) - x(q1), 1.5),
        mediana: x(this.n(f[c.mediana])),
        media: c.media ? x(this.n(f[c.media])) : null,
        color: this.color(i),
        titulo: `${etiqueta}: mín ${f[c.minimo]} · Q1 ${f[c.q1]} · mediana `
              + `${f[c.mediana]} · Q3 ${f[c.q3]} · máx ${f[c.maximo]}`
              + (c.media ? ` · media ${f[c.media]}` : '')
      });
    });

    for (let i = 0; i <= 4; i++) {
      this.ejeX.push({ etiqueta: this.corto((max / 4) * i), x: x((max / 4) * i) });
    }
  }

  /**
   * Mapa de calor de dos dimensiones.
   *
   * La intensidad se reparte sobre el máximo de la matriz y NO sobre el de cada
   * fila: normalizando por fila, la casilla más alta de cada fila saldría del
   * mismo color y la matriz dejaría de comparar entre filas, que es justo para
   * lo que existe.
   */
  private dibujarMatriz(filas: Record<string, any>[]): void {
    const campoF = this.pres.fila!;
    const campoC = this.pres.columna!;
    const campoV = this.pres.valor!;

    const rows = this.categoriasX(filas, campoF);
    const cols = this.categoriasX(filas, campoC);
    const izq = 210;
    const arriba = 46;
    const anchoCelda = Math.max((this.ancho - izq - 20) / Math.max(cols.length, 1), 10);
    const altoCelda = 30;

    this.altoVista = arriba + rows.length * altoCelda + 16;
    const max = Math.max(...filas.map(f => this.n(f[campoV])), 1);

    rows.forEach((r, ri) => {
      this.rotulosFila.push({
        etiqueta: this.recortar(r, 30),
        y: arriba + ri * altoCelda + altoCelda / 2 + 4
      });
      cols.forEach((c, ci) => {
        const fila = filas.find(f => String(f[campoF]) === r && String(f[campoC]) === c);
        const v = fila ? this.n(fila[campoV]) : 0;
        const intensidad = v / max;
        this.celdas.push({
          x: izq + ci * anchoCelda,
          y: arriba + ri * altoCelda,
          w: anchoCelda - 2,
          h: altoCelda - 2,
          // Una casilla SIN dato y una con cero no son lo mismo: la primera va
          // en gris y la segunda en el tono más claro de la escala.
          color: fila ? this.tono(intensidad) : '#f1f5f9',
          texto: fila ? this.corto(v) : '',
          textoColor: intensidad > 0.55 ? '#ffffff' : '#1f2937',
          titulo: fila ? `${r} · ${c}: ${this.formato(v)}` : `${r} · ${c}: sin datos`
        });
      });
    });

    cols.forEach((c, ci) => {
      this.rotulosColumna.push({
        etiqueta: this.recortar(c, 16),
        x: izq + ci * anchoCelda + anchoCelda / 2,
        y: arriba - 12
      });
    });
  }

  /** Escala secuencial de un solo tono: más oscuro = más valor. */
  private tono(t: number): string {
    const claro = [219, 234, 254];   // azul muy claro
    const oscuro = [30, 58, 138];    // azul profundo
    const c = claro.map((v, i) => Math.round(v + (oscuro[i] - v) * Math.min(Math.max(t, 0), 1)));
    return `rgb(${c[0]},${c[1]},${c[2]})`;
  }

  /** Ranking: barras horizontales del top N que el backend ya ordenó. */
  private dibujarRanking(filas: Record<string, any>[]): void {
    const campoX = this.pres.ejeX!;
    const campoV = this.pres.valor!;
    const max = Math.max(...filas.map(f => this.n(f[campoV])), 0) || 1;
    const altoPaso = 26;
    const anchoUtil = this.disponerPasos(filas, altoPaso);
    filas.forEach((f, i) => {
      const w = (this.n(f[campoV]) / max) * anchoUtil;
      this.pasos.push({
        etiqueta: this.recortar(String(f[campoX] ?? ''), 30),
        x: this.margenPasos.izquierda,
        y: this.margen.arriba + i * altoPaso,
        w: Math.max(w, 1.5),
        h: altoPaso - 7,
        color: this.color(0),
        valor: this.formato(f[campoV]),
        tasa: '',
        titulo: `${f[campoX]}: ${this.formato(f[campoV])}`
      });
    });
  }

  // ── Formato ──────────────────────────────────────────────────────────

  private n(v: any): number {
    const x = Number(v);
    return Number.isFinite(x) ? x : 0;
  }

  private miles(v: number): string {
    return v.toLocaleString('es-EC');
  }

  /** Etiqueta corta del eje: 1,2 M / 340 k / 87. */
  private corto(v: number): string {
    const a = Math.abs(v);
    if (a >= 1e6) { return (v / 1e6).toFixed(1).replace('.', ',') + ' M'; }
    if (a >= 1e3) { return (v / 1e3).toFixed(0) + ' k'; }
    // Un conteo entero se escribe entero: «1,00 incidencias» en una casilla de
    // un mapa de calor es ruido que hace dudar de si el numero es una tasa.
    if (Number.isInteger(v)) { return v.toFixed(0); }
    if (a >= 10) { return v.toFixed(1).replace('.', ','); }
    return v.toFixed(2).replace('.', ',');
  }

  private formato(v: any): string {
    const tipo = this.pres.valorTipo;
    const x = this.n(v);
    if (tipo === 'moneda') {
      return '$' + x.toLocaleString('es-EC',
        { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    }
    if (tipo === 'porcentaje') { return x.toLocaleString('es-EC') + ' %'; }
    return this.miles(x);
  }

  private recortar(s: string, n: number): string {
    return s.length > n ? s.slice(0, n) + '…' : s;
  }
}
