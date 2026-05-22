# Implementation Plan: RetailMind Package Reorganization

## Overview

Migración estructural del monorepo RetailMind desde una organización plana por capas técnicas (controller/, service/, repository/) hacia una estructura modular por dominios funcionales. Se ejecuta en orden: Backend (Java Spring Boot), Frontend (Angular 17), ETL (Python), y finalmente las referencias cruzadas y verificación.

## Tasks

- [x] 1. Create backend directory structure and move infrastructure modules
  - [x] 1.1 Create all target package directories under `src/main/java/com/retailmind/`
    - Create directories: `auth/`, `catalogo/`, `carrito/`, `wishlist/`, `pedidos/`, `analytics/dashboard/`, `analytics/funnel/`, `analytics/sesiones/`, `analytics/conversiones/`, `analytics/region/`, `analytics/dispositivo/`, `analytics/trafico/`, `admin/etl/`, `admin/usuarios/`, `admin/gestion/`, `admin/reportes/`, `perfil/`, `recomendaciones/`
    - Verify that `config/`, `security/`, `exception/`, `dto/` already exist and contain their files
    - _Requirements: 1.1, 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 1.2 Move auth module files and update package declarations and imports
    - Move `AuthController.java`, `AuthService.java`, `ClickHouseUserRepository.java`, `JwtUtil.java`, `DataInitializer.java`, `UsuarioSistema.java` to `com.retailmind.auth`
    - Update `package` declaration in each file to `package com.retailmind.auth;`
    - Update all import statements in other files referencing these classes (especially `JwtAuthenticationFilter.java` importing `JwtUtil`, `UsuariosAdminService.java` importing `UsuarioSistema` and `ClickHouseUserRepository`)
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 1.3 Move business module files (catalogo, carrito, wishlist, pedidos)
    - Move `ProductoCatalogoController.java` and `ProductoCatalogoService.java` to `com.retailmind.catalogo`
    - Move `CarritoController.java` and `CarritoService.java` to `com.retailmind.carrito`
    - Move `WishlistController.java` and `WishlistService.java` to `com.retailmind.wishlist`
    - Move `PedidosController.java` and `PedidosService.java` to `com.retailmind.pedidos`
    - Update `package` declarations and all cross-file imports for each moved class
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

- [x] 2. Move analytics and admin modules
  - [x] 2.1 Move analytics module files to sub-packages
    - Move `DashboardController.java` and `DashboardService.java` to `com.retailmind.analytics.dashboard`
    - Move `FunnelController.java` and `FunnelService.java` to `com.retailmind.analytics.funnel`
    - Move `SesionController.java` and `SesionService.java` to `com.retailmind.analytics.sesiones`
    - Move `ConversionController.java` and `ConversionService.java` to `com.retailmind.analytics.conversiones`
    - Update `package` declarations and all cross-file imports
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.6_

  - [x] 2.2 Move admin module files to sub-packages
    - Move `InicializacionController.java`, `EtlController.java`, `EtlService.java` to `com.retailmind.admin.etl`
    - Move `UsuariosAdminController.java` and `UsuariosAdminService.java` to `com.retailmind.admin.usuarios`
    - Move `GestionDatosController.java` and `GestionDatosService.java` to `com.retailmind.admin.gestion`
    - Update `package` declarations and all cross-file imports
    - _Requirements: 5.1, 5.2, 5.3, 5.5_

  - [x] 2.3 Create placeholder package-info.java files for future modules
    - Create `package-info.java` in `com.retailmind.perfil` with Javadoc: `/** Paquete perfil - Gestión de perfil de usuario - Pendiente Semana 4 */`
    - Create `package-info.java` in `com.retailmind.recomendaciones` with Javadoc: `/** Paquete recomendaciones - Motor de recomendaciones de productos - Pendiente Semana 4 */`
    - Create `package-info.java` in `com.retailmind.analytics.region` with Javadoc: `/** Paquete region - Análisis por región geográfica - Pendiente Semana 5 */`
    - Create `package-info.java` in `com.retailmind.analytics.dispositivo` with Javadoc: `/** Paquete dispositivo - Análisis por tipo de dispositivo - Pendiente Semana 5 */`
    - Create `package-info.java` in `com.retailmind.analytics.trafico` with Javadoc: `/** Paquete trafico - Análisis por fuente de tráfico - Pendiente Semana 5 */`
    - Create `package-info.java` in `com.retailmind.admin.reportes` with Javadoc: `/** Paquete reportes - Generación de reportes administrativos - Pendiente Semana 6 */`
    - _Requirements: 4.5, 5.4, 6.1, 6.2, 6.3, 6.4_

- [x] 3. Checkpoint - Backend compilation verification
  - Ensure `mvn clean compile` passes with exit code 0 and zero errors. Ask the user if questions arise.

- [x] 4. Clean up original backend directories
  - [x] 4.1 Remove empty original layer-based directories
    - Delete `controller/`, `service/`, `repository/`, `entity/`, `migration/` directories under `src/main/java/com/retailmind/` (only if empty after all moves)
    - Verify no Java files remain in these directories before deletion
    - _Requirements: 1.6_

- [x] 5. Reorganize frontend structure
  - [x] 5.1 Move analytics components to `features/analytics/` sub-directories
    - Move `features/dashboard/` contents to `features/analytics/dashboard/`
    - Move `features/funnel/` contents to `features/analytics/funnel/`
    - Move `features/sesiones/` contents to `features/analytics/sesiones/`
    - Move `features/conversiones/` contents to `features/analytics/conversiones/`
    - Remove original empty directories
    - _Requirements: 8.2, 8.3, 8.4, 8.5, 8.11_

  - [x] 5.2 Move admin components to `features/admin/` sub-directories
    - Move `features/admin-etl/` contents to `features/admin/etl/`
    - Move `features/admin-usuarios/` contents to `features/admin/usuarios/`
    - Move `features/admin-pedidos/` contents to `features/admin/pedidos/`
    - Move `features/gestion-datos/` contents to `features/admin/gestion/`
    - Move `features/inicializacion/` contents to `features/admin/inicializacion/`
    - Remove original empty directories
    - _Requirements: 8.6, 8.7, 8.8, 8.9, 8.10, 8.11_

  - [x] 5.3 Update `app.routes.ts` lazy-loaded import paths
    - Update `dashboard` route import to `./features/analytics/dashboard/dashboard.component`
    - Update `funnel` route import to `./features/analytics/funnel/funnel.component`
    - Update `sesiones` route import to `./features/analytics/sesiones/sesiones-list.component`
    - Update `conversiones` route import to `./features/analytics/conversiones/conversiones-list.component`
    - Update `admin-etl` route import to `./features/admin/etl/admin-etl.component`
    - Update `admin-usuarios` route import to `./features/admin/usuarios/admin-usuarios.component`
    - Update `admin-pedidos` route import to `./features/admin/pedidos/admin-pedidos.component`
    - Update `gestion-datos` route import to `./features/admin/gestion/gestion-datos.component`
    - Update `inicializacion` route import to `./features/admin/inicializacion/inicializacion.component`
    - Preserve unchanged routes: `login`, `shop`, `shop/producto/:id`, `shop/carrito`, `wishlist`, `mis-pedidos`
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 5.4 Create frontend placeholder directories with README files
    - Create `features/perfil/README.md` with content: "Módulo Perfil - Pendiente Semana 4"
    - Create `features/recomendaciones/README.md` with content: "Módulo Recomendaciones - Pendiente Semana 4"
    - Create `features/analytics/region/README.md` with content: "Análisis por Región - Pendiente Semana 5"
    - Create `features/analytics/dispositivo/README.md` with content: "Análisis por Dispositivo - Pendiente Semana 5"
    - Create `features/analytics/trafico/README.md` with content: "Análisis por Fuente de Tráfico - Pendiente Semana 5"
    - Create `features/admin/reportes/README.md` with content: "Módulo Reportes - Pendiente Semana 6"
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7_

- [x] 6. Checkpoint - Frontend compilation verification
  - Ensure `ng build` passes with exit code 0 and zero errors. Ask the user if questions arise.

- [x] 7. Reorganize ETL scripts and update backend references
  - [x] 7.1 Move ETL scripts to functional sub-directories
    - Move `08_extract_pocketbase.py` and `13_create_shop_tables.py` to `retailmind/etl/extraccion/`
    - Move `09_load_clickhouse.py`, `10_verify_clickhouse.py`, `11_reset_clickhouse.py` to `retailmind/etl/carga/`
    - Move `12_generate_synthetic.py` to `retailmind/etl/sinteticos/`
    - Keep scripts `01` through `07`, `08_apply_advanced_optimize.py`, `09_create_refresh_function.py`, and `load_csv_staging.py` in root `retailmind/etl/`
    - _Requirements: 11.1, 11.2, 11.3, 11.6, 11.8_

  - [x] 7.2 Create `__init__.py` and placeholder files for ETL sub-directories
    - Create `__init__.py` in `retailmind/etl/extraccion/`, `retailmind/etl/carga/`, `retailmind/etl/sinteticos/`, `retailmind/etl/analytics/`, `retailmind/etl/reportes/`
    - Create `retailmind/etl/analytics/README.md` with content: "Scripts de análisis - Pendiente Semana 5"
    - Create `retailmind/etl/reportes/README.md` with content: "Scripts de reportes - Pendiente Semana 6"
    - _Requirements: 11.4, 11.5, 11.7_

  - [x] 7.3 Update ProcessBuilder paths in `InicializacionController.java`
    - Update reference `etl/08_extract_pocketbase.py` → `etl/extraccion/08_extract_pocketbase.py`
    - Update reference `etl/09_load_clickhouse.py` → `etl/carga/09_load_clickhouse.py`
    - Update reference `etl/10_verify_clickhouse.py` → `etl/carga/10_verify_clickhouse.py`
    - Update reference `etl/11_reset_clickhouse.py` → `etl/carga/11_reset_clickhouse.py`
    - Update reference `etl/12_generate_synthetic.py` → `etl/sinteticos/12_generate_synthetic.py`
    - Update reference `etl/13_create_shop_tables.py` → `etl/extraccion/13_create_shop_tables.py`
    - Preserve `init.scripts.path` property value in `application.properties`
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_

- [x] 8. Final checkpoint - Full verification
  - Run `mvn clean compile` and verify exit code 0 with zero errors
  - Run `mvn test` and verify exit code 0 with zero test failures
  - Run `ng build` and verify exit code 0 with zero errors
  - Verify each moved ETL script exists at its new path
  - Verify ProcessBuilder references in `InicializacionController.java` point to existing scripts
  - Ensure all tests pass, ask the user if questions arise.
  - _Requirements: 13.1, 13.2, 13.3, 13.4_

## Notes

- This is a purely structural reorganization — no business logic is modified
- Spring Boot's `@SpringBootApplication` in `com.retailmind` automatically scans all sub-packages; no `@ComponentScan` changes needed
- Angular standalone components with lazy loading only require updating import paths in `app.routes.ts`
- ETL scripts should continue to be invoked from the project root directory to avoid relative import issues
- Property-Based Testing is NOT applicable to this feature (no new algorithms or business logic)
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation after each major sub-project reorganization

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1", "2.2"] },
    { "id": 3, "tasks": ["2.3", "4.1"] },
    { "id": 4, "tasks": ["5.1", "5.2", "7.1"] },
    { "id": 5, "tasks": ["5.3", "5.4", "7.2"] },
    { "id": 6, "tasks": ["7.3"] }
  ]
}
```
