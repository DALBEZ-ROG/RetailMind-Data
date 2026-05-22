import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-admin-pedidos',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatCardModule, MatIconModule, MatChipsModule, MatFormFieldModule, MatInputModule, MatButtonModule],
  templateUrl: './admin-pedidos.component.html',
  styleUrl: './admin-pedidos.component.scss'
})
export class AdminPedidosComponent implements OnInit {

  pedidos: any[] = [];
  filteredPedidos: any[] = [];
  filtroUsername = '';
  loading = true;

  constructor(private http: HttpClient) {}

  ngOnInit(): void {
    this.http.get<any[]>(`${environment.apiUrl}/api/pedidos/admin/todos`).subscribe({
      next: (data) => { this.pedidos = data; this.filteredPedidos = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  filtrar(): void {
    if (!this.filtroUsername.trim()) {
      this.filteredPedidos = this.pedidos;
    } else {
      this.filteredPedidos = this.pedidos.filter(p =>
        p.userId.toLowerCase().includes(this.filtroUsername.toLowerCase()));
    }
  }

  getOrdenCorta(id: string): string { return id ? id.substring(0, 12) : ''; }
}
