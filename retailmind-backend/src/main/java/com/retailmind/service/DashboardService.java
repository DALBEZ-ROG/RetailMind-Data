package com.retailmind.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.dto.DashboardResumenDTO;
import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.repository.FactEventoRepository;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final FactEventoRepository factEventoRepository;
    private final JdbcTemplate         postgresJdbc;

    public DashboardService(FactEventoRepository factEventoRepository,
                            @Qualifier("jdbcTemplate") JdbcTemplate postgresJdbc) {
        this.factEventoRepository = factEventoRepository;
        this.postgresJdbc         = postgresJdbc;
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
        dto.setSesionesPorCanal(List.of());
        dto.setSesionesPorRegion(List.of());
        dto.setSesionesPorDispositivo(List.of());
        return dto;
    }

    /**
     * Refresca vistas materializadas en PostgreSQL (legacy).
     */
    @Transactional
    public String refrescarVistas() {
        try {
            postgresJdbc.execute("SELECT refresh_dashboard_views()");
            return "Vistas materializadas refrescadas exitosamente.";
        } catch (Exception e) {
            return "Error al refrescar vistas: " + e.getMessage();
        }
    }
}
