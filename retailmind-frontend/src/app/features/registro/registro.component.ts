import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/services/auth.service';
import { mensajeError } from '../../core/services/api-error.util';
import { CampoNumeroDirective, CampoTextoDirective } from '../../core/validacion';

interface CategoriaInteres { id: number; nombre: string; elegida: boolean; }
interface CiudadRef { id: number; nombre: string; provincia?: string; }

/**
 * Alta de cliente desde la tienda, en cuatro pasos.
 *
 * <h2>Dónde se crea la cuenta, que es la decisión que ordena todo lo demás</h2>
 *
 * La cuenta NACE AL TERMINAR EL PASO 2, no al final. Los pasos 3 y 4
 * —dirección e intereses— ya se guardan con la sesión recién abierta, por los
 * endpoints normales del perfil. Tres consecuencias, y las tres son el motivo:
 *
 *  1. **Omitir de verdad es posible.** Si la cuenta se creara al final, saltarse
 *     el paso 3 significaría mandar una dirección vacía y el «omitir» sería
 *     decorativo. Aquí, omitir es no llamar a nada.
 *  2. **Nada opcional viaja por una ruta anónima.** El único endpoint público es
 *     el del alta; direcciones e intereses siguen exigiendo CLIENTE, con su RLS
 *     intacta. No hubo que abrir ni un permiso más.
 *  3. **Cerrar el navegador en el paso 3 deja una cuenta usable**, no un
 *     registro a medias que haya que limpiar después.
 *
 * <h2>El rol</h2>
 *
 * No se manda. No hay campo, no hay valor por defecto, no hay nada que enviar:
 * `POST /api/auth/registro-cliente` no lo lee y la función del motor no lo
 * acepta en su firma (script 112). Esta pantalla solo crea CLIENTES porque no
 * tiene forma de pedir otra cosa.
 */
@Component({
  selector: 'app-registro',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, MatIconModule,
    MatProgressSpinnerModule, CampoTextoDirective, CampoNumeroDirective],
  templateUrl: './registro.component.html',
  styleUrl: './registro.component.scss'
})
export class RegistroComponent implements OnInit {

  private readonly api = environment.apiUrl;

  /** 1 datos · 2 acceso · 3 dirección · 4 intereses · 5 hecho */
  paso = 1;
  readonly ultimoPaso = 4;

  cargando = false;
  error: string | null = null;
  verClave = false;

  /** A dónde volver cuando termine; lo pone el muro de sesión. */
  volverA = '/shop';

  /** La cuenta ya existe (se creó al salir del paso 2). */
  cuentaCreada = false;

  datos = {
    nombre: '', apellido: '', telefono: '',
    tipoIdentificacion: '', numeroIdentificacion: '',
    fechaNacimiento: '', genero: '', aceptaMarketing: true
  };

  acceso = { email: '', password: '', repetir: '' };

  direccion = {
    alias: 'Casa', destinatario: '', callePrincipal: '', calleSecundaria: '',
    numero: '', referencia: '', codigoPostal: '', telefono: '',
    ciudadId: null as number | null, esPredeterminada: true
  };

  ciudades: CiudadRef[] = [];
  intereses: CategoriaInteres[] = [];

  /** Hoy, para que el selector de fecha no admita nacer mañana. */
  readonly hoy = new Date().toISOString().slice(0, 10);

  constructor(private readonly http: HttpClient,
              private readonly auth: AuthService,
              private readonly router: Router,
              private readonly ruta: ActivatedRoute) {}

  ngOnInit(): void {
    const volver = this.ruta.snapshot.queryParamMap.get('volver');
    // Solo se admite una ruta INTERNA. Con una URL absoluta esto sería un
    // redirector abierto: bastaría enlazar /registro?volver=https://… para que
    // la tienda mandara a la gente a otro sitio tras registrarse.
    if (volver && volver.startsWith('/') && !volver.startsWith('//')) {
      this.volverA = volver;
    }
    // Quien ya entró no tiene nada que hacer aquí.
    if (this.auth.isAuthenticated() && this.auth.hasRole('CLIENTE')) {
      this.router.navigateByUrl(this.volverA);
    }
  }

  // ── Validación por paso ───────────────────────────────────────────────────

  get nombreValido(): boolean { return this.datos.nombre.trim().length >= 2; }

  /** El tipo y el número de identificación van juntos: los dos o ninguno. */
  get identificacionCoherente(): boolean {
    const tiene = !!this.datos.numeroIdentificacion.trim();
    const tipo = !!this.datos.tipoIdentificacion;
    return tiene === tipo;
  }

  get paso1Valido(): boolean {
    return this.nombreValido && this.identificacionCoherente;
  }

  get correoValido(): boolean {
    return /^[A-Za-z0-9._+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)+$/.test(this.acceso.email.trim());
  }

  get claveValida(): boolean { return this.acceso.password.length >= 8; }
  get claveRepetida(): boolean { return this.acceso.password === this.acceso.repetir; }

  get paso2Valido(): boolean {
    return this.correoValido && this.claveValida && this.claveRepetida;
  }

  get direccionValida(): boolean {
    return !!this.direccion.destinatario.trim()
        && !!this.direccion.callePrincipal.trim()
        && !!this.direccion.ciudadId;
  }

  /** La dirección es opcional, pero A MEDIAS no vale: o entera o ninguna. */
  get direccionEmpezada(): boolean {
    return !!this.direccion.destinatario.trim() || !!this.direccion.callePrincipal.trim()
        || !!this.direccion.ciudadId;
  }

  get interesesElegidos(): number { return this.intereses.filter(i => i.elegida).length; }

  // ── Navegación ────────────────────────────────────────────────────────────

  atras(): void {
    this.error = null;
    // No se puede volver a los datos ni al acceso después de crear la cuenta:
    // ya no son un formulario, son una cuenta. Se corrigen desde el perfil.
    if (this.paso > 1 && !(this.cuentaCreada && this.paso <= 3)) { this.paso--; }
  }

  siguiente(): void {
    this.error = null;
    if (this.paso === 1) {
      if (!this.paso1Valido) { return; }
      // Cortesía: el destinatario del envío suele ser quien se registra.
      if (!this.direccion.destinatario) {
        this.direccion.destinatario =
          `${this.datos.nombre} ${this.datos.apellido}`.trim();
      }
      if (!this.direccion.telefono) { this.direccion.telefono = this.datos.telefono; }
      this.paso = 2;
      return;
    }
    if (this.paso === 2) {
      if (!this.paso2Valido) { return; }
      this.crearCuenta();
      return;
    }
    if (this.paso === 3) { this.guardarDireccion(); return; }
    if (this.paso === 4) { this.guardarIntereses(); }
  }

  /** «Omitir» en los pasos opcionales: no llama a nada, solo avanza. */
  omitir(): void {
    this.error = null;
    if (this.paso === 3) { this.paso = 4; this.cargarIntereses(); return; }
    if (this.paso === 4) { this.terminar(); }
  }

  // ── Llamadas ──────────────────────────────────────────────────────────────

  private crearCuenta(): void {
    this.cargando = true;
    this.error = null;
    const cuerpo = {
      email: this.acceso.email.trim().toLowerCase(),
      password: this.acceso.password,
      nombre: this.datos.nombre.trim(),
      apellido: this.datos.apellido.trim() || null,
      telefono: this.datos.telefono.trim() || null,
      tipoIdentificacion: this.datos.tipoIdentificacion || null,
      numeroIdentificacion: this.datos.numeroIdentificacion.trim() || null,
      fechaNacimiento: this.datos.fechaNacimiento || null,
      genero: this.datos.genero || null,
      aceptaMarketing: this.datos.aceptaMarketing
    };
    // La respuesta es una sesión ya iniciada; se guarda por el mismo camino que
    // el login normal para que el resto de la aplicación no note la diferencia.
    this.http.post<any>(`${this.api}/api/auth/registro-cliente`, cuerpo).subscribe({
      next: sesion => {
        this.auth.guardarSesion(sesion);
        this.cuentaCreada = true;
        this.cargando = false;
        this.paso = 3;
        this.cargarCiudades();
      },
      error: e => {
        this.cargando = false;
        this.error = mensajeError(e, 'No pudimos crear tu cuenta.');
      }
    });
  }

  private cargarCiudades(): void {
    this.http.get<CiudadRef[]>(`${this.api}/api/perfil/ciudades`)
      .subscribe({ next: c => this.ciudades = c, error: () => this.ciudades = [] });
  }

  private cargarIntereses(): void {
    this.http.get<CategoriaInteres[]>(`${this.api}/api/perfil/intereses`)
      .subscribe({ next: c => this.intereses = c, error: () => this.intereses = [] });
  }

  private guardarDireccion(): void {
    if (!this.direccionEmpezada) { this.omitir(); return; }
    if (!this.direccionValida) {
      this.error = 'Para guardar la dirección hacen falta el destinatario, la calle y la ciudad.';
      return;
    }
    this.cargando = true;
    this.error = null;
    this.http.post(`${this.api}/api/perfil/direcciones`, { ...this.direccion, tipo: 'envio' })
      .subscribe({
        next: () => { this.cargando = false; this.paso = 4; this.cargarIntereses(); },
        error: e => {
          this.cargando = false;
          this.error = mensajeError(e, 'No pudimos guardar la dirección.');
        }
      });
  }

  private guardarIntereses(): void {
    const elegidas = this.intereses.filter(i => i.elegida).map(i => i.id);
    if (!elegidas.length) { this.terminar(); return; }
    this.cargando = true;
    this.http.put(`${this.api}/api/perfil/intereses`, { categorias: elegidas })
      .subscribe({
        next: () => { this.cargando = false; this.terminar(); },
        error: e => {
          this.cargando = false;
          // Los intereses son lo MENOS importante del registro: si fallan, la
          // cuenta ya existe y bloquear aquí sería castigar al usuario por algo
          // que no le importa. Se avisa y se termina igual.
          this.error = mensajeError(e, 'No pudimos guardar tus intereses, pero tu cuenta ya está lista.');
          setTimeout(() => this.terminar(), 1800);
        }
      });
  }

  private terminar(): void {
    this.paso = 5;
    setTimeout(() => this.router.navigateByUrl(this.volverA), 1600);
  }

  irAhora(): void { this.router.navigateByUrl(this.volverA); }
}
