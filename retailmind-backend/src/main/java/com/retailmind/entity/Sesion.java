package com.retailmind.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "sesiones")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Sesion {

    @Id
    @Column(name = "session_id", length = 100)
    private String sessionId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "region", "dispositivo"})
    private Usuario usuario;

    @Column(name = "timestamp_utc")
    private LocalDateTime timestampUtc;

    @Column(name = "session_length")
    private Double sessionLength;

    @Column(name = "interaction_count")
    private Integer interactionCount;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "channel_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private Canal canal;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "source_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    private FuenteTrafico fuenteTrafico;

    public Sesion() {}

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }

    public LocalDateTime getTimestampUtc() { return timestampUtc; }
    public void setTimestampUtc(LocalDateTime timestampUtc) { this.timestampUtc = timestampUtc; }

    public Double getSessionLength() { return sessionLength; }
    public void setSessionLength(Double sessionLength) { this.sessionLength = sessionLength; }

    public Integer getInteractionCount() { return interactionCount; }
    public void setInteractionCount(Integer interactionCount) { this.interactionCount = interactionCount; }

    public Canal getCanal() { return canal; }
    public void setCanal(Canal canal) { this.canal = canal; }

    public FuenteTrafico getFuenteTrafico() { return fuenteTrafico; }
    public void setFuenteTrafico(FuenteTrafico fuenteTrafico) { this.fuenteTrafico = fuenteTrafico; }
}
