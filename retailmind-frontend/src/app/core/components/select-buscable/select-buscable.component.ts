import { Component, EventEmitter, Input, OnDestroy, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatIconModule } from '@angular/material/icon';
import { Observable, Subject, Subscription, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';

import { CampoTextoDirective } from '../../validacion';
export interface OpcionBuscable { id: number; texto: string; }

/**
 * Reemplazo de mat-select para catálogos grandes: un mat-autocomplete que
 * filtra por texto y solo renderiza las primeras coincidencias, en lugar de
 * crear una opción DOM por registro.
 *
 * Uso EN MEMORIA (catálogos de miles, no de decenas de miles):
 *   <app-select-buscable label="Producto (SKU)" [opciones]="opcs" [(value)]="id">
 *
 * Uso CONTRA EL SERVIDOR, para catálogos que no caben en el navegador:
 *   <app-select-buscable label="Cliente" [buscador]="buscarCliente" [(value)]="id">
 *
 * <h3>Por qué existe el segundo modo</h3>
 * `referencias/clientes` devolvía los **50.072 clientes** (4,03 MB) en cada
 * apertura de dos pantallas, y este componente los filtraba aquí. Filtrar en el
 * navegador exige haberlo descargado todo: para el padrón de clientes eso ya no
 * es viable, así que el criterio se resuelve en SQL y solo bajan las
 * coincidencias. El modo en memoria se conserva tal cual para los catálogos que
 * sí caben (variantes: 6.222 filas, 0,66 MB medidos).
 */
@Component({
  selector: 'app-select-buscable',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule,
    MatAutocompleteModule, MatIconModule,
    CampoTextoDirective
  ],
  template: `
    <mat-form-field appearance="outline" class="sb-field">
      <mat-label>{{ label }}</mat-label>
      <input appTexto="libre" matInput [matAutocomplete]="auto" [placeholder]="placeholder"
             [disabled]="disabled" [ngModel]="entrada"
             (ngModelChange)="alEscribir($event)">
      <mat-icon matSuffix class="sb-icon">search</mat-icon>
      <mat-autocomplete #auto="matAutocomplete" [displayWith]="mostrar"
                        (optionSelected)="alSeleccionar($event.option.value)"
                        (closed)="alCerrar()">
        <mat-option *ngFor="let o of filtradas" [value]="o">{{ o.texto }}</mat-option>
        <mat-option *ngIf="ocultas > 0" disabled class="sb-mas">
          … {{ ocultas }} más — escribe para afinar
        </mat-option>
        <mat-option *ngIf="!filtradas.length" disabled>Sin coincidencias</mat-option>
      </mat-autocomplete>
    </mat-form-field>
  `,
  styles: [`
    .sb-field { width: 100%; }
    .sb-icon { color: var(--text-light); }
    .sb-mas { font-size: 12px; font-style: italic; }
  `]
})
export class SelectBuscableComponent implements OnDestroy {

  private static readonly MAX_VISIBLES = 50;

  @Input() label = '';
  @Input() placeholder = 'Escribe para buscar…';
  @Input() disabled = false;

  private todas: OpcionBuscable[] = [];
  private valorActual: number | null = null;

  filtradas: OpcionBuscable[] = [];
  ocultas = 0;
  entrada: string | OpcionBuscable | null = null;

  @Input() set opciones(v: OpcionBuscable[] | null) {
    this.todas = v ?? [];
    if (!this.buscadorServidor) { this.filtrar(''); }
    this.sincronizarTexto();
  }

  // ── Modo servidor ────────────────────────────────────────────────────

  private buscadorServidor?: (q: string) => Observable<OpcionBuscable[]>;
  private teclas$ = new Subject<string>();
  private sub?: Subscription;

  /**
   * Buscador en SERVIDOR. Al declararlo, el componente deja de filtrar en
   * memoria: cada pulsación (con 300 ms de debounce) consulta el endpoint y
   * pinta lo que devuelve. Es lo que permite elegir entre 50.072 clientes sin
   * descargarlos.
   */
  @Input() set buscador(fn: ((q: string) => Observable<OpcionBuscable[]>) | null) {
    this.buscadorServidor = fn ?? undefined;
    this.sub?.unsubscribe();
    if (!fn) { return; }
    this.sub = this.teclas$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => fn(q))
    ).subscribe({
      next: opciones => {
        // Las recibidas se recuerdan para poder mostrar el texto de la opción
        // elegida aunque una búsqueda posterior ya no la devuelva.
        const conocidas = new Map(this.todas.map(o => [o.id, o]));
        opciones.forEach(o => conocidas.set(o.id, o));
        this.todas = [...conocidas.values()];
        this.filtradas = opciones.slice(0, SelectBuscableComponent.MAX_VISIBLES);
        this.ocultas = 0;
      },
      error: () => { this.filtradas = []; this.ocultas = 0; }
    });
    // Primer disparo: el desplegable se abre con algo que enseñar.
    this.teclas$.next('');
  }

  ngOnDestroy(): void { this.sub?.unsubscribe(); }

  @Input() set value(id: number | null) {
    this.valorActual = id;
    this.sincronizarTexto();
  }
  @Output() valueChange = new EventEmitter<number | null>();

  mostrar = (o: OpcionBuscable | string | null): string =>
    typeof o === 'object' && o !== null ? o.texto : (o ?? '');

  alEscribir(v: string | OpcionBuscable): void {
    this.entrada = v;
    if (typeof v !== 'string') { return; }
    if (this.buscadorServidor) { this.teclas$.next(v); } else { this.filtrar(v); }
  }

  alSeleccionar(o: OpcionBuscable): void {
    this.valorActual = o.id;
    this.entrada = o;
    this.valueChange.emit(o.id);
  }

  /** Al cerrar el panel sin elegir: texto vacío limpia; texto suelto se revierte. */
  alCerrar(): void {
    if (typeof this.entrada !== 'string') return;
    if (!this.entrada.trim()) {
      if (this.valorActual !== null) {
        this.valorActual = null;
        this.valueChange.emit(null);
      }
      this.entrada = null;
    } else {
      this.sincronizarTexto();
    }
    if (this.buscadorServidor) { this.teclas$.next(''); } else { this.filtrar(''); }
  }

  private filtrar(texto: string): void {
    const q = texto.toLowerCase().trim();
    const coincidentes = q
      ? this.todas.filter(o => o.texto.toLowerCase().includes(q))
      : this.todas;
    this.filtradas = coincidentes.slice(0, SelectBuscableComponent.MAX_VISIBLES);
    this.ocultas = coincidentes.length - this.filtradas.length;
  }

  private sincronizarTexto(): void {
    if (this.valorActual === null) { this.entrada = null; return; }
    const opcion = this.todas.find(o => o.id === this.valorActual);
    if (opcion) this.entrada = opcion;
  }
}
