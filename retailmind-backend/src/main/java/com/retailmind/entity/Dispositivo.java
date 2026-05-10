package com.retailmind.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "dispositivos")
public class Dispositivo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_type_id")
    private Integer deviceTypeId;

    @Column(name = "device_type_name", nullable = false, unique = true, length = 100)
    private String deviceTypeName;

    public Dispositivo() {}

    public Integer getDeviceTypeId() { return deviceTypeId; }
    public void setDeviceTypeId(Integer deviceTypeId) { this.deviceTypeId = deviceTypeId; }

    public String getDeviceTypeName() { return deviceTypeName; }
    public void setDeviceTypeName(String deviceTypeName) { this.deviceTypeName = deviceTypeName; }
}
