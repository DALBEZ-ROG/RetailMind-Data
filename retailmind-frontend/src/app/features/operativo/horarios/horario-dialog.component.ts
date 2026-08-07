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
import { VentanaHoraria } from '../../../core/models/operativo.model';
import { RolGrupoPipe } from '../../../core/pipes/etiquetas.pipe';

export interface HorarioDialogData {
  ventana?: VentanaHoraria;
  modo: ModoFormulario;
  roles: string[];
  dias: string[];
}

export interface HorarioDialogResultado {
  rolGrupo: string;
  diaSemana: number;
  horaInicio: string;
  horaFin: string;
  activo: boolean;
}

/**
 * Ventana horaria de acceso (tabla `grupo_horario`). Patrón: docs/PATRON_UI.md §5.
 *
 * Sustituye a la EDICIÓN INLINE dentro de la fila, que era un paradigma de
 * formulario que no existía en ninguna otra pantalla y cuyos botones de
 * confirmar/cancelar eran dos iconos sin texto.
 *
 * **Rol y día son inmutables al modificar**: el backend solo admite cambiar
 * horas y estado (`PUT /api/admin/horarios/{id}` recibe horaInicio, horaFin y
 * activo). Mover una ventana a otro rol sería crear otra distinta, y el
 * formulario lo dice en vez de ofrecer un campo que no se guardaría.
 */
@Component({
  selector: 'app-horario-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatDialogModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, MatCheckboxModule, MatIconModule, ModoFormComponent, RolGrupoPipe],
  template: `
    <h2 mat-dialog-title>
      <mat-icon>schedule</mat-icon>
      Ventana horaria
      <app-modo-form [modo]="data.modo"></app-modo-form>
    </h2>

    <mat-dialog-content>
      <div class="grid">
        <mat-form-field appearance="outline">
          <mat-label>Rol de grupo</mat-label>
          <!-- CRÍTICO: [value] y [(ngModel)] llevan el identificador CRUDO
               (\`grp_…\`), que es lo que se manda en el POST y lo que se guarda en
               \`grupo_horario.rol_grupo\`. El pipe solo cambia la etiqueta visible. -->
          <mat-select [(ngModel)]="form.rolGrupo" [disabled]="!esNuevo">
            <mat-option *ngFor="let r of data.roles" [value]="r" [title]="r">
              {{ r | rolGrupo }}
            </mat-option>
          </mat-select>
          <mat-hint *ngIf="!esNuevo">El rol de una ventana existente no se cambia.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Día de la semana</mat-label>
          <mat-select [(ngModel)]="form.diaSemana" [disabled]="!esNuevo">
            <mat-option *ngFor="let d of data.dias; let i = index" [value]="i">{{ d }}</mat-option>
          </mat-select>
          <mat-hint *ngIf="!esNuevo">El día de una ventana existente no se cambia.</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Hora de inicio</mat-label>
          <input matInput type="time" [(ngModel)]="form.horaInicio" [disabled]="soloLectura">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Hora de fin</mat-label>
          <input matInput type="time" [(ngModel)]="form.horaFin" [disabled]="soloLectura">
        </mat-form-field>
      </div>

      <mat-checkbox *ngIf="!esNuevo" [(ngModel)]="form.activo" [disabled]="soloLectura">
        Activa (si se desmarca, equivale a eliminar)
      </mat-checkbox>
      <mat-checkbox *ngIf="esNuevo" [(ngModel)]="form.activo">Activa desde ya</mat-checkbox>

      <p class="aviso">
        <mat-icon>gpp_maybe</mat-icon>
        <span>
          Esta ventana la aplica <strong>la base de datos</strong>, no la interfaz: fuera de
          ella, <code>esta_en_horario()</code> rechaza cualquier operación del rol y su login.
          Dejar a un rol sin ninguna ventana activa lo deja fuera del sistema.
        </span>
      </p>

      <p class="hint" *ngIf="!valido && !soloLectura">
        La hora de inicio debe ser menor que la de fin.
      </p>
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button class="btn-cancelar" (click)="cancelar()">Cancelar</button>
      <button class="btn-aceptar" [disabled]="!puedeAceptar" (click)="aceptar()">Aceptar</button>
    </mat-dialog-actions>
  `,
  styles: [`
    mat-dialog-content { min-width: min(560px, 82vw); }
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 8px 16px;
    }
    .aviso {
      display: flex; align-items: flex-start; gap: 8px;
      margin: 14px 0 0; padding: 10px 14px; border-radius: 10px;
      background: rgba(255, 152, 0, 0.10);
      border-left: 3px solid rgba(255, 152, 0, 0.55);
      font-size: 12px; line-height: 1.5;

      mat-icon { flex: 0 0 auto; font-size: 18px; width: 18px; height: 18px; color: #ef6c00; }
      code { font-size: 11px; }
    }
    .hint { margin-top: 10px; font-size: 12px; color: #c62828; }
    mat-dialog-actions { padding: 12px 24px 20px; gap: 10px; }
  `]
})
export class HorarioDialogComponent {

  form: HorarioDialogResultado;

  constructor(public dialogRef: MatDialogRef<HorarioDialogComponent, HorarioDialogResultado>,
              @Inject(MAT_DIALOG_DATA) public data: HorarioDialogData) {
    const v = data.ventana;
    this.form = {
      rolGrupo: v?.rol_grupo ?? data.roles[0] ?? 'grp_vendedor',
      diaSemana: v?.dia_semana ?? 1,
      // El backend devuelve 'HH:MM'; <input type="time"> quiere exactamente eso.
      horaInicio: (v?.hora_inicio ?? '08:00').substring(0, 5),
      horaFin: (v?.hora_fin ?? '18:00').substring(0, 5),
      activo: v?.activo ?? true
    };
  }

  get esNuevo(): boolean { return this.data.modo === 'nuevo'; }
  get soloLectura(): boolean { return this.data.modo === 'consulta'; }

  get valido(): boolean {
    return !!this.form.horaInicio && !!this.form.horaFin
        && this.form.horaInicio < this.form.horaFin;
  }
  get puedeAceptar(): boolean { return this.soloLectura || this.valido; }

  cancelar(): void { this.dialogRef.close(); }

  aceptar(): void {
    if (this.soloLectura) { this.dialogRef.close(); return; }
    if (this.valido) this.dialogRef.close(this.form);
  }
}
