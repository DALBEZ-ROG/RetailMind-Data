import { Component, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, MatPaginator, PageEvent } from '@angular/material/paginator';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { ConversionService } from '../../../core/services/conversion.service';
import { Conversion, ConversionResumen } from '../../../core/models/conversion.model';

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
    MatChipsModule,
    MatButtonToggleModule
  ],
  templateUrl: './conversiones-list.component.html',
  styleUrl: './conversiones-list.component.scss'
})
export class ConversionesListComponent implements OnInit {
  @ViewChild(MatPaginator) paginator!: MatPaginator;

  displayedColumns = ['conversionId', 'sessionId', 'isConversion', 'dropOffFlag', 'conversionTime'];

  dataSource: Conversion[] = [];
  filteredData: Conversion[] = [];
  totalElements = 0;
  pageSize = 10;
  pageIndex = 0;
  loading = false;

  resumen: ConversionResumen | null = null;
  filterValue = 'all'; // 'all' | 'conversiones' | 'abandonos'

  constructor(private conversionService: ConversionService) {}

  ngOnInit(): void {
    this.loadData(0, this.pageSize);
    this.loadResumen();
  }

  loadData(page: number, size: number): void {
    this.loading = true;
    this.conversionService.getAll(page, size).subscribe({
      next: result => {
        this.dataSource    = result.content;
        this.applyFilter();
        this.totalElements = result.totalElements;
        this.pageIndex     = result.number;
        this.pageSize      = result.size;
        this.loading       = false;
      },
      error: () => { this.loading = false; }
    });
  }

  loadResumen(): void {
    this.conversionService.getResumen().subscribe({
      next: res => this.resumen = res
    });
  }

  onFilterChange(value: string): void {
    this.filterValue = value;
    this.applyFilter();
  }

  private applyFilter(): void {
    if (this.filterValue === 'conversiones') {
      this.filteredData = this.dataSource.filter(c => c.isConversion === true);
    } else if (this.filterValue === 'abandonos') {
      this.filteredData = this.dataSource.filter(c => c.dropOffFlag === true);
    } else {
      this.filteredData = [...this.dataSource];
    }
  }

  onPageChange(event: PageEvent): void {
    this.loadData(event.pageIndex, event.pageSize);
  }

  getRowClass(row: Conversion): string {
    if (row.isConversion) return 'row-conversion';
    if (row.dropOffFlag) return 'row-dropoff';
    return '';
  }
}
