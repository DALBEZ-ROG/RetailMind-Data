import {
  MatSnackBar,
  MatSnackBarModule
} from "./chunk-THFRD74P.js";
import {
  MatChip,
  MatChipsModule
} from "./chunk-ERLPKRYW.js";
import {
  MatCell,
  MatCellDef,
  MatColumnDef,
  MatHeaderCell,
  MatHeaderCellDef,
  MatHeaderRow,
  MatHeaderRowDef,
  MatRow,
  MatRowDef,
  MatTable,
  MatTableModule
} from "./chunk-TJ26OGNR.js";
import {
  MatTooltip,
  MatTooltipModule
} from "./chunk-PNULOCU7.js";
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogActions,
  MatDialogContent,
  MatDialogModule,
  MatDialogRef,
  MatDialogTitle,
  MatProgressBar,
  MatProgressBarModule
} from "./chunk-HXXHQ7BM.js";
import {
  MatDividerModule
} from "./chunk-A66SXKKV.js";
import {
  MatCard,
  MatCardAvatar,
  MatCardContent,
  MatCardHeader,
  MatCardModule,
  MatCardSubtitle,
  MatCardTitle
} from "./chunk-FYALMEC4.js";
import {
  CommonModule,
  DatePipe,
  DecimalPipe,
  HttpClient,
  HttpEventType,
  HttpRequest,
  MatButton,
  MatButtonModule,
  MatIcon,
  MatIconModule,
  NgIf,
  environment,
  ɵsetClassDebugInfo,
  ɵɵStandaloneFeature,
  ɵɵadvance,
  ɵɵclassProp,
  ɵɵdefineComponent,
  ɵɵdefineInjectable,
  ɵɵdirectiveInject,
  ɵɵelement,
  ɵɵelementContainerEnd,
  ɵɵelementContainerStart,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵgetCurrentView,
  ɵɵinject,
  ɵɵlistener,
  ɵɵnextContext,
  ɵɵpipe,
  ɵɵpipeBind1,
  ɵɵpipeBind2,
  ɵɵproperty,
  ɵɵreference,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵtemplate,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵtextInterpolate2
} from "./chunk-TF5X6N37.js";

// src/app/features/admin-etl/confirm-dialog.component.ts
var ConfirmDialogComponent = class _ConfirmDialogComponent {
  constructor(dialogRef, data) {
    this.dialogRef = dialogRef;
    this.data = data;
  }
  onCancel() {
    this.dialogRef.close(false);
  }
  onConfirm() {
    this.dialogRef.close(true);
  }
  static {
    this.\u0275fac = function ConfirmDialogComponent_Factory(t) {
      return new (t || _ConfirmDialogComponent)(\u0275\u0275directiveInject(MatDialogRef), \u0275\u0275directiveInject(MAT_DIALOG_DATA));
    };
  }
  static {
    this.\u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _ConfirmDialogComponent, selectors: [["app-confirm-dialog"]], standalone: true, features: [\u0275\u0275StandaloneFeature], decls: 12, vars: 2, consts: [["mat-dialog-title", ""], [1, "dialog-icon"], ["align", "end"], ["mat-stroked-button", "", 3, "click"], ["mat-raised-button", "", "color", "primary", 3, "click"]], template: function ConfirmDialogComponent_Template(rf, ctx) {
      if (rf & 1) {
        \u0275\u0275elementStart(0, "h2", 0)(1, "mat-icon", 1);
        \u0275\u0275text(2, "warning_amber");
        \u0275\u0275elementEnd();
        \u0275\u0275text(3);
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(4, "mat-dialog-content")(5, "p");
        \u0275\u0275text(6);
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(7, "mat-dialog-actions", 2)(8, "button", 3);
        \u0275\u0275listener("click", function ConfirmDialogComponent_Template_button_click_8_listener() {
          return ctx.onCancel();
        });
        \u0275\u0275text(9, "Cancelar");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(10, "button", 4);
        \u0275\u0275listener("click", function ConfirmDialogComponent_Template_button_click_10_listener() {
          return ctx.onConfirm();
        });
        \u0275\u0275text(11, "Ejecutar");
        \u0275\u0275elementEnd()();
      }
      if (rf & 2) {
        \u0275\u0275advance(3);
        \u0275\u0275textInterpolate1(" ", ctx.data.title, " ");
        \u0275\u0275advance(3);
        \u0275\u0275textInterpolate(ctx.data.message);
      }
    }, dependencies: [CommonModule, MatDialogModule, MatDialogTitle, MatDialogActions, MatDialogContent, MatButtonModule, MatButton, MatIconModule, MatIcon], styles: ["\n\n.dialog-icon[_ngcontent-%COMP%] {\n  vertical-align: middle;\n  margin-right: 8px;\n  color: #ff9800;\n}\np[_ngcontent-%COMP%] {\n  color: #616161;\n  font-size: 0.95rem;\n  line-height: 1.5;\n}\nmat-dialog-actions[_ngcontent-%COMP%] {\n  padding: 16px 0 0;\n}\n/*# sourceMappingURL=confirm-dialog.component.css.map */"] });
  }
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(ConfirmDialogComponent, { className: "ConfirmDialogComponent", filePath: "src\\app\\features\\admin-etl\\confirm-dialog.component.ts", lineNumber: 35 });
})();

// src/app/core/services/etl.service.ts
var EtlService = class _EtlService {
  constructor(http) {
    this.http = http;
    this.base = `${environment.apiUrl}/api/etl`;
  }
  uploadCsv(file) {
    const form = new FormData();
    form.append("file", file);
    const req = new HttpRequest("POST", `${this.base}/upload-csv`, form, {
      reportProgress: true
    });
    return this.http.request(req);
  }
  cargarStaging() {
    return this.http.post(`${this.base}/cargar-staging`, {});
  }
  ejecutarEtl() {
    return this.http.post(`${this.base}/ejecutar-etl`, {});
  }
  ejecutarCompleto() {
    return this.http.post(`${this.base}/ejecutar-completo`, {});
  }
  getHistorial() {
    return this.http.get(`${this.base}/historial`);
  }
  getEstadoTablas() {
    return this.http.get(`${this.base}/estado-tablas`);
  }
  static {
    this.\u0275fac = function EtlService_Factory(t) {
      return new (t || _EtlService)(\u0275\u0275inject(HttpClient));
    };
  }
  static {
    this.\u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _EtlService, factory: _EtlService.\u0275fac, providedIn: "root" });
  }
};

// src/app/features/admin-etl/admin-etl.component.ts
function AdminEtlComponent_p_17_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "p", 29);
    \u0275\u0275text(1, "Formato: .csv | Maximo: 50 MB");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_mat_progress_bar_20_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "mat-progress-bar", 30);
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275property("mode", ctx_r2.isRunning ? "indeterminate" : "determinate")("value", ctx_r2.uploadProgress);
  }
}
function AdminEtlComponent_mat_card_subtitle_44_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "mat-card-subtitle")(1, "span", 31)(2, "mat-icon");
    \u0275\u0275text(3);
    \u0275\u0275elementEnd();
    \u0275\u0275text(4);
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275classProp("status-badge--ok", ctx_r2.lastResponse.success)("status-badge--error", !ctx_r2.lastResponse.success);
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(ctx_r2.lastResponse.success ? "check_circle" : "error");
    \u0275\u0275advance();
    \u0275\u0275textInterpolate2(" ", ctx_r2.lastResponse.success ? "Exito" : "Error", " \u2014 ", ctx_r2.lastResponse.duracionSegundos, "s ");
  }
}
function AdminEtlComponent_th_62_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Tabla");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_td_63_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33)(1, "code");
    \u0275\u0275text(2);
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const row_r4 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate(row_r4.tabla);
  }
}
function AdminEtlComponent_th_65_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Total Registros");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_td_66_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33)(1, "span");
    \u0275\u0275text(2);
    \u0275\u0275pipe(3, "number");
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const row_r5 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275classProp("count-zero", row_r5.totalRegistros === 0);
    \u0275\u0275advance();
    \u0275\u0275textInterpolate1(" ", \u0275\u0275pipeBind1(3, 3, row_r5.totalRegistros), " ");
  }
}
function AdminEtlComponent_tr_67_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 34);
  }
}
function AdminEtlComponent_tr_68_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 35);
  }
}
function AdminEtlComponent_div_76_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 36)(1, "mat-icon");
    \u0275\u0275text(2, "inbox");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "p");
    \u0275\u0275text(4, "Sin cargas registradas aun.");
    \u0275\u0275elementEnd()();
  }
}
function AdminEtlComponent_table_77_th_2_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Semana");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_table_77_td_3_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33)(1, "mat-chip", 41);
    \u0275\u0275text(2);
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const row_r6 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1("Semana ", row_r6.semana, "");
  }
}
function AdminEtlComponent_table_77_th_5_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Fecha de Carga");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_table_77_td_6_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33);
    \u0275\u0275text(1);
    \u0275\u0275pipe(2, "date");
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r7 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(\u0275\u0275pipeBind2(2, 1, row_r7.fechaCarga, "dd/MM/yyyy HH:mm"));
  }
}
function AdminEtlComponent_table_77_th_8_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Procesados");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_table_77_td_9_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33);
    \u0275\u0275text(1);
    \u0275\u0275pipe(2, "number");
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r8 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(\u0275\u0275pipeBind1(2, 1, row_r8.registrosProcesados));
  }
}
function AdminEtlComponent_table_77_th_11_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 32);
    \u0275\u0275text(1, "Nuevos");
    \u0275\u0275elementEnd();
  }
}
function AdminEtlComponent_table_77_td_12_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 33)(1, "span", 42);
    \u0275\u0275text(2);
    \u0275\u0275pipe(3, "number");
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const row_r9 = ctx.$implicit;
    \u0275\u0275advance(2);
    \u0275\u0275textInterpolate1("+", \u0275\u0275pipeBind1(3, 1, row_r9.registrosNuevos), "");
  }
}
function AdminEtlComponent_table_77_tr_13_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 34);
  }
}
function AdminEtlComponent_table_77_tr_14_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 35);
  }
}
function AdminEtlComponent_table_77_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "table", 20);
    \u0275\u0275elementContainerStart(1, 37);
    \u0275\u0275template(2, AdminEtlComponent_table_77_th_2_Template, 2, 0, "th", 22)(3, AdminEtlComponent_table_77_td_3_Template, 3, 1, "td", 23);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(4, 38);
    \u0275\u0275template(5, AdminEtlComponent_table_77_th_5_Template, 2, 0, "th", 22)(6, AdminEtlComponent_table_77_td_6_Template, 3, 4, "td", 23);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(7, 39);
    \u0275\u0275template(8, AdminEtlComponent_table_77_th_8_Template, 2, 0, "th", 22)(9, AdminEtlComponent_table_77_td_9_Template, 3, 3, "td", 23);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(10, 40);
    \u0275\u0275template(11, AdminEtlComponent_table_77_th_11_Template, 2, 0, "th", 22)(12, AdminEtlComponent_table_77_td_12_Template, 4, 3, "td", 23);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275template(13, AdminEtlComponent_table_77_tr_13_Template, 1, 0, "tr", 25)(14, AdminEtlComponent_table_77_tr_14_Template, 1, 0, "tr", 26);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const ctx_r2 = \u0275\u0275nextContext();
    \u0275\u0275property("dataSource", ctx_r2.historial);
    \u0275\u0275advance(13);
    \u0275\u0275property("matHeaderRowDef", ctx_r2.historialCols);
    \u0275\u0275advance();
    \u0275\u0275property("matRowDefColumns", ctx_r2.historialCols);
  }
}
var AdminEtlComponent = class _AdminEtlComponent {
  constructor(etlService, snackBar, dialog) {
    this.etlService = etlService;
    this.snackBar = snackBar;
    this.dialog = dialog;
    this.operationState = "idle";
    this.uploadProgress = 0;
    this.consoleOutput = "";
    this.lastResponse = null;
    this.selectedFile = null;
    this.isDragOver = false;
    this.estadoTablas = [];
    this.historial = [];
    this.loadingTablas = false;
    this.loadingHistorial = false;
    this.tablaCols = ["tabla", "totalRegistros"];
    this.historialCols = ["semana", "fechaCarga", "registrosProcesados", "registrosNuevos"];
  }
  ngOnInit() {
    this.refreshEstadoTablas();
    this.refreshHistorial();
  }
  // ── Drag & Drop ────────────────────────────────────────────────────────────
  onDragOver(event) {
    event.preventDefault();
    this.isDragOver = true;
  }
  onDragLeave() {
    this.isDragOver = false;
  }
  onDrop(event) {
    event.preventDefault();
    this.isDragOver = false;
    const file = event.dataTransfer?.files[0];
    if (file)
      this.setFile(file);
  }
  onFileSelected(event) {
    const input = event.target;
    if (input.files?.length)
      this.setFile(input.files[0]);
  }
  setFile(file) {
    if (!file.name.endsWith(".csv")) {
      this.snackBar.open("Solo se aceptan archivos .csv", "Cerrar", { duration: 3e3 });
      return;
    }
    this.selectedFile = file;
    this.appendConsole(`Archivo seleccionado: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`);
  }
  // ── Acciones ETL ───────────────────────────────────────────────────────────
  uploadCsv() {
    if (!this.selectedFile) {
      this.snackBar.open("Selecciona un archivo CSV primero.", "Cerrar", { duration: 3e3 });
      return;
    }
    this.startOperation();
    this.appendConsole("Subiendo archivo CSV al servidor...");
    this.etlService.uploadCsv(this.selectedFile).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.uploadProgress = Math.round(100 * event.loaded / event.total);
        } else if (event.type === HttpEventType.Response) {
          const res = event.body;
          this.handleResponse(res, "Archivo subido");
        }
      },
      error: (err) => this.handleError("Error al subir el archivo: " + err.message)
    });
  }
  cargarStaging() {
    this.startOperation();
    this.appendConsole("Ejecutando carga a dataset_temporal...");
    this.etlService.cargarStaging().subscribe({
      next: (res) => this.handleResponse(res, "Carga a staging"),
      error: (err) => this.handleError(err.message)
    });
  }
  ejecutarEtl() {
    this.startOperation();
    this.appendConsole("Ejecutando ETL incremental (05_load_incremental.py)...");
    this.etlService.ejecutarEtl().subscribe({
      next: (res) => {
        this.handleResponse(res, "ETL incremental");
        if (res.success)
          this.refreshEstadoTablas();
        this.refreshHistorial();
      },
      error: (err) => this.handleError(err.message)
    });
  }
  ejecutarCompleto() {
    const dialogRef = this.dialog.open(ConfirmDialogComponent, {
      width: "420px",
      data: {
        title: "Confirmar ejecucion ETL",
        message: "Esta accion cargara los datos del CSV a la base de datos. Desea continuar?"
      }
    });
    dialogRef.afterClosed().subscribe((confirmed) => {
      if (confirmed) {
        this.startOperation();
        this.appendConsole("=== INICIANDO PROCESO COMPLETO ===");
        this.appendConsole("Paso 1: Carga a staging...");
        this.etlService.ejecutarCompleto().subscribe({
          next: (res) => {
            this.handleResponse(res, "Proceso completo");
            if (res.success) {
              this.refreshEstadoTablas();
              this.refreshHistorial();
            }
          },
          error: (err) => this.handleError(err.message)
        });
      }
    });
  }
  // ── Refresh ────────────────────────────────────────────────────────────────
  refreshEstadoTablas() {
    this.loadingTablas = true;
    this.etlService.getEstadoTablas().subscribe({
      next: (data) => {
        this.estadoTablas = data;
        this.loadingTablas = false;
      },
      error: () => {
        this.loadingTablas = false;
      }
    });
  }
  refreshHistorial() {
    this.loadingHistorial = true;
    this.etlService.getHistorial().subscribe({
      next: (data) => {
        this.historial = data;
        this.loadingHistorial = false;
      },
      error: () => {
        this.loadingHistorial = false;
      }
    });
  }
  // ── Helpers ────────────────────────────────────────────────────────────────
  startOperation() {
    this.operationState = "running";
    this.uploadProgress = 0;
    this.consoleOutput = "";
    this.lastResponse = null;
  }
  handleResponse(res, label) {
    this.lastResponse = res;
    this.operationState = res.success ? "success" : "error";
    this.uploadProgress = 100;
    if (res.output)
      this.appendConsole(res.output);
    this.appendConsole(`
[${res.success ? "OK" : "ERROR"}] ${res.mensaje} (${res.duracionSegundos}s)`);
  }
  handleError(msg) {
    this.operationState = "error";
    this.uploadProgress = 0;
    this.appendConsole(`[ERROR] ${msg}`);
  }
  appendConsole(text) {
    this.consoleOutput += text + "\n";
  }
  get isRunning() {
    return this.operationState === "running";
  }
  static {
    this.\u0275fac = function AdminEtlComponent_Factory(t) {
      return new (t || _AdminEtlComponent)(\u0275\u0275directiveInject(EtlService), \u0275\u0275directiveInject(MatSnackBar), \u0275\u0275directiveInject(MatDialog));
    };
  }
  static {
    this.\u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _AdminEtlComponent, selectors: [["app-admin-etl"]], standalone: true, features: [\u0275\u0275StandaloneFeature], decls: 78, vars: 24, consts: [["fileInput", ""], [1, "page-container"], [1, "page-title"], [1, "section-card"], ["mat-card-avatar", ""], [1, "drop-zone", 3, "dragover", "dragleave", "drop", "click"], [1, "drop-icon"], [1, "drop-text"], ["class", "drop-hint", 4, "ngIf"], ["type", "file", "accept", ".csv", "hidden", "", 3, "change"], ["class", "progress-bar", 3, "mode", "value", 4, "ngIf"], [1, "action-buttons"], ["mat-stroked-button", "", "color", "primary", "matTooltip", "Seleccionar archivo CSV con 100,000 registros", "matTooltipShowDelay", "500", 3, "click", "disabled"], ["mat-stroked-button", "", "color", "accent", "matTooltip", "Carga el CSV a la tabla dataset_temporal", "matTooltipShowDelay", "500", 3, "click", "disabled"], ["mat-stroked-button", "", "color", "accent", "matTooltip", "Distribuye los datos a las 10 tablas normalizadas", "matTooltipShowDelay", "500", 3, "click", "disabled"], ["mat-raised-button", "", "color", "primary", "matTooltip", "Ejecuta todos los pasos en secuencia", "matTooltipShowDelay", "500", 1, "btn-ejecutar-todo", 3, "click", "disabled"], [4, "ngIf"], [1, "console-output"], [1, "section-actions"], ["mat-stroked-button", "", 3, "click", "disabled"], ["mat-table", "", 1, "full-width", 3, "dataSource"], ["matColumnDef", "tabla"], ["mat-header-cell", "", 4, "matHeaderCellDef"], ["mat-cell", "", 4, "matCellDef"], ["matColumnDef", "totalRegistros"], ["mat-header-row", "", 4, "matHeaderRowDef"], ["mat-row", "", 4, "matRowDef", "matRowDefColumns"], ["class", "empty-state", 4, "ngIf"], ["mat-table", "", "class", "full-width", 3, "dataSource", 4, "ngIf"], [1, "drop-hint"], [1, "progress-bar", 3, "mode", "value"], [1, "status-badge"], ["mat-header-cell", ""], ["mat-cell", ""], ["mat-header-row", ""], ["mat-row", ""], [1, "empty-state"], ["matColumnDef", "semana"], ["matColumnDef", "fechaCarga"], ["matColumnDef", "registrosProcesados"], ["matColumnDef", "registrosNuevos"], [1, "chip-semana"], [1, "nuevos-badge"]], template: function AdminEtlComponent_Template(rf, ctx) {
      if (rf & 1) {
        const _r1 = \u0275\u0275getCurrentView();
        \u0275\u0275elementStart(0, "div", 1)(1, "h1", 2);
        \u0275\u0275text(2, "Administracion ETL");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(3, "mat-card", 3)(4, "mat-card-header")(5, "mat-icon", 4);
        \u0275\u0275text(6, "upload_file");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(7, "mat-card-title");
        \u0275\u0275text(8, "Carga de Datos");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(9, "mat-card-subtitle");
        \u0275\u0275text(10, "Sube un CSV con 100,000 registros y ejecuta el pipeline ETL");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(11, "mat-card-content")(12, "div", 5);
        \u0275\u0275listener("dragover", function AdminEtlComponent_Template_div_dragover_12_listener($event) {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.onDragOver($event));
        })("dragleave", function AdminEtlComponent_Template_div_dragleave_12_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.onDragLeave());
        })("drop", function AdminEtlComponent_Template_div_drop_12_listener($event) {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.onDrop($event));
        })("click", function AdminEtlComponent_Template_div_click_12_listener() {
          \u0275\u0275restoreView(_r1);
          const fileInput_r2 = \u0275\u0275reference(19);
          return \u0275\u0275resetView(fileInput_r2.click());
        });
        \u0275\u0275elementStart(13, "mat-icon", 6);
        \u0275\u0275text(14);
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(15, "p", 7);
        \u0275\u0275text(16);
        \u0275\u0275elementEnd();
        \u0275\u0275template(17, AdminEtlComponent_p_17_Template, 2, 0, "p", 8);
        \u0275\u0275elementStart(18, "input", 9, 0);
        \u0275\u0275listener("change", function AdminEtlComponent_Template_input_change_18_listener($event) {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.onFileSelected($event));
        });
        \u0275\u0275elementEnd()();
        \u0275\u0275template(20, AdminEtlComponent_mat_progress_bar_20_Template, 1, 2, "mat-progress-bar", 10);
        \u0275\u0275elementStart(21, "div", 11)(22, "button", 12);
        \u0275\u0275listener("click", function AdminEtlComponent_Template_button_click_22_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.uploadCsv());
        });
        \u0275\u0275elementStart(23, "mat-icon");
        \u0275\u0275text(24, "save_alt");
        \u0275\u0275elementEnd();
        \u0275\u0275text(25, " Subir CSV ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(26, "button", 13);
        \u0275\u0275listener("click", function AdminEtlComponent_Template_button_click_26_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.cargarStaging());
        });
        \u0275\u0275elementStart(27, "mat-icon");
        \u0275\u0275text(28, "storage");
        \u0275\u0275elementEnd();
        \u0275\u0275text(29, " Cargar a Staging ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(30, "button", 14);
        \u0275\u0275listener("click", function AdminEtlComponent_Template_button_click_30_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.ejecutarEtl());
        });
        \u0275\u0275elementStart(31, "mat-icon");
        \u0275\u0275text(32, "play_arrow");
        \u0275\u0275elementEnd();
        \u0275\u0275text(33, " ETL Incremental ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(34, "button", 15);
        \u0275\u0275listener("click", function AdminEtlComponent_Template_button_click_34_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.ejecutarCompleto());
        });
        \u0275\u0275elementStart(35, "mat-icon");
        \u0275\u0275text(36, "rocket_launch");
        \u0275\u0275elementEnd();
        \u0275\u0275text(37, " EJECUTAR TODO ");
        \u0275\u0275elementEnd()()()();
        \u0275\u0275elementStart(38, "mat-card", 3)(39, "mat-card-header")(40, "mat-icon", 4);
        \u0275\u0275text(41, "terminal");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(42, "mat-card-title");
        \u0275\u0275text(43, "Consola de Output");
        \u0275\u0275elementEnd();
        \u0275\u0275template(44, AdminEtlComponent_mat_card_subtitle_44_Template, 5, 7, "mat-card-subtitle", 16);
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(45, "mat-card-content")(46, "pre", 17);
        \u0275\u0275text(47);
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(48, "mat-card", 3)(49, "mat-card-header")(50, "mat-icon", 4);
        \u0275\u0275text(51, "table_chart");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(52, "mat-card-title");
        \u0275\u0275text(53, "Estado de Tablas");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(54, "mat-card-content")(55, "div", 18)(56, "button", 19);
        \u0275\u0275listener("click", function AdminEtlComponent_Template_button_click_56_listener() {
          \u0275\u0275restoreView(_r1);
          return \u0275\u0275resetView(ctx.refreshEstadoTablas());
        });
        \u0275\u0275elementStart(57, "mat-icon");
        \u0275\u0275text(58, "refresh");
        \u0275\u0275elementEnd();
        \u0275\u0275text(59, " Actualizar ");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(60, "table", 20);
        \u0275\u0275elementContainerStart(61, 21);
        \u0275\u0275template(62, AdminEtlComponent_th_62_Template, 2, 0, "th", 22)(63, AdminEtlComponent_td_63_Template, 3, 1, "td", 23);
        \u0275\u0275elementContainerEnd();
        \u0275\u0275elementContainerStart(64, 24);
        \u0275\u0275template(65, AdminEtlComponent_th_65_Template, 2, 0, "th", 22)(66, AdminEtlComponent_td_66_Template, 4, 5, "td", 23);
        \u0275\u0275elementContainerEnd();
        \u0275\u0275template(67, AdminEtlComponent_tr_67_Template, 1, 0, "tr", 25)(68, AdminEtlComponent_tr_68_Template, 1, 0, "tr", 26);
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(69, "mat-card", 3)(70, "mat-card-header")(71, "mat-icon", 4);
        \u0275\u0275text(72, "history");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(73, "mat-card-title");
        \u0275\u0275text(74, "Historial de Cargas");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(75, "mat-card-content");
        \u0275\u0275template(76, AdminEtlComponent_div_76_Template, 5, 0, "div", 27)(77, AdminEtlComponent_table_77_Template, 15, 3, "table", 28);
        \u0275\u0275elementEnd()()();
      }
      if (rf & 2) {
        \u0275\u0275advance(12);
        \u0275\u0275classProp("drop-zone--active", ctx.isDragOver)("drop-zone--selected", ctx.selectedFile);
        \u0275\u0275advance(2);
        \u0275\u0275textInterpolate(ctx.selectedFile ? "check_circle" : "cloud_upload");
        \u0275\u0275advance(2);
        \u0275\u0275textInterpolate1(" ", ctx.selectedFile ? ctx.selectedFile.name : "Arrastra tu CSV aqui o haz clic para seleccionar", " ");
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", !ctx.selectedFile);
        \u0275\u0275advance(3);
        \u0275\u0275property("ngIf", ctx.isRunning || ctx.uploadProgress > 0);
        \u0275\u0275advance(2);
        \u0275\u0275property("disabled", ctx.isRunning || !ctx.selectedFile);
        \u0275\u0275advance(4);
        \u0275\u0275property("disabled", ctx.isRunning || !ctx.selectedFile);
        \u0275\u0275advance(4);
        \u0275\u0275property("disabled", ctx.isRunning || !ctx.selectedFile);
        \u0275\u0275advance(4);
        \u0275\u0275property("disabled", ctx.isRunning || !ctx.selectedFile);
        \u0275\u0275advance(10);
        \u0275\u0275property("ngIf", ctx.lastResponse);
        \u0275\u0275advance(2);
        \u0275\u0275classProp("console-output--success", ctx.operationState === "success")("console-output--error", ctx.operationState === "error");
        \u0275\u0275advance();
        \u0275\u0275textInterpolate(ctx.consoleOutput || "Esperando ejecucion...");
        \u0275\u0275advance(9);
        \u0275\u0275property("disabled", ctx.loadingTablas);
        \u0275\u0275advance(4);
        \u0275\u0275property("dataSource", ctx.estadoTablas);
        \u0275\u0275advance(7);
        \u0275\u0275property("matHeaderRowDef", ctx.tablaCols);
        \u0275\u0275advance();
        \u0275\u0275property("matRowDefColumns", ctx.tablaCols);
        \u0275\u0275advance(8);
        \u0275\u0275property("ngIf", ctx.historial.length === 0 && !ctx.loadingHistorial);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.historial.length > 0);
      }
    }, dependencies: [
      CommonModule,
      NgIf,
      DecimalPipe,
      DatePipe,
      MatCardModule,
      MatCard,
      MatCardAvatar,
      MatCardContent,
      MatCardHeader,
      MatCardSubtitle,
      MatCardTitle,
      MatButtonModule,
      MatButton,
      MatIconModule,
      MatIcon,
      MatProgressBarModule,
      MatProgressBar,
      MatTableModule,
      MatTable,
      MatHeaderCellDef,
      MatHeaderRowDef,
      MatColumnDef,
      MatCellDef,
      MatRowDef,
      MatHeaderCell,
      MatCell,
      MatHeaderRow,
      MatRow,
      MatChipsModule,
      MatChip,
      MatDividerModule,
      MatSnackBarModule,
      MatTooltipModule,
      MatTooltip,
      MatDialogModule
    ], styles: ['\n\n.page-title[_ngcontent-%COMP%] {\n  font-size: 1.8rem;\n  font-weight: 500;\n  color: #333;\n  margin-bottom: 24px;\n}\n.section-card[_ngcontent-%COMP%] {\n  border-radius: 12px !important;\n  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08) !important;\n  margin-bottom: 24px;\n}\n.section-card[_ngcontent-%COMP%]   mat-card-title[_ngcontent-%COMP%] {\n  font-size: 1.05rem !important;\n  font-weight: 600;\n}\n.section-card[_ngcontent-%COMP%]   mat-card-avatar[_ngcontent-%COMP%] {\n  color: #3f51b5;\n}\n.drop-zone[_ngcontent-%COMP%] {\n  border: 2px dashed #bdbdbd;\n  border-radius: 12px;\n  padding: 40px 24px;\n  text-align: center;\n  cursor: pointer;\n  transition: border-color 0.2s, background-color 0.2s;\n  margin-bottom: 20px;\n}\n.drop-zone[_ngcontent-%COMP%]:hover, .drop-zone--active[_ngcontent-%COMP%] {\n  border-color: #3f51b5;\n  background-color: rgba(63, 81, 181, 0.04);\n}\n.drop-zone--selected[_ngcontent-%COMP%] {\n  border-color: #4caf50;\n  background-color: rgba(76, 175, 80, 0.04);\n}\n.drop-zone--selected[_ngcontent-%COMP%]   .drop-icon[_ngcontent-%COMP%] {\n  color: #4caf50;\n}\n.drop-zone[_ngcontent-%COMP%]   .drop-icon[_ngcontent-%COMP%] {\n  font-size: 48px;\n  width: 48px;\n  height: 48px;\n  color: #9e9e9e;\n  margin-bottom: 12px;\n}\n.drop-zone[_ngcontent-%COMP%]   .drop-text[_ngcontent-%COMP%] {\n  font-size: 1rem;\n  color: #424242;\n  margin-bottom: 4px;\n}\n.drop-zone[_ngcontent-%COMP%]   .drop-hint[_ngcontent-%COMP%] {\n  font-size: 0.82rem;\n  color: #9e9e9e;\n}\n.progress-bar[_ngcontent-%COMP%] {\n  margin-bottom: 16px;\n  border-radius: 4px;\n}\n.action-buttons[_ngcontent-%COMP%] {\n  display: flex;\n  flex-wrap: wrap;\n  gap: 12px;\n  align-items: center;\n}\n.action-buttons[_ngcontent-%COMP%]   button[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  gap: 6px;\n}\n.btn-ejecutar-todo[_ngcontent-%COMP%] {\n  font-size: 1rem !important;\n  font-weight: 700 !important;\n  padding: 0 24px !important;\n  height: 48px !important;\n  margin-left: auto;\n}\n.console-output[_ngcontent-%COMP%] {\n  background-color: #1e1e1e;\n  color: #d4d4d4;\n  font-family:\n    "Courier New",\n    Courier,\n    monospace;\n  font-size: 0.82rem;\n  line-height: 1.6;\n  padding: 16px;\n  border-radius: 8px;\n  min-height: 180px;\n  max-height: 400px;\n  overflow-y: auto;\n  white-space: pre-wrap;\n  word-break: break-word;\n}\n.console-output--success[_ngcontent-%COMP%] {\n  border-left: 4px solid #4caf50;\n}\n.console-output--error[_ngcontent-%COMP%] {\n  border-left: 4px solid #f44336;\n}\n.status-badge[_ngcontent-%COMP%] {\n  display: inline-flex;\n  align-items: center;\n  gap: 4px;\n  font-size: 0.85rem;\n  font-weight: 600;\n  padding: 2px 10px;\n  border-radius: 12px;\n}\n.status-badge[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%] {\n  font-size: 16px;\n  width: 16px;\n  height: 16px;\n}\n.status-badge--ok[_ngcontent-%COMP%] {\n  background: #e8f5e9;\n  color: #2e7d32;\n}\n.status-badge--error[_ngcontent-%COMP%] {\n  background: #ffebee;\n  color: #c62828;\n}\n.section-actions[_ngcontent-%COMP%] {\n  margin-bottom: 16px;\n}\nth.mat-header-cell[_ngcontent-%COMP%] {\n  font-weight: 600;\n  color: #424242;\n  background-color: #f5f5f5;\n}\ntr.mat-row[_ngcontent-%COMP%]:hover {\n  background-color: #fafafa;\n}\ncode[_ngcontent-%COMP%] {\n  background: #f5f5f5;\n  padding: 2px 6px;\n  border-radius: 4px;\n  font-size: 0.85rem;\n  color: #3f51b5;\n}\n.count-zero[_ngcontent-%COMP%] {\n  color: #9e9e9e;\n}\n.chip-semana[_ngcontent-%COMP%] {\n  background-color: #e8eaf6 !important;\n  color: #3f51b5 !important;\n  font-weight: 600;\n}\n.nuevos-badge[_ngcontent-%COMP%] {\n  background: #e8f5e9;\n  color: #2e7d32;\n  padding: 2px 8px;\n  border-radius: 10px;\n  font-weight: 600;\n  font-size: 0.85rem;\n}\n.empty-state[_ngcontent-%COMP%] {\n  text-align: center;\n  padding: 32px;\n  color: #9e9e9e;\n}\n.empty-state[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%] {\n  font-size: 48px;\n  width: 48px;\n  height: 48px;\n  display: block;\n  margin: 0 auto 8px;\n}\n/*# sourceMappingURL=admin-etl.component.css.map */'] });
  }
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(AdminEtlComponent, { className: "AdminEtlComponent", filePath: "src\\app\\features\\admin-etl\\admin-etl.component.ts", lineNumber: 40 });
})();
export {
  AdminEtlComponent
};
//# sourceMappingURL=chunk-SL5GMHBN.js.map
