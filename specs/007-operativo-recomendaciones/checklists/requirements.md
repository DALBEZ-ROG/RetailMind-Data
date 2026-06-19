# Specification Quality Checklist: Operativo - Recomendaciones

**Purpose**: Validar la completitud y calidad de la especificación antes de planificar.
**Created**: 2026-06-18
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] Enfocada en el valor del módulo (conversión y engagement vía personalización)
- [x] Todas las secciones solicitadas están completas
- [x] Documenta el comportamiento implementado verificado en el código
- [N/A] "Sin detalles de implementación": por instrucción explícita, la spec sí incluye detalles
  técnicos (endpoints, pesos, umbrales, tablas) para reflejar lo implementado

## Requirement Completeness

- [x] No quedan marcadores [NEEDS CLARIFICATION]
- [x] Los requisitos son verificables y no ambiguos
- [x] Los RNF están cuantificados (pesos 5/3/2/1/0.5, umbral 10, ±30%, límites 12/6/3, <4)
- [x] Los criterios de aceptación son medibles
- [x] Escenarios Gherkin: personalizadas por top categorías, similares ±30%, fallback <10, sin historial
- [x] Se identifican casos límite (sin historial, base inexistente, <4 recomendados)
- [x] Alcance acotado (catálogo, carrito y wishlist como fuentes/relación)
- [x] Dependencias, restricciones y supuestos documentados
- [x] Requisito de autenticación explícito

## Feature Readiness

- [x] Cada RF tiene criterios de aceptación y/o escenarios asociados
- [x] Los escenarios cubren los flujos primarios (personalizado, similares, fallback)
- [x] Trazabilidad RF → CU-O09/OE/OT con la deuda de trazabilidad registrada (EVF04 sin OO/CU)
- [x] Los elementos no implementados están marcados como PENDIENTE, no inventados

## Notes

- Pesos y umbrales CONFIRMADOS contra el código: purchase=5, add_to_cart=3, wishlist=2, click=1,
  0.5 por defecto; umbral fallback <10 eventos; similares ±30% (×0.7–×1.3); límites 12/6/3 y relleno <4.
- Discrepancia documentada (PEND-REC-02): el 0.5 es genérico (cualquier acción no listada), no
  exclusivo de `view`. Valores coinciden con lo esperado, semántica difiere.
- KPI CTR (meta 15%) marcado PENDIENTE (PEND-REC-01): no hay registro de impresiones/clics; no medible.
- Seguridad: IDOR sin validación de propiedad (PEND-REC-04) y SQL por concatenación (PEND-REC-05),
  consistentes con módulos previos.
- Observaciones de modelo de datos (dim_producto vs productos_catalogo, PEND-REC-03) y no
  determinismo por rand() (PEND-REC-07).
- Último módulo operativo. Próxima fase sugerida: `/speckit-plan` (no ejecutar todavía por indicación
  del usuario).
