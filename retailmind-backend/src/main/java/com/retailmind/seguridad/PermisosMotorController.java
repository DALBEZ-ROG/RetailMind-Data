package com.retailmind.seguridad;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mapa de la seguridad del motor y administración de privilegios.
 * Pantalla {@code /operativo/seguridad/permisos}.
 *
 * <b>Los seis endpoints son SOLO ADMIN</b> y están enumerados UNO A UNO en
 * {@code SecurityConfig} — nunca por comodín. No es una formalidad: aquí se
 * lee el mapa completo de privilegios del sistema y se ejecutan GRANT/REVOKE
 * reales, así que un comodín que un día cubriera un endpoint nuevo sería una
 * puerta abierta sin que nadie lo decidiera.
 */
@RestController
@RequestMapping("/api/seguridad")
public class PermisosMotorController {

    private final MapaSeguridadService mapa;
    private final PermisosMotorService permisos;
    private final RolPersonalizadoService roles;

    public PermisosMotorController(MapaSeguridadService mapa, PermisosMotorService permisos,
                                   RolPersonalizadoService roles) {
        this.mapa = mapa;
        this.permisos = permisos;
        this.roles = roles;
    }

    /** Roles creados desde esta pantalla (script 87). */
    @GetMapping("/roles-personalizados")
    public List<Map<String, Object>> rolesPersonalizados() {
        return roles.listar();
    }

    /** Crea un rol propio con sus seis piezas. */
    @PostMapping("/roles-personalizados")
    public Map<String, Object> crearRol(@RequestBody Map<String, String> body) {
        return roles.crear(body.get("codigo"), body.get("nombre"), body.get("rolBase"));
    }

    /** Elimina un rol propio. Nunca uno del sistema. */
    @DeleteMapping("/roles-personalizados/{codigo}")
    public Map<String, Object> eliminarRol(@PathVariable String codigo) {
        return roles.eliminar(codigo);
    }

    /** Bloques 1, 2 y 5: roles, usuarios por rol, ventana horaria y protecciones. */
    @GetMapping("/mapa")
    public Map<String, Object> mapa() {
        return mapa.mapa();
    }

    /** Bloque 3: privilegios de TABLA y de COLUMNA, en listas separadas. */
    @GetMapping("/permisos")
    public Map<String, Object> permisos(
            @RequestParam(required = false) String rol,
            @RequestParam(required = false) String tabla,
            @RequestParam(required = false) String privilegio,
            @RequestParam(required = false) String tipo) {
        return mapa.permisos(rol, tabla, privilegio, tipo);
    }

    /** Bloque 4: políticas RLS con su condición traducida. */
    @GetMapping("/politicas")
    public List<Map<String, Object>> politicas(
            @RequestParam(required = false) String tabla,
            @RequestParam(required = false) String rol) {
        return mapa.politicas(tabla, rol);
    }

    /** Catálogo de tablas administrables y sus columnas, para el formulario. */
    @GetMapping("/objetos")
    public List<Map<String, Object>> objetos() {
        return mapa.objetos();
    }

    /** GRANT. El cuerpo lleva rol, tabla, privilegio y —opcional— columna. */
    @PostMapping("/permisos/conceder")
    public Map<String, Object> conceder(@RequestBody Map<String, String> body) {
        return permisos.cambiar("conceder", body.get("rol"), body.get("tabla"),
                body.get("columna"), body.get("privilegio"));
    }

    /** REVOKE. Mismo cuerpo. Las protecciones se aplican antes de tocar el motor. */
    @PostMapping("/permisos/revocar")
    public Map<String, Object> revocar(@RequestBody Map<String, String> body) {
        return permisos.cambiar("revocar", body.get("rol"), body.get("tabla"),
                body.get("columna"), body.get("privilegio"));
    }
}
