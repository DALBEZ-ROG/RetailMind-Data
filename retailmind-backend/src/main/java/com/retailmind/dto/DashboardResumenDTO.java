package com.retailmind.dto;

import java.util.List;

public class DashboardResumenDTO {

    private Long   totalSesiones;
    private Long   totalUsuarios;
    private Long   totalConversiones;
    private Double tasaConversion;
    private Long   totalAbandonos;

    private List<GrupoConteoDTO> sesionesPorCanal;
    private List<GrupoConteoDTO> sesionesPorRegion;
    private List<GrupoConteoDTO> sesionesPorDispositivo;

    public Long getTotalSesiones()   { return totalSesiones; }
    public void setTotalSesiones(Long v)   { this.totalSesiones = v; }

    public Long getTotalUsuarios()   { return totalUsuarios; }
    public void setTotalUsuarios(Long v)   { this.totalUsuarios = v; }

    public Long getTotalConversiones() { return totalConversiones; }
    public void setTotalConversiones(Long v) { this.totalConversiones = v; }

    public Double getTasaConversion() { return tasaConversion; }
    public void setTasaConversion(Double v) { this.tasaConversion = v; }

    public Long getTotalAbandonos()  { return totalAbandonos; }
    public void setTotalAbandonos(Long v)  { this.totalAbandonos = v; }

    public List<GrupoConteoDTO> getSesionesPorCanal() { return sesionesPorCanal; }
    public void setSesionesPorCanal(List<GrupoConteoDTO> v) { this.sesionesPorCanal = v; }

    public List<GrupoConteoDTO> getSesionesPorRegion() { return sesionesPorRegion; }
    public void setSesionesPorRegion(List<GrupoConteoDTO> v) { this.sesionesPorRegion = v; }

    public List<GrupoConteoDTO> getSesionesPorDispositivo() { return sesionesPorDispositivo; }
    public void setSesionesPorDispositivo(List<GrupoConteoDTO> v) { this.sesionesPorDispositivo = v; }
}
