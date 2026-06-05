import { Component, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';

@Component({
  selector: 'app-region-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule, MatIconModule],
  templateUrl: './region-dialog.component.html',
  styleUrl: './region-dialog.component.scss'
})
export class RegionDialogComponent {
  constructor(
    @Inject(MAT_DIALOG_DATA) public data: { region: any; productos: any[] },
    private ref: MatDialogRef<RegionDialogComponent>
  ) {}
  close(): void { this.ref.close(); }
}
