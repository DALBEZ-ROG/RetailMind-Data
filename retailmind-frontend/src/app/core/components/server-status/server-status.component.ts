import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subscription } from 'rxjs';
import { HealthService, HealthStatus } from '../../services/health.service';

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

  get tooltipText(): string {
    return `BD: ${this.status.database} | Python: ${this.status.python}`;
  }
}
