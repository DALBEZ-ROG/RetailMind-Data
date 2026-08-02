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
                // Actualizacion del data warehouse (orquestador run_etl.py):
                // ADMIN y GERENTE. Es una ACCION, no una consulta, y por eso no
                // cuelga de /api/informes —esa rama termina en denyAll() para
                // todo lo que no sea GET—. Tampoco se mete en /api/etl, que es
                // el ETL legado y solo ADMIN: ampliar aquella linea le abriria
                // al gerente la carga de CSV. El ANALISTA queda fuera a
                // proposito: LEE los informes compuestos, pero decidir cuando
                // se reconstruye el almacen (19 tablas, con margenes, costos y
                // flujo de caja) es de direccion.
                .requestMatchers("/api/dwh/**").hasAnyAuthority("ADMIN", "GERENTE")
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
                // OTD-VEN-16 (participación de la venta por canal) es una lectura de
                // DIRECCIÓN, no de gestión de cartera: sostiene el objetivo
                // estratégico OE-06 y entra el ANALISTA, que en el resto de Ventas no
                // participa. El VENDEDOR queda fuera: la composición del ingreso por
                // canal no es su atribución. Lleva MONTO, así que BODEGA y DESPACHO
                // tampoco entran (el motor lo respalda: sin SELECT sobre pedido.total).
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/participacion-canal")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // OTD-VEN-06 (evolución de la venta mes a mes y por categoría) es
                // el primer informe COMPUESTO: lo sirve ClickHouse
                // (retailmind_dwh.fact_venta_linea), no PostgreSQL. Destinatarios
                // del catálogo: Gerente, Analista y Administrador.
                // Lleva DINERO (venta, costo y margen) y, a diferencia de los 29
                // simples, el motor NO respalda aquí el corte: ClickHouse no tiene
                // RLS por fila ni GRANT por columna, y el ETL escribe todas las
                // columnas de monto en tablas planas (§8.2 del diseño del
                // pipeline). El corte financiero lo hace ÍNTEGRAMENTE ESTA RUTA
                // — mismo caso declarado que OTD-INV-07, OTD-LOG-11 y OTD-GER-08,
                // y por eso va en su propia línea antes del comodín de Ventas.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/evolucion-mensual")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // ── Fase 2 del pipeline: los cinco compuestos de Ventas que
                // sirven fact_pedido y fact_flujo_caja. Mismo criterio que
                // OTD-VEN-06: TODOS llevan dinero y en ninguno lo respalda el
                // motor, así que Bodega y Despacho quedan fuera POR RUTA. Cada
                // uno lleva su línea porque los destinatarios del catálogo NO
                // coinciden entre sí ni con el comodín del departamento.
                //
                // OTD-VEN-05 (cartera de clientes) y OTD-VEN-13 (evolución de
                // la participación por canal) suman VENDEDOR — es su cartera y
                // su mercado— al trío de dirección.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/clientes",
                        "/api/informes/ventas/evolucion-canal")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "ANALISTA")
                // OTD-VEN-07 (ticket promedio) y OTD-VEN-09 (mezcla de formas de
                // cobro) son lecturas de DIRECCIÓN: entra el ANALISTA y no el
                // vendedor, igual que en OTD-VEN-16.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/ticket-promedio",
                        "/api/informes/ventas/formas-cobro")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // OTD-VEN-12 (cobros fallidos) es el corte más estrecho de
                // Ventas: el catálogo lo reserva a Gerente y Administrador. Sale
                // de fact_flujo_caja, que lleva el monto de cada intento.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/cobros-fallidos")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // ── Fase 4 del pipeline: los dos compuestos de posventa que
                // cierran Ventas.
                //
                // OTD-VEN-11 (calificación de cada producto) es el ÚNICO
                // informe compuesto de Ventas SIN dinero: es una escala de 1 a
                // 5. Aun así Bodega y Despacho quedan fuera — la opinión del
                // cliente sobre el catálogo no es su atribución— y entra el
                // VENDEDOR, que sí necesita saber qué se dice de lo que vende.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/resenas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR", "ANALISTA")
                // OTD-VEN-14 (dinero devuelto y su peso sobre la venta) es
                // DINERO: el catálogo lo reserva a Gerente, Administrador y
                // Analista, y deja fuera a Bodega y Despacho explícitamente.
                // También al VENDEDOR: la tasa de devolución del negocio es una
                // lectura de dirección, no de cartera.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/devoluciones")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // ── Fase E3 del nivel estratégico: la alerta de abandono.
                //
                // OTD-VEN-19 lleva MONTO (facturación 12m y valor en riesgo), y
                // como en todos los compuestos el corte lo hace la RUTA: Bodega
                // y Despacho fuera. El VENDEDOR entra —es quien ejecuta el gesto
                // comercial— y el servicio lo recorta a SU cartera. El ANALISTA
                // queda fuera a propósito: esto no es una lectura de análisis,
                // es una lista de personas a las que hay que llamar.
                //
                // La línea es REDUNDANTE con el comodín de abajo, que hoy
                // concede los mismos tres roles, y se escribe igual: si mañana
                // el comodín se ensancha, este endpoint no debe heredarlo por
                // accidente.
                .requestMatchers(HttpMethod.GET, "/api/informes/ventas/clientes-en-riesgo")
                    .hasAnyAuthority("ADMIN", "GERENTE", "VENDEDOR")
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
                // COMPUESTOS de Inventario (fuente ClickHouse, Fase 3B del ETL).
                // Van ANTES del comodín del departamento porque su reparto de
                // roles NO coincide con el de los simples: suman al ANALISTA.
                //
                // OTD-INV-09 (capital inmovilizado) es DINERO de principio a fin
                // y deja fuera a BODEGA — el segundo corte financiero del
                // departamento, junto con OTD-INV-07, y por el mismo motivo:
                // ClickHouse no tiene GRANT por columna (§8.2 del diseño), así
                // que la barrera es esta ruta.
                .requestMatchers(HttpMethod.GET,
                        "/api/informes/inventario/capital-inmovilizado")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // OTD-INV-04 (rotación) y OTD-INV-10 (mermas): BODEGA entra.
                // INV-04 no selecciona ni un importe; INV-10 es MIXTO y el
                // servicio decide sobre el rol del JWT — solo ADMIN y GERENTE
                // reciben las columnas de valor, que ni siquiera se seleccionan
                // para el resto.
                .requestMatchers(HttpMethod.GET, "/api/informes/inventario/rotacion",
                        "/api/informes/inventario/mermas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA", "BODEGA")
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
                // COMPRAS — OTD-COM-08 (pool de defectuosos y devoluciones al
                // proveedor) es el único SIN dinero, y BODEGA es quien marca e
                // inspecciona la mercancía mala: entra. El informe no selecciona
                // ninguna columna de monto — el motor NO lo impediría, porque el
                // script 45 dio a grp_bodega SELECT sobre item_defectuoso
                // .costo_unitario para el flujo operativo; el control es la consulta.
                // Con ellos entran los otros dos SIN dinero para Bodega:
                // OTD-COM-07 (rechazos en puerta) y OTD-COM-11 (entregas
                // incompletas), que el catálogo le da «en cantidades, sin
                // montos». Los tres son MIXTOS: la ruta deja pasar a Bodega y es
                // la CONSULTA la que no le selecciona el importe. En COM-07 el
                // motor tampoco alcanzaría —ClickHouse no tiene GRANT por
                // columna— y en COM-11 grp_bodega conserva a propósito SELECT
                // sobre orden_compra_detalle.precio_unitario (script 41, lo
                // necesita para valorizar el kardex al recibir).
                .requestMatchers(HttpMethod.GET, "/api/informes/compras/defectuosos",
                        "/api/informes/compras/rechazos",
                        "/api/informes/compras/entregas-incompletas")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS", "BODEGA")
                // Los cuatro compuestos a los que el catálogo SÍ invita al
                // ANALISTA: puntualidad de pago (COM-03), gasto de compras
                // (COM-04), días de ciclo (COM-06) y evolución del costo
                // (COM-12). Llevan dinero o precios, así que Bodega y Despacho
                // siguen fuera; lo que cambia respecto del comodín de abajo es
                // que se suma el analista, igual que ya ocurre en OTD-VEN-16.
                // Se enumeran POR NOMBRE y no con un comodín para que un
                // endpoint futuro no herede el permiso sin decidirlo.
                .requestMatchers(HttpMethod.GET, "/api/informes/compras/puntualidad-pago",
                        "/api/informes/compras/gasto-mensual",
                        "/api/informes/compras/ciclo-compra",
                        "/api/informes/compras/evolucion-costo")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS", "ANALISTA")
                // PREVISION DE DEMANDA (fase E2, §5.1.8) — el lado de COMPRAS.
                // Sirve a D-11.1 (plan de compra) y D-07.5 (nivel objetivo de
                // stock). El reparto coincide con el comodin del departamento y
                // aun asi va enumerada POR NOMBRE: la MISMA pantalla existe bajo
                // /api/informes/gerencia con OTRO reparto (alli entra el ANALISTA
                // y aqui no; aqui entra COMPRAS y alli no), y dos rutas gemelas
                // que se apoyan cada una en el comodin de su departamento
                // divergen sin que nadie lo decida en cuanto uno de los dos
                // comodines cambie. No lleva importes —son unidades— pero BODEGA
                // y DESPACHO quedan fuera: es material de planificacion.
                .requestMatchers(HttpMethod.GET, "/api/informes/compras/prevision-demanda")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS")
                // Resto de informes de Compras (COM-01 órdenes, COM-02 cuentas por
                // pagar, COM-10 catálogo proveedor-producto, COM-09 recuperación al
                // proveedor): llevan MONTO o COSTO, así que BODEGA y DESPACHO quedan
                // fuera. En los simples el motor SÍ respalda el corte (sin SELECT
                // sobre orden_compra.total, cuenta_por_pagar ni producto_proveedor);
                // en los compuestos lo hace solo esta ruta.
                // OTD-COM-05 (cumplimiento del plazo prometido) cae aquí a
                // propósito aunque NO seleccione ni un importe: el catálogo lo
                // reserva a Compras y Gerencia porque es material de negociación
                // con el proveedor. Es el único compuesto de Compras del que
                // también sale el ANALISTA.
                .requestMatchers(HttpMethod.GET, "/api/informes/compras/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS")
                // LOGÍSTICA / DESPACHO — OTD-LOG-11 (costo del envío por zona) es
                // DINERO: se cierra a ADMIN/GERENTE. Ese corte lo hace ESTA ruta y
                // no el motor, porque grp_despacho conserva SELECT sobre envio.costo
                // (lo escribe al despachar, script 47). Mismo caso declarado que
                // OTD-INV-07.
                // La SERIE mensual del mismo costo (compuesta, ClickHouse) va en
                // la MISMA línea y por el mismo motivo: es dinero. Se nombra
                // aparte y no con un comodín sobre `costo-envio*` para que el
                // corte siga siendo una decisión escrita y no un efecto del
                // prefijo — un endpoint futuro que empezara igual heredaría el
                // permiso sin que nadie lo hubiera decidido.
                // La FASE 4 añade a esta misma línea OTD-LOG-10 (reembolsos):
                // es el tercer informe con dinero del departamento y el único
                // de la posventa que lo lleva, así que DESPACHO queda fuera
                // igual que en los dos de costo. SOPORTE sí entra —es quien
                // gestiona el RMA y ve el reembolso en su bandeja—, y por eso
                // no comparte línea con los otros dos sino que se enumera
                // aparte, justo debajo.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/costo-envio",
                        "/api/informes/logistica/costo-envio-mensual")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/reembolsos")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE")
                // OTD-LOG-12 (tiempo por etapa del ciclo) es COMPUESTO: lo sirve
                // ClickHouse desde los hitos pivotados de fact_pedido. Suma al
                // ANALISTA, que no participa en el resto del departamento, y por
                // eso necesita línea propia. DESPACHO entra —es su operación— y
                // aquí la ausencia de dinero NO la garantiza el motor: la tabla
                // de origen sí tiene `total` y ClickHouse no tiene GRANT por
                // columna. La barrera es la CONSULTA, que no selecciona ningún
                // importe; mismo mecanismo declarado en OTD-COM-08.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/tiempos-ciclo")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO", "ANALISTA")
                // OTD-LOG-03 y OTD-LOG-04 (cumplimiento de la fecha prometida y
                // días reales de tránsito) son COMPUESTOS sobre fact_envio, y
                // comparten destinatarios con LOG-12: suman al ANALISTA. DESPACHO
                // entra porque son SU operación medida en fechas y veredictos —
                // la tabla de origen sí tiene `costo`, así que aquí la ausencia
                // de dinero tampoco la garantiza el motor: la garantizan estas
                // dos consultas, que no seleccionan ningún importe.
                .requestMatchers(HttpMethod.GET,
                        "/api/informes/logistica/cumplimiento-promesa",
                        "/api/informes/logistica/dias-transito")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO", "ANALISTA")
                // OTD-LOG-05 (problemas de entrega) cambia de reparto: entra
                // SOPORTE —la incidencia de entrega acaba en su bandeja— y NO el
                // analista, que no participa en la posventa. Sin columnas de
                // monto: fact_novedad_envio no lleva ninguna, así que aquí el
                // corte no depende de la consulta.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/novedades")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO", "SOPORTE")
                // ── Fase 4: los tres compuestos de posventa SIN dinero ────
                // OTD-LOG-07 (días de ciclo de la devolución) y OTD-LOG-08
                // (motivos y destino de la mercancía). El catálogo pone en los
                // dos a Gerente, Soporte y Analista; LOG-08 suma además a
                // BODEGA «en cantidades», porque es quien inspecciona. En los
                // dos la ausencia de dinero la garantiza la CONSULTA y no el
                // motor: fact_devolucion lleva `monto_total` y ClickHouse no
                // tiene GRANT por columna. Mismo mecanismo que OTD-COM-08.
                .requestMatchers(HttpMethod.GET,
                        "/api/informes/logistica/ciclo-devolucion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE", "ANALISTA")
                .requestMatchers(HttpMethod.GET,
                        "/api/informes/logistica/motivos-devolucion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE", "ANALISTA", "BODEGA")
                // OTD-LOG-09 (de cada 100 envíos, cuántos vuelven) cambia de
                // reparto otra vez: Gerente, Analista y DESPACHO «en conteos»
                // —es su operación—, y NO Soporte. Solo cuenta envíos y
                // devoluciones; ni un importe.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/tasa-devolucion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO", "ANALISTA")
                // OTD-LOG-06: el ciclo RMA lo comparten DESPACHO (tránsito y
                // recepción), SOPORTE (valida y cierra) y BODEGA (inspección, en
                // cantidades). Sin columnas de monto: el motor lo respalda.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/devoluciones")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO", "SOPORTE", "BODEGA")
                // OTD-LOG-01 y OTD-LOG-02: la operación diaria de salida, en estados
                // y cantidades.
                .requestMatchers(HttpMethod.GET, "/api/informes/logistica/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "DESPACHO")
                // SOPORTE — los tres simples de la mesa de ayuda
                // (OTD-SOP-01/04/05) más los cinco COMPUESTOS de la Fase 4
                // (SOP-02/03/06/07/08). Ninguno de los ocho lleva dinero, así
                // que no hay corte financiero que hacer y comparten el comodín
                // del departamento. DOS excepciones, ambas de la Fase 4 y las
                // dos por AMPLIACIÓN, nunca por restricción:
                //
                //   · OTD-SOP-03 (tiempo de resolución por categoría) suma al
                //     ANALISTA, que el catálogo nombra destinatario.
                //   · OTD-SOP-08 (productos que más reclamos y devoluciones
                //     generan) suma a COMPRAS: el ranking existe precisamente
                //     para que Compras vaya a revisar el producto con su
                //     proveedor. Son conteos de reclamos y unidades — la
                //     consulta no selecciona ni un importe.
                //
                // Van ANTES del comodín porque ampliarlo arrastraría a los
                // otros seis.
                .requestMatchers(HttpMethod.GET, "/api/informes/soporte/tiempo-resolucion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE", "ANALISTA")
                .requestMatchers(HttpMethod.GET,
                        "/api/informes/soporte/productos-reclamados")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE", "COMPRAS")
                .requestMatchers(HttpMethod.GET, "/api/informes/soporte/**")
                    .hasAnyAuthority("ADMIN", "GERENTE", "SOPORTE")
                // GERENCIA / DIRECCIÓN — OTD-GER-08 (auditoría del sistema) y
                // OTD-GER-09 (intentos de acceso) son DATOS SENSIBLES DE SEGURIDAD:
                // el corte más estricto del sistema, solo ADMIN y GERENTE. Se
                // declaran en su propia línea aunque hoy coincida con la del
                // departamento, para que ampliar Gerencia a otro rol no los
                // arrastre por descuido. En /accesos el motor respalda la ruta
                // (solo esos dos grupos tienen SELECT sobre log_acceso, script 53);
                // en /auditoria NO, porque grp_analista sí lee log_auditoria: ahí
                // el corte lo hace ESTA ruta, como en OTD-INV-07 y OTD-LOG-11.
                .requestMatchers(HttpMethod.GET, "/api/informes/gerencia/auditoria",
                        "/api/informes/gerencia/accesos")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // OTD-GER-02 (balanza mensual de caja) y OTD-GER-05 (descuento
                // por cupón y período) son COMPUESTOS: los sirve ClickHouse
                // desde fact_flujo_caja y fact_pedido. El catálogo suma el
                // ANALISTA a los dos, y por eso van en su PROPIA línea y no
                // ampliando el comodín del departamento: ese comodín también
                // cubriría —si se ampliara— los dos informes de seguridad de
                // arriba, que deben seguir en ADMIN+GERENTE. Separarlos es lo
                // que impide que un permiso de análisis abra un dato sensible.
                //
                // La FASE 4 añade cuatro más con el MISMO reparto y a la misma
                // línea: OTD-GER-03 (ganancia por categoría), GER-10 (margen
                // producto a producto), GER-11 (descuento total entregado) y
                // GER-07 (efecto de las promociones). El catálogo pone el
                // ANALISTA en los cuatro; los cuatro llevan dinero y ninguno lo
                // respalda el motor. Se enumeran POR NOMBRE y no con un comodín
                // sobre `margen-*` o `descuento-*` para que un endpoint futuro
                // que empiece igual no herede el permiso sin que nadie lo haya
                // decidido — el mismo criterio de `costo-envio-mensual`.
                .requestMatchers(HttpMethod.GET, "/api/informes/gerencia/balanza",
                        "/api/informes/gerencia/descuento-cupones",
                        "/api/informes/gerencia/margen-categoria",
                        "/api/informes/gerencia/margen-producto",
                        "/api/informes/gerencia/descuento-total",
                        "/api/informes/gerencia/efecto-promociones",
                        // PREVISION DE DEMANDA (fase E2, §5.1.8) — el lado de
                        // GERENCIA, que sirve a D-10.1: la previsión con la que
                        // se fijan las metas del período. Mismo reparto que el
                        // resto de esta línea (se suma el ANALISTA), y por eso
                        // cae aquí y no en el comodín del departamento, que
                        // dejaría al analista fuera de su propio trabajo.
                        "/api/informes/gerencia/prevision-demanda")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // Resto de informes de Gerencia (GER-01 foto del día, GER-04
                // cupones, GER-06 marketing vigente): dirección del negocio, con
                // montos y presupuesto — ADMIN y GERENTE.
                .requestMatchers(HttpMethod.GET, "/api/informes/gerencia/**")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Los informes son SOLO consulta: cualquier método que no sea GET
                // sobre /api/informes se rechaza de plano.
                .requestMatchers("/api/informes/**").denyAll()
                // ── TABLEROS DE DIRECCION (nivel ESTRATEGICO, fase E1-A) ──────
                // docs/estrategico/DISENO_NIVEL_ESTRATEGICO.md §4.
                //
                // Los TRES tableros de esta fase LLEVAN DINERO —margen, capital,
                // valor del cliente, monto devuelto—, asi que BODEGA y DESPACHO
                // quedan FUERA. El motor no puede respaldar ese corte: ClickHouse
                // no tiene GRANT por columna ni RLS, y el ETL escribe todas las
                // columnas de importe en tablas planas. La UNICA barrera es esta
                // ruta (R-5 del diseno), la misma situacion que OTD-LOG-11,
                // OTD-INV-07 y OTD-GER-08.
                //
                // Por eso cada tablero va ENUMERADO POR NOMBRE y no con un
                // comodin /api/tableros/**: la fase E1-B trae T-4 (operacion y
                // ultima milla), que es el UNICO SIN dinero y el unico al que
                // Bodega y Despacho podran entrar. Con un comodin, ese tablero
                // futuro heredaria el permiso —o forzaria a recordar recortarlo—
                // y el corte financiero se decidiria por descuido.
                .requestMatchers(HttpMethod.GET, "/api/tableros/omnicanal",
                        "/api/tableros/rentabilidad")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // T-3 suma SOPORTE, y solo el: entra al tablero por el bloque de
                // tickets y devoluciones. El recorte de los demas bloques NO lo
                // puede hacer esta linea —es dentro del mismo endpoint— y lo hace
                // la CONSULTA: para SOPORTE esos bloques no se ejecutan y el
                // sobre declara cuales omitio. Es la misma disciplina de COM-08,
                // un escalon mas fino.
                .requestMatchers(HttpMethod.GET, "/api/tableros/cliente-posventa")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA", "SOPORTE")
                // ── Fase E1-B ────────────────────────────────────────────────
                // T-4 (operacion y ultima milla) es el UNICO tablero SIN dinero
                // y el unico que DESPACHO y BODEGA pueden abrir. Que puedan
                // hacerlo depende de DOS cosas a la vez, y las dos son de este
                // proyecto y no del motor: esta linea, y que su consulta no
                // seleccione un solo importe (se mide en envios, dias, horas y
                // unidades). ClickHouse no tiene GRANT por columna que respalde
                // lo segundo, asi que hay una prueba automatica que recorre la
                // respuesta buscando columnas monetarias.
                .requestMatchers(HttpMethod.GET, "/api/tableros/operacion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA", "DESPACHO", "BODEGA")
                // T-5 es el gemelo CON dinero de T-4 —el costo del flete y del
                // retorno— y existe separado precisamente para que T-4 pueda
                // estar abierto a la operacion. Despacho y Bodega quedan fuera.
                .requestMatchers(HttpMethod.GET, "/api/tableros/costo-operacion")
                    .hasAnyAuthority("ADMIN", "GERENTE", "ANALISTA")
                // T-6 suma COMPRAS: es su objetivo (OE-11) y su centro de costo.
                // Lleva dinero, asi que Bodega y Despacho siguen fuera.
                .requestMatchers(HttpMethod.GET, "/api/tableros/abastecimiento")
                    .hasAnyAuthority("ADMIN", "GERENTE", "COMPRAS", "ANALISTA")
                // T-7 es DATO SENSIBLE: el corte mas estricto del sistema.
                // ANALISTA queda fuera A PROPOSITO aunque entre en los otros
                // seis tableros, y aqui la ruta NO coincide con el motor:
                // grp_analista SI tiene SELECT sobre log_auditoria (script 19),
                // asi que esta linea es la unica barrera real. Va enumerada por
                // nombre por eso mismo.
                .requestMatchers(HttpMethod.GET, "/api/tableros/gobierno-dato")
                    .hasAnyAuthority("ADMIN", "GERENTE")
                // Igual que los informes: los tableros son SOLO consulta. Cualquier
                // metodo distinto de GET —y cualquier ruta de tablero que no este
                // enumerada arriba— se rechaza de plano en vez de caer en
                // anyRequest().authenticated(), que la abriria a los nueve roles.
                .requestMatchers("/api/tableros/**").denyAll()
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
