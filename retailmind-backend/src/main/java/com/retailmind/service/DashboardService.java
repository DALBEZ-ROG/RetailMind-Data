package com.retailmind.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.dto.DashboardResumenDTO;
import com.retailmind.dto.GrupoConteoDTO;
import com.retailmind.entity.MvResumenDashboard;
import com.retailmind.repository.ConversionRepository;
import com.retailmind.repository.MvResumenDashboardRepository;
import com.retailmind.repository.MvSesionesPorCanalRepository;
import com.retailmind.repository.MvTasaConversionSemanalRepository;
import com.retailmind.repository.SesionRepository;
import com.retailmind.repository.UsuarioRepository;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final MvResumenDashboardRepository       mvResumenRepo;
    private final MvSesionesPorCanalRepository       mvCanalRepo;
    private final MvTasaConversionSemanalRepository  mvTasaRepo;
    private final SesionRepository                   sesionRepository;
    private final UsuarioRepository                  usuarioRepository;
    private final ConversionRepository               conversionRepository;
    private final SesionService                      sesionService;
    private final JdbcTemplate                       jdbc;

    public DashboardService(MvResumenDashboardRepository mvResumenRepo,
                            MvSesionesPorCanalRepository mvCanalRepo,
                            MvTasaConversionSemanalRepository mvTasaRepo,
                            SesionRepository sesionRepository,
                            UsuarioRepository usuarioRepository,
                            ConversionRepository conversionRepository,
                            SesionService sesionService,
                            JdbcTemplate jdbc) {
        this.mvResumenRepo       = mvResumenRepo;
        this.mvCanalRepo         = mvCanalRepo;
        this.mvTasaRepo          = mvTasaRepo;
        this.sesionRepository    = sesionRepository;
        this.usuarioRepository   = usuarioRepository;
        this.conversionRepository = conversionRepository;
        this.sesionService       = sesionService;
        this.jdbc                = jdbc;
    }

    /**
     * Obtiene el resumen del dashboard.
     * Intenta usar vistas materializadas; si no existen, usa consultas directas.
     */
    public DashboardResumenDTO getResumen() {
        try {
            return getResumenFromViews();
        } catch (Exception e) {
            // Fallback: vistas no existen aun
            return getResumenDirect();
        }
    }

    private DashboardResumenDTO getResumenFromViews() {
        List<MvResumenDashboard> resumenList = mvResumenRepo.findAll();
        if (resumenList.isEmpty()) throw new RuntimeException("Vista vacia");

        MvResumenDashboard mv = resumenList.get(0);

        List<GrupoConteoDTO> porCanal = mvCanalRepo.findAllByOrderByTotalDesc().stream()
                .map(r -> new GrupoConteoDTO(r.getNombre(), r.getTotal()))
                .collect(Collectors.toList());

        // Reutilizar las consultas directas para region y dispositivo
        // (o crear vistas adicionales si se necesita)
        List<GrupoConteoDTO> porRegion      = sesionService.countPorRegion();
        List<GrupoConteoDTO> porDispositivo = sesionService.countPorDispositivo();

        DashboardResumenDTO dto = new DashboardResumenDTO();
        dto.setTotalSesiones(mv.getTotalSesiones());
        dto.setTotalUsuarios(mv.getTotalUsuarios());
        dto.setTotalConversiones(mv.getTotalConversiones());
        dto.setTasaConversion(mv.getTasaConversion());
        dto.setTotalAbandonos(mv.getTotalAbandonos());
        dto.setSesionesPorCanal(porCanal);
        dto.setSesionesPorRegion(porRegion);
        dto.setSesionesPorDispositivo(porDispositivo);
        return dto;
    }

    private DashboardResumenDTO getResumenDirect() {
        long totalSesiones     = sesionRepository.count();
        long totalUsuarios     = usuarioRepository.count();
        long totalConversiones = conversionRepository.countByIsConversion(true);
        long totalAbandonos    = conversionRepository.countByIsConversion(false);
        double tasaConversion  = totalSesiones > 0
                ? (totalConversiones * 100.0 / totalSesiones)
                : 0.0;

        DashboardResumenDTO dto = new DashboardResumenDTO();
        dto.setTotalSesiones(totalSesiones);
        dto.setTotalUsuarios(totalUsuarios);
        dto.setTotalConversiones(totalConversiones);
        dto.setTasaConversion(Math.round(tasaConversion * 100.0) / 100.0);
        dto.setTotalAbandonos(totalAbandonos);
        dto.setSesionesPorCanal(sesionService.countPorCanal());
        dto.setSesionesPorRegion(sesionService.countPorRegion());
        dto.setSesionesPorDispositivo(sesionService.countPorDispositivo());
        return dto;
    }

    /**
     * Refresca todas las vistas materializadas del dashboard.
     */
    @Transactional
    public String refrescarVistas() {
        try {
            jdbc.execute("SELECT refresh_dashboard_views()");
            return "Vistas materializadas refrescadas exitosamente.";
        } catch (Exception e) {
            return "Error al refrescar vistas: " + e.getMessage();
        }
    }
}
