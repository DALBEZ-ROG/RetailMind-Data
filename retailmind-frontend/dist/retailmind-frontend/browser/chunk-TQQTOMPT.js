import {
  MatInput,
  MatInputModule
} from "./chunk-JGSDCDQF.js";
import {
  MatDialog,
  MatDialogModule,
  MatProgressBar,
  MatProgressBarModule
} from "./chunk-HXXHQ7BM.js";
import {
  MatDivider,
  MatDividerModule
} from "./chunk-A66SXKKV.js";
import {
  MatCard,
  MatCardContent,
  MatCardHeader,
  MatCardModule,
  MatCardTitle
} from "./chunk-FYALMEC4.js";
import {
  CommonModule,
  DefaultValueAccessor,
  FormsModule,
  HttpClient,
  MatButton,
  MatButtonModule,
  MatFormField,
  MatFormFieldModule,
  MatIcon,
  MatIconButton,
  MatIconModule,
  NgControlStatus,
  NgIf,
  NgModel,
  environment,
  ɵsetClassDebugInfo,
  ɵɵStandaloneFeature,
  ɵɵadvance,
  ɵɵclassProp,
  ɵɵdefineComponent,
  ɵɵdefineInjectable,
  ɵɵdirectiveInject,
  ɵɵelement,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵgetCurrentView,
  ɵɵinject,
  ɵɵlistener,
  ɵɵnextContext,
  ɵɵproperty,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵtemplate,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵtextInterpolate1,
  ɵɵtwoWayBindingSet,
  ɵɵtwoWayListener,
  ɵɵtwoWayProperty
} from "./chunk-TF5X6N37.js";

// src/app/core/services/inicializacion.service.ts
var InicializacionService = class _InicializacionService {
  constructor(http) {
    this.http = http;
    this.base = `${environment.apiUrl}/api/init`;
  }
  extraerPocketbase() {
    return this.http.post(`${this.base}/extraer-pocketbase`, {});
  }
  cargarClickhouse() {
    return this.http.post(`${this.base}/cargar-clickhouse`, {});
  }
  verificarClickhouse() {
    return this.http.post(`${this.base}/verificar-clickhouse`, {});
  }
  resetSistema() {
    return this.http.post(`${this.base}/reset-sistema`, {});
  }
  cargaCompleta() {
    return this.http.post(`${this.base}/carga-completa`, {});
  }
  static {
    this.\u0275fac = function InicializacionService_Factory(t) {
      return new (t || _InicializacionService)(\u0275\u0275inject(HttpClient));
    };
  }
  static {
    this.\u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _InicializacionService, factory: _InicializacionService.\u0275fac, providedIn: "root" });
  }
};

// src/app/features/inicializacion/inicializacion.component.ts
function InicializacionComponent_div_56_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 19);
    \u0275\u0275element(1, "mat-progress-bar", 20);
    \u0275\u0275elementStart(2, "span", 21);
    \u0275\u0275text(3);
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(3);
    \u0275\u0275textInterpolate1("\u23F1\uFE0F ", ctx_r0.tiempoTranscurrido, "s");
  }
}
function InicializacionComponent_div_79_Template(rf, ctx) {
  if (rf & 1) {
    const _r2 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 22);
    \u0275\u0275listener("click", function InicializacionComponent_div_79_Template_div_click_0_listener() {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.cerrarDialogReset());
    });
    \u0275\u0275elementStart(1, "div", 23);
    \u0275\u0275listener("click", function InicializacionComponent_div_79_Template_div_click_1_listener($event) {
      \u0275\u0275restoreView(_r2);
      return \u0275\u0275resetView($event.stopPropagation());
    });
    \u0275\u0275elementStart(2, "h3");
    \u0275\u0275text(3, "\u26A0\uFE0F Confirmar Reset del Sistema");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(4, "p");
    \u0275\u0275text(5, "Esta acci\xF3n eliminar\xE1 ");
    \u0275\u0275elementStart(6, "strong");
    \u0275\u0275text(7, "TODOS");
    \u0275\u0275elementEnd();
    \u0275\u0275text(8, " los datos de ClickHouse. Esta operaci\xF3n no se puede deshacer.");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(9, "p");
    \u0275\u0275text(10, "Escribe ");
    \u0275\u0275elementStart(11, "strong");
    \u0275\u0275text(12, "CONFIRMAR");
    \u0275\u0275elementEnd();
    \u0275\u0275text(13, " para habilitar el bot\xF3n:");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(14, "mat-form-field", 24)(15, "input", 25);
    \u0275\u0275twoWayListener("ngModelChange", function InicializacionComponent_div_79_Template_input_ngModelChange_15_listener($event) {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      \u0275\u0275twoWayBindingSet(ctx_r0.confirmacionTexto, $event) || (ctx_r0.confirmacionTexto = $event);
      return \u0275\u0275resetView($event);
    });
    \u0275\u0275elementEnd()();
    \u0275\u0275elementStart(16, "div", 26)(17, "button", 27);
    \u0275\u0275listener("click", function InicializacionComponent_div_79_Template_button_click_17_listener() {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.cerrarDialogReset());
    });
    \u0275\u0275text(18, "Cancelar");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(19, "button", 17);
    \u0275\u0275listener("click", function InicializacionComponent_div_79_Template_button_click_19_listener() {
      \u0275\u0275restoreView(_r2);
      const ctx_r0 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r0.ejecutarReset());
    });
    \u0275\u0275text(20, " ELIMINAR TODO ");
    \u0275\u0275elementEnd()()()();
  }
  if (rf & 2) {
    const ctx_r0 = \u0275\u0275nextContext();
    \u0275\u0275advance(15);
    \u0275\u0275twoWayProperty("ngModel", ctx_r0.confirmacionTexto);
    \u0275\u0275advance(4);
    \u0275\u0275property("disabled", !ctx_r0.puedeResetear);
  }
}
var InicializacionComponent = class _InicializacionComponent {
  constructor(inicializacionService, dialog) {
    this.inicializacionService = inicializacionService;
    this.dialog = dialog;
    this.estadoPocketbase = false;
    this.estadoClickhouse = false;
    this.estadoParquet = false;
    this.estadoTablas = false;
    this.verificando = false;
    this.ejecutando = false;
    this.ejecutandoPaso = null;
    this.progreso = 0;
    this.tiempoTranscurrido = 0;
    this.timerInterval = null;
    this.consolaOutput = "";
    this.mostrarDialogReset = false;
    this.confirmacionTexto = "";
  }
  ngOnDestroy() {
    this.detenerTimer();
  }
  // ── Sección 1: Estado del sistema ──────────────────────────────────────────
  verificarEstado() {
    this.verificando = true;
    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => {
        this.verificando = false;
        this.consolaOutput += res.output + "\n";
        this.parseEstado(res);
      },
      error: (err) => {
        this.verificando = false;
        this.consolaOutput += `Error al verificar: ${err.message}
`;
        this.estadoPocketbase = false;
        this.estadoClickhouse = false;
        this.estadoParquet = false;
        this.estadoTablas = false;
      }
    });
  }
  parseEstado(res) {
    const output = res.output || "";
    this.estadoClickhouse = res.success;
    this.estadoPocketbase = true;
    this.estadoParquet = output.includes("fact_eventos") && !output.includes("ERROR");
    this.estadoTablas = output.includes("fact_eventos") && !output.includes("0") || output.match(/fact_eventos\s+\d{2,}/) !== null;
    const match = output.match(/fact_eventos\s+(\d[\d,]*)/);
    if (match) {
      const count = parseInt(match[1].replace(/,/g, ""), 10);
      this.estadoTablas = count > 0;
    }
  }
  // ── Sección 2: Carga inicial ───────────────────────────────────────────────
  ejecutarCargaCompleta() {
    this.iniciarEjecucion("carga-completa");
    this.inicializacionService.cargaCompleta().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }
  ejecutarPaso1() {
    this.iniciarEjecucion("extraer");
    this.inicializacionService.extraerPocketbase().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }
  ejecutarPaso2() {
    this.iniciarEjecucion("cargar");
    this.inicializacionService.cargarClickhouse().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }
  ejecutarPaso3() {
    this.iniciarEjecucion("verificar");
    this.inicializacionService.verificarClickhouse().subscribe({
      next: (res) => this.finalizarEjecucion(res),
      error: (err) => this.finalizarConError(err)
    });
  }
  // ── Sección 4: Reset ──────────────────────────────────────────────────────
  abrirDialogReset() {
    this.mostrarDialogReset = true;
    this.confirmacionTexto = "";
  }
  cerrarDialogReset() {
    this.mostrarDialogReset = false;
    this.confirmacionTexto = "";
  }
  get puedeResetear() {
    return this.confirmacionTexto === "CONFIRMAR";
  }
  ejecutarReset() {
    if (!this.puedeResetear)
      return;
    this.cerrarDialogReset();
    this.iniciarEjecucion("reset");
    this.inicializacionService.resetSistema().subscribe({
      next: (res) => {
        this.finalizarEjecucion(res);
        this.estadoTablas = false;
        this.estadoParquet = false;
      },
      error: (err) => this.finalizarConError(err)
    });
  }
  // ── Utilidades ─────────────────────────────────────────────────────────────
  iniciarEjecucion(paso) {
    this.ejecutando = true;
    this.ejecutandoPaso = paso;
    this.progreso = 0;
    this.tiempoTranscurrido = 0;
    this.consolaOutput += `
>>> Iniciando: ${paso} ...
`;
    this.iniciarTimer();
  }
  finalizarEjecucion(res) {
    this.detenerTimer();
    this.ejecutando = false;
    this.ejecutandoPaso = null;
    this.progreso = 100;
    this.consolaOutput += res.output + "\n";
    this.consolaOutput += `
${res.success ? "\u2705" : "\u274C"} ${res.mensaje} (${res.duracionSegundos}s)
`;
  }
  finalizarConError(err) {
    this.detenerTimer();
    this.ejecutando = false;
    this.ejecutandoPaso = null;
    this.consolaOutput += `
\u274C Error de conexi\xF3n: ${err.message || err.statusText}
`;
  }
  iniciarTimer() {
    this.timerInterval = setInterval(() => {
      this.tiempoTranscurrido++;
      if (this.progreso < 90) {
        this.progreso += 2;
      }
    }, 1e3);
  }
  detenerTimer() {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
      this.timerInterval = null;
    }
  }
  limpiarConsola() {
    this.consolaOutput = "";
  }
  static {
    this.\u0275fac = function InicializacionComponent_Factory(t) {
      return new (t || _InicializacionComponent)(\u0275\u0275directiveInject(InicializacionService), \u0275\u0275directiveInject(MatDialog));
    };
  }
  static {
    this.\u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _InicializacionComponent, selectors: [["app-inicializacion"]], standalone: true, features: [\u0275\u0275StandaloneFeature], decls: 80, vars: 18, consts: [[1, "inicializacion-container"], [1, "page-title"], [1, "section-card"], [1, "status-grid"], [1, "status-item"], [1, "status-dot"], ["mat-raised-button", "", "color", "primary", 3, "click", "disabled"], [1, "section-description"], ["mat-raised-button", "", "color", "accent", 1, "btn-carga-completa", 3, "click", "disabled"], [1, "divider-pasos"], [1, "pasos-individuales"], ["mat-stroked-button", "", "color", "primary", 3, "click", "disabled"], ["class", "progress-section", 4, "ngIf"], ["mat-icon-button", "", "matTooltip", "Limpiar consola", 3, "click"], [1, "console-output"], [1, "section-card", "danger-card"], [1, "danger-description"], ["mat-raised-button", "", 1, "btn-reset", 3, "click", "disabled"], ["class", "reset-dialog-overlay", 3, "click", 4, "ngIf"], [1, "progress-section"], ["mode", "indeterminate", "color", "accent"], [1, "timer"], [1, "reset-dialog-overlay", 3, "click"], [1, "reset-dialog", 3, "click"], ["appearance", "outline", 1, "confirm-field"], ["matInput", "", "placeholder", "Escribe CONFIRMAR", 3, "ngModelChange", "ngModel"], [1, "dialog-actions"], ["mat-button", "", 3, "click"]], template: function InicializacionComponent_Template(rf, ctx) {
      if (rf & 1) {
        \u0275\u0275elementStart(0, "div", 0)(1, "h2", 1)(2, "mat-icon");
        \u0275\u0275text(3, "rocket_launch");
        \u0275\u0275elementEnd();
        \u0275\u0275text(4, " Inicializaci\xF3n del Sistema ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(5, "mat-card", 2)(6, "mat-card-header")(7, "mat-card-title");
        \u0275\u0275text(8, "Estado del Sistema");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(9, "mat-card-content")(10, "div", 3)(11, "div", 4);
        \u0275\u0275element(12, "span", 5);
        \u0275\u0275elementStart(13, "span");
        \u0275\u0275text(14, "Pocketbase conectado");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(15, "div", 4);
        \u0275\u0275element(16, "span", 5);
        \u0275\u0275elementStart(17, "span");
        \u0275\u0275text(18, "ClickHouse conectado");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(19, "div", 4);
        \u0275\u0275element(20, "span", 5);
        \u0275\u0275elementStart(21, "span");
        \u0275\u0275text(22, "Datos extra\xEDdos (parquet existe)");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(23, "div", 4);
        \u0275\u0275element(24, "span", 5);
        \u0275\u0275elementStart(25, "span");
        \u0275\u0275text(26, "Tablas cargadas (fact_eventos tiene registros)");
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(27, "button", 6);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_27_listener() {
          return ctx.verificarEstado();
        });
        \u0275\u0275elementStart(28, "mat-icon");
        \u0275\u0275text(29, "refresh");
        \u0275\u0275elementEnd();
        \u0275\u0275text(30);
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(31, "mat-card", 2)(32, "mat-card-header")(33, "mat-card-title");
        \u0275\u0275text(34, "Carga Inicial");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(35, "mat-card-content")(36, "p", 7);
        \u0275\u0275text(37, " Carga los 108,584 registros desde Pocketbase hacia ClickHouse por primera vez. ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(38, "button", 8);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_38_listener() {
          return ctx.ejecutarCargaCompleta();
        });
        \u0275\u0275elementStart(39, "mat-icon");
        \u0275\u0275text(40, "cloud_download");
        \u0275\u0275elementEnd();
        \u0275\u0275text(41, " CARGA COMPLETA DESDE POCKETBASE ");
        \u0275\u0275elementEnd();
        \u0275\u0275element(42, "mat-divider", 9);
        \u0275\u0275elementStart(43, "div", 10)(44, "button", 11);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_44_listener() {
          return ctx.ejecutarPaso1();
        });
        \u0275\u0275elementStart(45, "mat-icon");
        \u0275\u0275text(46, "download");
        \u0275\u0275elementEnd();
        \u0275\u0275text(47, " 1. Extraer de Pocketbase ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(48, "button", 11);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_48_listener() {
          return ctx.ejecutarPaso2();
        });
        \u0275\u0275elementStart(49, "mat-icon");
        \u0275\u0275text(50, "storage");
        \u0275\u0275elementEnd();
        \u0275\u0275text(51, " 2. Cargar a ClickHouse ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(52, "button", 11);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_52_listener() {
          return ctx.ejecutarPaso3();
        });
        \u0275\u0275elementStart(53, "mat-icon");
        \u0275\u0275text(54, "verified");
        \u0275\u0275elementEnd();
        \u0275\u0275text(55, " 3. Verificar carga ");
        \u0275\u0275elementEnd()();
        \u0275\u0275template(56, InicializacionComponent_div_56_Template, 4, 1, "div", 12);
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(57, "mat-card", 2)(58, "mat-card-header")(59, "mat-card-title");
        \u0275\u0275text(60, "Consola");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(61, "button", 13);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_61_listener() {
          return ctx.limpiarConsola();
        });
        \u0275\u0275elementStart(62, "mat-icon");
        \u0275\u0275text(63, "delete_sweep");
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(64, "mat-card-content")(65, "div", 14)(66, "pre");
        \u0275\u0275text(67);
        \u0275\u0275elementEnd()()()();
        \u0275\u0275elementStart(68, "mat-card", 15)(69, "mat-card-header")(70, "mat-card-title");
        \u0275\u0275text(71, "\u26A0\uFE0F Zona de Peligro");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(72, "mat-card-content")(73, "p", 16);
        \u0275\u0275text(74, " Borra todos los datos de ClickHouse. Usar solo cuando el docente lo indique. ");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(75, "button", 17);
        \u0275\u0275listener("click", function InicializacionComponent_Template_button_click_75_listener() {
          return ctx.abrirDialogReset();
        });
        \u0275\u0275elementStart(76, "mat-icon");
        \u0275\u0275text(77, "warning");
        \u0275\u0275elementEnd();
        \u0275\u0275text(78, " RESETEAR SISTEMA COMPLETO ");
        \u0275\u0275elementEnd()()();
        \u0275\u0275template(79, InicializacionComponent_div_79_Template, 21, 2, "div", 18);
        \u0275\u0275elementEnd();
      }
      if (rf & 2) {
        \u0275\u0275advance(12);
        \u0275\u0275classProp("active", ctx.estadoPocketbase);
        \u0275\u0275advance(4);
        \u0275\u0275classProp("active", ctx.estadoClickhouse);
        \u0275\u0275advance(4);
        \u0275\u0275classProp("active", ctx.estadoParquet);
        \u0275\u0275advance(4);
        \u0275\u0275classProp("active", ctx.estadoTablas);
        \u0275\u0275advance(3);
        \u0275\u0275property("disabled", ctx.verificando || ctx.ejecutando);
        \u0275\u0275advance(3);
        \u0275\u0275textInterpolate1(" ", ctx.verificando ? "Verificando..." : "Verificar Estado", " ");
        \u0275\u0275advance(8);
        \u0275\u0275property("disabled", ctx.ejecutando);
        \u0275\u0275advance(6);
        \u0275\u0275property("disabled", ctx.ejecutando);
        \u0275\u0275advance(4);
        \u0275\u0275property("disabled", ctx.ejecutando);
        \u0275\u0275advance(4);
        \u0275\u0275property("disabled", ctx.ejecutando);
        \u0275\u0275advance(4);
        \u0275\u0275property("ngIf", ctx.ejecutando);
        \u0275\u0275advance(11);
        \u0275\u0275textInterpolate(ctx.consolaOutput || "> Esperando ejecuci\xF3n...");
        \u0275\u0275advance(8);
        \u0275\u0275property("disabled", ctx.ejecutando);
        \u0275\u0275advance(4);
        \u0275\u0275property("ngIf", ctx.mostrarDialogReset);
      }
    }, dependencies: [
      CommonModule,
      NgIf,
      FormsModule,
      DefaultValueAccessor,
      NgControlStatus,
      NgModel,
      MatCardModule,
      MatCard,
      MatCardContent,
      MatCardHeader,
      MatCardTitle,
      MatButtonModule,
      MatButton,
      MatIconButton,
      MatIconModule,
      MatIcon,
      MatProgressBarModule,
      MatProgressBar,
      MatDividerModule,
      MatDivider,
      MatDialogModule,
      MatFormFieldModule,
      MatFormField,
      MatInputModule,
      MatInput
    ], styles: ['\n\n.inicializacion-container[_ngcontent-%COMP%] {\n  padding: 24px;\n  max-width: 900px;\n  margin: 0 auto;\n}\n.page-title[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  gap: 8px;\n  margin-bottom: 24px;\n  font-size: 1.5rem;\n  font-weight: 500;\n  color: #333;\n}\n.page-title[_ngcontent-%COMP%]   mat-icon[_ngcontent-%COMP%] {\n  color: #3f51b5;\n}\n.section-card[_ngcontent-%COMP%] {\n  margin-bottom: 20px;\n}\n.section-card[_ngcontent-%COMP%]   mat-card-header[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  justify-content: space-between;\n  margin-bottom: 16px;\n}\n.status-grid[_ngcontent-%COMP%] {\n  display: grid;\n  grid-template-columns: 1fr 1fr;\n  gap: 12px;\n  margin-bottom: 16px;\n}\n.status-item[_ngcontent-%COMP%] {\n  display: flex;\n  align-items: center;\n  gap: 10px;\n  padding: 8px 12px;\n  border-radius: 6px;\n  background: #f5f5f5;\n  font-size: 0.9rem;\n}\n.status-dot[_ngcontent-%COMP%] {\n  width: 12px;\n  height: 12px;\n  border-radius: 50%;\n  background: #e53935;\n  flex-shrink: 0;\n  transition: background 0.3s;\n}\n.status-dot.active[_ngcontent-%COMP%] {\n  background: #43a047;\n  box-shadow: 0 0 6px rgba(67, 160, 71, 0.5);\n}\n.section-description[_ngcontent-%COMP%] {\n  color: #666;\n  margin-bottom: 16px;\n  font-size: 0.9rem;\n}\n.btn-carga-completa[_ngcontent-%COMP%] {\n  width: 100%;\n  padding: 12px;\n  font-size: 1rem;\n  font-weight: 500;\n  letter-spacing: 0.5px;\n}\n.divider-pasos[_ngcontent-%COMP%] {\n  margin: 20px 0;\n}\n.pasos-individuales[_ngcontent-%COMP%] {\n  display: flex;\n  gap: 12px;\n  flex-wrap: wrap;\n}\n.pasos-individuales[_ngcontent-%COMP%]   button[_ngcontent-%COMP%] {\n  flex: 1;\n  min-width: 180px;\n}\n.progress-section[_ngcontent-%COMP%] {\n  margin-top: 16px;\n  display: flex;\n  align-items: center;\n  gap: 12px;\n}\n.progress-section[_ngcontent-%COMP%]   mat-progress-bar[_ngcontent-%COMP%] {\n  flex: 1;\n}\n.progress-section[_ngcontent-%COMP%]   .timer[_ngcontent-%COMP%] {\n  font-size: 0.85rem;\n  color: #666;\n  white-space: nowrap;\n}\n.console-output[_ngcontent-%COMP%] {\n  background: #1e1e1e;\n  color: #d4d4d4;\n  border-radius: 6px;\n  padding: 16px;\n  max-height: 350px;\n  overflow-y: auto;\n  font-family:\n    "Consolas",\n    "Courier New",\n    monospace;\n  font-size: 0.8rem;\n  line-height: 1.5;\n}\n.console-output[_ngcontent-%COMP%]   pre[_ngcontent-%COMP%] {\n  margin: 0;\n  white-space: pre-wrap;\n  word-break: break-word;\n}\n.danger-card[_ngcontent-%COMP%] {\n  border: 1px solid #ffcdd2;\n  background: #fff5f5;\n}\n.danger-card[_ngcontent-%COMP%]   .danger-description[_ngcontent-%COMP%] {\n  color: #c62828;\n  font-size: 0.9rem;\n  margin-bottom: 16px;\n}\n.btn-reset[_ngcontent-%COMP%] {\n  background: #e53935 !important;\n  color: white !important;\n}\n.btn-reset[_ngcontent-%COMP%]:disabled {\n  background: #ccc !important;\n  color: #999 !important;\n}\n.reset-dialog-overlay[_ngcontent-%COMP%] {\n  position: fixed;\n  top: 0;\n  left: 0;\n  width: 100%;\n  height: 100%;\n  background: rgba(0, 0, 0, 0.5);\n  display: flex;\n  align-items: center;\n  justify-content: center;\n  z-index: 1000;\n}\n.reset-dialog[_ngcontent-%COMP%] {\n  background: white;\n  border-radius: 8px;\n  padding: 24px;\n  max-width: 450px;\n  width: 90%;\n  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);\n}\n.reset-dialog[_ngcontent-%COMP%]   h3[_ngcontent-%COMP%] {\n  margin: 0 0 12px;\n  color: #c62828;\n}\n.reset-dialog[_ngcontent-%COMP%]   p[_ngcontent-%COMP%] {\n  color: #555;\n  font-size: 0.9rem;\n  margin-bottom: 12px;\n}\n.reset-dialog[_ngcontent-%COMP%]   .confirm-field[_ngcontent-%COMP%] {\n  width: 100%;\n}\n.reset-dialog[_ngcontent-%COMP%]   .dialog-actions[_ngcontent-%COMP%] {\n  display: flex;\n  justify-content: flex-end;\n  gap: 8px;\n  margin-top: 16px;\n}\n/*# sourceMappingURL=inicializacion.component.css.map */'] });
  }
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(InicializacionComponent, { className: "InicializacionComponent", filePath: "src\\app\\features\\inicializacion\\inicializacion.component.ts", lineNumber: 32 });
})();
export {
  InicializacionComponent
};
//# sourceMappingURL=chunk-TQQTOMPT.js.map
