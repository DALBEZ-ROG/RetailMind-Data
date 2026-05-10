package com.retailmind.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "fuentes_trafico")
public class FuenteTrafico {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "source_name", nullable = false, unique = true, length = 100)
    private String sourceName;

    @Column(name = "type", length = 50)
    private String type;

    public FuenteTrafico() {}

    public Integer getSourceId() { return sourceId; }
    public void setSourceId(Integer sourceId) { this.sourceId = sourceId; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
