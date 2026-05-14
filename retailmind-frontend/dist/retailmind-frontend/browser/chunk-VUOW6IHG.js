import {
  AuthService,
  Router
} from "./chunk-LPBYNUC6.js";
import {
  MatProgressSpinner,
  MatProgressSpinnerModule
} from "./chunk-7CEUYOZI.js";
import {
  MatSnackBar,
  MatSnackBarModule
} from "./chunk-THFRD74P.js";
import {
  MatInput,
  MatInputModule
} from "./chunk-JGSDCDQF.js";
import {
  MatCard,
  MatCardContent,
  MatCardFooter,
  MatCardModule
} from "./chunk-FYALMEC4.js";
import {
  CommonModule,
  DefaultValueAccessor,
  FormBuilder,
  FormControlName,
  FormGroupDirective,
  MatButton,
  MatButtonModule,
  MatError,
  MatFormField,
  MatFormFieldModule,
  MatIcon,
  MatIconButton,
  MatIconModule,
  MatLabel,
  MatPrefix,
  MatSuffix,
  NgControlStatus,
  NgControlStatusGroup,
  NgIf,
  ReactiveFormsModule,
  Validators,
  ɵNgNoValidate,
  ɵsetClassDebugInfo,
  ɵɵStandaloneFeature,
  ɵɵadvance,
  ɵɵattribute,
  ɵɵclassProp,
  ɵɵdefineComponent,
  ɵɵdirectiveInject,
  ɵɵelement,
  ɵɵelementEnd,
  ɵɵelementStart,
  ɵɵlistener,
  ɵɵproperty,
  ɵɵtemplate,
  ɵɵtext,
  ɵɵtextInterpolate
} from "./chunk-TF5X6N37.js";

// src/app/features/login/login.component.ts
function LoginComponent_mat_error_17_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "mat-error");
    \u0275\u0275text(1, "El usuario es requerido");
    \u0275\u0275elementEnd();
  }
}
function LoginComponent_mat_error_18_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "mat-error");
    \u0275\u0275text(1, "Minimo 3 caracteres");
    \u0275\u0275elementEnd();
  }
}
function LoginComponent_mat_error_28_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "mat-error");
    \u0275\u0275text(1, "La contrasena es requerida");
    \u0275\u0275elementEnd();
  }
}
function LoginComponent_mat_error_29_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "mat-error");
    \u0275\u0275text(1, "Minimo 6 caracteres");
    \u0275\u0275elementEnd();
  }
}
function LoginComponent_mat_spinner_31_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275element(0, "mat-spinner", 17);
  }
}
function LoginComponent_span_32_Template(rf, ctx) {
  if (rf & 1) {
    \u0275\u0275elementStart(0, "span");
    \u0275\u0275text(1, "INICIAR SESION");
    \u0275\u0275elementEnd();
  }
}
var LoginComponent = class _LoginComponent {
  constructor(fb, authService, router, snackBar) {
    this.fb = fb;
    this.authService = authService;
    this.router = router;
    this.snackBar = snackBar;
    this.loading = false;
    this.hidePassword = true;
    this.shakeCard = false;
    this.loginForm = this.fb.group({
      username: ["", [Validators.required, Validators.minLength(3)]],
      password: ["", [Validators.required, Validators.minLength(6)]]
    });
  }
  get f() {
    return this.loginForm.controls;
  }
  onSubmit() {
    if (this.loginForm.invalid)
      return;
    this.loading = true;
    const { username, password } = this.loginForm.value;
    this.authService.login(username, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(["/dashboard"]);
      },
      error: () => {
        this.loading = false;
        this.triggerShake();
        this.snackBar.open("Credenciales incorrectas. Intenta de nuevo.", "Cerrar", { duration: 4e3, panelClass: "snack-error" });
      }
    });
  }
  triggerShake() {
    this.shakeCard = true;
    setTimeout(() => this.shakeCard = false, 600);
  }
  static {
    this.\u0275fac = function LoginComponent_Factory(t) {
      return new (t || _LoginComponent)(\u0275\u0275directiveInject(FormBuilder), \u0275\u0275directiveInject(AuthService), \u0275\u0275directiveInject(Router), \u0275\u0275directiveInject(MatSnackBar));
    };
  }
  static {
    this.\u0275cmp = /* @__PURE__ */ \u0275\u0275defineComponent({ type: _LoginComponent, selectors: [["app-login"]], standalone: true, features: [\u0275\u0275StandaloneFeature], decls: 43, vars: 13, consts: [[1, "login-wrapper"], [1, "login-card"], [1, "login-header"], [1, "login-logo"], [1, "login-title"], [1, "login-subtitle"], [1, "login-form", 3, "ngSubmit", "formGroup"], ["appearance", "outline", 1, "full-width"], ["matInput", "", "formControlName", "username", "placeholder", "Ingresa tu usuario", "autocomplete", "username"], ["matPrefix", ""], [4, "ngIf"], ["matInput", "", "formControlName", "password", "placeholder", "Ingresa tu contrasena", "autocomplete", "current-password", 3, "type"], ["mat-icon-button", "", "matSuffix", "", "type", "button", 3, "click"], ["mat-raised-button", "", "color", "primary", "type", "submit", 1, "login-btn", "full-width", 3, "disabled"], ["diameter", "20", "class", "btn-spinner", 4, "ngIf"], [1, "login-footer"], [1, "version-text"], ["diameter", "20", 1, "btn-spinner"]], template: function LoginComponent_Template(rf, ctx) {
      if (rf & 1) {
        \u0275\u0275elementStart(0, "div", 0)(1, "mat-card", 1)(2, "div", 2)(3, "mat-icon", 3);
        \u0275\u0275text(4, "analytics");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(5, "h1", 4);
        \u0275\u0275text(6, "RetailMind Analytics");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(7, "p", 5);
        \u0275\u0275text(8, "Inicia sesion para continuar");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(9, "mat-card-content")(10, "form", 6);
        \u0275\u0275listener("ngSubmit", function LoginComponent_Template_form_ngSubmit_10_listener() {
          return ctx.onSubmit();
        });
        \u0275\u0275elementStart(11, "mat-form-field", 7)(12, "mat-label");
        \u0275\u0275text(13, "Usuario");
        \u0275\u0275elementEnd();
        \u0275\u0275element(14, "input", 8);
        \u0275\u0275elementStart(15, "mat-icon", 9);
        \u0275\u0275text(16, "person");
        \u0275\u0275elementEnd();
        \u0275\u0275template(17, LoginComponent_mat_error_17_Template, 2, 0, "mat-error", 10)(18, LoginComponent_mat_error_18_Template, 2, 0, "mat-error", 10);
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(19, "mat-form-field", 7)(20, "mat-label");
        \u0275\u0275text(21, "Contrasena");
        \u0275\u0275elementEnd();
        \u0275\u0275element(22, "input", 11);
        \u0275\u0275elementStart(23, "mat-icon", 9);
        \u0275\u0275text(24, "lock");
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(25, "button", 12);
        \u0275\u0275listener("click", function LoginComponent_Template_button_click_25_listener() {
          return ctx.hidePassword = !ctx.hidePassword;
        });
        \u0275\u0275elementStart(26, "mat-icon");
        \u0275\u0275text(27);
        \u0275\u0275elementEnd()();
        \u0275\u0275template(28, LoginComponent_mat_error_28_Template, 2, 0, "mat-error", 10)(29, LoginComponent_mat_error_29_Template, 2, 0, "mat-error", 10);
        \u0275\u0275elementEnd();
        \u0275\u0275elementStart(30, "button", 13);
        \u0275\u0275template(31, LoginComponent_mat_spinner_31_Template, 1, 0, "mat-spinner", 14)(32, LoginComponent_span_32_Template, 2, 0, "span", 10);
        \u0275\u0275elementEnd()()();
        \u0275\u0275elementStart(33, "mat-card-footer", 15)(34, "p");
        \u0275\u0275text(35, "Usuario por defecto: ");
        \u0275\u0275elementStart(36, "strong");
        \u0275\u0275text(37, "admin");
        \u0275\u0275elementEnd();
        \u0275\u0275text(38, " / ");
        \u0275\u0275elementStart(39, "strong");
        \u0275\u0275text(40, "admin123");
        \u0275\u0275elementEnd()();
        \u0275\u0275elementStart(41, "p", 16);
        \u0275\u0275text(42, "RetailMind Analytics v1.0 \xA9 2024");
        \u0275\u0275elementEnd()()()();
      }
      if (rf & 2) {
        \u0275\u0275advance();
        \u0275\u0275classProp("shake", ctx.shakeCard);
        \u0275\u0275advance(9);
        \u0275\u0275property("formGroup", ctx.loginForm);
        \u0275\u0275advance(7);
        \u0275\u0275property("ngIf", ctx.f["username"].hasError("required"));
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.f["username"].hasError("minlength"));
        \u0275\u0275advance(4);
        \u0275\u0275property("type", ctx.hidePassword ? "password" : "text");
        \u0275\u0275advance(3);
        \u0275\u0275attribute("aria-label", ctx.hidePassword ? "Mostrar" : "Ocultar");
        \u0275\u0275advance(2);
        \u0275\u0275textInterpolate(ctx.hidePassword ? "visibility_off" : "visibility");
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.f["password"].hasError("required"));
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.f["password"].hasError("minlength"));
        \u0275\u0275advance();
        \u0275\u0275property("disabled", ctx.loading || ctx.loginForm.invalid);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", ctx.loading);
        \u0275\u0275advance();
        \u0275\u0275property("ngIf", !ctx.loading);
      }
    }, dependencies: [CommonModule, NgIf, ReactiveFormsModule, \u0275NgNoValidate, DefaultValueAccessor, NgControlStatus, NgControlStatusGroup, FormGroupDirective, FormControlName, MatCardModule, MatCard, MatCardContent, MatCardFooter, MatFormFieldModule, MatFormField, MatLabel, MatError, MatPrefix, MatSuffix, MatInputModule, MatInput, MatButtonModule, MatButton, MatIconButton, MatIconModule, MatIcon, MatProgressSpinnerModule, MatProgressSpinner, MatSnackBarModule], styles: ["\n\n.login-wrapper[_ngcontent-%COMP%] {\n  min-height: 100vh;\n  display: flex;\n  align-items: center;\n  justify-content: center;\n  background:\n    linear-gradient(\n      135deg,\n      #1A3A5C 0%,\n      #1F77B4 100%);\n  padding: 24px;\n}\n.login-card[_ngcontent-%COMP%] {\n  width: 100%;\n  max-width: 400px;\n  border-radius: 16px !important;\n  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3) !important;\n  padding: 32px 24px 16px;\n}\n.login-card.shake[_ngcontent-%COMP%] {\n  animation: _ngcontent-%COMP%_shake 0.5s ease-in-out;\n}\n@keyframes _ngcontent-%COMP%_shake {\n  0%, 100% {\n    transform: translateX(0);\n  }\n  20% {\n    transform: translateX(-8px);\n  }\n  40% {\n    transform: translateX(8px);\n  }\n  60% {\n    transform: translateX(-6px);\n  }\n  80% {\n    transform: translateX(6px);\n  }\n}\n.login-header[_ngcontent-%COMP%] {\n  text-align: center;\n  margin-bottom: 24px;\n}\n.login-header[_ngcontent-%COMP%]   .login-logo[_ngcontent-%COMP%] {\n  font-size: 56px;\n  width: 56px;\n  height: 56px;\n  color: #1F77B4;\n  margin-bottom: 12px;\n}\n.login-header[_ngcontent-%COMP%]   .login-title[_ngcontent-%COMP%] {\n  font-size: 1.5rem;\n  font-weight: 700;\n  color: #1A3A5C;\n  margin: 0 0 4px;\n}\n.login-header[_ngcontent-%COMP%]   .login-subtitle[_ngcontent-%COMP%] {\n  font-size: 0.9rem;\n  color: #757575;\n  margin: 0;\n}\n.login-form[_ngcontent-%COMP%] {\n  display: flex;\n  flex-direction: column;\n  gap: 4px;\n}\n.full-width[_ngcontent-%COMP%] {\n  width: 100%;\n}\n.login-btn[_ngcontent-%COMP%] {\n  height: 48px;\n  font-size: 1rem;\n  font-weight: 700;\n  letter-spacing: 0.5px;\n  margin-top: 8px;\n  background-color: #1A3A5C !important;\n}\n.login-btn[_ngcontent-%COMP%]:hover:not([disabled]) {\n  background-color: #1F77B4 !important;\n}\n.btn-spinner[_ngcontent-%COMP%] {\n  display: inline-block;\n}\n.btn-spinner[_ngcontent-%COMP%]     circle {\n  stroke: #fff !important;\n}\n.login-footer[_ngcontent-%COMP%] {\n  text-align: center;\n  padding: 16px 0 0;\n  border-top: 1px solid #e0e0e0;\n  margin-top: 16px;\n}\n.login-footer[_ngcontent-%COMP%]   p[_ngcontent-%COMP%] {\n  font-size: 0.78rem;\n  color: #9e9e9e;\n  margin: 0 0 4px;\n}\n.login-footer[_ngcontent-%COMP%]   strong[_ngcontent-%COMP%] {\n  color: #1F77B4;\n}\n.login-footer[_ngcontent-%COMP%]   .version-text[_ngcontent-%COMP%] {\n  font-size: 0.7rem;\n  color: #bdbdbd;\n  margin-top: 8px;\n}\n  .snack-error {\n  background-color: #f44336 !important;\n  color: #fff !important;\n}\n/*# sourceMappingURL=login.component.css.map */"] });
  }
};
(() => {
  (typeof ngDevMode === "undefined" || ngDevMode) && \u0275setClassDebugInfo(LoginComponent, { className: "LoginComponent", filePath: "src\\app\\features\\login\\login.component.ts", lineNumber: 31 });
})();
export {
  LoginComponent
};
//# sourceMappingURL=chunk-VUOW6IHG.js.map
