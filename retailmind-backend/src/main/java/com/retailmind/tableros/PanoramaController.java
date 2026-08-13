package com.retailmind.tableros;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PANORAMA DEL NEGOCIO — {@code GET /api/panorama}.
 *
 * <h2>Por qué NO cuelga de {@code /api/tableros}</h2>
 * No es un octavo tablero. Un tablero acota un ámbito y se filtra para tomar una
 * decisión; esto es la foto de conjunto de la década entera y no lleva filtros.
 * Colgarlo de {@code /api/tableros/**} habría tenido además un efecto práctico
 * indeseable: esa rama termina en un {@code denyAll()} que obliga a enumerar, y
 * mezclar una pantalla de naturaleza distinta en esa lista invita justo al
 * descuido que el comentario de {@code SecurityConfig} intenta evitar.
 *
 * <h2>Autorización — sin abrir nada nuevo</h2>
 * ADMIN, GERENTE y ANALISTA: <b>exactamente los tres roles que ya leen el
 * almacén</b> en T-1 y T-2. La pantalla lleva dinero (venta, margen, flete), así
 * que BODEGA y DESPACHO quedan fuera por la misma razón y por el mismo
 * mecanismo que los demás: la RUTA, porque ClickHouse no tiene GRANT por
 * columna. No se ha creado ni ampliado ningún permiso.
 *
 * <h2>Sin {@code @Transactional}</h2>
 * No toca PostgreSQL. Anotarlo abriría una transacción inútil y haría que
 * {@code PgSessionRoleAspect} asumiera un rol de grupo para nada.
 */
@RestController
@RequestMapping("/api/panorama")
public class PanoramaController {

    private final PanoramaService panorama;

    public PanoramaController(PanoramaService panorama) {
        this.panorama = panorama;
    }

    /**
     * La foto de conjunto: 6 KPIs, 6 bloques y el estado del almacén, en UNA
     * respuesta. Sin parámetros: la ventana es la década completa.
     */
    @GetMapping
    public Map<String, Object> panorama() {
        return panorama.panorama();
    }
}
