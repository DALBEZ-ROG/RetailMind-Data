package com.retailmind.service;

import com.retailmind.entity.Conversion;
import com.retailmind.repository.ConversionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConversionService {

    private final ConversionRepository conversionRepository;

    public Page<Conversion> findAll(Pageable pageable) {
        return conversionRepository.findAll(pageable);
    }

    public Optional<Conversion> findById(Long id) {
        return conversionRepository.findById(id);
    }

    public Map<String, Long> getResumen() {
        long convertidas    = conversionRepository.countByIsConversion(true);
        long noConvertidas  = conversionRepository.countByIsConversion(false);
        long total          = convertidas + noConvertidas;

        Map<String, Long> resumen = new HashMap<>();
        resumen.put("conversiones",   convertidas);
        resumen.put("noConversiones", noConvertidas);
        resumen.put("total",          total);
        return resumen;
    }
}
