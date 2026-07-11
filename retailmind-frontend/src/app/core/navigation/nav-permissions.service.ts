import { Injectable } from '@angular/core';
import { AuthService } from '../services/auth.service';
import { AccionNav, AreaNav, DASHBOARD_AREAS, PermisoNav, ROLES_POR_PERMISO } from './nav-model';

/**
 * Evalúa la matriz ROLES_POR_PERMISO contra el usuario autenticado.
 * Lo consumen el sidebar (getters canX de AppComponent) y el dashboard de
 * inicio, de modo que ambos rendericen exactamente la misma visibilidad.
 */
@Injectable({ providedIn: 'root' })
export class NavPermissionsService {

  constructor(private auth: AuthService) {}

  can(permiso: PermisoNav): boolean {
    const user = this.auth.getCurrentUser();
    return !!user && ROLES_POR_PERMISO[permiso].includes(user.rol);
  }

  /** Áreas del dashboard con al menos una acción visible para el rol actual. */
  areasVisibles(): AreaNav[] {
    return DASHBOARD_AREAS.filter(area => this.accionesVisibles(area).length > 0);
  }

  area(id: string): AreaNav | undefined {
    return DASHBOARD_AREAS.find(a => a.id === id);
  }

  accionesVisibles(area: AreaNav): AccionNav[] {
    return area.acciones.filter(accion => this.can(accion.permiso));
  }
}
