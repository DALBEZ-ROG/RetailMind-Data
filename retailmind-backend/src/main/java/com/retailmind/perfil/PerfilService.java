package com.retailmind.perfil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.retailmind.auth.ClickHouseUserRepository;
import com.retailmind.auth.UsuarioSistema;

@Service
public class PerfilService {

    private final JdbcTemplate ch;
    private final ClickHouseUserRepository usuarioRepo;
    private final PasswordEncoder passwordEncoder;

    public PerfilService(@Qualifier("clickHouseJdbc") JdbcTemplate ch,
                         ClickHouseUserRepository usuarioRepo,
                         PasswordEncoder passwordEncoder) {
        this.ch = ch;
        this.usuarioRepo = usuarioRepo;
        this.passwordEncoder = passwordEncoder;
    }

    public Map<String, Object> getPerfil(String username) {
        UsuarioSistema usuario = usuarioRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + username));

        Map<String, Object> perfil = new LinkedHashMap<>();
        perfil.put("username", usuario.getUsername());
        perfil.put("email", usuario.getNombre()); // nombre almacena el email
        perfil.put("rol", usuario.getRol().name());
        perfil.put("activo", usuario.getActivo());
        perfil.put("fechaCreacion", usuario.getFechaCreacion());

        // Total compras
        Long totalCompras = queryLong(
                "SELECT count() FROM retailmind.ordenes WHERE user_id = '" + username + "'");
        perfil.put("totalCompras", totalCompras);

        // Total gastado
        Double totalGastado = queryDouble(
                "SELECT sum(total) FROM retailmind.ordenes WHERE user_id = '" + username + "'");
        perfil.put("totalGastado", totalGastado != null ? totalGastado : 0.0);

        // Productos en wishlist
        Long productosWishlist = queryLong(
                "SELECT count() FROM retailmind.wishlist_items WHERE user_id = '" + username + "'");
        perfil.put("productosWishlist", productosWishlist);

        // Total eventos
        Long totalEventos = queryLong(
                "SELECT count() FROM retailmind.fact_eventos WHERE user_id = '" + username + "'");
        perfil.put("totalEventos", totalEventos);

        // Categoría favorita
        String categoriaFavorita = getCategoriaFavorita(username);
        perfil.put("categoriaFavorita", categoriaFavorita != null ? categoriaFavorita : "Sin datos");

        // Canal preferido
        String canalPreferido = getCanalPreferido(username);
        perfil.put("canalPreferido", canalPreferido != null ? canalPreferido : "Sin datos");

        return perfil;
    }

    private String getCategoriaFavorita(String username) {
        try {
            List<String> result = ch.query(
                    "SELECT c.categoria_nombre " +
                    "FROM retailmind.fact_eventos fe " +
                    "JOIN retailmind.dim_producto p ON fe.product_id = p.producto_id " +
                    "JOIN retailmind.dim_categoria c ON p.categoria_id = c.categoria_id " +
                    "WHERE fe.user_id = '" + username + "' " +
                    "GROUP BY p.categoria_id, c.categoria_nombre " +
                    "ORDER BY count() DESC LIMIT 1",
                    (rs, rn) -> rs.getString("categoria_nombre"));
            return result.isEmpty() ? null : result.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String getCanalPreferido(String username) {
        try {
            List<String> result = ch.query(
                    "SELECT channel FROM retailmind.fact_eventos " +
                    "WHERE user_id = '" + username + "' " +
                    "GROUP BY channel ORDER BY count() DESC LIMIT 1",
                    (rs, rn) -> rs.getString("channel"));
            return result.isEmpty() ? null : result.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public void actualizarEmail(String username, String email) {
        if (!usuarioRepo.existsByUsername(username)) {
            throw new IllegalArgumentException("Usuario no encontrado: " + username);
        }
        ch.execute("ALTER TABLE retailmind.usuarios_sistema UPDATE nombre = '" + email +
                "' WHERE username = '" + username + "' SETTINGS mutations_sync = 1");
    }

    public void cambiarPassword(String username, String passwordActual, String passwordNuevo) {
        UsuarioSistema usuario = usuarioRepo.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado: " + username));

        if (!passwordEncoder.matches(passwordActual, usuario.getPassword())) {
            throw new IllegalStateException("La contraseña actual es incorrecta");
        }

        String nuevoHash = passwordEncoder.encode(passwordNuevo);
        ch.execute("ALTER TABLE retailmind.usuarios_sistema UPDATE password = '" + nuevoHash +
                "' WHERE username = '" + username + "' SETTINGS mutations_sync = 1");
    }

    private Long queryLong(String sql) {
        try {
            return ch.queryForObject(sql, Long.class);
        } catch (Exception e) {
            return 0L;
        }
    }

    private Double queryDouble(String sql) {
        try {
            return ch.queryForObject(sql, Double.class);
        } catch (Exception e) {
            return 0.0;
        }
    }
}
