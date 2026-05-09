package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "regiones")
@Getter @Setter @NoArgsConstructor
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "region_id")
    private Integer regionId;

    @Column(name = "region_name", nullable = false, unique = true, length = 100)
    private String regionName;

    @Column(name = "country", length = 100)
    private String country;
}
