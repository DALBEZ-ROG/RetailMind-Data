import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import {
  ModoFormComponent, ModoFormulario
} from '../../../core/components/modo-form/modo-form.component';
import { VarianteAdmin } from '../../../core/models/operativo.model';
import { VarianteBody } from '../../../core/services/catalogo-admin.service';

import { CampoNumeroDirective, CampoTextoDirective } from '../../../core/validacion';

export interface VarianteDialogData {
  productoNombre: string;
  /** Presente en 'actualizar' y 'consulta' (SKU/precio/costo, precargados). */
  variante?: VarianteAdmin;
  modo: ModoFormulario;
}

/** Igual que en producto: `activo` viaja aparte, por su propio endpoint. */
export type VarianteDialogResultado = VarianteBody & { activo: boolean };

/**
 * Alta / edición / consulta de variante (SKU) en modal estilo Dubai,
 * con el mismo patrón que el diálogo de producto: chip de modo (regla 3)
 * y dos botones, Aceptar y Cancelar (regla 4).
 */
@Component({
  selector: 'app-variante-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatCheckboxModule, MatIconModule, ModoFormComponent,
    CampoNumeroDirective, CampoTextoDirective
  ],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>style</mat-icon>
      Variante
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <p class="sub">Producto: <strong>{{ data.productoNombre }}</strong></p>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>SKU</mat-label>
          <input appTexto="sku" exigido matInput [(ngModel)]="form.sku" [disabled]="soloLectura" required>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Precio</mat-label>
          <input appNumero="dinero" matInput type="number" min="0" step="0.01" [(ngModel)]="form.precio"
                 [disabled]="soloLectura" required>
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Costo</mat-label>
          <input appNumero="dinero" matInput type="number" min="0" step="0.01" [(ngModel)]="form.costo"
                 [disabled]="soloLectura">
          <span matTextPrefix>$&nbsp;</span>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Peso</mat-label>
          <input appNumero="decimal" [decimales]="3" matInput type="number" min="0.001" step="0.001" [(ngModel)]="form.pesoKg"
                 [disabled]="soloLectura" required>
          <span matTextSuffix>&nbsp;kg</span>
          <mat-hint *ngIf="!soloLectura">Con él se cobra el flete por kilo</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline" *ngIf="esNuevo">
          <mat-label>Código de barras</mat-label>
          <input appTexto="sku" matInput [(ngModel)]="form.codigoBarras">
        </mat-form-field>
      </div>

      <!--
        Solo sale en las variantes antiguas, que se dieron de alta cuando la
        pantalla no capturaba el peso. No es decorativo: mientras siga vacío, el
        envío de CUALQUIER pedido con esta variante se cobra sin el cargo por kilo.
      -->
      <p class="aviso-peso" *ngIf="!esNuevo && !pesoOriginal">
        <mat-icon>scale</mat-icon>
        Esta variante no tiene peso registrado, así que el envío de los pedidos que
        la incluyan se está cobrando solo con la tarifa base. Indícalo para corregirlo.
      </p>

      <div class="banderas">
        <mat-checkbox *ngIf="esNuevo" [(ngModel)]="form.esPredeterminada">
          Variante predeterminada
        </mat-checkbox>
        <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
          Activa (si se desmarca, equivale a eliminar)
        </mat-checkbox>
      </div>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(560px, 80vw); }
    .sub { font-size: 13px; color: var(--text-secondary); margin: 0 0 12px; }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 16px;
    }
    .banderas { display: flex; flex-wrap: wrap; gap: 8px 28px; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
    .aviso-peso {
      display: flex; align-items: flex-start; gap: 8px;
      margin: 4px 0 14px; padding: 10px 12px;
      font-size: 13px; line-height: 1.45;
      border-radius: 8px;
      color: var(--text-primary);
      background: rgba(212, 175, 55, 0.10);
      border: 1px solid rgba(212, 175, 55, 0.35);
    }
    .aviso-peso mat-icon {
      flex: 0 0 auto; font-size: 19px; width: 19px; height: 19px;
      color: var(--dubai-gold, #d4af37);
    }
  `]
})
export class VarianteDialogComponent {

  form: VarianteDialogResultado;

  /**
   * Si la variante YA tenía peso al abrir el diálogo. Distingue «venía vacía»
   * de «el usuario la acaba de rellenar», que es lo que decide si se muestra el
   * aviso — sin esto el aviso desaparecería al teclear el primer dígito.
   */
  readonly pesoOriginal: boolean;

  constructor(public dialogRef: MatDialogRef<VarianteDialogComponent, VarianteDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: VarianteDialogData) {
    const v = data.variante;
    this.pesoOriginal = v?.peso_kg != null && Number(v.peso_kg) > 0;
    // Precarga total fuera del alta: nada de campos vacíos que obliguen a reescribir.
    // `pesoKg` va a null y NO a 0 cuando falta: 0 es un peso afirmado y el backend
    // lo rechaza, mientras que null deja el campo vacío para que se escriba.
    this.form = {
      sku: v?.sku ?? '',
      precio: v != null ? Number(v.precio) : 0,
      costo: v != null ? Number(v.costo) : 0,
      pesoKg: this.pesoOriginal ? Number(v!.peso_kg) : null,
      codigoBarras: '',
      esPredeterminada: false,
      activo: v?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  /**
   * El peso se exige SIEMPRE que se vaya a guardar —al crear y al editar—, no
   * solo en el alta: si se dejara pasar en la edición, la pantalla no serviría
   * para arreglar las variantes que hoy lo tienen vacío, que es medio motivo de
   * que el campo exista.
   */
  get valido(): boolean {
    return !!this.form.sku.trim()
        && this.form.precio > 0
        && this.form.pesoKg != null && this.form.pesoKg > 0;
  }

  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (!this.valido) return;
    // Sin código de barras se manda null, no ''. La columna es UNIQUE y en
    // Postgres los NULL no colisionan entre sí, pero dos cadenas vacías sí:
    // con '' la segunda variante sin código rebota con un 400 del motor.
    this.dialogRef.close({
      ...this.form,
      codigoBarras: this.form.codigoBarras?.trim() || null
    });
  }
}
