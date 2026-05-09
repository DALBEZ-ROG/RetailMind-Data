package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "fuentes_trafico")
@Getter @Setter @NoArgsConstructor
public class FuenteTrafico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "source_name", nullable = false, unique = true, length = 100)
    private String sourceName;

    @Column(name = "type", length = 50)
    private String type;
}
