package com.retailmind.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "regiones")
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "region_id")
    private Integer regionId;

    @Column(name = "region_name", nullable = false, unique = true, length = 100)
    private String regionName;

    @Column(name = "country", length = 100)
    private String country;

    public Region() {}

    public Integer getRegionId() { return regionId; }
    public void setRegionId(Integer regionId) { this.regionId = regionId; }

    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
}
