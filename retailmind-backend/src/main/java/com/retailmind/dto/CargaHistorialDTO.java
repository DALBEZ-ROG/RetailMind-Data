package com.retailmind.dto;

public class CargaHistorialDTO {

    private Integer semana;
    private String  fechaCarga;
    private Integer registrosProcesados;
    private Integer registrosNuevos;

    public CargaHistorialDTO() {}

    public CargaHistorialDTO(Integer semana, String fechaCarga,
                              Integer registrosProcesados, Integer registrosNuevos) {
        this.semana               = semana;
        this.fechaCarga           = fechaCarga;
        this.registrosProcesados  = registrosProcesados;
        this.registrosNuevos      = registrosNuevos;
    }

    public Integer getSemana()                      { return semana; }
    public void    setSemana(Integer v)             { this.semana = v; }

    public String  getFechaCarga()                  { return fechaCarga; }
    public void    setFechaCarga(String v)          { this.fechaCarga = v; }

    public Integer getRegistrosProcesados()         { return registrosProcesados; }
    public void    setRegistrosProcesados(Integer v){ this.registrosProcesados = v; }

    public Integer getRegistrosNuevos()             { return registrosNuevos; }
    public void    setRegistrosNuevos(Integer v)    { this.registrosNuevos = v; }
}
