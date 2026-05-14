import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterOutlet, RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatListModule } from '@angular/material/list';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatChipsModule } from '@angular/material/chips';
import { filter } from 'rxjs';
import { AuthService } from './core/services/auth.service';
import { ServerStatusComponent } from './core/components/server-status/server-status.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    CommonModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
    MatDividerModule,
    MatMenuModule,
    MatTooltipModule,
    MatChipsModule,
    ServerStatusComponent
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'RetailMind Analytics';
  sidenavOpened = true;
  breadcrumb = 'Dashboard';

  private routeMap: Record<string, string> = {
    '/dashboard': 'Dashboard',
    '/sesiones': 'Sesiones',
    '/conversiones': 'Conversiones',
    '/admin-etl': 'Administracion ETL',
    '/inicializacion': 'Inicializacion del Sistema'
  };

  constructor(public authService: AuthService, private router: Router) {
    this.router.events.pipe(
      filter(e => e instanceof NavigationEnd)
    ).subscribe((e: any) => {
      this.breadcrumb = this.routeMap[e.urlAfterRedirects] || 'Dashboard';
    });
  }

  get isLoginPage(): boolean {
    return this.router.url === '/login';
  }

  get currentUser() {
    return this.authService.getCurrentUser();
  }

  get isAdmin(): boolean {
    return this.authService.hasRole('ADMIN');
  }

  logout(): void {
    this.authService.logout();
  }
}
