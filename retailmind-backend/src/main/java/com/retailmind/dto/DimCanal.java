package com.retailmind.dto;

/**
 * Representa un registro de la tabla dim_canal en ClickHouse.
 */
public class DimCanal {

    private Long   canalId;
    private String canalNombre;

    public DimCanal() {}

    public DimCanal(Long canalId, String canalNombre) {
        this.canalId = canalId;
        this.canalNombre = canalNombre;
    }

    public Long getCanalId()              { return canalId; }
    public void setCanalId(Long v)        { this.canalId = v; }

    public String getCanalNombre()        { return canalNombre; }
    public void setCanalNombre(String v)  { this.canalNombre = v; }
}
