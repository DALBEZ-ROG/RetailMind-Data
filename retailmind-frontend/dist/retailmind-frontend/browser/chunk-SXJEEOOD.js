import {
  HttpClient,
  HttpParams,
  environment,
  ɵɵdefineInjectable,
  ɵɵinject
} from "./chunk-TF5X6N37.js";

// src/app/core/services/sesion.service.ts
var SesionService = class _SesionService {
  constructor(http) {
    this.http = http;
    this.base = `${environment.apiUrl}/api/sesiones`;
  }
  getAll(page, size) {
    const params = new HttpParams().set("page", page).set("size", size);
    return this.http.get(this.base, { params });
  }
  getById(id) {
    return this.http.get(`${this.base}/${id}`);
  }
  getByUsuario(userId, page, size) {
    const params = new HttpParams().set("page", page).set("size", size);
    return this.http.get(`${this.base}/usuario/${userId}`, { params });
  }
  getByCanal(canalId, page, size) {
    const params = new HttpParams().set("page", page).set("size", size);
    return this.http.get(`${this.base}/canal/${canalId}`, { params });
  }
  static {
    this.\u0275fac = function SesionService_Factory(t) {
      return new (t || _SesionService)(\u0275\u0275inject(HttpClient));
    };
  }
  static {
    this.\u0275prov = /* @__PURE__ */ \u0275\u0275defineInjectable({ token: _SesionService, factory: _SesionService.\u0275fac, providedIn: "root" });
  }
};

export {
  SesionService
};
//# sourceMappingURL=chunk-SXJEEOOD.js.map
