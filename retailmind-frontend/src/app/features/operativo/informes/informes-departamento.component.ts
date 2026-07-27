import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged } from 'rxjs/operators';

import { InformesService } from '../../../core/services/informes.service';
import { AuthService } from '../../../core/services/auth.service';
import { mensajeError } from '../../../core/services/api-error.util';
import {
  ColumnaInforme, DefinicionDepartamento, DefinicionInforme, FiltroInforme,
  KpiInforme, TipoValor
} from '../../../core/models/informe.model';
import { definicionDepartamento } from './definiciones/catalogo-informes';

/**
 * PANTALLA GENÉRICA de informes tácticos — sirve a los seis departamentos.
 *
 * El departamento llega por `data.departamento` de la ruta; de ahí sale su
 * `DefinicionDepartamento` y con ella se pintan el selector de informes, los
 * filtros, el resumen y la tabla. NO hay lógica de negocio aquí: todo lo
 * específico vive en el archivo de definiciones del departamento y en su
 * servicio del backend.
 *
 * Consulta POR PANTALLA: filtros en vivo, tabla paginada y registros
 * visibles. NO hay exportación a PDF (decisión de alcance del nivel táctico;
 * los PDF quedan para documentos operativos).
 *
 * Permisos: el selector solo ofrece los informes cuyo `roles` incluye el rol
 * del usuario — espeja SecurityConfig, que es quien realmente decide, y evita
 * disparar peticiones que la API negaría con 403.
 */
@Component({
  selector: 'app-informes-departamento',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatIconModule, MatButtonModule,
    MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule,
    MatTooltipModule, MatPaginatorModule, MatProgressBarModule],
  templateUrl: './informes-departamento.component.html',
  styleUrls: ['../operativo-shared.scss', './informes.scss']
})
export class InformesDepartamentoComponent implements OnInit {

  depto?: DefinicionDepartamento;
  /** Informes que el rol actual puede consultar. */
  disponibles: DefinicionInforme[] = [];
  actual?: DefinicionInforme;

  filas: Record<string, any>[] = [];
  resumen: KpiInforme[] = [];
  columnas: string[] = [];
  total = 0;
  pagina = 0;
  tamPagina = 25;
  readonly tamanos = [25, 50, 100];
  loading = false;
  /** Aviso cuando el backend recortó el informe (VEN-02 para un vendedor). */
  avisoAlcance = '';

  /** Valores actuales de los filtros del informe seleccionado. */
  valores: Record<string, string> = {};
  private texto$ = new Subject<void>();

  constructor(private ruta: ActivatedRoute,
              private srv: InformesService,
              private auth: AuthService,
              private snackBar: MatSnackBar) {}

  ngOnInit(): void {
    const nombre = this.ruta.snapshot.data['departamento'] as string;
    this.depto = definicionDepartamento(nombre);
    if (!this.depto) {
      this.snackBar.open(`No hay informes definidos para «${nombre}»`, 'Cerrar', { duration: 5000 });
      return;
    }
    const rol = this.auth.getCurrentUser()?.rol ?? '';
    this.disponibles = this.depto.informes.filter(i => i.roles.includes(rol));

    this.texto$.pipe(debounceTime(350), distinctUntilChanged())
      .subscribe(() => { this.pagina = 0; this.cargar(); });

    if (this.disponibles.length) {
      this.seleccionar(this.disponibles[0]);
    }
  }

  // ── Selección de informe ─────────────────────────────────────────────

  seleccionar(informe: DefinicionInforme): void {
    this.actual = informe;
    this.columnas = informe.columnas.map(c => c.campo);
    this.valores = {};
    informe.filtros.forEach(f => this.valores[f.param] = this.valorInicialDe(f));
    this.pagina = 0;
    this.filas = [];
    this.resumen = [];
    this.total = 0;
    this.cargar();
  }

  esActual(informe: DefinicionInforme): boolean {
    return this.actual?.id === informe.id;
  }

  // ── Consulta ─────────────────────────────────────────────────────────

  cargar(): void {
    if (!this.depto || !this.actual) { return; }
    const informe = this.actual;
    this.loading = true;
    this.avisoAlcance = '';

    const filtros: Record<string, string | number> = { ...this.valores };
    // El filtro de período se envía descompuesto en año y mes.
    if (filtros['periodo']) {
      const [anio, mes] = String(filtros['periodo']).split('-');
      filtros['anio'] = anio;
      filtros['mes'] = String(Number(mes));
    }
    delete filtros['periodo'];
    if (!informe.sinPaginar) {
      filtros['page'] = this.pagina;
      filtros['size'] = this.tamPagina;
    }

    this.srv.consultar(this.depto.departamento, informe.endpoint, filtros).subscribe({
      next: sobre => {
        this.filas = sobre.items ?? [];
        this.total = sobre.total ?? this.filas.length;
        this.resumen = sobre.resumen ?? [];
        if (sobre.alcance === 'propio') {
          this.avisoAlcance = 'Estás viendo únicamente tus propias ventas: '
                            + 'el detalle del resto del equipo es atribución de Gerencia.';
        }
        this.loading = false;
      },
      error: e => {
        this.filas = [];
        this.resumen = [];
        this.total = 0;
        this.loading = false;
        this.snackBar.open(
          mensajeError(e, `No se pudo consultar el informe ${informe.id}`),
          'Cerrar', { duration: 6000 });
      }
    });
  }

  aplicarFiltros(): void { this.pagina = 0; this.cargar(); }

  alEscribir(): void { this.texto$.next(); }

  limpiar(): void {
    this.actual?.filtros.forEach(f => this.valores[f.param] = this.valorInicialDe(f));
    this.pagina = 0;
    this.cargar();
  }

  /** El período arranca SIEMPRE en el mes en curso; el resto, en lo declarado. */
  private valorInicialDe(f: FiltroInforme): string {
    if (f.valorInicial) { return f.valorInicial; }
    return f.tipo === 'periodo' ? this.mesActual() : '';
  }

  alPaginar(e: PageEvent): void {
    this.pagina = e.pageIndex;
    this.tamPagina = e.pageSize;
    this.cargar();
  }

  // ── Formato de celdas y tarjetas ─────────────────────────────────────

  valor(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    if (crudo === null || crudo === undefined || crudo === '') { return '—'; }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar
      ? texto.slice(0, col.recortar) + '…'
      : texto;
  }

  /** Texto completo para el tooltip de las celdas recortadas. */
  valorCompleto(col: ColumnaInforme, fila: Record<string, any>): string {
    const crudo = fila[col.campo];
    if (crudo === null || crudo === undefined || crudo === '') { return ''; }
    const texto = col.etiqueta ? col.etiqueta(crudo, fila) : this.formatear(crudo, col.tipo);
    return col.recortar && texto.length > col.recortar ? texto : '';
  }

  formatear(crudo: any, tipo: TipoValor): string {
    switch (tipo) {
      case 'moneda':
        return '$' + Number(crudo).toLocaleString('es-EC',
          { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      case 'numero':
        return Number(crudo).toLocaleString('es-EC');
      case 'porcentaje':
        return Number(crudo).toLocaleString('es-EC',
          { minimumFractionDigits: 1, maximumFractionDigits: 2 }) + ' %';
      case 'dias': {
        const n = Number(crudo);
        return n === 1 ? '1 día' : `${n.toLocaleString('es-EC')} días`;
      }
      case 'fecha':
        return new Date(crudo).toLocaleDateString('es-EC',
          { day: '2-digit', month: '2-digit', year: '2-digit' });
      case 'fechaHora':
        return new Date(crudo).toLocaleString('es-EC',
          { day: '2-digit', month: '2-digit', year: '2-digit',
            hour: '2-digit', minute: '2-digit' });
      case 'estrellas':
        return '★'.repeat(Number(crudo)) + '☆'.repeat(Math.max(5 - Number(crudo), 0));
      case 'booleano':
        return crudo === true ? 'Sí' : 'No';
      default:
        return String(crudo);
    }
  }

  claseChip(col: ColumnaInforme, fila: Record<string, any>): string {
    return col.color ? col.color(fila) : 'neutral';
  }

  /**
   * Barra de avance: solo para los informes que la piden (`barraAvance`) y que
   * traen un porcentaje en el resumen. No basta con que HAYA un porcentaje —
   * una tasa de resolución (SOP-05) o una ocupación media (INV-08) no son un
   * avance sobre una meta, y pintarlas como tal sería un dato falso.
   */
  get avance(): number | null {
    if (!this.actual?.barraAvance) { return null; }
    const k = this.resumen.find(r => r.tipo === 'porcentaje');
    return k ? Math.min(Number(k.valor), 100) : null;
  }

  /** Valor del filtro de período por defecto: el mes en curso (input month). */
  mesActual(): string {
    const hoy = new Date();
    return `${hoy.getFullYear()}-${String(hoy.getMonth() + 1).padStart(2, '0')}`;
  }
}
