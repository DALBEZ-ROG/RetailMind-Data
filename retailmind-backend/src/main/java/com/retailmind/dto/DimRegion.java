package com.retailmind.dto;

/**
 * Representa un registro de la tabla dim_region en ClickHouse.
 */
public class DimRegion {

    private Long   regionId;
    private String regionNombre;

    public DimRegion() {}

    public DimRegion(Long regionId, String regionNombre) {
        this.regionId = regionId;
        this.regionNombre = regionNombre;
    }

    public Long getRegionId()              { return regionId; }
    public void setRegionId(Long v)        { this.regionId = v; }

    public String getRegionNombre()        { return regionNombre; }
    public void setRegionNombre(String v)  { this.regionNombre = v; }
}
