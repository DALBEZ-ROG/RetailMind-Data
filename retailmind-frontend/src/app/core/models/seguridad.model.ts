/**
 * Mapa de la seguridad del MOTOR (pantalla /operativo/seguridad/permisos).
 *
 * Todo esto sale de los catálogos de PostgreSQL, no de tablas de aplicación:
 * `permiso` y `rol_permiso` existen en el esquema pero están VACÍAS y son
 * vestigiales. El control de acceso real lo hacen los GRANT, las políticas RLS
 * y la ventana horaria.
 */

export interface ResumenSeguridad {
  roles_grupo: number;
  roles_login: number;
  politicas_rls: number;
  tablas_con_rls: number;
  columnas_con_acl: number;
  tablas_con_acl_columna: number;
  /** Los 7 privilegios del estándar SQL (la cifra documentada: 1.354). */
  grants_tabla: number;
  /** MAINTAIN (PostgreSQL 17+), que information_schema no modela. */
  grants_maintain: number;
  tablas_totales: number;
  usuarios_activos: number;
}

export interface RolMotor {
  rol_motor: string;
  rol_app: string | null;
  clase: 'grupo' | 'superusuario' | 'servicio';
  puede_login: boolean;
  bypass_rls: boolean;
  es_superusuario: boolean;
  usuarios_activos: number;
  usuarios_total: number;
  permisos_tabla: number;
  permisos_columna: number;
  politicas: number;
  /** Cuántos roles son miembros DE éste (cada grp_* tiene 1: retailmind_app). */
  miembros_motor: number;
  /** De cuántos roles es miembro ÉSTE (retailmind_app: 9 — lo que permite SET LOCAL ROLE). */
  pertenece_a: number;
}

export interface UsuarioDeRol {
  id: number;
  nombre: string;
  apellido: string;
  email: string;
  activo: boolean;
  rol_app: string;
  rol_nombre: string;
  rol_motor: string;
}

export interface VentanaHoraria {
  id: number;
  rol_grupo: string;
  dia_semana: number;
  hora_inicio: string;
  hora_fin: string;
  activo: boolean;
  es_hoy: boolean;
  /** Lo calcula esta_en_horario(): la MISMA función que evalúan las políticas. */
  dentro_ahora: boolean;
}

export interface ReglaProtegida {
  id: string;
  titulo: string;
  porque: string;
  alcance: string[];
}

export interface MapaSeguridad {
  resumen: ResumenSeguridad;
  roles: RolMotor[];
  usuarios: UsuarioDeRol[];
  /**
   * Cuántos usuarios se listan POR ROL en `usuarios`. `usuario` tiene 50.182
   * filas y devolverlas todas hacía que este sobre pesara 8,6 MB; el recuento
   * exacto por rol sigue estando en la tabla de roles.
   */
  topeUsuariosPorRol?: number;
  horarios: VentanaHoraria[];
  protegidos: ReglaProtegida[];
}

export interface PermisoTabla {
  rol_motor: string;
  tabla: string;
  privilegio: string;
  transferible: boolean;
}

export interface PermisoColumna {
  rol_motor: string;
  tabla: string;
  columna: string;
  privilegio: string;
  /** Si el rol ya tiene el privilegio de TABLA, esta entrada no restringe nada. */
  cubierto_por_tabla: boolean;
}

export interface PermisosPage {
  tabla: PermisoTabla[];
  columna: PermisoColumna[];
  totalTabla: number;
  totalColumna: number;
  totalTablaEstandar: number;
  totalTablaMaintain: number;
}

export interface PoliticaRls {
  tabla: string;
  politica: string;
  comando: string;
  tipo: string;
  roles: string;
  condicion_lectura: string | null;
  condicion_escritura: string | null;
  explicacion: string;
  restringePorHorario: boolean;
  restringePorCliente: boolean;
  usaSubconsulta: boolean;
}

export interface ObjetoAdministrable {
  tabla: string;
  /** Con RLS, conceder SELECT no basta: sin politica el rol lee CERO filas. */
  con_rls: boolean;
  /** Nombres de columna separados por coma (string_agg, no array). */
  columnas: string;
}

export interface CambioPermiso {
  /** El motor CONFIRMÓ que el privilegio efectivo cambió. */
  aplicado: boolean;
  antes: boolean;
  despues: boolean;
  sentencia: string;
  accion: string;
  rol: string;
  tabla: string;
  columna: string | null;
  privilegio: string;
  mensaje: string;
}

/** Rol creado desde la pantalla (script 87). */
export interface RolPersonalizado {
  id: number;
  codigo: string;
  nombre: string;
  activo: boolean;
  rol_grupo: string;
  /** Rol del sistema al que imita para las RUTAS. No concede nada en la BD. */
  rol_base_codigo: string | null;
  fecha_creacion: string;
  usuarios: number;
  politicas: number;
  ventanas: number;
}

export interface ResultadoRol {
  codigo: string;
  rol_grupo: string;
  politicas: number;
  ventanas: number;
  mensaje: string;
}
