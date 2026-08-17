package com.retailmind.admin.catalogo;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gestión de catálogo (ADMIN). Bajo /api/admin/** ya exige authority ADMIN
 * en SecurityConfig; la BD además aplica SET LOCAL ROLE grp_administrador.
 */
@RestController
@RequestMapping("/api/admin/catalogo")
public class CatalogoAdminController {

    public record CategoriaReq(String nombre, String slug, String descripcion, Long padreId) {}
    public record MarcaReq(String nombre, String slug, String descripcion) {}
    public record ProductoReq(String nombre, String slug, Long marcaId, String descripcionCorta,
                              String descripcion, Boolean publicado, List<Long> categoriaIds) {}
    /**
     * `pesoKg` es OBLIGATORIO al crear y OPCIONAL al editar (omitirlo conserva el
     * que hubiera). La regla y su porqué viven en `CatalogoAdminService`: sin peso,
     * `pesoTotalPedido` deja en NULL el peso del pedido ENTERO y el flete se cobra
     * sin el cargo por kilo.
     */
    public record VarianteReq(String sku, BigDecimal precio, BigDecimal costo,
                              String codigoBarras, Boolean esPredeterminada,
                              BigDecimal pesoKg) {}
    public record ActivoReq(boolean activo) {}
    public record AtributoReq(String codigo, String nombre, String tipo) {}
    public record ValorAtributoReq(String valor) {}
    public record AsociacionReq(long valorAtributoId) {}

    private final CatalogoAdminService servicio;

    public CatalogoAdminController(CatalogoAdminService servicio) {
        this.servicio = servicio;
    }

    // ── Categorías ───────────────────────────────────────────────────────
    @GetMapping("/categorias")
    public List<Map<String, Object>> categorias() { return servicio.listarCategorias(); }

    @PostMapping("/categorias")
    public ResponseEntity<?> crearCategoria(@RequestBody CategoriaReq r) {
        long id = servicio.crearCategoria(r.nombre(), r.slug(), r.descripcion(), r.padreId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/categorias/{id}")
    public ResponseEntity<?> editarCategoria(@PathVariable long id, @RequestBody CategoriaReq r) {
        servicio.editarCategoria(id, r.nombre(), r.slug(), r.descripcion());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/categorias/{id}/activo")
    public ResponseEntity<?> activarCategoria(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarCategoria(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Marcas ───────────────────────────────────────────────────────────
    @GetMapping("/marcas")
    public List<Map<String, Object>> marcas() { return servicio.listarMarcas(); }

    @PostMapping("/marcas")
    public ResponseEntity<?> crearMarca(@RequestBody MarcaReq r) {
        long id = servicio.crearMarca(r.nombre(), r.slug(), r.descripcion());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/marcas/{id}")
    public ResponseEntity<?> editarMarca(@PathVariable long id, @RequestBody MarcaReq r) {
        servicio.editarMarca(id, r.nombre(), r.slug(), r.descripcion());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/marcas/{id}/activo")
    public ResponseEntity<?> activarMarca(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarMarca(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Productos ────────────────────────────────────────────────────────
    @GetMapping("/productos")
    public List<Map<String, Object>> productos() { return servicio.listarProductos(); }

    /** Búsqueda paginada: q busca en nombre/slug/marca/SKU de variante. */
    @GetMapping("/productos/buscar")
    public Map<String, Object> buscarProductos(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long marcaId,
            @RequestParam(required = false) Long categoriaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return servicio.buscarProductos(q, marcaId, categoriaId, page, size);
    }

    @GetMapping("/productos/{id}")
    public Map<String, Object> producto(@PathVariable long id) { return servicio.obtenerProducto(id); }

    @PostMapping("/productos")
    public ResponseEntity<?> crearProducto(@RequestBody ProductoReq r) {
        long id = servicio.crearProducto(r.nombre(), r.slug(), r.marcaId(), r.descripcionCorta(),
                r.descripcion(), Boolean.TRUE.equals(r.publicado()), r.categoriaIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/productos/{id}")
    public ResponseEntity<?> editarProducto(@PathVariable long id, @RequestBody ProductoReq r) {
        servicio.editarProducto(id, r.nombre(), r.slug(), r.marcaId(), r.descripcionCorta(),
                r.descripcion(), r.publicado());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/productos/{id}/activo")
    public ResponseEntity<?> activarProducto(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarProducto(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Variantes ────────────────────────────────────────────────────────
    @PostMapping("/productos/{productoId}/variantes")
    public ResponseEntity<?> crearVariante(@PathVariable long productoId, @RequestBody VarianteReq r) {
        long id = servicio.crearVariante(productoId, r.sku(), r.precio(), r.costo(),
                r.codigoBarras(), Boolean.TRUE.equals(r.esPredeterminada()), r.pesoKg());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PutMapping("/variantes/{id}")
    public ResponseEntity<?> editarVariante(@PathVariable long id, @RequestBody VarianteReq r) {
        servicio.editarVariante(id, r.sku(), r.precio(), r.costo(), r.pesoKg());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PatchMapping("/variantes/{id}/activo")
    public ResponseEntity<?> activarVariante(@PathVariable long id, @RequestBody ActivoReq r) {
        servicio.activarVariante(id, r.activo());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @PostMapping("/variantes/{id}/atributos")
    public ResponseEntity<?> asociarAtributo(@PathVariable long id, @RequestBody AsociacionReq r) {
        servicio.asociarAtributo(id, r.valorAtributoId());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Atributos ────────────────────────────────────────────────────────
    @GetMapping("/atributos")
    public List<Map<String, Object>> atributos() { return servicio.listarAtributos(); }

    @PostMapping("/atributos")
    public ResponseEntity<?> crearAtributo(@RequestBody AtributoReq r) {
        long id = servicio.crearAtributo(r.codigo(), r.nombre(), r.tipo());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @PostMapping("/atributos/{id}/valores")
    public ResponseEntity<?> crearValor(@PathVariable long id, @RequestBody ValorAtributoReq r) {
        long valorId = servicio.crearValorAtributo(id, r.valor());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", valorId));
    }
}
