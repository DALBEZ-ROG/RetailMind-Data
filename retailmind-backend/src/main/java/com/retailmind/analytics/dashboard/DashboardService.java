package com.retailmind.analytics.dashboard;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.dto.DashboardResumenDTO;
import com.retailmind.dto.FactEventoRepository;
import com.retailmind.dto.GrupoConteoDTO;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final FactEventoRepository factEventoRepository;

    public DashboardService(FactEventoRepository factEventoRepository) {
        this.factEventoRepository = factEventoRepository;
    }

    /**
     * Obtiene el resumen del dashboard desde ClickHouse.
     * Fallback a valores vacíos si ClickHouse no está disponible.
     */
    public DashboardResumenDTO getResumen() {
        try {
            return getResumenFromClickHouse();
        } catch (Exception e) {
            logger.warn("Error al consultar ClickHouse para dashboard: {}", e.getMessage());
            return getResumenVacio();
        }
    }

    private DashboardResumenDTO getResumenFromClickHouse() {
        long totalSesiones     = factEventoRepository.countDistinctSesiones();
        long totalUsuarios     = factEventoRepository.countDistinctUsuarios();
        long totalConversiones = factEventoRepository.countConversiones();
        long totalAbandonos    = factEventoRepository.countAbandonos();
        long totalEventos      = factEventoRepository.countTotalEventos();
        int  semanasCargadas   = factEventoRepository.countDistinctSemanas();

        double tasaConversion = totalSesiones > 0
                ? Math.round((totalConversiones * 100.0 / totalSesiones) * 100.0) / 100.0
                : 0.0;

        List<GrupoConteoDTO> porCanal       = factEventoRepository.countSesionesPorCanal();
        List<GrupoConteoDTO> porRegion      = factEventoRepository.countSesionesPorRegion();
        List<GrupoConteoDTO> porDispositivo = factEventoRepository.countSesionesPorDispositivo();

        DashboardResumenDTO dto = new DashboardResumenDTO();
        dto.setTotalSesiones(totalSesiones);
        dto.setTotalUsuarios(totalUsuarios);
        dto.setTotalConversiones(totalConversiones);
        dto.setTasaConversion(tasaConversion);
        dto.setTotalAbandonos(totalAbandonos);
        dto.setTotalEventos(totalEventos);
        dto.setSemanasCargadas(semanasCargadas);
        dto.setSesionesPorCanal(porCanal);
        dto.setSesionesPorRegion(porRegion);
        dto.setSesionesPorDispositivo(porDispositivo);
        return dto;
    }

    private DashboardResumenDTO getResumenVacio() {
        DashboardResumenDTO dto = new DashboardResumenDTO();
        dto.setTotalSesiones(0L);
        dto.setTotalUsuarios(0L);
        dto.setTotalConversiones(0L);
        dto.setTasaConversion(0.0);
        dto.setTotalAbandonos(0L);
        dto.setTotalEventos(0L);
        dto.setSemanasCargadas(0);
        dto.setMensaje("Sistema vacio - ejecute la carga inicial desde el modulo de Inicializacion");
        dto.setSesionesPorCanal(List.of());
        dto.setSesionesPorRegion(List.of());
        dto.setSesionesPorDispositivo(List.of());
        return dto;
    }

    /**
     * En la arquitectura con ClickHouse, los datos se consultan en tiempo real.
     * No se necesitan vistas materializadas.
     */
    @Transactional
    public String refrescarVistas() {
        return "Datos actualizados correctamente. ClickHouse consulta en tiempo real.";
    }
}
