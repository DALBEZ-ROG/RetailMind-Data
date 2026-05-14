import {
  SesionService
} from "./chunk-SXJEEOOD.js";
import {
  MatPaginator,
  MatPaginatorModule
} from "./chunk-5Q65RES4.js";
import {
  MatProgressSpinner,
  MatProgressSpinnerModule
} from "./chunk-7CEUYOZI.js";
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
  MatInput,
  MatInputModule
} from "./chunk-JGSDCDQF.js";
import {
  MatCard,
  MatCardContent,
  MatCardModule
} from "./chunk-FYALMEC4.js";
import {
  CommonModule,
  DatePipe,
  DecimalPipe,
  DefaultValueAccessor,
  FormsModule,
  MatButton,
  MatButtonModule,
  MatFormField,
  MatFormFieldModule,
  MatIcon,
  MatIconButton,
  MatIconModule,
  MatLabel,
  MatPrefix,
  MatSuffix,
  NgControlStatus,
  NgIf,
  NgModel,
  Subject,
  debounceTime,
  distinctUntilChanged,
  ɵsetClassDebugInfo,
  ɵɵStandaloneFeature,
  ɵɵadvance,
  ɵɵdefineComponent,
  ɵɵdirectiveInject,
  ɵɵelement,
  ɵɵelementContainerEnd,
  ɵɵelementContainerStart,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵgetCurrentView,
  ɵɵlistener,
  ɵɵloadQuery,
  ɵɵnextContext,
  ɵɵpipe,
  ɵɵpipeBind2,
  ɵɵproperty,
  ɵɵpureFunction0,
  ɵɵqueryRefresh,
  ɵɵresetView,
  ɵɵrestoreView,
  ɵɵtemplate,
  ɵɵtext,
  ɵɵtextInterpolate,
  ɵɵviewQuery
} from "./chunk-TF5X6N37.js";

// src/app/features/sesiones/sesiones-list.component.ts
var _c0 = () => [10, 20, 50];
function SesionesListComponent_button_11_Template(rf, ctx) {
  if (rf & 1) {
    const _r1 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "button", 9);
    \u0275\u0275listener("click", function SesionesListComponent_button_11_Template_button_click_0_listener() {
      \u0275\u0275restoreView(_r1);
      const ctx_r1 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r1.clearFilters());
    });
    \u0275\u0275elementStart(1, "mat-icon");
    \u0275\u0275text(2, "close");
    \u0275\u0275elementEnd()();
  }
}
function SesionesListComponent_div_12_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "div", 10);
    \u0275\u0275element(1, "mat-spinner", 11);
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_13_Template(rf, ctx) {
  if (rf & 1) {
    const _r3 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 12)(1, "mat-icon", 13);
    \u0275\u0275text(2, "search_off");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(3, "p");
    \u0275\u0275text(4, "No se encontraron sesiones");
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(5, "button", 14);
    \u0275\u0275listener("click", function SesionesListComponent_div_13_Template_button_click_5_listener() {
      \u0275\u0275restoreView(_r3);
      const ctx_r1 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r1.clearFilters());
    });
    \u0275\u0275elementStart(6, "mat-icon");
    \u0275\u0275text(7, "clear_all");
    \u0275\u0275elementEnd();
    \u0275\u0275text(8, " Limpiar filtros ");
    \u0275\u0275elementEnd()();
  }
}
function SesionesListComponent_div_14_th_3_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 35);
    \u0275\u0275text(1, "Session ID");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_4_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r5 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(row_r5.sessionId);
  }
}
function SesionesListComponent_div_14_th_6_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 37);
    \u0275\u0275text(1, "Usuario");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_7_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    let tmp_3_0;
    const row_r6 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate((tmp_3_0 = row_r6.userId) !== null && tmp_3_0 !== void 0 ? tmp_3_0 : "-");
  }
}
function SesionesListComponent_div_14_th_9_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 38);
    \u0275\u0275text(1, "Fecha / Hora");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_10_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275pipe(2, "date");
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r7 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(row_r7.timestampUtc ? \u0275\u0275pipeBind2(2, 1, row_r7.timestampUtc, "dd/MM/yyyy HH:mm") : "-");
  }
}
function SesionesListComponent_div_14_th_12_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 39);
    \u0275\u0275text(1, "Duracion (s)");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_13_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275pipe(2, "number");
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r8 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(row_r8.sessionLength !== null ? \u0275\u0275pipeBind2(2, 1, row_r8.sessionLength, "1.0-1") : "-");
  }
}
function SesionesListComponent_div_14_th_15_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 40);
    \u0275\u0275text(1, "Interacciones");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_16_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    let tmp_3_0;
    const row_r9 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate((tmp_3_0 = row_r9.interactionCount) !== null && tmp_3_0 !== void 0 ? tmp_3_0 : "-");
  }
}
function SesionesListComponent_div_14_th_18_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 41);
    \u0275\u0275text(1, "Canal");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_19_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    let tmp_3_0;
    const row_r10 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate((tmp_3_0 = row_r10.channel) !== null && tmp_3_0 !== void 0 ? tmp_3_0 : "-");
  }
}
function SesionesListComponent_div_14_th_21_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "th", 42);
    \u0275\u0275text(1, "Conversion");
    \u0275\u0275elementEnd();
  }
}
function SesionesListComponent_div_14_td_22_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "td", 36);
    \u0275\u0275text(1);
    \u0275\u0275elementEnd();
  }
  if (rf & 2) {
    const row_r11 = ctx.$implicit;
    \u0275\u0275advance();
    \u0275\u0275textInterpolate(row_r11.isConversion === 1 ? "Si" : "No");
  }
}
function SesionesListComponent_div_14_tr_23_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 43);
  }
}
function SesionesListComponent_div_14_tr_24_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "tr", 44);
  }
}
function SesionesListComponent_div_14_Template(rf, ctx) {
  if (rf & 1) {
    const _r4 = \u0275\u0275getCurrentView();
    \u0275\u0275elementStart(0, "div", 15)(1, "table", 16);
    \u0275\u0275elementContainerStart(2, 17);
    \u0275\u0275template(3, SesionesListComponent_div_14_th_3_Template, 2, 0, "th", 18)(4, SesionesListComponent_div_14_td_4_Template, 2, 1, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(5, 20);
    \u0275\u0275template(6, SesionesListComponent_div_14_th_6_Template, 2, 0, "th", 21)(7, SesionesListComponent_div_14_td_7_Template, 2, 1, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(8, 22);
    \u0275\u0275template(9, SesionesListComponent_div_14_th_9_Template, 2, 0, "th", 23)(10, SesionesListComponent_div_14_td_10_Template, 3, 4, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(11, 24);
    \u0275\u0275template(12, SesionesListComponent_div_14_th_12_Template, 2, 0, "th", 25)(13, SesionesListComponent_div_14_td_13_Template, 3, 4, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(14, 26);
    \u0275\u0275template(15, SesionesListComponent_div_14_th_15_Template, 2, 0, "th", 27)(16, SesionesListComponent_div_14_td_16_Template, 2, 1, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(17, 28);
    \u0275\u0275template(18, SesionesListComponent_div_14_th_18_Template, 2, 0, "th", 29)(19, SesionesListComponent_div_14_td_19_Template, 2, 1, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275elementContainerStart(20, 30);
    \u0275\u0275template(21, SesionesListComponent_div_14_th_21_Template, 2, 0, "th", 31)(22, SesionesListComponent_div_14_td_22_Template, 2, 1, "td", 19);
    \u0275\u0275elementContainerEnd();
    \u0275\u0275template(23, SesionesListComponent_div_14_tr_23_Template, 1, 0, "tr", 32)(24, SesionesListComponent_div_14_tr_24_Template, 1, 0, "tr", 33);
    \u0275\u0275elementEnd();
    \u0275\u0275elementStart(25, "mat-paginator", 34);
    \u0275\u0275listener("page", function SesionesListComponent_div_14_Template_mat_paginator_page_25_listener($event) {
      \u0275\u0275restoreView(_r4);
      const ctx_r1 = \u0275\u0275nextContext();
      return \u0275\u0275resetView(ctx_r1.onPageChange($event));
    });
    \u0275\u0275elementEnd()();
  }
  if (rf & 2) {
    const ctx_r1 = \u0275\u0275nextContext();
    \u0275\u0275advance();
    \u0275\u0275property("dataSource", ctx_r1.dataSource);
    \u0275\u0275advance(22);
    \u0275\u0275property("matHeaderRowDef", ctx_r1.displayedColumns);
    \u0275\u0275advance();
    \u0275\u0275property("matRowDefColumns", ctx_r1.displayedColumns);
    \u0275\u0275advance();
    \u0275\u0275property("length", ctx_r1.totalElements)("pageSize", ctx_r1.pageSize)("pageIndex", ctx_r1.pageIndex)("pageSizeOptions", \u0275\u0275pureFunction0(7, _c0));
  }
}
var SesionesListComponent = class _SesionesListComponent {
  constructor(sesionService) {
    this.sesionService = sesionService;
    this.displayedColumns = ["sessionId", "usuario", "timestampUtc", "sessionLength", "interactionCount", "canal", "fuenteTrafico"];
    this.dataSource = [];
    this.totalElements = 0;
    this.pageSize = 10;
    this.pageIndex = 0;
    this.loading = false;
    this.searchTerm = "";
    this.searchSubject = new Subject();
  }
  ngOnInit() {
    this.loadData(0, this.pageSize);
    this.searchSub = this.searchSubject.pipe(debounceTime(400), distinctUntilChanged()).subscribe((term) => {
      this.searchTerm = term;
      this.pageIndex = 0;
      this.loadData(0, this.pageSize);
    });
  }
  ngOnDestroy() {
    this.searchSub?.unsubscribe();
  }
  loadData(page, size) {
    this.loading = true;
    if (this.searchTerm.trim()) {
      this.sesionService.getByUsuario(this.searchTerm.trim(), page, size).subscribe({
        next: (result) => this.handleResult(result),
        error: () => {
          this.dataSource = [];
          this.totalElements = 0;
          this.loading = false;
        }
      });
    } else {
      this.sesionService.getAll(page, size).subscribe({
        next: (result) => this.handleResult(result),
        error: () => {
          this.loading = false;
        }
      });
    }
  }
  handleResult(result) {
    this.dataSource = result.content;
    this.totalElements = result.totalElements;
    this.pageIndex = result.number;
    this.pageSize = result.size;
    this.loading = false;
  }
  onSearchChange(value) {
    this.searchSubject.next(value);
  }
  clearFilters() {
    this.searchTerm = "";
    this.searchSubject.next("");
  }
  onPageChange(event) {
    this.loadData(event.pageIndex, event.pageSize);
  }
  static {
    this.\u0275fac = function SesionesListComponent_Factory(t) {
      return new (t || _SesionesListComponent)(\u0275\u0275directiveInject(SesionService));
    };
  }
  static {
    this.\u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _SesionesListComponent, selectors: [["app-sesiones-list"]], viewQuery: function SesionesListComponent_Query(rf, ctx) {
      if (rf & 1) {
        \u0275\u0275viewQuery(MatPaginator, 5);
      }
      if (rf & 2) {
        let _t;
        \u0275\u0275queryRefresh(_t = \u0275\u0275loadQuery()) && (ctx.paginator = _t.first);
      }
    }, standalone: true, features: [\u0275\u0275StandaloneFeature], decls: 15, vars: 5, consts: [[1, "page-container"], [1, "page-title"], ["appearance", "outline", 1, "search-field"], ["matInput", "", "placeholder", "Escribe un user_id...", 3, "ngModelChange", "ngModel"], ["matPrefix", ""], ["mat-icon-button", "", "matSuffix", "", 3, "click", 4, "ngIf"], ["class", "spinner-container", 4, "ngIf"], ["class", "empty-state", 4, "ngIf"], ["class", "table-wrapper", 4, "ngIf"], ["mat-icon-button", "", "matSuffix", "", 3, "click"], [1, "spinner-container"], ["diameter", "50"], [1, "empty-state"], [1, "empty-icon"], ["mat-stroked-button", "", "color", "primary", 3, "click"], [1, "table-wrapper"], ["mat-table", "", 1, "full-width", 3, "dataSource"], ["matColumnDef", "sessionId"], ["mat-header-cell", "", "matTooltip", "Identificador unico de la sesion", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["mat-cell", "", 4, "matCellDef"], ["matColumnDef", "usuario"], ["mat-header-cell", "", "matTooltip", "ID del usuario que inicio la sesion", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["matColumnDef", "timestampUtc"], ["mat-header-cell", "", "matTooltip", "Fecha y hora de inicio de la sesion", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["matColumnDef", "sessionLength"], ["mat-header-cell", "", "matTooltip", "Duracion total de la sesion en segundos", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["matColumnDef", "interactionCount"], ["mat-header-cell", "", "matTooltip", "Numero de interacciones del usuario", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["matColumnDef", "canal"], ["mat-header-cell", "", "matTooltip", "Canal de venta utilizado", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["matColumnDef", "fuenteTrafico"], ["mat-header-cell", "", "matTooltip", "Conversion realizada", "matTooltipShowDelay", "500", 4, "matHeaderCellDef"], ["mat-header-row", "", 4, "matHeaderRowDef"], ["mat-row", "", "class", "hoverable-row", 4, "matRowDef", "matRowDefColumns"], ["showFirstLastButtons", "", 3, "page", "length", "pageSize", "pageIndex", "pageSizeOptions"], ["mat-header-cell", "", "matTooltip", "Identificador unico de la sesion", "matTooltipShowDelay", "500"], ["mat-cell", ""], ["mat-header-cell", "", "matTooltip", "ID del usuario que inicio la sesion", "matTooltipShowDelay", "500"], ["mat-header-cell", "", "matTooltip", "Fecha y hora de inicio de la sesion", "matTooltipShowDelay", "500"], ["mat-header-cell", "", "matTooltip", "Duracion total de la sesion en segundos", "matTooltipShowDelay", "500"], ["mat-header-cell", "", "matTooltip", "Numero de interacciones del usuario", "matTooltipShowDelay", "500"], ["mat-header-cell", "", "matTooltip", "Canal de venta utilizado", "matTooltipShowDelay", "500"], ["mat-header-cell", "", "matTooltip", "Conversion realizada", "matTooltipShowDelay", "500"], ["mat-header-row", ""], ["mat-row", "", 1, "hoverable-row"]], template: function SesionesListComponent_Template(rf, ctx) {
      if (rf & 1) {
        \u0275\u0275elementStart(0, "div", 0)(1, "h1", 1);
        \u0275\u0275text(2, "Sesiones");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(3, "mat-card")(4, "mat-card-content")(5, "mat-form-field", 2)(6, "mat-label");
        \u0275\u0275text(7, "Buscar por User ID");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(8, "input", 3);
        \u0275\u0275listener("ngModelChange", function SesionesListComponent_Template_input_ngModelChange_8_listener($event) {
          return ctx.onSearchChange($event);
        });
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(9, "mat-icon", 4);
        \u0275\u0275text(10, "search");
        \u0275\u0275elementEnd();
        \u0275\u0275template(11, SesionesListComponent_button_11_Template, 3, 0, "button", 5);
        \u0275\u0275elementEnd();
        \u0275\u0275template(12, SesionesListComponent_div_12_Template, 2, 0, "div", 6)(13, SesionesListComponent_div_13_Template, 9, 0, "div", 7)(14, SesionesListComponent_div_14_Template, 26, 8, "div", 8);
        \u0275\u0275elementEnd()()();
      }
      if (rf & 2) {
        \u0275\u0275advance(8);
        \u0275\u0275property("ngModel", ctx.searchTerm);
        \u0275\u0275advance(3);
        \u0275\u0275property("ngIf", ctx.searchTerm);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.loading);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", !ctx.loading && ctx.dataSource.length === 0);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", !ctx.loading && ctx.dataSource.length > 0);
      }
    }, dependencies: [CommonModule, NgIf, DecimalPipe, DatePipe, FormsModule, DefaultValueAccessor, NgControlStatus, NgModel, MatTableModule, MatTable, MatHeaderCellDef, MatHeaderRowDef, MatColumnDef, MatCellDef, MatRowDef, MatHeaderCell, MatCell, MatHeaderRow, MatRow, MatPaginatorModule, MatPaginator, MatProgressSpinnerModule, MatProgressSpinner, MatCardModule, MatCard, MatCardContent, MatIconModule, MatIcon, MatFormFieldModule, MatFormField, MatLabel, MatPrefix, MatSuffix, MatInputModule, MatInput, MatButtonModule, MatButton, MatIconButton, MatTooltipModule, MatTooltip], styles: ["\n\n.page-title[_ngcontent-%COMP%] {\n  font-size: 1.8rem;\n  font-weight: 500;\n  color: #333;\n  margin-bottom: 24px;\n}\n.search-field[_ngcontent-%COMP%] {\n  width: 100%;\n  max-width: 400px;\n  margin-bottom: 16px;\n}\n.table-wrapper[_ngcontent-%COMP%] {\n  overflow-x: auto;\n}\ntable[_ngcontent-%COMP%] {\n  min-width: 800px;\n}\nth.mat-header-cell[_ngcontent-%COMP%] {\n  font-weight: 600;\n  color: #424242;\n  background-color: #f5f5f5;\n}\n.hoverable-row[_ngcontent-%COMP%]:hover {\n  background-color: #e3f2fd !important;\n}\n.empty-state[_ngcontent-%COMP%] {\n  text-align: center;\n  padding: 48px 24px;\n  color: #9e9e9e;\n}\n.empty-state[_ngcontent-%COMP%]   .empty-icon[_ngcontent-%COMP%] {\n  font-size: 64px;\n  width: 64px;\n  height: 64px;\n  display: block;\n  margin: 0 auto 12px;\n  color: #bdbdbd;\n}\n.empty-state[_ngcontent-%COMP%]   p[_ngcontent-%COMP%] {\n  margin-bottom: 16px;\n  font-size: 1rem;\n}\n.no-data[_ngcontent-%COMP%] {\n  text-align: center;\n  padding: 32px;\n  color: #9e9e9e;\n}\n/*# sourceMappingURL=sesiones-list.component.css.map */"] });
  }
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(SesionesListComponent, { className: "SesionesListComponent", filePath: "src\\app\\features\\sesiones\\sesiones-list.component.ts", lineNumber: 36 });
})();
export {
  SesionesListComponent
};
//# sourceMappingURL=chunk-B4AI4HQR.js.map
