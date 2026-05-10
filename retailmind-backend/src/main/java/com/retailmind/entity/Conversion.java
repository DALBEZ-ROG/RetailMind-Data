package com.retailmind.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversiones")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Conversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversion_id")
    private Long conversionId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "session_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler",
                            "usuario", "canal", "fuenteTrafico"})
    private Sesion sesion;

    @Column(name = "is_conversion")
    private Boolean isConversion;

    @Column(name = "drop_off_flag")
    private Boolean dropOffFlag;

    @Column(name = "conversion_time")
    private LocalDateTime conversionTime;

    public Conversion() {}

    public Long getConversionId() { return conversionId; }
    public void setConversionId(Long conversionId) { this.conversionId = conversionId; }

    public Sesion getSesion() { return sesion; }
    public void setSesion(Sesion sesion) { this.sesion = sesion; }

    public Boolean getIsConversion() { return isConversion; }
    public void setIsConversion(Boolean isConversion) { this.isConversion = isConversion; }

    public Boolean getDropOffFlag() { return dropOffFlag; }
    public void setDropOffFlag(Boolean dropOffFlag) { this.dropOffFlag = dropOffFlag; }

    public LocalDateTime getConversionTime() { return conversionTime; }
    public void setConversionTime(LocalDateTime conversionTime) { this.conversionTime = conversionTime; }
}
