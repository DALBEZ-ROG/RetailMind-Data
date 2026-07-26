package com.retailmind.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Rutas publicas
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Tienda del cliente (PostgreSQL): catalogo para CLIENTE
                // (+ADMIN demo); carrito/wishlist/direcciones solo CLIENTE
                // (RLS por app.cliente_id los aisla a sus filas)
                .requestMatchers(HttpMethod.POST, "/api/catalogo/eventos").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/catalogo/**")
                    .hasAnyAuthority("ADMIN", "CLIENTE")
                .requestMatchers("/api/carrito/**").hasAuthority("CLIENTE")
                .requestMatchers("/api/wishlist/**").hasAuthority("CLIENTE")
                .requestMatchers("/api/perfil/direcciones/**", "/api/perfil/ciudades")
                    .hasAuthority("CLIENTE")
                .requestMatchers(HttpMethod.PUT, "/api/perfil").hasAuthority("CLIENTE")
                .requestMatchers("/api/recomendaciones/**").hasAuthority("CLIENTE")
                // Gestion de usuarios solo ADMIN
                .requestMatchers(HttpMethod.POST, "/api/auth/register").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/auth/usuarios").hasAuthority("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/auth/usuarios/**").hasAuthority("ADMIN")
                // ETL solo para ADMIN
                .requestMatchers("/api/etl/**").hasAuthority("ADMIN")
                // Inicializacion solo para ADMIN
                .requestMatchers("/api/init/**").hasAuthority("ADMIN")
                // Gestion de datos solo para ADMIN
                .requestMatchers("/api/gestion/**").hasAuthority("ADMIN")
                // Aprobar orden de compra (CU-O-12): solo gerencia
                .requestMatchers(HttpMethod.POST, "/api/compras/ordenes/*/aprobar")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Devolución a proveedor (script 45): BODEGA identifica el
                // defectuoso tras la recepción; COMPRAS gestiona el ciclo
                // (espeja INSERT item_defectuoso = bodega/compras y
                // INSERT/UPDATE devolucion_proveedor = compras)
                .requestMatchers(HttpMethod.POST, "/api/compras/recepciones/detalles/*/defectuoso")
                    .hasAnyAuthority("ADMIN", "BODEGA")
                .requestMatchers(HttpMethod.PATCH, "/api/compras/items-defectuosos/*/proveedor")
                    .hasAnyAuthority("ADMIN", "COMPRAS")
                .requestMatchers(HttpMethod.POST, "/api/compras/devoluciones-proveedor",
                        "/api/compras/devoluciones-proveedor/*/enviar",
                        "/api/compras/devoluciones-proveedor/*/resolver",
                        "/api/compras/devoluciones-proveedor/*/cerrar")
                    .hasAnyAuthority("ADMIN", "COMPRAS")
                // Catálogo proveedor-producto (OTD-COM-10, script 51): contiene
                // COSTO, así que BODEGA queda fuera (segregación financiera).
                // Lectura ADMIN/GERENTE/COMPRAS; escritura ADMIN/COMPRAS
                // (espeja los GRANTs de producto_proveedor).
                .requestMatchers(HttpMethod.GET, "/api/compras/proveedores",
                        "/api/compras/proveedores/*/productos", "/api/compras/productos-ref")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS")
                .requestMatchers("/api/compras/proveedores/*/productos",
                        "/api/compras/productos-proveedor/**")
                    .hasAnyAuthority("ADMIN", "COMPRAS")
                // Ciclo de compra: roles operativos (la BD afina por SET LOCAL ROLE)
                .requestMatchers("/api/compras/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS", "BODEGA")
                // Notas de pedido: solo personal del ciclo de venta las CREA
                // (el cliente las lee vía GET, filtradas por RLS a las visibles)
                .requestMatchers(HttpMethod.POST, "/api/ventas/pedidos/*/notas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "DESPACHO")
                // Cobro del pedido (espeja la BD: INSERT en pago = admin/vendedor)
                .requestMatchers(HttpMethod.POST, "/api/ventas/pedidos/*/pagos")
                    .hasAnyAuthority("ADMIN", "VENDEDOR")
                // Factura MANUAL (pedidos internos; la online se emite sola al
                // pagar el checkout). Espeja INSERT factura_venta = admin/vendedor
                .requestMatchers(HttpMethod.POST, "/api/ventas/pedidos/*/factura")
                    .hasAnyAuthority("ADMIN", "VENDEDOR")
                // Preparación de pedidos (picking/empaque): BODEGA (script 39)
                .requestMatchers("/api/ventas/preparacion/**", "/api/ventas/preparacion",
                        "/api/ventas/pedidos/*/preparacion", "/api/ventas/pedidos/*/preparado")
                    .hasAnyAuthority("ADMIN", "BODEGA")
                // Despacho: solo pedidos PREPARADOS (espeja INSERT envio = admin/despacho)
                .requestMatchers(HttpMethod.POST, "/api/ventas/pedidos/*/despacho")
                    .hasAnyAuthority("ADMIN", "DESPACHO")
                .requestMatchers(HttpMethod.GET, "/api/ventas/despacho/*")
                    .hasAnyAuthority("ADMIN", "DESPACHO")
                // Entrega: cierra la logística (espeja UPDATE envio = admin/despacho)
                .requestMatchers(HttpMethod.POST, "/api/ventas/pedidos/*/entrega")
                    .hasAnyAuthority("ADMIN", "DESPACHO")
                // Novedades de envío (script 44): registrar/resolver = despacho
                // (espeja INSERT/UPDATE novedad_envio); la consulta GET cae en
                // /api/ventas/** e incluye al CLIENTE (RLS pol_cliente_propio)
                .requestMatchers(HttpMethod.POST, "/api/ventas/envios/*/novedades",
                        "/api/ventas/novedades/*/reprogramar",
                        "/api/ventas/novedades/*/devolver-almacen")
                    .hasAnyAuthority("ADMIN", "DESPACHO")
                // Listado de facturas de venta: solo personal (el CLIENTE llega a
                // SU factura por el detalle del pedido, aislado por RLS)
                .requestMatchers(HttpMethod.GET, "/api/ventas/facturas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR")
                // Ciclo de venta: vendedor/despacho + cliente (RLS lo aisla a sus filas)
                .requestMatchers("/api/ventas/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "DESPACHO", "CLIENTE")
                // RMA / logística inversa: un rol por transición (script 38).
                // La solicitud NACE del cliente; el resto es el pipeline interno.
                .requestMatchers(HttpMethod.POST, "/api/devoluciones").hasAuthority("CLIENTE")
                .requestMatchers(HttpMethod.GET, "/api/devoluciones/pedido/*/elegibilidad")
                    .hasAnyAuthority("ADMIN", "CLIENTE")
                .requestMatchers(HttpMethod.GET, "/api/devoluciones/transportistas-ref")
                    .hasAnyAuthority("ADMIN", "SOPORTE")
                .requestMatchers("/api/devoluciones/*/revision", "/api/devoluciones/*/aprobar",
                        "/api/devoluciones/*/rechazar", "/api/devoluciones/*/cerrar")
                    .hasAnyAuthority("ADMIN", "SOPORTE")
                .requestMatchers("/api/devoluciones/*/transito")
                    .hasAnyAuthority("ADMIN", "DESPACHO")
                .requestMatchers("/api/devoluciones/*/recepcion")
                    .hasAnyAuthority("ADMIN", "DESPACHO", "BODEGA")
                .requestMatchers("/api/devoluciones/*/inspeccion")
                    .hasAnyAuthority("ADMIN", "BODEGA")
                .requestMatchers("/api/devoluciones/*/reembolso")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Listado/detalle/guía: todos los roles del pipeline + CLIENTE
                // (RLS pol_cliente_propio lo aisla a sus devoluciones)
                .requestMatchers("/api/devoluciones/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "DESPACHO",
                            "BODEGA", "SOPORTE", "CLIENTE")
                // Ajuste de inventario (CU-O-16): solo bodega/admin
                .requestMatchers("/api/inventario/ajustes/**")
                    .hasAnyAuthority("ADMIN", "BODEGA")
                // Niveles mín/máx (OTD-INV-08): solo bodega/admin (espeja el
                // UPDATE de inventario en BD; gerente solo tiene SELECT)
                .requestMatchers("/api/inventario/niveles")
                    .hasAnyAuthority("ADMIN", "BODEGA")
                // Kardex (CU-O-17): lectura ampliada a gerencia y analista
                .requestMatchers(HttpMethod.GET, "/api/inventario/kardex")
                    .hasAnyAuthority("ADMIN", "GERENTE", "BODEGA", "ANALISTA")
                // Transferencias de inventario: bodega
                .requestMatchers("/api/inventario/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "BODEGA")
                // Metas de venta (OTD-VEN-15, script 48): fijar/editar es
                // atribución de gerencia; vendedor/analista solo leen el
                // avance (espeja los GRANTs de meta_venta)
                .requestMatchers(HttpMethod.GET, "/api/gerencia/metas", "/api/gerencia/metas/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "ANALISTA")
                .requestMatchers("/api/gerencia/metas", "/api/gerencia/metas/**")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Marketing: lectura ADMIN/GERENTE; escritura solo ADMIN
                // (espeja la BD: grp_gerente solo SELECT en cupon/promocion/campana/banner)
                .requestMatchers(HttpMethod.GET, "/api/marketing/**")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                .requestMatchers("/api/marketing/**").hasAuthority("ADMIN")
                // Soporte: referencia (categorías activas / FAQ activas) espeja el SELECT
                // de la BD (grp_admin/gerente/analista/cliente)
                .requestMatchers(HttpMethod.GET,
                        "/api/soporte/categorias-ref", "/api/soporte/faqs-activas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA", "CLIENTE", "SOPORTE")
                // Selector "pedido relacionado" del ticket: personal + cliente
                // (CLIENTE queda aislado a sus pedidos por RLS)
                .requestMatchers(HttpMethod.GET, "/api/soporte/pedidos-ref")
                    .hasAnyAuthority("ADMIN", "GERENTE", "CLIENTE", "SOPORTE")
                // Buscador "producto relacionado" del ticket (script 50):
                // mismos roles; grp_soporte busca con grants de columna sin dinero
                .requestMatchers(HttpMethod.GET, "/api/soporte/productos-ref")
                    .hasAnyAuthority("ADMIN", "GERENTE", "CLIENTE", "SOPORTE")
                // Tomar (auto-asignarse) y cambiar prioridad: agente de soporte
                // y ADMIN (la prioridad NACE automática según la categoría)
                .requestMatchers("/api/soporte/tickets/*/tomar",
                        "/api/soporte/tickets/*/prioridad")
                    .hasAnyAuthority("ADMIN", "SOPORTE")
                // Estado y asignación de tickets: gestión + agentes de soporte
                .requestMatchers(HttpMethod.PATCH,
                        "/api/soporte/tickets/*/estado", "/api/soporte/tickets/*/asignar")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE")
                // Tickets: personal ve todos; CLIENTE solo los suyos (RLS pol_cliente_propio
                // script 29; grp_soporte con pol_soporte, script 37)
                .requestMatchers("/api/soporte/tickets", "/api/soporte/tickets/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "CLIENTE", "SOPORTE")
                // Resto de soporte: lectura ADMIN/GERENTE/SOPORTE; gestión de
                // categorías/FAQ solo ADMIN
                .requestMatchers(HttpMethod.GET, "/api/soporte/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE")
                .requestMatchers("/api/soporte/**").hasAuthority("ADMIN")
                // Reseñas y preguntas de producto: el CLIENTE crea/vota/reporta;
                // el listado público por producto y las referencias también son
                // suyos; la moderación y las bandejas quedan en ADMIN/GERENTE
                .requestMatchers(HttpMethod.GET,
                        "/api/resenas/productos-ref", "/api/resenas/producto/*",
                        "/api/resenas/preguntas/producto/*")
                    .hasAnyAuthority("ADMIN", "GERENTE", "CLIENTE")
                .requestMatchers(HttpMethod.GET, "/api/resenas/mias",
                        "/api/resenas/productos-comprados").hasAuthority("CLIENTE")
                .requestMatchers(HttpMethod.POST,
                        "/api/resenas/*/voto", "/api/resenas/*/reporte",
                        "/api/resenas/preguntas", "/api/resenas")
                    .hasAuthority("CLIENTE")
                .requestMatchers(HttpMethod.POST, "/api/resenas/preguntas/*/respuestas")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                .requestMatchers("/api/resenas/**").hasAnyAuthority("ADMIN", "GERENTE")
                // Admin solo ADMIN
                .requestMatchers("/api/admin/**").hasAuthority("ADMIN")
                // Funnel solo ADMIN
                .requestMatchers("/api/funnel/**").hasAuthority("ADMIN")
                // Analytics avanzado solo ADMIN
                .requestMatchers("/api/analytics/region/**").hasAuthority("ADMIN")
                .requestMatchers("/api/analytics/dispositivo/**").hasAuthority("ADMIN")
                .requestMatchers("/api/analytics/trafico/**").hasAuthority("ADMIN")
                // Reportes solo ADMIN
                .requestMatchers("/api/reportes/**").hasAuthority("ADMIN")
                // Dashboard refrescar vistas solo ADMIN
                .requestMatchers(HttpMethod.POST, "/api/dashboard/refrescar-vistas").hasAuthority("ADMIN")
                // Intentos de acceso al sistema (OTD-GER-09, script 53): informe
                // de seguridad — solo Administración y Gerencia (espeja los GRANTs
                // de log_acceso). El REGISTRO lo escribe el flujo de login (público).
                .requestMatchers(HttpMethod.GET, "/api/seguridad/accesos")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // ── INFORMES TÁCTICOS (docs/tactico/CATALOGO_OBJETIVOS_TACTICOS.md) ──
                // Convención: /api/informes/{departamento}/{informe}, todos GET de
                // solo lectura. Se declaran del más específico al más general.
                //
                // VENTAS — OTD-VEN-10 (cola de moderación) es atribución de los
                // moderadores del sistema: ADMIN/GERENTE (espeja el SELECT de
                // resena/pregunta_producto, que el VENDEDOR no tiene en el motor).
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/moderacion")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Resto de informes de Ventas (VEN-01/02/08/15): llevan MONTO, por
                // lo que BODEGA y DESPACHO quedan fuera por segregación financiera
                // (el motor lo respalda: sin SELECT sobre pedido.total, carrito ni
                // meta_venta). El VENDEDOR entra, y VEN-02 lo acota a lo suyo.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR")
                // INVENTARIO / BODEGA — aquí BODEGA sí es la destinataria natural,
                // porque seis de los siete informes son de CANTIDADES. La excepción
                // es OTD-INV-07 (valor del inventario), que es DINERO: se cierra a
                // ADMIN/GERENTE/ANALISTA. Ese corte lo hace ESTA ruta y no el motor,
                // porque grp_bodega conserva SELECT sobre producto_variante.costo
                // por la excepción declarada del script 41 (valoriza su kardex).
                .requestMatchers(HttpMethod.GET, "/api/informes/inventario/valor-inventario")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // OTD-INV-02: existencias — COMPRAS y VENDEDOR también necesitan
                // saber qué hay antes de comprar o de vender.
                .requestMatchers(HttpMethod.GET, "/api/informes/inventario/stock-bodega")
                    .hasAnyAuthority("ADMIN", "GERENTE", "BODEGA", "COMPRAS", "VENDEDOR")
                // OTD-INV-01 y OTD-INV-08: las dos listas que disparan y frenan la
                // reposición, así que COMPRAS entra.
                .requestMatchers(HttpMethod.GET, "/api/informes/inventario/bajo-minimo",
                        "/api/informes/inventario/sobre-stock")
                    .hasAnyAuthority("ADMIN", "GERENTE", "BODEGA", "COMPRAS")
                // OTD-INV-03/05/06 (kardex, ajustes, transferencias): operación
                // interna del almacén.
                .requestMatchers(HttpMethod.GET, "/api/informes/inventario/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "BODEGA")
                // Los informes son SOLO consulta: cualquier método que no sea GET
                // sobre /api/informes se rechaza de plano.
                .requestMatchers("/api/informes/**").denyAll()
                // Perfil (ficha básica) — usuario autenticado (cualquier rol)
                .requestMatchers("/api/perfil/**").authenticated()
                // Todo lo demas requiere autenticacion
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:4200"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
