package com.retailmind.dto;

public class TasaSemanaDTO {

    private Integer semana;
    private Long    totalSesiones;
    private Long    totalConversiones;
    private Double  tasaConversion;

    public TasaSemanaDTO(Integer semana, Long totalSesiones, Long totalConversiones) {
        this.semana            = semana;
        this.totalSesiones     = totalSesiones;
        this.totalConversiones = totalConversiones;
        this.tasaConversion    = totalSesiones > 0
                ? (totalConversiones * 100.0 / totalSesiones)
                : 0.0;
    }

    public Integer getSemana()            { return semana; }
    public Long    getTotalSesiones()     { return totalSesiones; }
    public Long    getTotalConversiones() { return totalConversiones; }
    public Double  getTasaConversion()    { return tasaConversion; }
}
