package com.retailmind.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retailmind.dto.DashboardResumenDTO;
import com.retailmind.repository.ConversionRepository;
import com.retailmind.repository.SesionRepository;
import com.retailmind.repository.UsuarioRepository;

@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final SesionRepository     sesionRepository;
    private final UsuarioRepository    usuarioRepository;
    private final ConversionRepository conversionRepository;
    private final SesionService        sesionService;

    public DashboardService(SesionRepository sesionRepository,
                            UsuarioRepository usuarioRepository,
                            ConversionRepository conversionRepository,
                            SesionService sesionService) {
        this.sesionRepository     = sesionRepository;
        this.usuarioRepository    = usuarioRepository;
        this.conversionRepository = conversionRepository;
        this.sesionService        = sesionService;
    }

    public DashboardResumenDTO getResumen() {
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
}
