import { Component, OnInit, ViewChild, AfterViewInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { SesionService } from '../../core/services/sesion.service';
import { Sesion } from '../../core/models/sesion.model';

@Component({
  selector: 'app-sesiones-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatIconModule
  ],
  templateUrl: './sesiones-list.component.html',
  styleUrl: './sesiones-list.component.scss'
})
export class SesionesListComponent implements OnInit, AfterViewInit {
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  displayedColumns = [
    'sessionId',
    'usuario',
    'timestampUtc',
    'sessionLength',
    'interactionCount',
    'canal',
    'fuenteTrafico'
  ];

  dataSource: Sesion[] = [];
  totalElements = 0;
  pageSize = 10;
  pageIndex = 0;
  loading = false;

  constructor(private sesionService: SesionService) {}

  ngOnInit(): void {
    this.loadData(0, this.pageSize);
  }

  ngAfterViewInit(): void {
    // El paginator se conecta manualmente via onPageChange
  }

  loadData(page: number, size: number): void {
    this.loading = true;
    this.sesionService.getAll(page, size).subscribe({
      next: result => {
        this.dataSource     = result.content;
        this.totalElements  = result.totalElements;
        this.pageIndex      = result.number;
        this.pageSize       = result.size;
        this.loading        = false;
      },
      error: () => { this.loading = false; }
    });
  }

  onPageChange(event: PageEvent): void {
    this.loadData(event.pageIndex, event.pageSize);
  }
}
