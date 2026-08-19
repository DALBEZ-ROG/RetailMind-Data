import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatTabsModule } from '@angular/material/tabs';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable } from 'rxjs';
import { RedLogisticaService } from '../../../core/services/red-logistica.service';
import { mensajeError } from '../../../core/services/api-error.util';

/** Una de las cinco entidades de la red. */
type Entidad = 'bodegas' | 'transportistas' | 'metodos' | 'zonas' | 'tarifas';

/**
 * RED LOGÍSTICA — bodegas, transportistas, métodos, zonas y tarifas de envío.
 *
 * ── POR QUÉ ESTA PANTALLA EXISTE ───────────────────────────────────────────
 * Cierra el defecto D-09. Estas cinco tablas sostienen el ciclo de venta
 * entero y hasta ahora **solo se podían poblar con scripts de siembra**: no
 * había ni un endpoint de escritura en el backend. En la práctica eso
 * significaba que una instalación nueva no podía tomar un pedido —hace falta
 * una bodega— y que una instalación en marcha no podía abrir una segunda
 * bodega ni contratar un transportista sin un DBA. La pantalla de
 * transferencias entre bodegas funcionaba, pero su operando no se podía crear.
 *
 * ── POR QUÉ CINCO PESTAÑAS Y NO CINCO PANTALLAS ────────────────────────────
 * Son tablas de CONFIGURACIÓN, no de operación diaria: se tocan al montar la
 * tienda y luego de tarde en tarde. Además solo tienen sentido juntas — una
 * tarifa necesita su zona y su método, y un método su transportista—, así que
 * separarlas obligaría a saltar entre pantallas para dar de alta una sola cosa.
 *
 * ── BAJA LÓGICA, NUNCA BORRADO ─────────────────────────────────────────────
 * No hay «eliminar»: se desactiva. Una bodega o un transportista está
 * referenciado por pedidos, envíos y kardex históricos, y borrarlo rompería la
 * trazabilidad. Es el mismo criterio que marcas y categorías.
 */
@Component({
  selector: 'app-red-logistica',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatTabsModule, MatIconModule,
    MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatSnackBarModule, MatTooltipModule],
  templateUrl: './red-logistica.component.html',
  styleUrl: '../operativo-shared.scss'
})
export class RedLogisticaComponent implements OnInit {

  cargando = true;
  refs: any = { paises: [], provincias: [], ciudades: [], transportistas: [], zonas: [], metodos: [] };

  bodegas: any[] = [];
  transportistas: any[] = [];
  metodos: any[] = [];
  zonas: any[] = [];
  tarifas: any[] = [];

  /** Qué formulario está abierto y sobre qué registro (null = alta). */
  formAbierto: Entidad | null = null;
  editando: any = null;
  modelo: any = {};
  guardando = false;

  colBodegas = ['codigo', 'nombre', 'ciudad', 'direccion', 'principal', 'inventario', 'acciones'];
  colTransportistas = ['nombre', 'ruc', 'telefono', 'email', 'metodos', 'acciones'];
  colMetodos = ['codigo', 'nombre', 'transportista', 'plazo', 'orden', 'acciones'];
  colZonas = ['nombre', 'nivel', 'ambito', 'tarifas', 'acciones'];
  colTarifas = ['zona', 'metodo', 'costoBase', 'costoPorKg', 'tramo', 'gratisDesde', 'acciones'];

  constructor(private red: RedLogisticaService, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.cargarTodo(); }

  cargarTodo(): void {
    this.cargando = true;
    this.red.referencias().subscribe({ next: r => this.refs = r, error: () => {} });
    this.red.bodegas().subscribe({ next: d => this.bodegas = d, error: e => this.error(e) });
    this.red.transportistas().subscribe({ next: d => this.transportistas = d, error: e => this.error(e) });
    this.red.metodos().subscribe({ next: d => this.metodos = d, error: e => this.error(e) });
    this.red.zonas().subscribe({ next: d => this.zonas = d, error: e => this.error(e) });
    this.red.tarifas().subscribe({
      next: d => { this.tarifas = d; this.cargando = false; },
      error: e => { this.error(e); this.cargando = false; }
    });
  }

  // ── Formulario colapsable ────────────────────────────────────────────

  abrirAlta(entidad: Entidad): void {
    this.formAbierto = entidad;
    this.editando = null;
    this.modelo = this.modeloVacio(entidad);
  }

  abrirEdicion(entidad: Entidad, fila: any): void {
    this.formAbierto = entidad;
    this.editando = fila;
    this.modelo = this.desdeFila(entidad, fila);
  }

  cerrar(): void {
    this.formAbierto = null;
    this.editando = null;
    this.modelo = {};
  }

  /**
   * Un modelo nuevo empieza con los campos en `null`, no en `0` ni en `''`.
   *
   * No es cosmético: un `0` en `costoBase` es un valor AFIRMADO —transportar
   * gratis— y el motor lo acepta; un `null` deja el campo vacío y la
   * validación lo puede exigir. Es la misma lección del peso de variante.
   */
  private modeloVacio(entidad: Entidad): any {
    switch (entidad) {
      case 'bodegas':        return { codigo: null, nombre: null, ciudadId: null,
                                      direccion: null, telefono: null, esPrincipal: false };
      case 'transportistas': return { nombre: null, ruc: null, telefono: null, email: null,
                                      sitioWeb: null, urlSeguimiento: null };
      case 'metodos':        return { codigo: null, nombre: null, descripcion: null,
                                      transportistaId: null, diasEntregaMin: null,
                                      diasEntregaMax: null, orden: 0 };
      case 'zonas':          return { nombre: null, paisId: null, provinciaId: null,
                                      ciudadId: null, descripcion: null };
      case 'tarifas':        return { zonaEnvioId: null, metodoEnvioId: null, costoBase: null,
                                      costoPorKg: null, pesoMinKg: null, pesoMaxKg: null,
                                      envioGratisDesde: null };
    }
  }

  private desdeFila(entidad: Entidad, f: any): any {
    switch (entidad) {
      case 'bodegas':        return { codigo: f.codigo, nombre: f.nombre, ciudadId: f.ciudad_id,
                                      direccion: f.direccion, telefono: f.telefono,
                                      esPrincipal: f.es_principal };
      case 'transportistas': return { nombre: f.nombre, ruc: f.ruc, telefono: f.telefono,
                                      email: f.email, sitioWeb: f.sitio_web,
                                      urlSeguimiento: f.url_seguimiento };
      case 'metodos':        return { codigo: f.codigo, nombre: f.nombre, descripcion: f.descripcion,
                                      transportistaId: f.transportista_id,
                                      diasEntregaMin: f.dias_entrega_min,
                                      diasEntregaMax: f.dias_entrega_max, orden: f.orden };
      case 'zonas':          return { nombre: f.nombre, paisId: f.pais_id,
                                      provinciaId: f.provincia_id, ciudadId: f.ciudad_id,
                                      descripcion: f.descripcion };
      case 'tarifas':        return { zonaEnvioId: f.zona_envio_id, metodoEnvioId: f.metodo_envio_id,
                                      costoBase: f.costo_base, costoPorKg: f.costo_por_kg,
                                      pesoMinKg: f.peso_min_kg, pesoMaxKg: f.peso_max_kg,
                                      envioGratisDesde: f.envio_gratis_desde };
    }
  }

  guardar(): void {
    const e = this.formAbierto;
    if (!e || this.guardando) { return; }
    this.guardando = true;
    const id = this.editando?.id;
    const op: Observable<any> = id ? this.editar(e, id) : this.crear(e);
    op.subscribe({
      next: () => {
        this.snackBar.open(id ? 'Cambios guardados' : 'Registro creado', 'Cerrar',
                           { duration: 3000 });
        this.guardando = false;
        this.cerrar();
        this.cargarTodo();
      },
      error: err => { this.guardando = false; this.error(err); }
    });
  }

  private crear(e: Entidad): Observable<any> {
    const m = this.modelo;
    switch (e) {
      case 'bodegas':        return this.red.crearBodega(m);
      case 'transportistas': return this.red.crearTransportista(m);
      case 'metodos':        return this.red.crearMetodo(m);
      case 'zonas':          return this.red.crearZona(m);
      case 'tarifas':        return this.red.crearTarifa(m);
    }
  }

  private editar(e: Entidad, id: number): Observable<any> {
    const m = this.modelo;
    switch (e) {
      case 'bodegas':        return this.red.editarBodega(id, m);
      case 'transportistas': return this.red.editarTransportista(id, m);
      case 'metodos':        return this.red.editarMetodo(id, m);
      case 'zonas':          return this.red.editarZona(id, m);
      case 'tarifas':        return this.red.editarTarifa(id, m);
    }
  }

  alternarActivo(e: Entidad, fila: any): void {
    const activo = !fila.activo;
    const op: Observable<any> =
      e === 'bodegas'        ? this.red.activarBodega(fila.id, activo)
    : e === 'transportistas' ? this.red.activarTransportista(fila.id, activo)
    : e === 'metodos'        ? this.red.activarMetodo(fila.id, activo)
    : e === 'zonas'          ? this.red.activarZona(fila.id, activo)
    :                          this.red.activarTarifa(fila.id, activo);
    op.subscribe({
      next: () => { fila.activo = activo; },
      error: err => this.error(err)
    });
  }

  // ── Ayudas de la vista ───────────────────────────────────────────────

  /** Provincias del país elegido; sin país, ninguna. */
  provinciasDelPais(): any[] {
    return (this.refs.provincias || []).filter((p: any) => p.pais_id === this.modelo.paisId);
  }

  /** Ciudades de la provincia elegida. */
  ciudadesDeProvincia(): any[] {
    return (this.refs.ciudades || []).filter((c: any) => c.provincia_id === this.modelo.provinciaId);
  }

  ambitoDeZona(z: any): string {
    return z.ciudad || z.provincia || z.pais || '—';
  }

  tramoDeTarifa(t: any): string {
    const min = t.peso_min_kg ?? 0;
    return t.peso_max_kg != null ? `${min} – ${t.peso_max_kg} kg` : `desde ${min} kg`;
  }

  plazoDeMetodo(m: any): string {
    if (m.dias_entrega_min == null && m.dias_entrega_max == null) { return '—'; }
    if (m.dias_entrega_max == null) { return `${m.dias_entrega_min} d`; }
    return `${m.dias_entrega_min ?? 0} – ${m.dias_entrega_max} d`;
  }

  private error(err: any): void {
    this.snackBar.open(mensajeError(err, "No se pudo completar la operación."), "Cerrar", { duration: 6000 });
  }
}
