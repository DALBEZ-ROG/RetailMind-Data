package com.retailmind.controller;

import com.retailmind.entity.Region;
import com.retailmind.service.RegionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/regiones")
public class RegionController {

    private final RegionService regionService;

    public RegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    @GetMapping
    public ResponseEntity<Page<Region>> findAll(
            @PageableDefault(size = 20, sort = "regionId") Pageable pageable) {
        return ResponseEntity.ok(regionService.findAll(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Region> findById(@PathVariable Integer id) {
        return regionService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
