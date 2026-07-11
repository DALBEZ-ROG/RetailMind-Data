import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatIconModule } from '@angular/material/icon';

export interface OpcionBuscable { id: number; texto: string; }

/**
 * Reemplazo de mat-select para catálogos grandes (1.200+ opciones): un
 * mat-autocomplete que filtra por texto y solo renderiza las primeras
 * coincidencias, en lugar de crear una opción DOM por registro.
 *
 * Uso: <app-select-buscable label="Producto (SKU)" [opciones]="opcs" [(value)]="id">
 * donde opcs = [{ id, texto }].
 */
@Component({
  selector: 'app-select-buscable',
  standalone: true,
  imports: [CommonModule, FormsModule, MatFormFieldModule, MatInputModule,
    MatAutocompleteModule, MatIconModule],
  template: `
    <mat-form-field appearance="outline" class="sb-field">
      <mat-label>{{ label }}</mat-label>
      <input matInput [matAutocomplete]="auto" [placeholder]="placeholder"
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
export class SelectBuscableComponent {

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
    this.filtrar('');
    this.sincronizarTexto();
  }

  @Input() set value(id: number | null) {
    this.valorActual = id;
    this.sincronizarTexto();
  }
  @Output() valueChange = new EventEmitter<number | null>();

  mostrar = (o: OpcionBuscable | string | null): string =>
    typeof o === 'object' && o !== null ? o.texto : (o ?? '');

  alEscribir(v: string | OpcionBuscable): void {
    this.entrada = v;
    if (typeof v === 'string') this.filtrar(v);
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
    this.filtrar('');
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
