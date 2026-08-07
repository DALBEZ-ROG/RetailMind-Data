# Project Structure

Monorepo con tres sub-proyectos. Arquitectura híbrida: **PostgreSQL** (BD `retailmind`) es la base
operativa principal (incluida tienda del cliente); **ClickHouse** sirve solo la analítica. Con CH
apagado todo funciona excepto analytics/recomendaciones (degradan).

> **Desde el 2026-08-03 los dos motores corren EN CONTENEDORES** (`docs/DESPLIEGUE_EJECUTADO.md`):
> el **5432 es el contenedor de PostgreSQL** (base viva) y el PostgreSQL local de Windows pasó al
> **5433** (plan B + bases de otras materias).

```
1M6DatosCS/
├── retailmind/                       # Pipeline ETL Python 3.12 + DDL de PostgreSQL
│   ├── config/
│   │   ├── clickhouse_connection.py  # Conexión ClickHouse + logging
│   │   └── db_connection.py          # Conexión PostgreSQL (inspección/admin)
│   ├── etl/
│   │   ├── extraccion/               # PocketBase -> data/stage/datos.parquet
│   │   ├── carga/                    # Parquet -> ClickHouse (+ verify/reset)
│   │   ├── sinteticos/               # Generador de datos sintéticos
│   │   ├── analytics/ y reportes/    # Scripts/notas
│   ├── data/stage/                   # Capa cruda Parquet
│   ├── sql/postgres/                 # ★ DDL OPERATIVO VIGENTE: scripts numerados 01–85 + 99
│   │                                 #   (46-84 = seed y saneamiento; 85 = rol `retailmind_etl`)
│   │                                 #   01-15 módulos (seguridad, clientes, catálogo, ventas,
│   │                                 #   compras, inventario, marketing, soporte, reseñas...),
│   │                                 #   16 triggers, 17 seeds catálogos,
│   │                                 #   18-22 seguridad motor (grp_*, privilegios, RLS, horarios, app_login),
│   │                                 #   23-27 seeds demo/roles/usuarios prueba,
│   │                                 #   28-35 grants consolidación/soporte/reseñas/tienda_cliente,
│   │                                 #   36 checkout online, 37 rol SOPORTE, 38 RMA devoluciones,
│   │                                 #   39 tramo salida (facturado/preparado/transportista),
│   │                                 #   40 descuentos marketing, 41 segregación financiera,
│   │                                 #   42 trazabilidad de autor + auditoría,
│   │                                 #   43 saneamiento Tipo 1 (RLS pago/cupón, seq global, correlativo tickets),
│   │                                 #   44 novedades de envío ('no_entregado'),
│   │                                 #   45 devolución a proveedor (item_defectuoso, DP-…)
│   ├── etl/dwh/                      # ★ ETL VIGENTE: PostgreSQL -> ClickHouse `retailmind_dwh`
│   │                                 #   (21 tablas, carga atomica; modelos/ = prevision y alerta)
│   ├── requirements.txt / Dockerfile
│   └── .env                          # gitignored (credenciales del ETL)
│
├── retailmind-backend/               # API REST Spring Boot 3.5 / Java 17
│   ├── pom.xml
│   └── src/main/java/com/retailmind/
│       ├── RetailmindApplication.java
│       ├── config/                   # PostgresConfig (@Primary tx), ClickHouseConfig (fail-fast:
│       │                             # timeouts para que /api/health no cuelgue sin CH), Cors, Health
│       ├── security/                 # SecurityConfig, JwtAuthenticationFilter,
│       │                             # PgSessionRoleAspect (SET LOCAL ROLE), DbGroupRole (lista blanca)
│       ├── auth/                     # AuthService (login vs PostgreSQL), PostgresUserRepository,
│       │                             # JwtUtil, AppUserPrincipal, DataInitializer
│       ├── exception/                # GlobalExceptionHandler -> ApiErrorDTO
│       ├── auditoria/                # AuditoriaService: rastro en log_auditoria (script 42),
│       │                             # autor SIEMPRE del JWT, dentro de la @Transactional del caso de uso
│       ├── dto/                      # DTOs compartidos + repos ClickHouse legacy
│       ├── pdf/                      # Base de documentos PDF (iText 5, DocumentoPdfService)
│       ├── referencias/              # Selects de referencia (proveedores, bodegas...)
│       │
│       │   # --- Núcleo operativo (PostgreSQL, @Transactional + SET LOCAL ROLE) ---
│       ├── admin/catalogo/           # CRUD catálogo maestro (ADMIN)
│       ├── admin/horarios/           # Ventanas horarias por rol (ADMIN)
│       ├── admin/usuarios/           # Gestión de usuarios
│       ├── compras/                  # Orden -> aprobar -> recepción (con rechazo en puerta) -> factura
│       │                             # -> pago (+PDF); DevolucionProveedorService (script 45: pool
│       │                             # item_defectuoso + ciclo devolucion_proveedor DP-…)
│       ├── inventario/               # Transferencias, ajustes (+ anulación), kardex, StockService
│       ├── ventas/                   # Pedido -> pago -> facturado -> preparación (bodega) ->
│       │                             # despacho (solo 'preparado', override transportista) -> entrega (+PDF);
│       │                             # novedades de envío (script 44: reprogramar máx. 3 / devolver
│       │                             # al almacén -> pedido 'no_entregado' terminal)
│       ├── devoluciones/             # RMA logística inversa (ciclo multi-rol, guía RET-, inspección por ítem)
│       ├── marketing/                # Cupones, promociones(+productos), campañas, banners, newsletter;
│       │                             # DescuentosService (promos automáticas + cupón backend, script 40)
│       ├── soporte/                  # Tickets rol SOPORTE: prioridad automática, SLA, TICK-AAAA-NNNN
│       ├── resenas/                  # Reseñas compra verificada, votos, reportes, Q&A, moderación
│       │
│       │   # --- Tienda online (PostgreSQL, rol CLIENTE) ---
│       ├── catalogo/                 # Catálogo público (búsqueda/paginación, id público = variante)
│       ├── carrito/                  # Carrito de compras
│       ├── wishlist/                 # Lista de deseos
│       ├── pedidos/                  # Mis pedidos (RLS, estado/guía/factura PDF)
│       ├── perfil/                   # Perfil + CRUD direcciones
│       ├── recomendaciones/          # Señal CH + productos PG, degrada a destacados
│       │
│       │   # --- Analítica (ClickHouse, EXCLUIDA del SET LOCAL ROLE) ---
│       ├── analytics/                # dashboard, sesiones, conversiones, funnel,
│       │                             # region, dispositivo, trafico
│       └── admin/                    # etl/ (disparo ETL), gestion/ (dims CH), reportes/ (Excel/PDF)
│
├── retailmind-frontend/              # SPA Angular 17 standalone (diseño "Dubai")
│   └── src/app/
│       ├── app.routes.ts             # Rutas lazy con authGuard/adminGuard/roleGuard([...])
│       ├── app.component.*           # Sidebar por rol + breadcrumbs (routeMap)
│       ├── core/
│       │   ├── services/             # Servicios API (~15 líneas, environment.apiUrl),
│       │   │                         # api-error.util.ts (mensajeError)
│       │   ├── guards/               # auth.guard, role.guard (roleGuard/adminGuard)
│       │   ├── interceptors/         # JWT
│       │   └── models/               # operativo.model.ts (snake_case del backend), etc.
│       └── features/
│           ├── login/  shop/  wishlist/  pedidos/  perfil/  recomendaciones/
│           ├── analytics/            # dashboard, funnel, sesiones, region... (ADMIN)
│           ├── admin/                # etl, gestión de datos, usuarios, reportes
│           └── operativo/            # ★ pantallas del back-office (patrón a imitar:
│               │                     #   tabla + formulario + toggle activo, operativo-shared.scss)
│               ├── catalogo/         # productos-admin
│               ├── compras/          # órdenes, recepciones, facturas, devoluciones-proveedor (multi-rol)
│               ├── inventario/       # transferencias, ajustes, kardex
│               ├── ventas/           # pedidos, facturas, preparación, despachos (+ novedades de envío),
│               │                     # devoluciones (RMA), mis-pedidos
│               ├── marketing/        # cupones, promociones, campañas, banners, newsletter
│               ├── soporte/          # bandeja de tickets (SOPORTE/ADMIN) + tickets del cliente
│               ├── resenas/          # moderación de reseñas (ADMIN/GERENTE)
│               └── horarios/
│
├── docker-compose.yml                # ★ 6 servicios: postgres, clickhouse, backend, frontend
│                                     #   (perfil `demo`), etl y pgadmin (perfil `tools`).
│                                     #   `pocketbase` ELIMINADO. `up -d` levanta los 4 primeros.
├── deploy/                           # ★ Infraestructura del despliegue (2026-08-03)
│   ├── postgres/initdb/              #   00_roles.sql + 02_restaurar.sh + dump (gitignored).
│   │                                 #   OJO: corre UNA SOLA VEZ, con el volumen vacio
│   ├── secrets/                      #   secretos de Docker (gitignored)
│   └── verificar_migracion.sql       #   V1-V9, diffable entre dos servidores
├── .env.example                      # Plantilla versionada: CLAVES sin VALORES
├── CLAUDE.md                         # Contexto para Claude Code (equivalente a este steering)
├── DEUDA_TECNICA.md                  # Registro canónico de deuda por fase (re-auditado 2026-07-18:
│                                     # cero Tipo 1 vigentes) + docs/INVENTARIO_DEUDA_CONSOLIDADO.md
├── ROADMAP.md                        # Decisiones de alcance formales (p. ej. Lote/FEFO pospuesto)
├── .kiro/steering/                   # Steering para Kiro (este archivo)
└── .specify/ / openspec/             # Spec Kit
```

## Architecture Patterns

- **Backend por capas**: Controller → Service (`@Transactional`, guardias de estado) →
  `pgJdbcTemplate` con SQL parametrizado. DTOs de entrada como `record` anidados en el controller;
  respuestas como `List<Map<String,Object>>` (snake_case) que el frontend tipa en
  `operativo.model.ts`.
- **Seguridad en dos capas**: `SecurityConfig` autoriza por ruta/rol; dentro de la transacción,
  `PgSessionRoleAspect` ejecuta `SET LOCAL ROLE grp_*` y la BD aplica privilegios + RLS + horario.
  El paquete `analytics/` queda excluido del aspecto (es ClickHouse).
- **La BD es dueña de la integridad**: totales y columnas GENERATED los calculan triggers; el
  código nunca los escribe. Los CHECKs se espejan con listas blancas en los services para dar
  mensajes claros antes del 400 genérico.
- **Pantalla operativa canónica** (imitar siempre): tabla Material + formulario colapsable +
  toggle activo + snackbar con `mensajeError`; estilos de `operativo-shared.scss`.
- **Ruta nueva** = SecurityConfig + `roleGuard` + sidebar (`app.component.html`, visibilidad por
  getters `canX`) + `routeMap` de breadcrumbs.
- **Flujo analítico**: PostgreSQL → `retailmind/etl/dwh/` (rol `retailmind_etl`, solo lectura) →
  ClickHouse `retailmind_dwh` ← `informes/` compuestos y `tableros/` ← Angular. (El viejo camino
  desde PocketBase es histórico; el servicio se eliminó del compose.)
- **Flujo operativo**: Angular ← API Spring ← PostgreSQL (retailmind_app + SET LOCAL ROLE).
- **Tienda del cliente**: checkout llama a `VentasService.crearPedido` (mismo flujo back-office);
  el id público de producto es el de la VARIANTE; eventos a CH best-effort.
- **Trazabilidad**: las acciones críticas guardan autor en columnas directas (FK a usuario,
  del JWT) y llaman a `AuditoriaService.registrar()` dentro de la misma transacción; el
  checkout online NO loguea (grp_cliente sin INSERT a `log_auditoria` a propósito — su rastro
  es `cliente_id` + canal 'web' + historial con RLS).
