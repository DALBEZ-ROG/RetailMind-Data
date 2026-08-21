import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';
import { BannerRow, CampanaRow } from '../../../core/models/operativo.model';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

export interface BannerDialogData {
  banner?: BannerRow;
  campanas: CampanaRow[];
  modo: ModoFormulario;
}

export interface BannerDialogResultado {
  titulo: string; imagenUrl: string; urlDestino: string; posicion: string;
  orden: number; campanaId: number | null;
  fechaInicio: string; fechaFin: string;
  activo: boolean;
}

/** Alta / edición / consulta de banner. Patrón: docs/PATRON_UI.md §5. */
@Component({
  selector: 'app-banner-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoNumeroDirective, CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>view_carousel</mat-icon>
      Banner
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>Título</mat-label>
          <input appTexto="nombre" exigido matInput [(ngModel)]="form.titulo" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>URL de la imagen</mat-label>
          <input appTexto="url" exigido matInput [(ngModel)]="form.imagenUrl" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline" class="ancho">
          <mat-label>URL de destino (opcional)</mat-label>
          <input appTexto="url" matInput [(ngModel)]="form.urlDestino" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Posición</mat-label>
          <mat-select [(ngModel)]="form.posicion" [disabled]="soloLectura">
            <mat-option *ngFor="let p of posiciones" [value]="p">{{ p }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Orden</mat-label>
          <input appNumero="entero" matInput type="number" min="0" [(ngModel)]="form.orden" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Campaña (opcional)</mat-label>
          <mat-select [(ngModel)]="form.campanaId" [disabled]="soloLectura">
            <mat-option [value]="null">— Sin campaña —</mat-option>
            <mat-option *ngFor="let c of data.campanas" [value]="c.id">{{ c.nombre }}</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Vigente desde</mat-label>
          <input matInput type="datetime-local" [(ngModel)]="form.fechaInicio"
                 [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Vigente hasta</mat-label>
          <input matInput type="datetime-local" [(ngModel)]="form.fechaFin" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activo (si se desmarca, equivale a eliminar)
      </mat-checkbox>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(680px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 8px 16px;
    }
    .ancho { grid-column: 1 / -1; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class BannerDialogComponent {

  readonly posiciones = ['home_principal', 'home_secundario', 'categoria', 'checkout'];

  form: BannerDialogResultado;

  constructor(public dialogRef: MatDialogRef<BannerDialogComponent, BannerDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: BannerDialogData) {
    const b = data.banner;
    this.form = {
      titulo: b?.titulo ?? '',
      imagenUrl: b?.imagen_url ?? '',
      urlDestino: b?.url_destino ?? '',
      posicion: b?.posicion ?? 'home_principal',
      orden: b?.orden ?? 0,
      campanaId: b?.campana_id ?? null,
      fechaInicio: (b?.fecha_inicio ?? '').substring(0, 16),
      fechaFin: (b?.fecha_fin ?? '').substring(0, 16),
      activo: b?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean { return !!this.form.titulo.trim() && !!this.form.imagenUrl.trim(); }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
