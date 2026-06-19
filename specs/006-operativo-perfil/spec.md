# Feature Specification: Operativo - Perfil del cliente

**Feature Branch**: `006-operativo-perfil`

**Created**: 2026-06-18

**Status**: Draft

**Input**: Sexto módulo del nivel operativo de RetailMind. Especificación derivada del código real
implementado (Angular 17 + Spring Boot 3.5 + ClickHouse). Cubre la visualización del perfil del
cliente con estadísticas derivadas de su actividad y la edición de sus propios datos (email y
contraseña). La autenticación/login y la administración de usuarios por el ADMIN son módulos aparte
(fuera de alcance).

> **Nota metodológica**: por instrucción explícita, esta especificación documenta el
> comportamiento **ya implementado** e incluye detalles técnicos verificables (endpoints, tablas
> ClickHouse). Lo no implementado se marca como **PENDIENTE** y no se inventa.
>
> **Nota de trazabilidad**: el documento de objetivos **EVF04 NO define un OO ni un CU propios**
> para el perfil del cliente. Esta spec usa el caso de uso **CU-O08 "Ver y editar mi perfil"** del
> documento **TA06** y lo relaciona con **OE-04** y **OT-07**, dejando registrada la **deuda de
> trazabilidad** en lugar de forzar un mapeo a un OO inexistente.

---

## 1. Objetivo

Permitir que un cliente autenticado consulte su propio perfil —datos de identidad y estadísticas
derivadas de su actividad— y edite sus datos editables (email y contraseña), operando sobre la
tabla `usuarios_sistema` (identidad) y agregando métricas desde `ordenes`, `wishlist_items` y
`fact_eventos`. El módulo refuerza la gestión segura de la identidad del usuario.

---

## 2. Usuarios / Actores

- **CLIENTE autenticado**: consulta su perfil y estadísticas, actualiza su email y cambia su
  contraseña. Es el actor principal.
- **ADMIN autenticado**: puede consultar/editar su propio perfil como cualquier usuario autenticado
  (la administración de usuarios de terceros es otro módulo, fuera de alcance).
- **Sistema (backend)**: recupera la identidad desde `usuarios_sistema`, calcula las estadísticas y
  aplica las actualizaciones de email y contraseña.

> El módulo **requiere autenticación**: las rutas `/api/perfil/**` caen bajo `.authenticated()` en
> `SecurityConfig` (requieren JWT) y la ruta de tienda `/perfil` está protegida por `authGuard`. En
> la interfaz, todas las operaciones se realizan sobre el `username` del usuario en sesión.

---

## 3. Contexto del problema

El cliente necesita un espacio para ver quién es dentro de la plataforma, entender su actividad
(cuánto ha comprado, qué le interesa) y mantener actualizados sus datos de acceso (email y
contraseña). Sin un perfil, la gestión de identidad queda dispersa y el cliente no puede corregir
sus datos ni cambiar su contraseña de forma autónoma. RetailMind necesita exponer un perfil personal
seguro, donde cada cliente vea y edite únicamente lo suyo, alineado con la gestión segura de
identidades (OT-07) y el control de acceso (OE-04).

---

## 4. Requisitos Funcionales

| ID | Requisito |
|----|-----------|
| **RF-PER-001** | El sistema MUST exponer el perfil de un usuario mediante `GET /api/perfil/{username}` (requiere autenticación), devolviendo datos de identidad: `username`, `email`, `rol`, `activo`, `fechaCreacion`. |
| **RF-PER-002** | El perfil MUST incluir **6 estadísticas** derivadas de la actividad: `totalCompras` (nº de órdenes), `totalGastado` (suma de totales), `productosWishlist` (nº en wishlist), `totalEventos` (nº de eventos), `categoriaFavorita` (categoría más frecuente) y `canalPreferido` (canal más frecuente). |
| **RF-PER-003** | El `email` mostrado/editado MUST corresponder al campo `nombre` de `usuarios_sistema` (no existe una columna `email` dedicada). |
| **RF-PER-004** | El sistema MUST permitir actualizar el email mediante `PUT /api/perfil/{username}/email`, exigiendo que el `email` no esté vacío. |
| **RF-PER-005** | El sistema MUST permitir cambiar la contraseña mediante `PUT /api/perfil/{username}/password`, exigiendo `passwordActual` y `passwordNuevo`, validando que la nueva sea distinta de la actual y que la actual coincida (BCrypt), y almacenando la nueva como hash BCrypt. |
| **RF-PER-006** | El sistema MUST devolver HTTP 404 cuando el usuario indicado no existe. |
| **RF-PER-007** | El cliente MUST validar en formulario el formato del email y la contraseña nueva (mínimo 6 caracteres, confirmación coincidente, distinta de la actual) antes de enviar. |
| **RF-PER-008** | Todas las operaciones del módulo MUST requerir un usuario autenticado (token JWT válido). |

---

## 5. Requisitos No Funcionales (cuantificados)

| ID | Requisito |
|----|-----------|
| **RNF-PER-001** | Las rutas `/api/perfil/**` MUST requerir token JWT válido (autorización `authenticated`). |
| **RNF-PER-002** | Las contraseñas MUST almacenarse con **BCrypt**; nunca en texto plano y nunca devueltas en el perfil. |
| **RNF-PER-003** | Las actualizaciones de email y contraseña MUST ejecutarse de forma síncrona (`SETTINGS mutations_sync = 1`). |
| **RNF-PER-004** | Las 6 estadísticas MUST calcularse desde `usuarios_sistema`, `ordenes`, `wishlist_items` y `fact_eventos` (estas dos últimas vía agregación). |
| **RNF-PER-005** | El cálculo de estadísticas MUST ser tolerante a fallos: ante error, devolver `0` (numéricas) o `"Sin datos"` (categoría/canal) sin romper la respuesta del perfil. |
| **RNF-PER-006** | La contraseña nueva en cliente MUST tener **mínimo 6 caracteres** y coincidir con su confirmación. |
| **RNF-PER-007** | **PENDIENTE (objetivo de rendimiento)**: el cliente mide el tiempo de carga (`queryMs`) pero no existe un umbral definido. Debe establecerse y medirse antes de tratarlo como requisito vigente. |

---

## 6. Reglas de Negocio

| ID | Regla |
|----|-------|
| **RN-PER-001** | Un cliente debe poder ver y editar **únicamente su propio** perfil. **PENDIENTE**: el backend **no valida** que el `username` de la ruta coincida con el usuario del token (ver PEND-PER-01). |
| **RN-PER-002** | La nueva contraseña debe ser distinta de la actual (validado en cliente y backend). |
| **RN-PER-003** | El cambio de contraseña exige que la `passwordActual` proporcionada coincida con la almacenada (BCrypt); si no, se rechaza con "La contraseña actual es incorrecta". |
| **RN-PER-004** | El email del usuario se persiste en el campo `nombre` de `usuarios_sistema`. |
| **RN-PER-005** | Cuando no hay datos de actividad, las estadísticas muestran `0` o `"Sin datos"`. |
| **RN-PER-006** | En la interfaz, el módulo opera sobre el usuario en sesión (su `username`). |
| **RN-PER-007** | No se permite editar `username` ni `rol` desde el perfil (solo email y contraseña). |

---

## 7. Entradas

- **Ver perfil** (`GET /api/perfil/{username}`): `username` en la ruta.
- **Actualizar email** (`PUT /api/perfil/{username}/email`), JSON: `{ "email": string }`.
- **Cambiar contraseña** (`PUT /api/perfil/{username}/password`), JSON:
  `{ "passwordActual": string, "passwordNuevo": string }`.
- **Autenticación**: cabecera `Authorization: Bearer <token>` en todas las operaciones.
- **Formulario (cliente)**: email con formato válido; contraseña nueva ≥ 6 caracteres + confirmación.

---

## 8. Salidas (incluye mensajes de error)

**Éxito**

- **Ver perfil 200**:
  `{ username, email, rol, activo, fechaCreacion, totalCompras, totalGastado, productosWishlist, totalEventos, categoriaFavorita, canalPreferido }`.
- **Actualizar email 200**: `{ "success": true, "mensaje": "Email actualizado correctamente" }`.
- **Cambiar contraseña 200**: `{ "success": true, "mensaje": "Contraseña actualizada correctamente" }`.

**Error**

- **Perfil/email/password 404**: usuario no encontrado (cuerpo vacío, `notFound`).
- **Actualizar email 400**: `{ "error": "El email es requerido" }`.
- **Cambiar contraseña 400** (faltan campos): `{ "error": "passwordActual y passwordNuevo son requeridos" }`.
- **Cambiar contraseña 400** (igual a la actual): `{ "error": "La nueva contraseña debe ser diferente a la actual" }`.
- **Cambiar contraseña 400** (actual incorrecta): `{ "error": "La contraseña actual es incorrecta" }`.
- **500**: `{ "error": "Error al obtener perfil: ..." | "Error al actualizar email: ..." | "Error al cambiar contraseña: ..." }`.

**Mensajes en cliente (UI)**

- "Email actualizado ✓" / "Error al actualizar email".
- "Contraseña actualizada ✓" / "Error al cambiar contraseña" (o el mensaje del backend).
- "Error al cargar el perfil" si falla la carga.

---

## 9. Escenarios (Gherkin)

### Escenario 1 — Ver perfil con estadísticas (CU-O08)

```gherkin
Dado un cliente autenticado
Cuando solicita GET /api/perfil/{username} con su propio username
Entonces el sistema responde HTTP 200 con sus datos de identidad
  Y con las 6 estadísticas: totalCompras, totalGastado, productosWishlist, totalEventos,
     categoriaFavorita y canalPreferido
  Y las estadísticas sin datos se muestran como 0 o "Sin datos"
```

### Escenario 2 — Editar perfil exitoso (email) (CU-O08)

```gherkin
Dado un cliente autenticado con un email válido en el formulario
Cuando envía PUT /api/perfil/{username}/email con un email no vacío
Entonces el sistema actualiza el campo nombre de usuarios_sistema
  Y responde { success: true, mensaje: "Email actualizado correctamente" }
```

### Escenario 3 — Editar con datos inválidos (CU-O08)

```gherkin
Dado un cliente autenticado
Cuando envía PUT /api/perfil/{username}/email con email vacío
Entonces el sistema responde HTTP 400 con "El email es requerido"

Cuando envía PUT /api/perfil/{username}/password con la contraseña actual incorrecta
Entonces el sistema responde HTTP 400 con "La contraseña actual es incorrecta"

Cuando envía una contraseña nueva igual a la actual
Entonces el sistema responde HTTP 400 con "La nueva contraseña debe ser diferente a la actual"
```

### Escenario 4 — Intento de ver/editar un perfil ajeno (comportamiento actual)

```gherkin
Dado un cliente autenticado
Cuando solicita GET o PUT /api/perfil/{otroUsername} con un username distinto al suyo
Entonces el sistema procesa la operación sobre ESE otro usuario
  Porque el backend NO valida la propiedad contra el token (PENDIENTE de seguridad)
Nota: cambiar la contraseña ajena igualmente exige conocer la contraseña actual de esa cuenta,
  pero ver el perfil y actualizar el email no están protegidos contra consulta/edición cruzada.
```

---

## 10. Criterios de Aceptación

| ID | Criterio |
|----|----------|
| **CA-PER-001** | `GET /api/perfil/{username}` devuelve los datos de identidad y las 6 estadísticas del usuario. |
| **CA-PER-002** | Las estadísticas se calculan desde `ordenes`, `wishlist_items` y `fact_eventos`; sin datos devuelven `0`/`"Sin datos"`. |
| **CA-PER-003** | Actualizar el email con valor no vacío persiste el cambio en `usuarios_sistema.nombre` y responde éxito. |
| **CA-PER-004** | El email vacío se rechaza con HTTP 400 "El email es requerido". |
| **CA-PER-005** | Cambiar la contraseña exige la actual correcta y una nueva distinta; almacena la nueva como hash BCrypt. |
| **CA-PER-006** | Una contraseña actual incorrecta o una nueva igual a la actual se rechazan con HTTP 400 y su mensaje. |
| **CA-PER-007** | Un usuario inexistente devuelve HTTP 404. |
| **CA-PER-008** | Todas las operaciones rechazan peticiones sin token JWT válido. |
| **CA-PER-009** | **PENDIENTE**: validar que un cliente no pueda ver/editar el perfil de otro usuario (hoy no se impide a nivel de API). |

---

## 11. Restricciones

- **Base de datos única**: la identidad reside en `retailmind.usuarios_sistema`; las estadísticas se
  agregan desde `ordenes`, `wishlist_items`, `fact_eventos`, `dim_producto` y `dim_categoria`.
- **Acceso a datos**: `JdbcTemplate` (sin JPA). Las consultas y mutaciones se construyen por
  concatenación de cadenas (ver PENDIENTE de seguridad).
- **Campos editables**: solo email (campo `nombre`) y contraseña; no se editan `username` ni `rol`.
- **Autenticación obligatoria**: el módulo no opera de forma anónima.
- **Stack fijado**: Spring Boot 3.5 (backend) y Angular 17 standalone con Angular Material
  (frontend); su cambio requiere enmienda a la constitución.

---

## 12. Dependencias

- **Módulo de Autenticación (001)**: comparte `usuarios_sistema`, `ClickHouseUserRepository` y el
  `PasswordEncoder` (BCrypt); el login y la gestión de tokens pertenecen a ese módulo → ver Fuera de
  Alcance.
- **Módulo de Carrito/Pedidos (003/005)**: las órdenes (`ordenes`) alimentan `totalCompras` y
  `totalGastado`.
- **Módulo de Wishlist (004)**: `wishlist_items` alimenta `productosWishlist`.
- **Módulo de Catálogo (002)**: `fact_eventos` + `dim_producto`/`dim_categoria` alimentan
  `categoriaFavorita` y `canalPreferido`.
- **ClickHouse** con las tablas mencionadas pobladas.
- **Constitución** (`.specify/memory/constitution.md`), Principio V (seguridad: autenticación,
  BCrypt y control de acceso por usuario).

---

## 13. Fuera de Alcance

- **Autenticación / login (001)**: emisión y validación de tokens; este módulo solo consume la
  sesión ya establecida.
- **Administración de usuarios por el ADMIN (OO-13)**: crear/eliminar/activar usuarios de terceros
  es un módulo administrativo aparte.
- **Recuperación / restablecimiento de contraseña** (olvido de contraseña): no implementado.
- **Edición de `username` o `rol`** desde el perfil: no permitido.
- **Avatar / foto de perfil**: no implementado (la inicial se deriva del username en el cliente).
- **Historial de cambios del perfil / auditoría**: no implementado.

---

## 14. KPI y Criterios de Éxito Medibles

- **KPI**: **PENDIENTE**. Ni el documento EVF04 ni el código exponen un KPI claro y propio para el
  módulo de perfil. No se define uno para no inventarlo.
  - **SC-PER-001 (cualitativo, propuesto)**: el cliente puede consultar su perfil y mantener
    actualizados su email y contraseña sin intervención de un administrador. (Sujeto a confirmación;
    no es un KPI cuantitativo oficial.)
  - **Observación**: las estadísticas mostradas (compras, gasto, wishlist, eventos, categoría/canal)
    son indicadores de actividad del usuario, no un KPI de desempeño del módulo.

---

## 15. Trazabilidad

> **Deuda de trazabilidad**: EVF04 **no asigna** un Objetivo Operativo (OO) ni un Caso de Uso (CU)
> propios al perfil del cliente. Se usa **CU-O08** (TA06) y se vincula a **OE-04** y **OT-07**. La
> casilla "OO" queda marcada como **sin asignación en EVF04**.

| Requisito | OO (EVF04) | CU (TA06) | OT | OE |
|-----------|------------|-----------|----|----|
| RF-PER-001 Ver perfil | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-002 Estadísticas del perfil | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-003 Email en campo nombre | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-004 Actualizar email | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-005 Cambiar contraseña | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-006 404 usuario inexistente | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-007 Validación en cliente | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |
| RF-PER-008 Requiere autenticación | — (sin OO en EVF04) | CU-O08 | OT-07 | OE-04 |

**Leyenda de objetivos**:

- **OE-04**: Garantizar la seguridad mediante control de acceso basado en roles (RBAC).
- **OT-07**: Gestionar de forma segura identidades y credenciales.
- **CU-O08** (TA06): Ver y editar mi perfil (actor: Cliente).
- **OO**: EVF04 no define un objetivo operativo para el perfil (deuda de trazabilidad registrada).

---

## 16. Pendientes detectados (no inventar; tratar como trabajo futuro)

- **PEND-PER-01** (seguridad): los endpoints `/api/perfil/{username}` **no validan la propiedad**:
  un usuario autenticado puede ver/editar el perfil de otro pasando su `username` en la ruta (IDOR,
  análogo a PEND-PED-01). Debe forzarse que el `username` coincida con el usuario del token.
- **PEND-PER-02** (seguridad): las consultas y mutaciones se construyen por **concatenación de
  cadenas** (no parametrizadas) → riesgo de inyección (consistente con PEND-AUT-04, PEND-CAT-03,
  PEND-CAR-03, PEND-WIS-02, PEND-PED-02).
- **PEND-PER-03**: el backend **no valida el formato del email** (solo que no esté vacío); la
  validación de formato existe únicamente en el cliente.
- **PEND-PER-04**: el backend **no valida la longitud/fuerza** de la contraseña nueva (el mínimo de
  6 caracteres solo se exige en el cliente).
- **PEND-PER-05**: no existe un **KPI** propio del módulo en EVF04 ni en el código.
- **PEND-PER-06**: deuda de trazabilidad — EVF04 no define OO/CU para el perfil (se usa CU-O08 de
  TA06).
