import { Component, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Subject, Subscription, debounceTime, distinctUntilChanged } from 'rxjs';
import { SesionService } from '../../../core/services/sesion.service';
import { Sesion } from '../../../core/models/sesion.model';

@Component({
  selector: 'app-sesiones-list',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatTooltipModule
  ],
  templateUrl: './sesiones-list.component.html',
  styleUrl: './sesiones-list.component.scss'
})
export class SesionesListComponent implements OnInit, OnDestroy {
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  displayedColumns = ['sessionId', 'usuario', 'timestampUtc', 'sessionLength', 'interactionCount', 'canal', 'fuenteTrafico'];

  dataSource: Sesion[] = [];
  totalElements = 0;
  pageSize = 10;
  pageIndex = 0;
  loading = false;

  searchTerm = '';
  private searchSubject = new Subject<string>();
  private searchSub?: Subscription;

  constructor(private sesionService: SesionService) {}

  ngOnInit(): void {
    this.loadData(0, this.pageSize);

    this.searchSub = this.searchSubject.pipe(
      debounceTime(400),
      distinctUntilChanged()
    ).subscribe(term => {
      this.searchTerm = term;
      this.pageIndex = 0;
      this.loadData(0, this.pageSize);
    });
  }

  ngOnDestroy(): void {
    this.searchSub?.unsubscribe();
  }

  loadData(page: number, size: number): void {
    this.loading = true;
    // Si hay termino de busqueda, filtrar por usuario
    if (this.searchTerm.trim()) {
      this.sesionService.getByUsuario(this.searchTerm.trim(), page, size).subscribe({
        next: result => this.handleResult(result),
        error: () => { this.dataSource = []; this.totalElements = 0; this.loading = false; }
      });
    } else {
      this.sesionService.getAll(page, size).subscribe({
        next: result => this.handleResult(result),
        error: () => { this.loading = false; }
      });
    }
  }

  private handleResult(result: any): void {
    this.dataSource    = result.content;
    this.totalElements = result.totalElements;
    this.pageIndex     = result.number;
    this.pageSize      = result.size;
    this.loading       = false;
  }

  onSearchChange(value: string): void {
    this.searchSubject.next(value);
  }

  clearFilters(): void {
    this.searchTerm = '';
    this.searchSubject.next('');
  }

  onPageChange(event: PageEvent): void {
    this.loadData(event.pageIndex, event.pageSize);
  }
}
