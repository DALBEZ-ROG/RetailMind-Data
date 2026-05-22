# Requirements Document

## Introduction

Reorganización completa del proyecto RetailMind en una estructura modular por paquetes/dominios funcionales. El proyecto actualmente usa una estructura plana por capas técnicas (controller/, service/, repository/) y necesita migrar a una estructura por módulos de negocio (auth/, catalogo/, carrito/, analytics/, admin/) para mejorar la cohesión, facilitar el mantenimiento y preparar la base para módulos futuros (semanas 4-6).

La reorganización abarca los tres sub-proyectos del monorepo: Backend Java (Spring Boot), Frontend Angular, y ETL Python.

## Glossary

- **Backend**: Sub-proyecto Java Spring Boot ubicado en `retailmind-backend/src/main/java/com/retailmind/`
- **Frontend**: Sub-proyecto Angular 17 ubicado en `retailmind-frontend/src/app/features/`
- **ETL**: Sub-proyecto Python ubicado en `retailmind/etl/`
- **Paquete_Modular**: Carpeta que agrupa controladores, servicios y repositorios de un mismo dominio funcional
- **Package_Declaration**: Sentencia `package com.retailmind.[modulo];` al inicio de cada archivo Java
- **Lazy_Import**: Importación dinámica de componentes Angular usando `loadComponent` en las rutas
- **Package_Info**: Archivo `package-info.java` que documenta un paquete Java pendiente de implementación
- **App_Routes**: Archivo `app.routes.ts` que define todas las rutas del frontend Angular
- **ProcessBuilder**: Clase Java que ejecuta scripts Python desde el backend, referenciando rutas de archivos ETL
- **Component_Scan**: Mecanismo de Spring Boot que detecta automáticamente beans (@Component, @Service, @RestController) en sub-paquetes

## Requirements

### Requirement 1: Reorganizar Backend en paquetes modulares por dominio

**User Story:** As a developer, I want the backend organized by business domain packages, so that related classes are co-located and easier to maintain.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL have the following top-level packages under `com.retailmind`: `auth`, `catalogo`, `carrito`, `wishlist`, `pedidos`, `perfil`, `recomendaciones`, `analytics`, `admin`, `config`, `security`, `exception`, `dto`
2. WHEN a Java file is moved to a new package, THE Backend SHALL update the `package` declaration at the top of the file to match the new directory path
3. WHEN a Java file is moved to a new package, THE Backend SHALL update all import statements in other files that reference the moved class
4. THE Backend SHALL keep `RetailmindApplication.java` in the root package `com.retailmind`
5. WHEN the reorganization is complete, THE Component_Scan SHALL detect all @Component, @Service, @RestController, and @Configuration beans in the new sub-packages without adding `@ComponentScan(basePackages=...)` or modifying the `@SpringBootApplication` annotation
6. WHEN the reorganization is complete, THE Backend SHALL have zero Java files remaining in the original layer-based packages (`controller/`, `service/`, `repository/`, `entity/`, `migration/`)
7. WHEN a Java file exists in the current structure but is not explicitly assigned to a domain package by Requirements 2 through 7, THE Backend SHALL place it in the domain package whose functional responsibility it supports (e.g., `EtlController.java` and `EtlService.java` in `com.retailmind.admin.etl`, `HealthController.java` and `HealthCheckService.java` in `com.retailmind.config`, entity and repository files used by a single domain in that domain's package, and shared entity/repository files in `com.retailmind.dto` or the domain package of their primary consumer)
8. WHEN the reorganization is complete, THE Backend SHALL contain `CorsConfig.java` in the package `com.retailmind.config`

### Requirement 2: Organizar módulo auth del backend

**User Story:** As a developer, I want all authentication-related classes grouped in the `auth` package, so that security logic is centralized.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain `AuthController.java`, `AuthService.java`, `ClickHouseUserRepository.java`, `JwtUtil.java`, `DataInitializer.java`, and `UsuarioSistema.java` in the package `com.retailmind.auth`
2. WHEN the auth classes are moved, THE Backend SHALL update each file's Package_Declaration to `package com.retailmind.auth;`
3. WHEN the auth classes are moved, THE Backend SHALL update all import statements in files outside the `auth` package that reference the moved classes to use the new `com.retailmind.auth` prefix (including `JwtAuthenticationFilter.java` importing `JwtUtil`, and `UsuariosAdminService.java` importing `UsuarioSistema` and `ClickHouseUserRepository`)
4. WHEN the reorganization is complete, THE Backend SHALL compile successfully with zero unresolved import errors related to the 6 relocated auth classes

### Requirement 3: Organizar módulos de negocio del backend (catálogo, carrito, wishlist, pedidos)

**User Story:** As a developer, I want business domain classes grouped by their functional module, so that each domain is self-contained.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain `ProductoCatalogoController.java` and `ProductoCatalogoService.java` in the package `com.retailmind.catalogo`
2. WHEN the reorganization is complete, THE Backend SHALL contain `CarritoController.java` and `CarritoService.java` in the package `com.retailmind.carrito`
3. WHEN the reorganization is complete, THE Backend SHALL contain `WishlistController.java` and `WishlistService.java` in the package `com.retailmind.wishlist`
4. WHEN the reorganization is complete, THE Backend SHALL contain `PedidosController.java` and `PedidosService.java` in the package `com.retailmind.pedidos`
5. WHEN the business module classes are moved, THE Backend SHALL update each file's Package_Declaration to match its target package (e.g., `package com.retailmind.catalogo;` for catalogo files, `package com.retailmind.carrito;` for carrito files, `package com.retailmind.wishlist;` for wishlist files, `package com.retailmind.pedidos;` for pedidos files)
6. WHEN the business module classes are moved, THE Backend SHALL update the import statement in each Controller file to reference its co-located Service using the new package path (e.g., `import com.retailmind.catalogo.ProductoCatalogoService;` in `ProductoCatalogoController.java`)

### Requirement 4: Organizar módulo analytics del backend

**User Story:** As a developer, I want analytics classes organized in sub-packages by analytics domain, so that each analytics feature is isolated.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain `DashboardController.java` and `DashboardService.java` in the package `com.retailmind.analytics.dashboard`, with each file's Package_Declaration set to `package com.retailmind.analytics.dashboard;`
2. WHEN the reorganization is complete, THE Backend SHALL contain `FunnelController.java` and `FunnelService.java` in the package `com.retailmind.analytics.funnel`, with each file's Package_Declaration set to `package com.retailmind.analytics.funnel;`
3. WHEN the reorganization is complete, THE Backend SHALL contain `SesionController.java` and `SesionService.java` in the package `com.retailmind.analytics.sesiones`, with each file's Package_Declaration set to `package com.retailmind.analytics.sesiones;`
4. WHEN the reorganization is complete, THE Backend SHALL contain `ConversionController.java` and `ConversionService.java` in the package `com.retailmind.analytics.conversiones`, with each file's Package_Declaration set to `package com.retailmind.analytics.conversiones;`
5. WHEN the reorganization is complete, THE Backend SHALL contain a Package_Info file in each of the following packages: `com.retailmind.analytics.region`, `com.retailmind.analytics.dispositivo`, `com.retailmind.analytics.trafico`, each following the format `/** Paquete [nombre] - [descripcion] - Pendiente Semana 5 */ package com.retailmind.analytics.[subpaquete];`
6. WHEN the analytics classes are moved to sub-packages, THE Backend SHALL update all import statements in files outside the analytics packages that reference the moved classes to use the new fully-qualified package paths

### Requirement 5: Organizar módulo admin del backend

**User Story:** As a developer, I want admin classes organized in sub-packages by admin function, so that administrative features are clearly separated.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain `InicializacionController.java` in the package `com.retailmind.admin.etl`
2. WHEN the reorganization is complete, THE Backend SHALL contain `UsuariosAdminController.java` and `UsuariosAdminService.java` in the package `com.retailmind.admin.usuarios`
3. WHEN the reorganization is complete, THE Backend SHALL contain `GestionDatosController.java` and `GestionDatosService.java` in the package `com.retailmind.admin.gestion`
4. WHEN the reorganization is complete, THE Backend SHALL contain a Package_Info file in the package `com.retailmind.admin.reportes` following the format `/** Paquete reportes - Generación de reportes administrativos - Pendiente Semana 6 */ package com.retailmind.admin.reportes;`
5. WHEN admin classes are moved to sub-packages, THE Backend SHALL update each file's Package_Declaration to match its new package path (e.g., `package com.retailmind.admin.etl;` for `InicializacionController.java`)

### Requirement 6: Crear paquetes placeholder para módulos futuros del backend

**User Story:** As a developer, I want placeholder packages for future modules, so that the planned architecture is visible and documented.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain a Package_Info file in `com.retailmind.perfil` following the format defined in criterion 3 with nombre "perfil", descripcion "Gestión de perfil de usuario", and Semana "4"
2. WHEN the reorganization is complete, THE Backend SHALL contain a Package_Info file in `com.retailmind.recomendaciones` following the format defined in criterion 3 with nombre "recomendaciones", descripcion "Motor de recomendaciones de productos", and Semana "4"
3. THE Package_Info files SHALL follow the format: `/** Paquete [nombre] - [descripcion] - Pendiente Semana X */ package com.retailmind.[paquete];` where [nombre] is the package name, [descripcion] is a single-line summary of the module's purpose, and X is the planned implementation week number
4. WHEN the backend reorganization is complete, THE Backend SHALL compile successfully with `mvn clean compile` including the Package_Info files without syntax errors

### Requirement 7: Mantener módulos de infraestructura del backend

**User Story:** As a developer, I want infrastructure classes (config, security, exception, dto) to remain in their dedicated packages, so that cross-cutting concerns stay separate from business logic.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Backend SHALL contain `ClickHouseConfig.java` and `CorsConfig.java` in the package `com.retailmind.config`
2. WHEN the reorganization is complete, THE Backend SHALL contain `SecurityConfig.java` and `JwtAuthenticationFilter.java` in the package `com.retailmind.security`
3. WHEN the reorganization is complete, THE Backend SHALL contain `GlobalExceptionHandler.java` in the package `com.retailmind.exception`
4. WHEN the reorganization is complete, THE Backend SHALL contain the following 11 DTO files in the package `com.retailmind.dto`: `ApiErrorDTO.java`, `CargaHistorialDTO.java`, `DashboardResumenDTO.java`, `EstadoTablasDTO.java`, `EtlResponseDTO.java`, `GrupoConteoDTO.java`, `InicializacionResponseDTO.java`, `LoginRequestDTO.java`, `LoginResponseDTO.java`, `RefreshTokenRequestDTO.java`, `TasaSemanaDTO.java`
5. WHEN the reorganization is complete, THE Backend SHALL have each infrastructure file's Package_Declaration matching its target package (`com.retailmind.config`, `com.retailmind.security`, `com.retailmind.exception`, or `com.retailmind.dto` respectively)

### Requirement 8: Reorganizar Frontend en estructura modular por dominio

**User Story:** As a developer, I want the frontend features organized by business domain with analytics and admin as parent groups, so that the feature structure mirrors the backend.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Frontend SHALL maintain the existing `shop/`, `wishlist/`, `pedidos/`, and `login/` directories with all their current files at their current paths within `features/`
2. WHEN the reorganization is complete, THE Frontend SHALL contain `dashboard.component.ts`, `dashboard.component.html`, and `dashboard.component.scss` in `features/analytics/dashboard/`
3. WHEN the reorganization is complete, THE Frontend SHALL contain `funnel.component.ts`, `funnel.component.html`, and `funnel.component.scss` in `features/analytics/funnel/`
4. WHEN the reorganization is complete, THE Frontend SHALL contain `sesiones-list.component.ts`, `sesiones-list.component.html`, and `sesiones-list.component.scss` in `features/analytics/sesiones/`
5. WHEN the reorganization is complete, THE Frontend SHALL contain `conversiones-list.component.ts`, `conversiones-list.component.html`, and `conversiones-list.component.scss` in `features/analytics/conversiones/`
6. WHEN the reorganization is complete, THE Frontend SHALL contain `admin-etl.component.ts`, `admin-etl.component.html`, `admin-etl.component.scss`, and `confirm-dialog.component.ts` in `features/admin/etl/`
7. WHEN the reorganization is complete, THE Frontend SHALL contain `admin-usuarios.component.ts`, `admin-usuarios.component.html`, and `admin-usuarios.component.scss` in `features/admin/usuarios/`
8. WHEN the reorganization is complete, THE Frontend SHALL contain `admin-pedidos.component.ts`, `admin-pedidos.component.html`, and `admin-pedidos.component.scss` in `features/admin/pedidos/`
9. WHEN the reorganization is complete, THE Frontend SHALL contain `gestion-datos.component.ts`, `gestion-datos.component.html`, `gestion-datos.component.scss`, and `gestion-datos.service.ts` in `features/admin/gestion/`
10. WHEN the reorganization is complete, THE Frontend SHALL contain `inicializacion.component.ts`, `inicializacion.component.html`, and `inicializacion.component.scss` in `features/admin/inicializacion/`
11. WHEN the reorganization is complete, THE Frontend SHALL NOT contain the original flat directories `admin-etl/`, `admin-usuarios/`, `admin-pedidos/`, `gestion-datos/`, `inicializacion/`, `dashboard/`, `funnel/`, `sesiones/`, or `conversiones/` directly under `features/`

### Requirement 9: Actualizar rutas del Frontend después de la reorganización

**User Story:** As a developer, I want all lazy-loaded route imports updated to reflect the new file paths, so that the application routes resolve correctly.

#### Acceptance Criteria

1. WHEN a component is moved to a new directory as defined in Requirement 8, THE App_Routes SHALL update the corresponding Lazy_Import path string to match the new file location relative to `src/app/`
2. WHEN the reorganization is complete, THE App_Routes SHALL update the following Lazy_Import paths to reference their new locations: `dashboard` to `./features/analytics/dashboard/`, `funnel` to `./features/analytics/funnel/`, `sesiones` to `./features/analytics/sesiones/`, `conversiones` to `./features/analytics/conversiones/`, `admin-etl` to `./features/admin/etl/`, `admin-usuarios` to `./features/admin/usuarios/`, `admin-pedidos` to `./features/admin/pedidos/`, `gestion-datos` to `./features/admin/gestion/`, and `inicializacion` to `./features/admin/inicializacion/`
3. WHEN the reorganization is complete, THE App_Routes SHALL preserve unchanged the Lazy_Import paths for routes `login`, `shop`, `shop/producto/:id`, `shop/carrito`, `wishlist`, and `mis-pedidos`
4. WHEN the reorganization is complete, THE Frontend SHALL compile successfully with `ng build` producing zero import resolution errors in App_Routes
5. IF a Lazy_Import path in App_Routes references a file that does not exist at the specified location, THEN THE Frontend build SHALL fail with a TypeScript module-not-found error, indicating the broken path

### Requirement 10: Crear placeholders para módulos futuros del Frontend

**User Story:** As a developer, I want placeholder directories with README files for planned frontend modules, so that the roadmap is visible in the file structure.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/perfil/` whose entire content is the single line "Módulo Perfil - Pendiente Semana 4"
2. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/recomendaciones/` whose entire content is the single line "Módulo Recomendaciones - Pendiente Semana 4"
3. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/analytics/region/` whose entire content is the single line "Análisis por Región - Pendiente Semana 5"
4. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/analytics/dispositivo/` whose entire content is the single line "Análisis por Dispositivo - Pendiente Semana 5"
5. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/analytics/trafico/` whose entire content is the single line "Análisis por Fuente de Tráfico - Pendiente Semana 5"
6. WHEN the reorganization is complete, THE Frontend SHALL contain a README.md in `features/admin/reportes/` whose entire content is the single line "Módulo Reportes - Pendiente Semana 6"
7. WHEN the reorganization is complete, each placeholder directory (`features/perfil/`, `features/recomendaciones/`, `features/analytics/region/`, `features/analytics/dispositivo/`, `features/analytics/trafico/`, `features/admin/reportes/`) THE Frontend SHALL contain no files other than the README.md

### Requirement 11: Reorganizar scripts ETL Python en sub-carpetas funcionales

**User Story:** As a developer, I want ETL scripts organized by their function (extraction, loading, synthetic data), so that the pipeline stages are clearly separated.

#### Acceptance Criteria

1. WHEN the reorganization is complete, THE ETL SHALL contain `08_extract_pocketbase.py` and `13_create_shop_tables.py` in the directory `retailmind/etl/extraccion/`
2. WHEN the reorganization is complete, THE ETL SHALL contain `09_load_clickhouse.py`, `10_verify_clickhouse.py`, and `11_reset_clickhouse.py` in the directory `retailmind/etl/carga/`
3. WHEN the reorganization is complete, THE ETL SHALL contain `12_generate_synthetic.py` in the directory `retailmind/etl/sinteticos/`
4. WHEN the reorganization is complete, THE ETL SHALL contain a README.md in `retailmind/etl/analytics/` with the text "Scripts de análisis - Pendiente Semana 5"
5. WHEN the reorganization is complete, THE ETL SHALL contain a README.md in `retailmind/etl/reportes/` with the text "Scripts de reportes - Pendiente Semana 6"
6. WHEN the reorganization is complete, THE ETL SHALL retain the scripts `01_create_tables.py` through `07_monitor_performance.py`, `08_apply_advanced_optimize.py`, `09_create_refresh_function.py`, and `load_csv_staging.py` in the root directory `retailmind/etl/` without moving them
7. WHEN the reorganization is complete, THE ETL SHALL contain an `__init__.py` file in each new sub-directory (`extraccion/`, `carga/`, `sinteticos/`, `analytics/`, `reportes/`) so that they are importable as Python sub-packages
8. WHEN a script is moved to a sub-directory, THE ETL SHALL preserve all internal import statements such that the script executes without ModuleNotFoundError when invoked from the project root directory `retailmind/`

### Requirement 12: Actualizar referencias a rutas de scripts ETL en el backend

**User Story:** As a developer, I want the backend ProcessBuilder references updated to the new ETL script paths, so that ETL execution from the backend continues working.

#### Acceptance Criteria

1. WHEN ETL scripts are moved to sub-directories, THE Backend SHALL update the ProcessBuilder path reference for `08_extract_pocketbase.py` in `InicializacionController.java` from `etl/08_extract_pocketbase.py` to `etl/extraccion/08_extract_pocketbase.py`
2. WHEN ETL scripts are moved to sub-directories, THE Backend SHALL update the ProcessBuilder path reference for `09_load_clickhouse.py` in `InicializacionController.java` from `etl/09_load_clickhouse.py` to `etl/carga/09_load_clickhouse.py`
3. WHEN ETL scripts are moved to sub-directories, THE Backend SHALL update the ProcessBuilder path references for `10_verify_clickhouse.py` and `11_reset_clickhouse.py` in `InicializacionController.java` from `etl/10_verify_clickhouse.py` and `etl/11_reset_clickhouse.py` to `etl/carga/10_verify_clickhouse.py` and `etl/carga/11_reset_clickhouse.py`
4. WHEN ETL scripts are moved to sub-directories, THE Backend SHALL update the ProcessBuilder path reference for `12_generate_synthetic.py` in `InicializacionController.java` from `etl/12_generate_synthetic.py` to `etl/sinteticos/12_generate_synthetic.py`
5. WHEN ETL scripts are moved to sub-directories, THE Backend SHALL update the ProcessBuilder path reference for `13_create_shop_tables.py` in `InicializacionController.java` from `etl/13_create_shop_tables.py` to `etl/extraccion/13_create_shop_tables.py`
6. THE Backend SHALL preserve the `init.scripts.path` property value unchanged in `application.properties`, since the base path (`/app`) remains valid and sub-directory resolution is handled by the relative script paths in the controller
7. IF a ProcessBuilder execution fails because the referenced script file does not exist at the new path, THEN THE Backend SHALL return a response with `success=false` and a message indicating the script was not found

### Requirement 13: Verificar compilación y funcionamiento post-reorganización

**User Story:** As a developer, I want the system to compile and function correctly after reorganization, so that no functionality is broken by the structural changes.

#### Acceptance Criteria

1. WHEN the backend reorganization is complete, THE Backend SHALL compile successfully with `mvn clean compile` producing a process exit code of 0 and zero compilation errors in the output
2. WHEN the frontend reorganization is complete, THE Frontend SHALL compile successfully with `ng build` producing a process exit code of 0 and zero errors of any type (import resolution, template, or type errors) in the output
3. WHEN the reorganization is complete, THE Backend SHALL start the Spring application context successfully, confirming that Component_Scan detects all @Component, @Service, @RestController, and @Configuration beans in the new package hierarchy without requiring changes to `@SpringBootApplication` scan base packages
4. WHEN the reorganization is complete, THE Backend SHALL pass all existing unit and integration tests with `mvn test` producing a process exit code of 0 and zero test failures
