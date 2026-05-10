import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { HttpEventType } from '@angular/common/http';

import { EtlService } from '../../core/services/etl.service';
import { EtlResponse, EstadoTabla, CargaHistorial } from '../../core/models/etl.model';
import { ConfirmDialogComponent } from './confirm-dialog.component';

type OperationState = 'idle' | 'running' | 'success' | 'error';

@Component({
  selector: 'app-admin-etl',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatChipsModule,
    MatDividerModule,
    MatSnackBarModule,
    MatTooltipModule,
    MatDialogModule
  ],
  templateUrl: './admin-etl.component.html',
  styleUrl: './admin-etl.component.scss'
})
export class AdminEtlComponent implements OnInit {

  // ── Estado general ─────────────────────────────────────────────────────────
  operationState: OperationState = 'idle';
  uploadProgress  = 0;
  consoleOutput   = '';
  lastResponse:   EtlResponse | null = null;

  // ── Archivo seleccionado ───────────────────────────────────────────────────
  selectedFile:   File | null = null;
  isDragOver      = false;

  // ── Tablas ─────────────────────────────────────────────────────────────────
  estadoTablas:   EstadoTabla[]    = [];
  historial:      CargaHistorial[] = [];
  loadingTablas   = false;
  loadingHistorial = false;

  tablaCols    = ['tabla', 'totalRegistros'];
  historialCols = ['semana', 'fechaCarga', 'registrosProcesados', 'registrosNuevos'];

  constructor(
    private etlService: EtlService,
    private snackBar:   MatSnackBar,
    private dialog:     MatDialog
  ) {}

  ngOnInit(): void {
    this.refreshEstadoTablas();
    this.refreshHistorial();
  }

  // ── Drag & Drop ────────────────────────────────────────────────────────────

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = true;
  }

  onDragLeave(): void {
    this.isDragOver = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragOver = false;
    const file = event.dataTransfer?.files[0];
    if (file) this.setFile(file);
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files?.length) this.setFile(input.files[0]);
  }

  private setFile(file: File): void {
    if (!file.name.endsWith('.csv')) {
      this.snackBar.open('Solo se aceptan archivos .csv', 'Cerrar', { duration: 3000 });
      return;
    }
    this.selectedFile = file;
    this.appendConsole(`Archivo seleccionado: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`);
  }

  // ── Acciones ETL ───────────────────────────────────────────────────────────

  uploadCsv(): void {
    if (!this.selectedFile) {
      this.snackBar.open('Selecciona un archivo CSV primero.', 'Cerrar', { duration: 3000 });
      return;
    }
    this.startOperation();
    this.appendConsole('Subiendo archivo CSV al servidor...');

    this.etlService.uploadCsv(this.selectedFile).subscribe({
      next: event => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress = Math.round(100 * event.loaded / event.total);
        } else if (event.type === HttpEventType.Response) {
          const res = event.body as EtlResponse;
          this.handleResponse(res, 'Archivo subido');
        }
      },
      error: err => this.handleError('Error al subir el archivo: ' + err.message)
    });
  }

  cargarStaging(): void {
    this.startOperation();
    this.appendConsole('Ejecutando carga a dataset_temporal...');
    this.etlService.cargarStaging().subscribe({
      next: res => this.handleResponse(res, 'Carga a staging'),
      error: err => this.handleError(err.message)
    });
  }

  ejecutarEtl(): void {
    this.startOperation();
    this.appendConsole('Ejecutando ETL incremental (05_load_incremental.py)...');
    this.etlService.ejecutarEtl().subscribe({
      next: res => {
        this.handleResponse(res, 'ETL incremental');
        if (res.success) this.refreshEstadoTablas();
        this.refreshHistorial();
      },
      error: err => this.handleError(err.message)
    });
  }

  ejecutarCompleto(): void {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: '420px',
      data: {
        title: 'Confirmar ejecucion ETL',
        message: 'Esta accion cargara los datos del CSV a la base de datos. Desea continuar?'
      }
    });

    dialogRef.afterClosed().subscribe(confirmed => {
      if (confirmed) {
        this.startOperation();
        this.appendConsole('=== INICIANDO PROCESO COMPLETO ===');
        this.appendConsole('Paso 1: Carga a staging...');
        this.etlService.ejecutarCompleto().subscribe({
          next: res => {
            this.handleResponse(res, 'Proceso completo');
            if (res.success) {
              this.refreshEstadoTablas();
              this.refreshHistorial();
            }
          },
          error: err => this.handleError(err.message)
        });
      }
    });
  }

  // ── Refresh ────────────────────────────────────────────────────────────────

  refreshEstadoTablas(): void {
    this.loadingTablas = true;
    this.etlService.getEstadoTablas().subscribe({
      next: data => { this.estadoTablas = data; this.loadingTablas = false; },
      error: ()  => { this.loadingTablas = false; }
    });
  }

  refreshHistorial(): void {
    this.loadingHistorial = true;
    this.etlService.getHistorial().subscribe({
      next: data => { this.historial = data; this.loadingHistorial = false; },
      error: ()  => { this.loadingHistorial = false; }
    });
  }

  // ── Helpers ────────────────────────────────────────────────────────────────

  private startOperation(): void {
    this.operationState = 'running';
    this.uploadProgress = 0;
    this.consoleOutput  = '';
    this.lastResponse   = null;
  }

  private handleResponse(res: EtlResponse, label: string): void {
    this.lastResponse   = res;
    this.operationState = res.success ? 'success' : 'error';
    this.uploadProgress = 100;
    if (res.output) this.appendConsole(res.output);
    this.appendConsole(`\n[${res.success ? 'OK' : 'ERROR'}] ${res.mensaje} (${res.duracionSegundos}s)`);
  }

  private handleError(msg: string): void {
    this.operationState = 'error';
    this.uploadProgress = 0;
    this.appendConsole(`[ERROR] ${msg}`);
  }

  private appendConsole(text: string): void {
    this.consoleOutput += text + '\n';
  }

  get isRunning(): boolean { return this.operationState === 'running'; }
}
