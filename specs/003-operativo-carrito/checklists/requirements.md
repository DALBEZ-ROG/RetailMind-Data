# Specification Quality Checklist: Operativo - Carrito de compras y checkout

**Purpose**: Validar la completitud y calidad de la especificación antes de planificar.
**Created**: 2026-06-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Enfocada en el valor del módulo (materializar la venta: carrito → orden)
- [x] Todas las secciones solicitadas están completas
- [x] Documenta el comportamiento implementado verificado en el código
- [N/A] "Sin detalles de implementación": por instrucción explícita, la spec sí incluye detalles
  técnicos (endpoints, tablas, eventos) para reflejar lo implementado

## Requirement Completeness

- [x] No quedan marcadores [NEEDS CLARIFICATION]
- [x] Los requisitos son verificables y no ambiguos
- [x] Los RNF están cuantificados (cantidad def 1, precio snapshot, mutations_sync, estado fijo)
- [x] Los criterios de aceptación son medibles
- [x] Escenarios Gherkin: agregar, modificar cantidad (comportamiento real), eliminar, checkout
  exitoso con orden+purchase, checkout con carrito vacío
- [x] Se identifican casos límite (carrito vacío, producto inexistente)
- [x] Alcance acotado (historial de pedidos y wishlist fuera de alcance)
- [x] Dependencias, restricciones y supuestos documentados
- [x] Requisito de autenticación explícito

## Feature Readiness

- [x] Cada RF tiene criterios de aceptación y/o escenarios asociados
- [x] Los escenarios cubren los flujos primarios (agregar, gestionar, finalizar)
- [x] Existe trazabilidad RF → OO/CU/OT/OE
- [x] Los elementos no implementados están marcados como PENDIENTE, no inventados

## Notes

- KPI tiempo de checkout (PEND-CAR-06): derivable de `fact_eventos` (add_to_cart vs purchase) pero
  sin instrumentación/cálculo actual.
- "Modificar cantidad" (PEND-CAR-01) NO existe: cada agregar inserta una fila; se documentó el
  comportamiento real en el Escenario 2.
- Hallazgos de seguridad/consistencia: SQL por concatenación (PEND-CAR-03) y checkout no atómico
  (PEND-CAR-02); abordar en feature de limpieza/seguridad posterior.
- Próxima fase sugerida: `/speckit-plan` (no ejecutar todavía por indicación del usuario).
