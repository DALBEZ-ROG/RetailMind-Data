package com.retailmind.service;

import com.retailmind.entity.Region;
import com.retailmind.repository.RegionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class RegionService {

    private final RegionRepository regionRepository;

    public RegionService(RegionRepository regionRepository) {
        this.regionRepository = regionRepository;
    }

    public Page<Region> findAll(Pageable pageable) {
        return regionRepository.findAll(pageable);
    }

    public Optional<Region> findById(Integer id) {
        return regionRepository.findById(id);
    }
}
