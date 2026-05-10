package com.retailmind.service;

import com.retailmind.dto.TasaSemanaDTO;
import com.retailmind.entity.Conversion;
import com.retailmind.repository.ConversionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ConversionService {

    private final ConversionRepository conversionRepository;

    public ConversionService(ConversionRepository conversionRepository) {
        this.conversionRepository = conversionRepository;
    }

    public Page<Conversion> findAll(Pageable pageable) {
        return conversionRepository.findAll(pageable);
    }

    public Optional<Conversion> findById(Integer id) {
        return conversionRepository.findById(id);
    }

    public Map<String, Long> getResumen() {
        long convertidas   = conversionRepository.countByIsConversion(true);
        long noConvertidas = conversionRepository.countByIsConversion(false);
        long total         = convertidas + noConvertidas;

        Map<String, Long> resumen = new HashMap<>();
        resumen.put("conversiones",   convertidas);
        resumen.put("noConversiones", noConvertidas);
        resumen.put("total",          total);
        return resumen;
    }

    public List<TasaSemanaDTO> getTasaPorSemana() {
        return conversionRepository.tasaPorSemana().stream()
                .map(row -> new TasaSemanaDTO(
                        ((Number) row[0]).intValue(),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).longValue()))
                .collect(Collectors.toList());
    }
}
