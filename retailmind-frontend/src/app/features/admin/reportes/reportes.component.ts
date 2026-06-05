import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTableModule } from '@angular/material/table';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { environment } from '../../../../environments/environment';

interface DescargaHistorial {
  reporte: string;
  formato: string;
  fecha: string;
  estado: string;
}

@Component({
  selector: 'app-reportes',
  standalone: true,
  imports: [
    CommonModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatProgressSpinnerModule, MatTableModule, MatSnackBarModule
  ],
  templateUrl: './reportes.component.html',
  styleUrl: './reportes.component.scss'
})
export class ReportesComponent {

  historial: DescargaHistorial[] = [];
  displayedColumns = ['reporte', 'formato', 'fecha', 'estado'];

  generando: Record<string, boolean> = {};

  private readonly base = `${environment.apiUrl}/api/reportes`;

  constructor(private http: HttpClient, private snackBar: MatSnackBar) {}

  descargar(endpoint: string, filename: string, reporte: string, formato: string): void {
    const key = `${reporte}_${formato}`;
    this.generando[key] = true;

    this.http.get(`${this.base}/${endpoint}`, { responseType: 'blob' }).subscribe({
      next: (blob) => {
        this.generando[key] = false;
        this.triggerDownload(blob, filename);
        this.historial = [
          { reporte, formato, fecha: new Date().toLocaleString(), estado: '✅ Exitoso' },
          ...this.historial
        ];
        this.snackBar.open(`${reporte} descargado ✓`, 'OK', { duration: 3000, panelClass: ['snack-success'] });
      },
      error: () => {
        this.generando[key] = false;
        this.historial = [
          { reporte, formato, fecha: new Date().toLocaleString(), estado: '❌ Error' },
          ...this.historial
        ];
        this.snackBar.open('Error al generar reporte', 'Cerrar', { duration: 4000, panelClass: ['snack-error'] });
      }
    });
  }

  private triggerDownload(blob: Blob, filename: string): void {
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    window.URL.revokeObjectURL(url);
  }

  isGenerando(reporte: string, formato: string): boolean {
    return !!this.generando[`${reporte}_${formato}`];
  }
}
