package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dispositivos")
@Getter @Setter @NoArgsConstructor
public class Dispositivo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "device_type_id")
    private Integer deviceTypeId;

    @Column(name = "device_type_name", nullable = false, unique = true, length = 100)
    private String deviceTypeName;
}
