package com.retailmind.entity;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "mv_resumen_dashboard")
public class MvResumenDashboard {

    @Id
    @Column(name = "total_sesiones")
    private Long totalSesiones;

    @Column(name = "total_usuarios")
    private Long totalUsuarios;

    @Column(name = "total_conversiones")
    private Long totalConversiones;

    @Column(name = "tasa_conversion")
    private Double tasaConversion;

    @Column(name = "total_abandonos")
    private Long totalAbandonos;

    public MvResumenDashboard() {}

    public Long getTotalSesiones()       { return totalSesiones; }
    public Long getTotalUsuarios()       { return totalUsuarios; }
    public Long getTotalConversiones()   { return totalConversiones; }
    public Double getTasaConversion()    { return tasaConversion; }
    public Long getTotalAbandonos()      { return totalAbandonos; }
}
