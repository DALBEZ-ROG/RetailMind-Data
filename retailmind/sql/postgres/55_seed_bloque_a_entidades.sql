-- =====================================================================
-- 55_seed_bloque_a_entidades.sql
-- SIEMBRA DE VOLUMEN HISTORICO — BLOQUE A / Parte 1: ENTIDADES
-- ---------------------------------------------------------------------
-- Amplia el universo de entidades para que los informes tacticos
-- agrupados por proveedor / transportista / vendedor / cliente
-- discriminen. NO toca ningun dato real preexistente.
--
-- MECANISMO DE MARCA / REVERSION (sin DDL de esquema):
--   * Se registra una fila en configuracion_tienda con
--     clave = 'seed_ba_55_entidades' y valor = JSON con los max(id)
--     PRE-siembra de cada tabla afectada. El script de reversion
--     (99_revert_bloque_a.sql) borra todo lo sembrado usando esos
--     umbrales (id > umbral) en orden seguro de FKs.
--   * Idempotencia: si la clave ya existe, el script no hace nada.
--   * Todo el archivo corre en UNA transaccion (psql -1): es atomico.
--
-- Ejecutar como superusuario postgres (exento de RLS y de horario:
-- fn_grupo_actual() => grp_administrador).
-- =====================================================================

DO $$
DECLARE
    v_thresholds jsonb;
    -- ids de proveedores sembrados (se resuelven por RUC tras el INSERT)
    v_ge_masc  text[] := ARRAY['Juan','Carlos','Luis','Jorge','Andres','Diego','Marco','Pedro','Fernando','Santiago','Cristian','Byron','Wilson','Kevin','Bryan','Angel','Milton','Freddy','Danilo','Gabriel','Roberto','Hector','Javier','Esteban','Ivan','Patricio','Galo','Xavier','Manuel','Julio'];
    v_ge_fem   text[] := ARRAY['Maria','Ana','Rosa','Veronica','Gabriela','Andrea','Carmen','Jessica','Katherine','Daniela','Paola','Monica','Silvia','Johanna','Tatiana','Cinthia','Karina','Nube','Elena','Diana','Marcela','Belen','Fernanda','Alexandra','Valeria','Doris','Narcisa','Yadira','Grace','Estefania'];
    v_ap       text[] := ARRAY['Zambrano','Cedeno','Vera','Mendoza','Bravo','Alvarado','Moreira','Loor','Macias','Intriago','Chavez','Ponce','Salazar','Castro','Vasquez','Anchundia','Palma','Delgado','Solorzano','Vinueza','Toro','Guaman','Yepez','Andrade','Cabrera','Ortega','Reyes','Paredes','Villacres','Naranjo','Espinoza','Gonzalez','Torres','Suarez','Jimenez','Moscoso','Carrion','Pacheco','Coello','Franco'];
    v_calles   text[] := ARRAY['Av. Walter Andrade','Calle Bolivar','Av. June Guzman','Calle Sucre','Av. Quito','Calle 7 de Octubre','Av. Jaime Roldos','Calle Malecon','Av. Los Rios','Calle Rocafuerte','Av. Guayaquil','Calle Garcia Moreno','Av. Amazonas','Calle Olmedo','Av. Universitaria'];
    v_dominios text[] := ARRAY['gmail.com','hotmail.com','outlook.com','yahoo.com','gmail.com','hotmail.com'];
    v_hash_staff   text;
    v_hash_cliente text;
    i int;
    v_gen text; v_nom text; v_ape text; v_email text; v_ident text;
    v_reg timestamptz; v_nac date; v_uid bigint; v_ciudad bigint;
    v_ced_base bigint := 1206500000;  -- base para cedulas sinteticas (provincia 12)
    v_ciudades bigint[];
    v_prov_ids bigint[];
BEGIN
    IF EXISTS (SELECT 1 FROM configuracion_tienda WHERE clave = 'seed_ba_55_entidades') THEN
        RAISE NOTICE 'Bloque A / 55 (entidades) ya sembrado; se omite.';
        RETURN;
    END IF;

    PERFORM setseed(0.5501);

    -- Snapshot de umbrales PRE-siembra (para reversion) -----------------
    v_thresholds := jsonb_build_object(
        'proveedor',          (SELECT COALESCE(max(id),0) FROM proveedor),
        'contacto_proveedor', (SELECT COALESCE(max(id),0) FROM contacto_proveedor),
        'transportista',      (SELECT COALESCE(max(id),0) FROM transportista),
        'ciudad',             (SELECT COALESCE(max(id),0) FROM ciudad),
        'usuario',            (SELECT COALESCE(max(id),0) FROM usuario),
        'usuario_rol',        (SELECT COALESCE(max(id),0) FROM usuario_rol),
        'cliente',            (SELECT COALESCE(max(id),0) FROM cliente),
        'direccion',          (SELECT COALESCE(max(id),0) FROM direccion),
        'producto_proveedor', (SELECT COALESCE(max(id),0) FROM producto_proveedor)
    );

    -- Hashes BCrypt reales reutilizados (misma contrasena que el staff /
    -- clientes existentes): Retail2026! y Cliente2026! respectivamente.
    SELECT password_hash INTO v_hash_staff   FROM usuario WHERE id = 7;  -- vendedor@ (Retail2026!)
    SELECT password_hash INTO v_hash_cliente FROM usuario WHERE id = 4;  -- maria.lopez (Cliente2026!)

    -- =================================================================
    -- 1) GEOGRAFIA: ciudades ecuatorianas para cubrir direcciones
    -- =================================================================
    INSERT INTO ciudad (provincia_id, nombre, codigo_postal) VALUES
        ( 9,'Guayaquil','090150'),
        ( 9,'Duran','092405'),
        ( 9,'Milagro','091050'),
        (17,'Quito','170150'),
        (17,'Sangolqui','171103'),
        ( 1,'Cuenca','010150'),
        (13,'Manta','130802'),
        (13,'Portoviejo','130105'),
        ( 7,'Machala','070150'),
        (18,'Ambato','180150'),
        ( 6,'Riobamba','060150'),
        (11,'Loja','110150'),
        ( 8,'Esmeraldas','080150'),
        (23,'Santo Domingo','230150'),
        (10,'Ibarra','100150'),
        ( 5,'Latacunga','050150'),
        (24,'La Libertad','241550'),
        (25,'Ventanas','120550'),
        (25,'Vinces','120650')
    ON CONFLICT (provincia_id, nombre) DO NOTHING;

    -- Conjunto de ciudades destino para clientes (peso a la zona real
    -- del negocio: Los Rios / Guayas, y grandes urbes).
    v_ciudades := ARRAY[
        (SELECT id FROM ciudad WHERE nombre='Quevedo'      LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Quevedo'      LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Quevedo'      LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Babahoyo'     LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Ventanas'     LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Vinces'       LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Guayaquil'    LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Guayaquil'    LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Duran'        LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Milagro'      LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Quito'        LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Manta'        LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Portoviejo'   LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Machala'      LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Ambato'       LIMIT 1),
        (SELECT id FROM ciudad WHERE nombre='Santo Domingo'LIMIT 1)
    ];

    -- =================================================================
    -- 2) PROVEEDORES (9 nuevos; totales 11) por giro
    -- =================================================================
    INSERT INTO proveedor (ruc, razon_social, nombre_comercial, email, telefono, ciudad_id, direccion, dias_credito, activo, fecha_creacion) VALUES
      ('0992500011001','Importadora TecnoAndes S.A.','TecnoAndes',       'compras@tecnoandes.ec','04-2501100',(SELECT id FROM ciudad WHERE nombre='Guayaquil' LIMIT 1),'Parque Ind. Pascuales Km 11.5, Guayaquil',30,true,'2025-01-03'),
      ('0992500012001','Comercial El Costeno Cia. Ltda.','El Costeno',    'ventas@elcosteno.ec',  '04-2502200',(SELECT id FROM ciudad WHERE nombre='Guayaquil' LIMIT 1),'Av. Juan Tanca Marengo, Guayaquil',30,true,'2025-01-06'),
      ('1792500013001','Distribuidora BellaVida S.A.','BellaVida',        'pedidos@bellavida.ec', '02-2503300',(SELECT id FROM ciudad WHERE nombre='Quito' LIMIT 1),'Av. 6 de Diciembre N45-12, Quito',45,true,'2025-01-08'),
      ('1892500014001','Calzado del Pacifico Cia. Ltda.','CalzaPacifico', 'ventas@calzapacifico.ec','03-2504400',(SELECT id FROM ciudad WHERE nombre='Ambato' LIMIT 1),'Av. Cevallos 08-24, Ambato',30,true,'2025-01-10'),
      ('1792500015001','Textiles ModaViva S.A.','ModaViva',              'compras@modaviva.ec',   '02-2505500',(SELECT id FROM ciudad WHERE nombre='Quito' LIMIT 1),'Av. Maldonado S12-45, Quito',45,true,'2025-01-13'),
      ('0192500016001','HogarPlus Distribuciones Cia. Ltda.','HogarPlus', 'ventas@hogarplus.ec',   '07-2506600',(SELECT id FROM ciudad WHERE nombre='Cuenca' LIMIT 1),'Av. de las Americas 3-40, Cuenca',30,true,'2025-01-15'),
      ('0992500017001','Importadora Accesorios Total S.A.','AccesoTotal', 'pedidos@accesototal.ec','04-2507700',(SELECT id FROM ciudad WHERE nombre='Guayaquil' LIMIT 1),'Cdla. La Garzota Mz 22, Guayaquil',15,true,'2025-01-18'),
      ('1792500018001','Mundo Deportivo Ecuador Cia. Ltda.','MundoDeportivo','ventas@mundodeportivo.ec','02-2508800',(SELECT id FROM ciudad WHERE nombre='Quito' LIMIT 1),'Av. Republica E7-123, Quito',30,true,'2025-01-20'),
      ('0792500019001','Comercializadora Multimarca del Litoral S.A.','MultiLitoral','compras@multilitoral.ec','07-2509900',(SELECT id FROM ciudad WHERE nombre='Machala' LIMIT 1),'Av. 25 de Junio, Machala',60,true,'2025-01-22')
    ON CONFLICT (ruc) DO NOTHING;

    -- Contactos de proveedor (contacto_proveedor estaba vacia) ---------
    INSERT INTO contacto_proveedor (proveedor_id, nombre, cargo, email, telefono, es_principal, activo)
    SELECT p.id,
           (v_ge_masc[1 + (abs(hashtext(p.ruc)) % array_length(v_ge_masc,1))]) || ' ' ||
           (v_ap[1 + (abs(hashtext(p.ruc||'a')) % array_length(v_ap,1))]),
           (ARRAY['Ejecutivo de Ventas','Gerente Comercial','Jefe de Cuenta','Asesor de Compras'])[1 + (abs(hashtext(p.ruc||'c')) % 4)],
           'contacto@' || split_part(p.email,'@',2),
           p.telefono,
           true, true
    FROM proveedor p
    WHERE p.ruc LIKE '%2500%';   -- solo los sembrados

    -- Segundo contacto para algunos (es_principal=false)
    INSERT INTO contacto_proveedor (proveedor_id, nombre, cargo, email, telefono, es_principal, activo)
    SELECT p.id,
           (v_ge_fem[1 + (abs(hashtext(p.ruc||'f')) % array_length(v_ge_fem,1))]) || ' ' ||
           (v_ap[1 + (abs(hashtext(p.ruc||'b')) % array_length(v_ap,1))]),
           'Asistente Comercial',
           'facturacion@' || split_part(p.email,'@',2),
           p.telefono,
           false, true
    FROM proveedor p
    WHERE p.ruc LIKE '%2500%' AND (abs(hashtext(p.ruc)) % 2) = 0;

    -- =================================================================
    -- 3) TRANSPORTISTAS (3 nuevos; totales 5)
    -- =================================================================
    INSERT INTO transportista (nombre, ruc, telefono, email, activo) VALUES
      ('Laar Courier',        '0993456789001','1800-052227', 'servicio@laarcourier.ec', true),
      ('Urbano Express',      '1793456780001','1700-872266', 'contacto@urbano.ec',      true),
      ('Speed Mail Ecuador',  '0993456781001','04-2601122',  'ventas@speedmail.ec',     true)
    ON CONFLICT DO NOTHING;

    -- =================================================================
    -- 4) USUARIOS INTERNOS: 5 vendedores + 3 soporte (con rol)
    -- =================================================================
    -- Vendedores (rol VENDEDOR = 3)
    FOR i IN 1..5 LOOP
        v_nom := v_ge_masc[i+2]; v_ape := v_ap[i+5];
        v_email := lower(v_nom || '.' || v_ape || '@retailmind.com');
        INSERT INTO usuario (email, password_hash, nombre, apellido, telefono, email_verificado, activo, fecha_creacion)
        VALUES (v_email, v_hash_staff, v_nom, v_ape, '09'||lpad((90000000+i)::text,8,'0'), true, true,
                ('2025-01-'||lpad((10+i)::text,2,'0'))::timestamptz)
        RETURNING id INTO v_uid;
        INSERT INTO usuario_rol (usuario_id, rol_id) VALUES (v_uid, 3) ON CONFLICT DO NOTHING;
    END LOOP;
    -- Soporte (rol SOPORTE = 9)
    FOR i IN 1..3 LOOP
        v_nom := v_ge_fem[i+3]; v_ape := v_ap[i+15];
        v_email := lower(v_nom || '.' || v_ape || '@retailmind.com');
        INSERT INTO usuario (email, password_hash, nombre, apellido, telefono, email_verificado, activo, fecha_creacion)
        VALUES (v_email, v_hash_staff, v_nom, v_ape, '09'||lpad((91000000+i)::text,8,'0'), true, true,
                ('2025-01-'||lpad((20+i)::text,2,'0'))::timestamptz)
        RETURNING id INTO v_uid;
        INSERT INTO usuario_rol (usuario_id, rol_id) VALUES (v_uid, 9) ON CONFLICT DO NOTHING;
    END LOOP;

    -- =================================================================
    -- 5) CLIENTES: 70 (usuario CLIENTE + perfil cliente + direccion)
    --    fecha de registro repartida en 18 meses (nuevos vs recurrentes)
    -- =================================================================
    FOR i IN 1..70 LOOP
        -- genero con distribucion realista, nombre coherente
        IF random() < 0.48 THEN
            v_gen := 'masculino';
            v_nom := v_ge_masc[1 + (i % array_length(v_ge_masc,1))];
        ELSIF random() < 0.94 THEN
            v_gen := 'femenino';
            v_nom := v_ge_fem[1 + (i % array_length(v_ge_fem,1))];
        ELSIF random() < 0.6 THEN
            v_gen := 'otro';
            v_nom := v_ge_fem[1 + ((i*3) % array_length(v_ge_fem,1))];
        ELSE
            v_gen := 'no_indica';
            v_nom := v_ge_masc[1 + ((i*5) % array_length(v_ge_masc,1))];
        END IF;
        v_ape := v_ap[1 + (i % array_length(v_ap,1))] || ' ' || v_ap[1 + ((i*7) % array_length(v_ap,1))];

        -- fecha de registro: 2025-01-05 .. 2026-07-10 (uniforme => mezcla
        -- sana de clientes antiguos [recurrentes] y recientes [nuevos])
        v_reg := timestamptz '2025-01-05 09:00:00-05' + (random()*550) * interval '1 day';
        v_nac := (date '1968-01-01' + (random()*13000)::int);  -- ~1968..2003
        v_email := lower(replace(v_nom,' ','') || '.' || replace(split_part(v_ape,' ',1),' ','') || i || '@' || v_dominios[1 + (i % array_length(v_dominios,1))]);
        v_ident := lpad((v_ced_base + i)::text, 10, '0');
        v_ciudad := v_ciudades[1 + (i % array_length(v_ciudades,1))];

        INSERT INTO usuario (email, password_hash, nombre, apellido, telefono, email_verificado, activo, fecha_creacion)
        VALUES (v_email, v_hash_cliente, v_nom, v_ape, '09'||lpad((60000000+i)::text,8,'0'), true, true, v_reg)
        RETURNING id INTO v_uid;

        INSERT INTO usuario_rol (usuario_id, rol_id) VALUES (v_uid, 7) ON CONFLICT DO NOTHING;

        INSERT INTO cliente (usuario_id, tipo_identificacion, numero_identificacion, nombre, apellido, email,
                             telefono, fecha_nacimiento, genero, acepta_marketing, activo, fecha_creacion)
        VALUES (v_uid, 'cedula', v_ident, v_nom, v_ape, v_email, '09'||lpad((60000000+i)::text,8,'0'),
                v_nac, v_gen, (random()<0.55), true, v_reg);

        INSERT INTO direccion (usuario_id, ciudad_id, tipo, alias, destinatario, calle_principal, calle_secundaria,
                               numero, referencia, telefono, es_predeterminada, activo, fecha_creacion)
        VALUES (v_uid, v_ciudad, 'envio', 'Casa', v_nom||' '||v_ape,
                v_calles[1 + (i % array_length(v_calles,1))],
                v_calles[1 + ((i*3) % array_length(v_calles,1))],
                'N'||(10+(i%80))::text||'-'||(1+(i%50))::text,
                'Casa esquinera color '||(ARRAY['blanco','celeste','verde','beige'])[1+(i%4)],
                '09'||lpad((60000000+i)::text,8,'0'), true, true, v_reg);
    END LOOP;

    -- =================================================================
    -- 6) PRODUCTO_PROVEEDOR: assortment activo (~360 variantes) con
    --    proveedor PREFERIDO por categoria (giro). Se excluyen las
    --    variantes con kardex real para no tocar sus cadenas.
    -- =================================================================
    WITH var_cat AS (
        SELECT pv.id AS variante_id, pv.costo, min(pc.categoria_id) AS cat
        FROM producto_variante pv
        JOIN producto p ON p.id = pv.producto_id
        JOIN producto_categoria pc ON pc.producto_id = p.id
        WHERE pv.costo IS NOT NULL AND pv.costo > 0
          AND pv.id NOT IN (SELECT DISTINCT producto_variante_id FROM movimiento_inventario)
        GROUP BY pv.id, pv.costo
    ),
    ranked AS (
        SELECT *, row_number() OVER (PARTITION BY cat ORDER BY variante_id) rn FROM var_cat
    ),
    mapa(cat, ruc, lead) AS (
        VALUES (10,'0992500011001',12),(5,'0992500012001',5),(7,'1792500013001',8),
               (4,'1892500014001',10),(12,'1792500015001',9),(1,'1792500015001',9),
               (3,'1792500015001',9),(11,'0192500016001',7),(2,'0992500017001',6),
               (9,'1792500018001',8)
    )
    INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, codigo_proveedor, costo,
                                    tiempo_entrega_dias, cantidad_minima, es_preferido, activo)
    SELECT pr.id, r.variante_id, 'PRV'||pr.id||'-'||lpad(r.variante_id::text,5,'0'),
           round(r.costo,2), m.lead, 1, true, true
    FROM ranked r
    JOIN mapa m ON m.cat = r.cat
    JOIN proveedor pr ON pr.ruc = m.ruc
    WHERE r.rn <= 45
    ON CONFLICT (proveedor_id, producto_variante_id) DO NOTHING;

    -- Proveedor SECUNDARIO (no preferido) para Abarrotes/Hogar: MultiLitoral
    INSERT INTO producto_proveedor (proveedor_id, producto_variante_id, codigo_proveedor, costo,
                                    tiempo_entrega_dias, cantidad_minima, es_preferido, activo)
    SELECT (SELECT id FROM proveedor WHERE ruc='0792500019001'), pp.producto_variante_id,
           'MLT-'||lpad(pp.producto_variante_id::text,5,'0'),
           round(pp.costo * 1.04, 2), 15, 1, false, true
    FROM producto_proveedor pp
    JOIN proveedor pr ON pr.id = pp.proveedor_id
    WHERE pr.ruc IN ('0992500012001','0192500016001') AND pp.es_preferido
      AND (abs(hashtext(pp.producto_variante_id::text)) % 2) = 0
    ON CONFLICT (proveedor_id, producto_variante_id) DO NOTHING;

    -- =================================================================
    -- 7) MARCA DE SIEMBRA (registro de umbrales para reversion)
    -- =================================================================
    INSERT INTO configuracion_tienda (clave, valor, tipo_dato, descripcion, fecha_creacion)
    VALUES ('seed_ba_55_entidades', v_thresholds::text, 'json',
            'Bloque A/55: umbrales max(id) PRE-siembra de entidades. Reversion: borrar id>umbral.', now());

    RAISE NOTICE 'Bloque A / 55 (entidades) sembrado OK. Umbrales: %', v_thresholds::text;
END $$;
