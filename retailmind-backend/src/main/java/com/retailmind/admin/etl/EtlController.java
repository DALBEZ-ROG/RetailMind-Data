package com.retailmind.admin.etl;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.retailmind.dto.CargaHistorialDTO;
import com.retailmind.dto.EstadoTablasDTO;
import com.retailmind.dto.EtlResponseDTO;

@RestController
@RequestMapping("/api/etl")
public class EtlController {

    private final EtlService etlService;

    public EtlController(EtlService etlService) {
        this.etlService = etlService;
    }

    /**
     * POST /api/etl/upload-csv
     * Recibe el archivo CSV y lo guarda en la carpeta del proyecto ETL.
     */
    @PostMapping(value = "/upload-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EtlResponseDTO> uploadCsv(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new EtlResponseDTO(false, "El archivo esta vacio.", "", 0));
        }
        return ResponseEntity.ok(etlService.guardarCsv(file));
    }

    /**
     * POST /api/etl/cargar-staging
     * Ejecuta el script Python que carga el CSV a dataset_temporal.
     */
    @PostMapping("/cargar-staging")
    public ResponseEntity<EtlResponseDTO> cargarStaging() {
        return ResponseEntity.ok(etlService.cargarStaging());
    }

    /**
     * POST /api/etl/ejecutar-etl
     * Ejecuta 05_load_incremental.py.
     */
    @PostMapping("/ejecutar-etl")
    public ResponseEntity<EtlResponseDTO> ejecutarEtl() {
        return ResponseEntity.ok(etlService.ejecutarEtlIncremental());
    }

    /**
     * POST /api/etl/ejecutar-completo
     * Ejecuta staging + incremental en secuencia.
     */
    @PostMapping("/ejecutar-completo")
    public ResponseEntity<EtlResponseDTO> ejecutarCompleto() {
        return ResponseEntity.ok(etlService.ejecutarCompleto());
    }

    /**
     * GET /api/etl/historial
     * Devuelve los registros de carga_historial.
     */
    @GetMapping("/historial")
    public ResponseEntity<List<CargaHistorialDTO>> getHistorial() {
        return ResponseEntity.ok(etlService.getHistorial());
    }

    /**
     * GET /api/etl/estado-tablas
     * Devuelve el conteo de registros de cada tabla normalizada.
     */
    @GetMapping("/estado-tablas")
    public ResponseEntity<List<EstadoTablasDTO>> getEstadoTablas() {
        return ResponseEntity.ok(etlService.getEstadoTablas());
    }
}
