import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';
import { AccesosService } from '../../../core/services/accesos.service';
import { mensajeError } from '../../../core/services/api-error.util';
import { LogAccesoRow } from '../../../core/models/operativo.model';

import { CampoTextoDirective } from '../../../core/validacion';

/** Etiquetas legibles de log_acceso.motivo_fallo (OTD-GER-09, script 53). */
const MOTIVOS: Record<string, string> = {
  email_no_registrado: 'Correo no registrado',
  password_incorrecto: 'Contraseña incorrecta',
  usuario_inactivo:    'Usuario inactivo',
  fuera_horario:       'Fuera de horario del rol'
};

/**
 * Intentos de acceso al sistema (OTD-GER-09, script 53).
 * Solo Administración y Gerencia. Consulta por pantalla (sin PDF): tabla
 * paginada con filtros por fecha, resultado (exitoso/fallido) y correo.
 */
@Component({
  selector: 'app-accesos',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule,
    CampoTextoDirective
  ],
  templateUrl: './accesos.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class AccesosComponent implements OnInit {

  accesos: LogAccesoRow[] = [];
  total = 0;
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];
  loading = true;

  // Filtros
  desde = '';
  hasta = '';
  resultado = '';   // '' = todos | 'exitoso' | 'fallido'
  email = '';
  private email$ = new Subject<string>();

  columnas = ['fecha', 'email', 'usuario', 'resultado', 'motivo', 'ip', 'origen'];

  constructor(private srv: AccesosService, private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    this.cargar();
    this.email$.pipe(debounceTime(350), distinctUntilChanged()).subscribe(() => {
      this.pagina = 0;
      this.cargar();
    });
  }

  cargar(): void {
    this.loading = true;
    this.srv.consultar({
      desde: this.desde, hasta: this.hasta, resultado: this.resultado,
      email: this.email, page: this.pagina, size: this.tamPagina
    }).subscribe({
      next: pg => { this.accesos = pg.items; this.total = pg.total; this.loading = false; },
      error: e => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'No se pudieron cargar los intentos de acceso'),
          'Cerrar', { duration: 5000 });
      }
    });
  }

  /** Aplicar filtros de fecha/resultado vuelve a la primera página. */
  aplicarFiltros(): void { this.pagina = 0; this.cargar(); }

  alEscribirEmail(): void { this.email$.next(this.email); }

  limpiar(): void {
    this.desde = ''; this.hasta = ''; this.resultado = ''; this.email = '';
    this.pagina = 0;
    this.cargar();
  }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.cargar();
  }

  etiquetaMotivo(m: string | null): string {
    return m ? (MOTIVOS[m] || m) : '—';
  }
}
