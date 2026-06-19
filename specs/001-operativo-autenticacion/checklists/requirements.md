# Specification Quality Checklist: Operativo - Autenticación y control de acceso

**Purpose**: Validar la completitud y calidad de la especificación antes de planificar.
**Created**: 2026-06-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Enfocada en el valor y las reglas verdaderas del módulo (RBAC, JWT, sesión)
- [x] Todas las secciones obligatorias solicitadas están completas
- [x] Documenta el comportamiento implementado verificado en el código
- [N/A] "Sin detalles de implementación": por instrucción explícita, esta spec sí incluye detalles
  técnicos (endpoints, JWT, BCrypt, ClickHouse) para reflejar lo implementado

## Requirement Completeness

- [x] No quedan marcadores [NEEDS CLARIFICATION]
- [x] Los requisitos son verificables y no ambiguos
- [x] Los RNF están cuantificados (24 h, 7 días, ≥256 bits, 2 reintentos/1000 ms, BCrypt coste 10)
- [x] Los criterios de aceptación son medibles
- [x] Todos los escenarios solicitados están definidos en Gherkin
- [x] Se identifican casos límite (login fallido, refresh fallido, registro inválido, rutas)
- [x] El alcance está claramente acotado (administración de usuarios fuera de alcance)
- [x] Se documentan dependencias, restricciones y supuestos

## Feature Readiness

- [x] Cada RF tiene criterios de aceptación y/o escenarios asociados
- [x] Los escenarios cubren los flujos primarios (login, registro, redirección)
- [x] Existe trazabilidad RF → OO/CU/OT/OE
- [x] Los elementos no implementados están marcados como PENDIENTE, no inventados

## Notes

- El KPI (intentos no autorizados bloqueados / total) requiere instrumentación PENDIENTE
  (PEND-AUT-01): hoy no hay contador de intentos de login.
- Hallazgo de seguridad PEND-AUT-04: el alta de usuarios usa SQL por concatenación; conviene
  endurecerlo en una feature de limpieza/seguridad posterior.
- Próxima fase sugerida: `/speckit-plan` (no ejecutar todavía por indicación del usuario).
