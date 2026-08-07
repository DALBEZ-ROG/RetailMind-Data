import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatChipsModule } from '@angular/material/chips';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { environment } from '../../../environments/environment';
import { ConfirmService } from '../../core/services/confirm.service';
import { mensajeError } from '../../core/services/api-error.util';
import { CodigoLegiblePipe } from '../../core/pipes/etiquetas.pipe';

/**
 * Perfil sobre PostgreSQL. Para el CLIENTE: datos de la tabla cliente
 * (RLS: su fila), estadísticas reales de pedidos/wishlist y CRUD de
 * direcciones (tabla direccion, baja lógica). Para roles operativos:
 * ficha básica del JWT, sin datos de tienda.
 */
@Component({
  selector: 'app-perfil',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    MatCardModule, MatButtonModule, MatIconModule,
    MatFormFieldModule, MatInputModule, MatSelectModule,
    MatChipsModule, MatCheckboxModule, MatProgressSpinnerModule,
    MatSnackBarModule, MatTooltipModule, CodigoLegiblePipe],
  templateUrl: './perfil.component.html',
  styleUrl: './perfil.component.scss'
})
export class PerfilComponent implements OnInit {

  private readonly base = `${environment.apiUrl}/api/perfil`;

  perfil: any = null;
  loading = true;

  // Formulario de datos personales (solo cliente)
  datos = { nombre: '', apellido: '', telefono: '', genero: '', aceptaMarketing: false };
  guardandoDatos = false;

  // Direcciones
  direcciones: any[] = [];
  ciudades: any[] = [];
  mostrarFormDireccion = false;
  editandoId: number | null = null;
  guardandoDireccion = false;
  dir = this.direccionVacia();

  constructor(private http: HttpClient, private snackBar: MatSnackBar,
              private confirmar: ConfirmService) {}

  ngOnInit(): void {
    this.cargarPerfil();
  }

  get esCliente(): boolean {
    return !!this.perfil?.esCliente;
  }

  private cargarPerfil(): void {
    this.loading = true;
    this.http.get<any>(this.base).subscribe({
      next: (data) => {
        this.perfil = data;
        this.loading = false;
        if (data.esCliente) {
          this.datos = {
            nombre: data.nombre || '',
            apellido: data.apellido || '',
            telefono: data.telefono || '',
            genero: data.genero || '',
            aceptaMarketing: !!data.aceptaMarketing
          };
          this.cargarDirecciones();
          this.cargarCiudades();
        }
      },
      error: (e) => {
        this.loading = false;
        this.snackBar.open(mensajeError(e, 'Error al cargar el perfil'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  guardarDatos(): void {
    if (!this.datos.nombre.trim()) {
      this.snackBar.open('El nombre es requerido', 'OK', { duration: 2500 });
      return;
    }
    this.guardandoDatos = true;
    this.http.put(this.base, this.datos).subscribe({
      next: () => {
        this.guardandoDatos = false;
        this.snackBar.open('Datos actualizados ✓', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargarPerfil();
      },
      error: (e) => {
        this.guardandoDatos = false;
        this.snackBar.open(mensajeError(e, 'Error al actualizar datos'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  // ── Direcciones ───────────────────────────────────────────────────────

  cargarDirecciones(): void {
    this.http.get<any[]>(`${this.base}/direcciones`).subscribe({
      next: (d) => this.direcciones = d,
      error: () => this.direcciones = []
    });
  }

  cargarCiudades(): void {
    this.http.get<any[]>(`${this.base}/ciudades`).subscribe({
      next: (c) => this.ciudades = c,
      error: () => this.ciudades = []
    });
  }

  nuevaDireccion(): void {
    this.editandoId = null;
    this.dir = this.direccionVacia();
    this.mostrarFormDireccion = true;
  }

  editarDireccion(d: any): void {
    this.editandoId = d.id;
    this.dir = {
      alias: d.alias || '', destinatario: d.destinatario || '',
      callePrincipal: d.callePrincipal || '', calleSecundaria: d.calleSecundaria || '',
      numero: d.numero || '', referencia: d.referencia || '',
      codigoPostal: d.codigoPostal || '', telefono: d.telefono || '',
      ciudadId: d.ciudadId, tipo: d.tipo || 'envio',
      esPredeterminada: !!d.esPredeterminada
    };
    this.mostrarFormDireccion = true;
  }

  cancelarDireccion(): void {
    this.mostrarFormDireccion = false;
    this.editandoId = null;
  }

  guardarDireccion(): void {
    if (!this.dir.destinatario.trim() || !this.dir.callePrincipal.trim() || !this.dir.ciudadId) {
      this.snackBar.open('Destinatario, calle principal y ciudad son requeridos', 'OK', { duration: 3000 });
      return;
    }
    this.guardandoDireccion = true;
    const req = this.editandoId
      ? this.http.put(`${this.base}/direcciones/${this.editandoId}`, this.dir)
      : this.http.post(`${this.base}/direcciones`, this.dir);
    req.subscribe({
      next: () => {
        this.guardandoDireccion = false;
        this.mostrarFormDireccion = false;
        this.editandoId = null;
        this.snackBar.open('Dirección guardada ✓', 'OK', { duration: 2500, panelClass: ['snack-success'] });
        this.cargarDirecciones();
      },
      error: (e) => {
        this.guardandoDireccion = false;
        this.snackBar.open(mensajeError(e, 'Error al guardar la dirección'), 'Cerrar', { duration: 4000 });
      }
    });
  }

  eliminarDireccion(d: any): void {
    // Baja LÓGICA: `PerfilService.eliminarDireccion` hace
    // `UPDATE direccion SET activo = false, es_predeterminada = false`
    // (grp_cliente no tiene DELETE sobre direccion, por diseño). Por eso el
    // mensaje no promete un borrado que no ocurre, ni asusta con los pedidos.
    this.confirmar.eliminacion(
      `la dirección «${d.alias || d.callePrincipal}»`,
      'Dejará de aparecer en tu perfil y de ofrecerse en el checkout. Los pedidos que ya se '
      + 'enviaron a esta dirección conservan sus datos de envío intactos.'
      + (d.esPredeterminada
         ? ' Además es tu dirección predeterminada: al eliminarla te quedas sin ninguna, y '
           + 'tendrás que marcar otra.'
         : '')
    ).subscribe(ok => {
      if (!ok) return;
      this.http.delete(`${this.base}/direcciones/${d.id}`).subscribe({
        next: () => {
          this.snackBar.open('Dirección eliminada', 'OK', { duration: 2000 });
          this.cargarDirecciones();
        },
        error: (e) => this.snackBar.open(mensajeError(e, 'Error al eliminar'), 'Cerrar', { duration: 4000 })
      });
    });
  }

  marcarPredeterminada(d: any): void {
    this.http.put(`${this.base}/direcciones/${d.id}`, {
      alias: d.alias, destinatario: d.destinatario,
      callePrincipal: d.callePrincipal, calleSecundaria: d.calleSecundaria,
      numero: d.numero, referencia: d.referencia,
      codigoPostal: d.codigoPostal, telefono: d.telefono,
      ciudadId: d.ciudadId, tipo: d.tipo, esPredeterminada: true
    }).subscribe({
      next: () => {
        this.snackBar.open('Dirección predeterminada ✓', 'OK', { duration: 2000 });
        this.cargarDirecciones();
      },
      error: (e) => this.snackBar.open(mensajeError(e, 'Error'), 'Cerrar', { duration: 3000 })
    });
  }

  private direccionVacia() {
    return {
      alias: '', destinatario: '', callePrincipal: '', calleSecundaria: '',
      numero: '', referencia: '', codigoPostal: '', telefono: '',
      ciudadId: null as number | null, tipo: 'envio', esPredeterminada: false
    };
  }

  getInitial(): string {
    return (this.perfil?.nombre || this.perfil?.username || '?').charAt(0).toUpperCase();
  }

  getAvatarColor(): string {
    return this.esCliente ? '#00897b' : '#3f51b5';
  }
}
