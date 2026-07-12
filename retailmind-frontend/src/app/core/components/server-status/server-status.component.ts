import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { HealthService, HealthStatus } from '../../services/health.service';

/**
 * Indicador de estado: verde = todo bien; ámbar = operativo pero con la
 * analítica (ClickHouse) fuera de línea; rojo = sin PostgreSQL/backend.
 */
@Component({
  selector: 'app-server-status',
  standalone: true,
  imports: [CommonModule, MatTooltipModule],
  templateUrl: './server-status.component.html',
  styleUrl: './server-status.component.scss'
})
export class ServerStatusComponent implements OnInit, OnDestroy {
  status: HealthStatus = { database: 'UP', python: 'UP', status: 'UP' };
  private sub?: Subscription;

  constructor(private healthService: HealthService) {}

  ngOnInit(): void {
    this.sub = this.healthService.status$.subscribe(s => this.status = s);
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
  }

  get isUp(): boolean {
    return this.status.status === 'UP';
  }

  get analyticsDegraded(): boolean {
    return this.isUp && this.status.analytics === 'DEGRADED';
  }

  get statusText(): string {
    if (!this.isUp) return 'Sin conexion con el servidor';
    return this.analyticsDegraded ? 'Operativo · analitica fuera de linea' : 'Sistema operativo';
  }

  get tooltipText(): string {
    return `PostgreSQL: ${this.status.postgres || this.status.database}`
      + ` | ClickHouse: ${this.status.clickhouse || 'N/D'}`
      + ` | Python: ${this.status.python}`;
  }
}
