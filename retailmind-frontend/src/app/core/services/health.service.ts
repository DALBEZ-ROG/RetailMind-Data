import { Injectable, OnDestroy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, Subscription, interval, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { environment } from '../../../environments/environment';

export interface HealthStatus {
  database: string;
  python:   string;
  status:   string;
}

@Injectable({ providedIn: 'root' })
export class HealthService implements OnDestroy {
  private readonly url = `${environment.apiUrl}/api/health`;
  private statusSubject = new BehaviorSubject<HealthStatus>({ database: 'UP', python: 'UP', status: 'UP' });
  private pollSub?: Subscription;

  status$ = this.statusSubject.asObservable();

  constructor(private http: HttpClient) {
    this.startPolling();
  }

  private startPolling(): void {
    // Check immediately
    this.check();
    // Then every 30 seconds
    this.pollSub = interval(30000).subscribe(() => this.check());
  }

  check(): void {
    this.http.get<HealthStatus>(this.url).pipe(
      catchError(() => of({ database: 'DOWN', python: 'DOWN', status: 'DOWN' }))
    ).subscribe(status => this.statusSubject.next(status));
  }

  isUp(): Observable<boolean> {
    return this.status$.pipe(map(s => s.status === 'UP'));
  }

  ngOnDestroy(): void {
    this.pollSub?.unsubscribe();
  }
}
