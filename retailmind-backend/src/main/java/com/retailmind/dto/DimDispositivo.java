package com.retailmind.dto;

/**
 * Representa un registro de la tabla dim_dispositivo en ClickHouse.
 */
public class DimDispositivo {

    private Long   dispositivoId;
    private String dispositivoNombre;

    public DimDispositivo() {}

    public DimDispositivo(Long dispositivoId, String dispositivoNombre) {
        this.dispositivoId = dispositivoId;
        this.dispositivoNombre = dispositivoNombre;
    }

    public Long getDispositivoId()              { return dispositivoId; }
    public void setDispositivoId(Long v)        { this.dispositivoId = v; }

    public String getDispositivoNombre()        { return dispositivoNombre; }
    public void setDispositivoNombre(String v)  { this.dispositivoNombre = v; }
}
