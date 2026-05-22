import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatChipsModule } from '@angular/material/chips';
import { MatDividerModule } from '@angular/material/divider';
import { HttpClient } from '@angular/common/http';
import { AuthService } from '../../core/services/auth.service';
import { environment } from '../../../environments/environment';

@Component({
  selector: 'app-mis-pedidos',
  standalone: true,
  imports: [CommonModule, MatCardModule, MatIconModule, MatExpansionModule, MatChipsModule, MatDividerModule],
  templateUrl: './mis-pedidos.component.html',
  styleUrl: './mis-pedidos.component.scss'
})
export class MisPedidosComponent implements OnInit {

  pedidos: any[] = [];
  loading = true;

  constructor(private http: HttpClient, private authService: AuthService) {}

  ngOnInit(): void {
    const user = this.authService.getCurrentUser();
    if (!user) return;
    this.http.get<any[]>(`${environment.apiUrl}/api/pedidos/${user.username}`).subscribe({
      next: (data) => { this.pedidos = data; this.loading = false; },
      error: () => { this.pedidos = []; this.loading = false; }
    });
  }

  getOrdenCorta(ordenId: string): string {
    return ordenId ? ordenId.substring(0, 12) : '';
  }
}
