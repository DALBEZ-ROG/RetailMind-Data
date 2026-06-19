# Feature Specification: Operativo - Autenticación y control de acceso

**Feature Branch**: `001-operativo-autenticacion`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Módulo piloto del nivel operativo de RetailMind. Especificación derivada del código
real implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre login, registro, sesión/
refresh, identidad, redirección por rol y protección de rutas. La administración de usuarios por
ADMIN queda fuera de alcance.

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, JWT,
> BCrypt, ClickHouse, guards). Lo no implementado se marca como **PENDIENTE** y no se inventa.

---

## 1. Objetivo

Permitir que un usuario se autentique de forma segura en RetailMind mediante credenciales
(`username` + `password`) validadas contra la tabla `usuarios_sistema` en ClickHouse, emitir un
token JWT con el rol embebido, mantener la sesión mediante refresh token, y controlar el acceso a
las vistas y endpoints según el rol (RBAC con `ADMIN` y `CLIENTE`). El módulo es la base de
seguridad sobre la que operan el resto de los módulos del nivel operativo, táctico y estratégico.

---

## 2. Usuarios / Actores

- **Usuario no autenticado (visitante)**: solo puede acceder a recursos públicos (catálogo,
  pantalla de login). Intenta iniciar sesión.
- **CLIENTE**: usuario autenticado de la tienda. Tras login es redirigido a `/shop`. Accede a
  tienda, carrito, wishlist, pedidos, perfil y recomendaciones.
- **ADMIN**: usuario autenticado administrador. Tras login es redirigido a `/dashboard`. Accede
  además a analítica avanzada, ETL, gestión y reportes. Es el único rol que puede invocar el alta
  de usuarios (`POST /api/auth/register`).
- **Sistema (backend)**: emite y valida tokens, aplica el filtro de seguridad en cada petición y
  siembra el usuario `admin` inicial al arrancar.

> Observación: el enum de roles del backend define `ADMIN`, `CLIENTE` y `VIEWER`. `VIEWER` existe
> en el código pero **no se usa** en ningún flujo de autorización (PENDIENTE: definir o retirar).

---

## 3. Contexto del problema

RetailMind es una plataforma web con tienda online y analítica de negocio. Si la plataforma no
controla quién accede y a qué, expone datos de negocio y operaciones de venta a usuarios no
autorizados. Se necesita un mecanismo de autenticación sin estado (apto para escalado horizontal)
y una autorización por rol que separe la experiencia de cliente de la de administración. La
solución debe evitar filtrar información sensible en los mensajes de error (no revelar si falló el
usuario o la contraseña) y mantener la sesión sin reautenticación constante mediante refresh token.

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-AUT-001** | El sistema MUST autenticar al usuario mediante `POST /api/auth/login` recibiendo `username` y `password`. |
| **RF-AUT-002** | El sistema MUST validar las credenciales contra la tabla `retailmind.usuarios_sistema` en ClickHouse (no PostgreSQL). |
| **RF-AUT-003** | El sistema MUST verificar la contraseña comparándola con el hash **BCrypt** almacenado. |
| **RF-AUT-004** | El sistema MUST rechazar el inicio de sesión de usuarios marcados como inactivos (`activo = 0`). |
| **RF-AUT-005** | El sistema MUST generar un **token JWT de acceso** cuyo `subject` es el `username` e incluye el claim `rol`. |
| **RF-AUT-006** | El sistema MUST generar un **refresh token** JWT independiente del token de acceso. |
| **RF-AUT-007** | La respuesta de login MUST incluir: `token`, `refreshToken`, `username`, `nombre`, `rol`, `expiresIn`. |
| **RF-AUT-008** | El sistema MUST renovar el token de acceso mediante `POST /api/auth/refresh` validando el refresh token vigente. |
| **RF-AUT-009** | El sistema MUST exponer la identidad autenticada mediante `GET /api/auth/me`, devolviendo `username` y `rol`. |
| **RF-AUT-010** | El sistema MUST exponer `POST /api/auth/logout`; el cierre de sesión efectivo se realiza en el cliente eliminando el token, refresh token y datos de usuario. |
| **RF-AUT-011** | El sistema MUST permitir el alta de usuarios mediante `POST /api/auth/register`, **restringido al rol ADMIN**, exigiendo `username`, `password` y `rol`, validando rol permitido y unicidad de `username`, y almacenando la contraseña hasheada con BCrypt. |
| **RF-AUT-012** | El cliente MUST persistir la sesión en almacenamiento local del navegador (`token`, `refreshToken`, datos del usuario). |
| **RF-AUT-013** | El cliente MUST adjuntar el token en la cabecera `Authorization: Bearer <token>` en las peticiones, excepto a `login`, `refresh` y `health`. |
| **RF-AUT-014** | Ante una respuesta `401`, el cliente MUST intentar **una** renovación automática del token; si la renovación falla, MUST cerrar la sesión. |
| **RF-AUT-015** | El cliente MUST reintentar peticiones `GET` fallidas por error de red (`status 0`) o error de servidor (`status ≥ 500`) hasta **2 veces** con **1000 ms** de espera. |
| **RF-AUT-016** | El cliente MUST proteger las rutas con `authGuard` (requiere sesión) y `adminGuard` (requiere rol `ADMIN`). |
| **RF-AUT-017** | Tras un login exitoso, el cliente MUST redirigir a `/dashboard` si el rol es `ADMIN` y a `/shop` para cualquier otro rol. |
| **RF-AUT-018** | El formulario de login MUST validar en cliente que `username` tenga ≥ 3 caracteres y `password` ≥ 6 caracteres antes de enviar. |
| **RF-AUT-019** | El backend MUST validar el token JWT en cada petición mediante un filtro y establecer el contexto de seguridad cuando el token sea válido; un token ausente o inválido no establece autenticación. |
| **RF-AUT-020** | Al arrancar, el backend MUST sembrar un usuario `admin` con rol `ADMIN` si no existe. |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-AUT-001** | El token de acceso MUST expirar a los **86.400.000 ms (24 horas)** desde su emisión (`jwt.expiration`). |
| **RNF-AUT-002** | El refresh token MUST expirar a los **604.800.000 ms (7 días)** desde su emisión (`jwt.refresh-expiration`). |
| **RNF-AUT-003** | El JWT MUST firmarse con **HMAC-SHA** usando una clave de **≥ 256 bits** (la clave se rellena a 32 bytes si es menor). |
| **RNF-AUT-004** | Las contraseñas MUST almacenarse con **BCrypt** (`BCryptPasswordEncoder`, factor de coste por defecto = 10). En ningún caso se almacenan ni se devuelven en texto plano. |
| **RNF-AUT-005** | El backend MUST operar **sin estado de sesión** (`SessionCreationPolicy.STATELESS`), habilitando escalado horizontal. |
| **RNF-AUT-006** | El backend MUST aceptar peticiones CORS únicamente desde el origen **`http://localhost:4200`**, con métodos `GET, POST, PUT, DELETE, OPTIONS` y credenciales habilitadas. |
| **RNF-AUT-007** | Ante fallo de login, el sistema MUST responder con código **HTTP 401** y un mensaje **genérico** que no revele si el fallo fue por usuario, contraseña o estado de la cuenta. |
| **RNF-AUT-008** | El acceso a las credenciales en login MUST realizarse mediante consulta **parametrizada** a ClickHouse. |
| **RNF-AUT-009** | El sistema MUST registrar en log (`logs/retailmind.log`, nivel INFO) los eventos relevantes de inicialización y operación del backend. |
| **RNF-AUT-010** | **PENDIENTE (objetivo de rendimiento)**: no existe en el código un umbral de latencia medido para el login. Debe definirse y medirse (p. ej., p95 < N ms) antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-AUT-001** | Solo los usuarios con `activo = 1` pueden autenticarse. |
| **RN-AUT-002** | El mensaje ante credenciales inválidas es genérico ("Credenciales incorrectas") y NO distingue entre usuario inexistente, contraseña incorrecta o cuenta desactivada. |
| **RN-AUT-003** | El rol del usuario determina su vista inicial: `ADMIN` → `/dashboard`; cualquier otro rol → `/shop`. |
| **RN-AUT-004** | Los roles aceptados en el registro son `ADMIN` y `CLIENTE`. Un rol distinto se rechaza con error de validación. |
| **RN-AUT-005** | El `username` MUST ser único; no se permite registrar un `username` ya existente. |
| **RN-AUT-006** | El alta de usuarios (`register`) solo puede ejecutarla un usuario autenticado con rol `ADMIN`. No existe auto-registro público de clientes. |
| **RN-AUT-007** | El token de acceso transporta el rol como claim, que es la fuente de verdad para la autorización del backend y del frontend. |
| **RN-AUT-008** | El refresh token se reutiliza al renovar: la renovación emite un nuevo token de acceso pero conserva el refresh token vigente. |

---

## 7. Entradas

- **Login** (`POST /api/auth/login`), JSON: `{ "username": string, "password": string }`.
- **Refresh** (`POST /api/auth/refresh`), JSON: `{ "refreshToken": string }`.
- **Identidad** (`GET /api/auth/me`): cabecera `Authorization: Bearer <token>`.
- **Logout** (`POST /api/auth/logout`): cabecera `Authorization: Bearer <token>`.
- **Registro** (`POST /api/auth/register`, solo ADMIN), JSON:
  `{ "username": string, "password": string, "rol": "ADMIN"|"CLIENTE", "email"?: string }`.
  *(Observación: el valor `email` se almacena hoy en el campo `nombre` del usuario.)*
- **Peticiones protegidas**: cabecera `Authorization: Bearer <token>`.
- **Formulario de login (cliente)**: `username` (≥ 3 caracteres), `password` (≥ 6 caracteres).

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Login 200**: `{ token, refreshToken, username, nombre, rol, expiresIn }`.
- **Refresh 200**: `{ token (nuevo), refreshToken (mismo), username, nombre, rol, expiresIn }`.
- **/me 200**: `{ "username": ..., "rol": ... }`.
- **Logout 200**: `{ "mensaje": "Sesion cerrada exitosamente" }`.
- **Register 200**: `{ "success": true, "mensaje": "Usuario creado" }`.

**Error**

- **Login 401**: `{ "error": "Credenciales incorrectas" }` (cualquier causa).
- **Refresh 401**: `{ "error": "Refresh token invalido o expirado" }`.
- **/me 401**: cuerpo vacío (no autenticado).
- **Register 400** (faltan campos): `{ "error": "username, password y rol son requeridos" }`.
- **Register 400** (duplicado): `{ "error": "El usuario '<username>' ya existe" }`.
- **Register 400** (rol inválido): `{ "error": "Rol invalido. Use ADMIN o CLIENTE" }`.
- **Register 500**: `{ "error": "Error al crear usuario: <detalle>" }`.

**Mensajes en cliente (UI)**

- Fallo de login en pantalla: "Credenciales incorrectas. Intenta de nuevo." + animación de
  sacudida del formulario.
- Notificación (snackbar) ante `status ≥ 500`: "Error del servidor. Intente nuevamente."
- Notificación (snackbar) ante `status 0`: "Sin conexion con el servidor."

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Login exitoso (CU-01)

```gherkin
Dado un usuario activo con credenciales válidas registradas en usuarios_sistema
Cuando envía POST /api/auth/login con su username y password correctos
Entonces el sistema responde HTTP 200
  Y devuelve token, refreshToken, username, nombre, rol y expiresIn
  Y el token JWT incluye el claim "rol" del usuario
```

### Escenario 2 — Login fallido (credenciales inválidas)

```gherkin
Dado un intento de inicio de sesión
Cuando envía POST /api/auth/login con un username inexistente o una password incorrecta
  O cuando la cuenta está desactivada (activo = 0)
Entonces el sistema responde HTTP 401
  Y devuelve el mensaje genérico "Credenciales incorrectas"
  Y NO revela si el fallo fue por el usuario, la contraseña o el estado de la cuenta
```

### Escenario 3 — Registro exitoso (CU-02, solo ADMIN)

```gherkin
Dado un administrador autenticado con rol ADMIN
Cuando envía POST /api/auth/register con username nuevo, password y rol válido (ADMIN o CLIENTE)
Entonces el sistema responde HTTP 200 con { success: true, mensaje: "Usuario creado" }
  Y la contraseña se almacena hasheada con BCrypt
  Y el usuario queda activo en usuarios_sistema
```

### Escenario 4 — Registro con datos inválidos

```gherkin
Dado un administrador autenticado con rol ADMIN
Cuando envía POST /api/auth/register sin username, sin password o sin rol
Entonces el sistema responde HTTP 400 con "username, password y rol son requeridos"

Cuando envía POST /api/auth/register con un username que ya existe
Entonces el sistema responde HTTP 400 con "El usuario '<username>' ya existe"

Cuando envía POST /api/auth/register con un rol no permitido
Entonces el sistema responde HTTP 400 con "Rol invalido. Use ADMIN o CLIENTE"
```

### Escenario 5 — Redirección por rol (OO-14)

```gherkin
Dado un login exitoso
Cuando el usuario autenticado tiene rol ADMIN
Entonces el cliente lo redirige a /dashboard

Cuando el usuario autenticado tiene rol CLIENTE (u otro distinto de ADMIN)
Entonces el cliente lo redirige a /shop
```

### Escenario 6 — Protección de rutas (OT-08) [complementario]

```gherkin
Dado un usuario sin sesión activa
Cuando intenta acceder a una ruta protegida por authGuard
Entonces el cliente lo redirige a /login

Dado un usuario autenticado sin rol ADMIN
Cuando intenta acceder a una ruta protegida por adminGuard
Entonces el cliente lo redirige a /dashboard
```

### Escenario 7 — Renovación de sesión (refresh) [complementario]

```gherkin
Dada una sesión cuyo token de acceso expiró
Cuando una petición protegida responde 401 y existe un refresh token vigente
Entonces el cliente solicita POST /api/auth/refresh una vez
  Y reintenta la petición original con el nuevo token
Pero si el refresh token es inválido o expiró
Entonces el cliente cierra la sesión y redirige a /login
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-AUT-001** | Un usuario activo con credenciales correctas obtiene HTTP 200 y un token JWT cuyo claim `rol` coincide con su rol en `usuarios_sistema`. |
| **CA-AUT-002** | Un intento con usuario inexistente, contraseña incorrecta o cuenta inactiva devuelve HTTP 401 con el mensaje exacto "Credenciales incorrectas". |
| **CA-AUT-003** | Las contraseñas en `usuarios_sistema` nunca se almacenan ni se transmiten en texto plano (siempre hash BCrypt). |
| **CA-AUT-004** | Un token de acceso emitido deja de ser aceptado pasadas 24 h; un refresh token deja de ser aceptado pasados 7 días. |
| **CA-AUT-005** | Tras login, un ADMIN aterriza en `/dashboard` y un CLIENTE en `/shop`. |
| **CA-AUT-006** | Un usuario sin sesión que navega a una ruta protegida es redirigido a `/login`; un no-ADMIN que navega a una ruta de admin es redirigido a `/dashboard`. |
| **CA-AUT-007** | Una petición protegida que recibe 401 dispara un único intento de refresh; si falla, la sesión se cierra. |
| **CA-AUT-008** | `POST /api/auth/register` invocado sin rol ADMIN es rechazado por la capa de seguridad (no crea usuario). |
| **CA-AUT-009** | El registro rechaza username duplicado, rol no permitido y campos faltantes con HTTP 400 y el mensaje correspondiente. |
| **CA-AUT-010** | `GET /api/auth/me` con token válido devuelve `username` y `rol`; sin token devuelve 401. |

---

## 11. Restricciones

- **Base de datos única**: las credenciales residen exclusivamente en `retailmind.usuarios_sistema`
  (ClickHouse). PostgreSQL fue eliminado.
- **Acceso a datos**: el backend usa `JdbcTemplate` (sin JPA). El login consulta de forma
  parametrizada.
- **Stack fijado**: Spring Boot 3.5, Spring Security + jjwt 0.12.3 (backend); Angular 17 standalone
  con guards funcionales e interceptor funcional (frontend). Su cambio requiere enmienda a la
  constitución.
- **Sin estado en servidor**: la sesión vive en el cliente (localStorage) + JWT; no hay sesión de
  servidor que invalidar.
- **CORS** limitado al origen del frontend (`http://localhost:4200`).

---

## 12. Dependencias

- **ClickHouse** disponible con la base `retailmind` y la tabla `usuarios_sistema` creada (el
  backend la crea al arrancar si no existe).
- **Variables de entorno**: `JWT_SECRET`, `JWT_EXPIRATION`, `JWT_REFRESH_EXPIRATION`, y la
  configuración del datasource ClickHouse.
- **Spring Security** (`AuthenticationManager`, `BCryptPasswordEncoder`, filtro JWT).
- **Frontend**: `AuthService`, `authGuard`, `adminGuard`, `authInterceptor`, `MatSnackBar`.
- **Constitución del proyecto** (`.specify/memory/constitution.md`), Principio V (Seguridad por
  defecto: JWT + RBAC), de cumplimiento obligatorio.

---

## 13. Fuera de Alcance

- **Administración de usuarios por ADMIN (OO-13)**: listar, crear (vía `POST /api/admin/usuarios`),
  eliminar y activar/desactivar usuarios. Es un **módulo administrativo aparte** (paquete
  `admin/usuarios` en backend y feature `admin/usuarios` en frontend). El endpoint
  `POST /api/auth/register` se documenta aquí únicamente como mecanismo de alta de credenciales del
  módulo de autenticación.
- **Gestión de perfil y cambio de contraseña** por el propio usuario (módulo `perfil`).
- **Auto-registro público de clientes**: no existe en el código.
- **Recuperación/restablecimiento de contraseña**: no implementado.
- **Autenticación multifactor (MFA)**, federación/SSO/OAuth: no implementados.
- **Bloqueo de cuenta y limitación de tasa (rate limiting)** por intentos fallidos: no implementado
  (PENDIENTE).
- **Invalidación de tokens en el servidor (logout server-side / blacklist)**: no implementado.
- **Rol `VIEWER`**: definido en el enum pero sin flujos asociados.

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI oficial**: *intentos de acceso no autorizado bloqueados / total de intentos de login*.
  - **SC-AUT-001 (objetivo)**: el 100 % de los intentos con credenciales inválidas o cuentas
    inactivas se rechazan con HTTP 401 (sin conceder token).
  - **PENDIENTE de instrumentación**: actualmente el sistema **no contabiliza** intentos de login
    (exitosos/fallidos) en una métrica persistente. Para calcular el KPI se requiere instrumentar
    el registro de intentos (p. ej., contador o tabla de auditoría). Hasta entonces, el KPI no es
    medible automáticamente.

---

## 15. Trazabilidad

Vínculo de cada requisito funcional con los objetivos operativos (OO), casos de uso (CU),
objetivos tácticos (OT) y el objetivo estratégico (OE).

| Requisito | OO | CU | OT | OE |
|-----------|----|----|----|----|
| RF-AUT-001 Login | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-002 Validar contra ClickHouse | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-003 Verificar BCrypt | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-004 Rechazar inactivos | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-005 JWT con rol | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-006 Refresh token | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-007 Respuesta de login | OO-12 / OO-14 | CU-01 | OT-07 | OE-04 |
| RF-AUT-008 Renovar token | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-009 Identidad (/me) | OO-14 | CU-01 | OT-08 | OE-04 |
| RF-AUT-010 Logout | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-011 Registro (ADMIN) | OO-12 | CU-02 | OT-07 | OE-04 |
| RF-AUT-012 Persistir sesión cliente | OO-14 | CU-01 | OT-07 | OE-04 |
| RF-AUT-013 Adjuntar JWT (interceptor) | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-014 Refresh automático ante 401 | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-015 Reintento de GET | OO-14 | CU-01 | OT-08 | OE-04 |
| RF-AUT-016 Guards (auth/admin) | OO-14 | CU-01 | OT-08 | OE-04 |
| RF-AUT-017 Redirección por rol | OO-14 | CU-01 | OT-08 | OE-04 |
| RF-AUT-018 Validación de formulario | OO-12 | CU-01 | OT-07 | OE-04 |
| RF-AUT-019 Filtro JWT en backend | OO-12 | CU-01 | OT-08 | OE-04 |
| RF-AUT-020 Seed usuario admin | OO-12 | CU-02 | OT-07 | OE-04 |

**Leyenda de objetivos**:

- **OE-04**: Garantizar la seguridad mediante control de acceso basado en roles (RBAC).
- **OT-07**: Gestionar de forma segura identidades y credenciales.
- **OT-08**: Separar y proteger vistas según rol.
- **OO-12**: Autenticar con JWT y credenciales hasheadas en ClickHouse.
- **OO-14**: Mostrar interfaces según rol.
- **CU-01**: Iniciar sesión. **CU-02**: Registrar usuario.

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-AUT-01**: No existe medición del KPI (sin contador/auditoría de intentos de login).
- **PEND-AUT-02**: No hay bloqueo por intentos fallidos ni rate limiting.
- **PEND-AUT-03**: `register` no valida formato de `email` ni fuerza de contraseña en backend; el
  `email` se persiste en el campo `nombre`.
- **PEND-AUT-04**: `ClickHouseUserRepository.save()` construye el `INSERT` por concatenación de
  cadenas con escape manual (no parametrizado); endurecer para evitar riesgo de inyección.
- **PEND-AUT-05**: El logout no invalida el token en el servidor (solo limpia el cliente).
- **PEND-AUT-06**: El rol `VIEWER` está definido pero no se usa en ningún flujo.
