package com.retailmind.dto;

public class EstadoTablasDTO {

    private String tabla;
    private long   totalRegistros;
    private String ultimaActualizacion;

    public EstadoTablasDTO() {}

    public EstadoTablasDTO(String tabla, long totalRegistros, String ultimaActualizacion) {
        this.tabla               = tabla;
        this.totalRegistros      = totalRegistros;
        this.ultimaActualizacion = ultimaActualizacion;
    }

    public String getTabla()                    { return tabla; }
    public void   setTabla(String v)            { this.tabla = v; }

    public long   getTotalRegistros()           { return totalRegistros; }
    public void   setTotalRegistros(long v)     { this.totalRegistros = v; }

    public String getUltimaActualizacion()      { return ultimaActualizacion; }
    public void   setUltimaActualizacion(String v) { this.ultimaActualizacion = v; }
}
