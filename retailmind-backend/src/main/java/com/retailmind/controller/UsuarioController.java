package com.retailmind.controller;

import com.retailmind.entity.Usuario;
import com.retailmind.service.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    /** GET /api/usuarios?page=0&size=20 */
    @GetMapping
    public ResponseEntity<Page<Usuario>> findAll(
            @PageableDefault(size = 20, sort = "userId") Pageable pageable) {
        return ResponseEntity.ok(usuarioService.findAll(pageable));
    }

    /** GET /api/usuarios/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<Usuario> findById(@PathVariable String id) {
        return usuarioService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
