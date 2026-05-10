package com.retailmind.dto;

public class GrupoConteoDTO {

    private String nombre;
    private Long total;

    public GrupoConteoDTO(String nombre, Long total) {
        this.nombre = nombre;
        this.total  = total;
    }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public Long getTotal() { return total; }
    public void setTotal(Long total) { this.total = total; }
}
