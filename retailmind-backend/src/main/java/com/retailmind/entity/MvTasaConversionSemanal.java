package com.retailmind.entity;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "mv_tasa_conversion_semanal")
public class MvTasaConversionSemanal {

    @Id
    @Column(name = "semana")
    private Integer semana;

    @Column(name = "total_sesiones")
    private Long totalSesiones;

    @Column(name = "total_conversiones")
    private Long totalConversiones;

    @Column(name = "tasa_conversion")
    private Double tasaConversion;

    public MvTasaConversionSemanal() {}

    public Integer getSemana()             { return semana; }
    public Long    getTotalSesiones()      { return totalSesiones; }
    public Long    getTotalConversiones()  { return totalConversiones; }
    public Double  getTasaConversion()     { return tasaConversion; }
}
