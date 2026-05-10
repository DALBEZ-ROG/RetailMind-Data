package com.retailmind.entity;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "mv_sesiones_por_canal")
public class MvSesionesPorCanal {

    @Id
    @Column(name = "nombre")
    private String nombre;

    @Column(name = "total")
    private Long total;

    public MvSesionesPorCanal() {}

    public String getNombre() { return nombre; }
    public Long   getTotal()  { return total; }
}
