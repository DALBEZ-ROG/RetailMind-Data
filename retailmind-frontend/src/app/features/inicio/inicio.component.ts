import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/services/auth.service';
import { NavPermissionsService } from '../../core/navigation/nav-permissions.service';
import { AccionNav, AreaNav } from '../../core/navigation/nav-model';

/**
 * Dashboard de inicio en dos niveles:
 *  - /inicio        → cards de las áreas visibles para el rol actual
 *  - /inicio/:area  → cards de las acciones de esa área (también filtradas)
 * La visibilidad sale de NavPermissionsService (misma matriz que el sidebar).
 */
@Component({
  selector: 'app-inicio',
  standalone: true,
  imports: [CommonModule, RouterLink, MatIconModule],
  templateUrl: './inicio.component.html',
  styleUrl: './inicio.component.scss'
})
export class InicioComponent {
  areas: AreaNav[] = [];
  area: AreaNav | null = null;
  acciones: AccionNav[] = [];

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private nav: NavPermissionsService,
    private auth: AuthService
  ) {
    this.route.paramMap.subscribe(params => {
      this.areas = this.nav.areasVisibles();
      const id = params.get('area');
      if (!id) {
        this.area = null;
        this.acciones = [];
        return;
      }
      const area = this.nav.area(id);
      const acciones = area ? this.nav.accionesVisibles(area) : [];
      if (!area || acciones.length === 0) {
        this.router.navigate(['/inicio']);
        return;
      }
      this.area = area;
      this.acciones = acciones;
    });
  }

  get nombre(): string {
    return this.auth.getCurrentUser()?.nombre || 'Usuario';
  }

  get rol(): string {
    return this.auth.getCurrentUser()?.rol || '';
  }

  get esCliente(): boolean {
    return this.auth.hasRole('CLIENTE');
  }

  contarAcciones(area: AreaNav): number {
    return this.nav.accionesVisibles(area).length;
  }

  tituloAccion(accion: AccionNav): string {
    return this.esCliente && accion.tituloCliente ? accion.tituloCliente : accion.titulo;
  }
}
