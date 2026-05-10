import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { ConversionService } from '../../core/services/conversion.service';
import { Conversion } from '../../core/models/conversion.model';

@Component({
  selector: 'app-conversiones-list',
  standalone: true,
  imports: [
    CommonModule,
    MatTableModule,
    MatPaginatorModule,
    MatProgressSpinnerModule,
    MatCardModule,
    MatIconModule,
    MatChipsModule
  ],
  templateUrl: './conversiones-list.component.html',
  styleUrl: './conversiones-list.component.scss'
})
export class ConversionesListComponent implements OnInit {
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  displayedColumns = [
    'conversionId',
    'sessionId',
    'isConversion',
    'dropOffFlag',
    'conversionTime'
  ];

  dataSource: Conversion[] = [];
  totalElements = 0;
  pageSize = 10;
  pageIndex = 0;
  loading = false;

  constructor(private conversionService: ConversionService) {}

  ngOnInit(): void {
    this.loadData(0, this.pageSize);
  }

  loadData(page: number, size: number): void {
    this.loading = true;
    this.conversionService.getAll(page, size).subscribe({
      next: result => {
        this.dataSource    = result.content;
        this.totalElements = result.totalElements;
        this.pageIndex     = result.number;
        this.pageSize      = result.size;
        this.loading       = false;
      },
      error: () => { this.loading = false; }
    });
  }

  onPageChange(event: PageEvent): void {
    this.loadData(event.pageIndex, event.pageSize);
  }
}
