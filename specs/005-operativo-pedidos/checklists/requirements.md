# Specification Quality Checklist: Operativo - Pedidos del cliente

**Purpose**: Validar la completitud y calidad de la especificación antes de planificar.
**Created**: 2026-06-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Enfocada en el valor del módulo (seguimiento de pedidos, transparencia y retención)
- [x] Todas las secciones solicitadas están completas
- [x] Documenta el comportamiento implementado verificado en el código
- [N/A] "Sin detalles de implementación": por instrucción explícita, la spec sí incluye detalles
  técnicos (endpoints, tablas) para reflejar lo implementado

## Requirement Completeness

- [x] No quedan marcadores [NEEDS CLARIFICATION]
- [x] Los requisitos son verificables y no ambiguos
- [x] Los RNF están cuantificados/explícitos (orden fecha desc, solo lectura, N+1, JWT)
- [x] Los criterios de aceptación son medibles
- [x] Escenarios Gherkin: lista de pedidos, detalle de orden, cliente sin pedidos, orden ajena
- [x] Se identifican casos límite (sin pedidos, consulta cruzada de usuario)
- [x] Alcance acotado (checkout y supervisión admin fuera de alcance)
- [x] Dependencias, restricciones y supuestos documentados
- [x] Requisito de autenticación explícito

## Feature Readiness

- [x] Cada RF tiene criterios de aceptación y/o escenarios asociados
- [x] Los escenarios cubren los flujos primarios (ver historial, ver detalle)
- [x] Existe trazabilidad RF → OO/CU/OT/OE
- [x] Los elementos no implementados están marcados como PENDIENTE, no inventados

## Notes

- Hallazgo de seguridad crítico (PEND-PED-01): el backend NO valida propiedad; un cliente puede
  consultar pedidos de otro pasando su username en la ruta (IDOR). Verificado en el código y
  documentado en RN-PED-001, CA-PED-007 y el Escenario 4.
- El detalle de orden (CU-14) se entrega embebido en el listado; no hay endpoint de detalle
  separado. Reflejado en RF-PED-005.
- KPI frecuencia de compra (PEND-PED-05): derivable de `ordenes` pero sin instrumentación.
- Rendimiento: patrón N+1 (PEND-PED-03) y sin paginación (PEND-PED-04).
- Próxima fase sugerida: `/speckit-plan` (no ejecutar todavía por indicación del usuario).
