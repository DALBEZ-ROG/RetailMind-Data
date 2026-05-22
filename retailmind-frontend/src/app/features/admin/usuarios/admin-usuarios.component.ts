import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-admin-usuarios',
  standalone: true,
  imports: [CommonModule, FormsModule, MatTableModule, MatCardModule, MatIconModule, MatButtonModule, MatChipsModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatSnackBarModule],
  templateUrl: './admin-usuarios.component.html',
  styleUrl: './admin-usuarios.component.scss'
})
export class AdminUsuariosComponent implements OnInit {

  usuarios: any[] = [];
  loading = true;
  showForm = false;

  newUsername = '';
  newPassword = '';
  newEmail = '';
  newRol = 'CLIENTE';

  constructor(private http: HttpClient, private snackBar: MatSnackBar) {}

  ngOnInit(): void { this.loadUsuarios(); }

  loadUsuarios(): void {
    this.loading = true;
    this.http.get<any[]>(`${environment.apiUrl}/api/admin/usuarios`).subscribe({
      next: (data) => { this.usuarios = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  crearUsuario(): void {
    if (!this.newUsername || !this.newPassword) return;
    this.http.post(`${environment.apiUrl}/api/admin/usuarios`, {
      username: this.newUsername, password: this.newPassword,
      email: this.newEmail, rol: this.newRol
    }).subscribe({
      next: () => {
        this.snackBar.open('Usuario creado', 'OK', { duration: 2000, panelClass: ['snack-success'] });
        this.showForm = false; this.newUsername = ''; this.newPassword = ''; this.newEmail = '';
        this.loadUsuarios();
      },
      error: (e) => this.snackBar.open(e.error?.error || 'Error', 'Cerrar', { duration: 3000 })
    });
  }

  toggleActivo(username: string): void {
    this.http.put(`${environment.apiUrl}/api/admin/usuarios/${username}/toggle-activo`, {}).subscribe({
      next: () => { this.snackBar.open('Estado actualizado', 'OK', { duration: 2000 }); this.loadUsuarios(); },
      error: (e) => this.snackBar.open(e.error?.error || 'Error', 'Cerrar', { duration: 3000 })
    });
  }

  eliminarUsuario(username: string): void {
    if (!confirm('Eliminar usuario ' + username + '?')) return;
    this.http.delete(`${environment.apiUrl}/api/admin/usuarios/${username}`).subscribe({
      next: () => { this.snackBar.open('Usuario eliminado', 'OK', { duration: 2000 }); this.loadUsuarios(); },
      error: (e) => this.snackBar.open(e.error?.error || 'Error', 'Cerrar', { duration: 3000 })
    });
  }
}
