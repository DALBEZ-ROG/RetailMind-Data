package com.retailmind.informes;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * INFORMES TÁCTICOS — GERENCIA / DIRECCIÓN.
 *
 * Misma convención que el resto del nivel táctico:
 * {@code /api/informes/{departamento}/{informe}}. Todos son GET de solo lectura
 * y todos devuelven el MISMO sobre {@code {items, total, page, size, resumen[]}},
 * que la pantalla genérica del frontend sabe pintar sin conocer el informe.
 *
 * Consulta POR PANTALLA con filtros: estos endpoints NO generan PDF.
 *
 * Autorización en SecurityConfig: los CINCO informes son de dirección, así que
 * el bloque entero es ADMIN + GERENTE. Dentro de ese bloque, {@code /auditoria}
 * (OTD-GER-08) y {@code /accesos} (OTD-GER-09) son además datos SENSIBLES DE
 * SEGURIDAD y se declaran con su propia línea explícita: aunque hoy coincida
 * con la del departamento, ampliar Gerencia a un rol más no debe arrastrarlos
 * por descuido. Ver el javadoc de {@link InformesGerenciaService} para qué capa
 * sostiene cada corte (ruta o motor).
 */
@RestController
@RequestMapping("/api/informes/gerencia")
public class InformesGerenciaController {

    private final InformesGerenciaService servicio;

    public InformesGerenciaController(InformesGerenciaService servicio) {
        this.servicio = servicio;
    }

    /**
     * OTD-GER-01 — Foto del día: pedidos, cobros y pendientes que necesitan
     * decisión. Sin paginar (son agregados). Sin {@code fecha} = hoy.
     * GET /api/informes/gerencia/foto-dia?fecha=AAAA-MM-DD
     */
    @GetMapping("/foto-dia")
    public Map<String, Object> fotoDia(@RequestParam(required = false) String fecha) {
        return servicio.fotoDia(fecha);
    }

    /**
     * OTD-GER-04 — Cupones, usos restantes y vencimiento.
     * GET /api/informes/gerencia/cupones?situacion=&tipo=&buscar=&page=&size=
     */
    @GetMapping("/cupones")
    public Map<String, Object> cupones(
            @RequestParam(required = false) String situacion,
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.cupones(situacion, tipo, buscar, page, size);
    }

    /**
     * OTD-GER-06 — Promociones, campañas y banners con su vigencia.
     * GET /api/informes/gerencia/marketing?tipo=&vigencia=&buscar=&page=&size=
     */
    @GetMapping("/marketing")
    public Map<String, Object> marketing(
            @RequestParam(required = false) String tipo,
            @RequestParam(required = false) String vigencia,
            @RequestParam(required = false) String buscar,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.marketing(tipo, vigencia, buscar, page, size);
    }

    /**
     * OTD-GER-08 — Auditoría del sistema: quién hizo qué, con el antes/después.
     * DATO SENSIBLE DE SEGURIDAD: SecurityConfig lo cierra a ADMIN y GERENTE.
     * GET /api/informes/gerencia/auditoria?usuario=&tabla=&accion=&desde=&hasta=&page=&size=
     */
    @GetMapping("/auditoria")
    public Map<String, Object> auditoria(
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) String tabla,
            @RequestParam(required = false) String accion,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.auditoria(usuario, tabla, accion, desde, hasta, page, size);
    }

    /**
     * OTD-GER-09 — Intentos de acceso al sistema, con motivo, IP y correo.
     * DATO SENSIBLE DE SEGURIDAD: SecurityConfig lo cierra a ADMIN y GERENTE, y
     * el motor lo respalda (solo esos dos grupos leen log_acceso).
     * GET /api/informes/gerencia/accesos?resultado=&correo=&desde=&hasta=&page=&size=
     */
    @GetMapping("/accesos")
    public Map<String, Object> accesos(
            @RequestParam(required = false) String resultado,
            @RequestParam(required = false) String correo,
            @RequestParam(required = false) String desde,
            @RequestParam(required = false) String hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.accesos(resultado, correo, desde, hasta, page, size);
    }
}
