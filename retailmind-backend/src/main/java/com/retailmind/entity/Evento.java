package com.retailmind.entity;

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
@Table(name = "eventos")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evento_id")
    private Long eventoId;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "session_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler",
                            "usuario", "canal", "fuenteTrafico"})
    private Sesion sesion;

    @Column(name = "event_index")
    private Integer eventIndex;

    @Column(name = "user_action", length = 100)
    private String userAction;

    @Column(name = "time_spent_sec")
    private Double timeSpentSec;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_id")
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "categoria"})
    private Producto producto;

    public Evento() {}

    public Long getEventoId() { return eventoId; }
    public void setEventoId(Long eventoId) { this.eventoId = eventoId; }

    public Sesion getSesion() { return sesion; }
    public void setSesion(Sesion sesion) { this.sesion = sesion; }

    public Integer getEventIndex() { return eventIndex; }
    public void setEventIndex(Integer eventIndex) { this.eventIndex = eventIndex; }

    public String getUserAction() { return userAction; }
    public void setUserAction(String userAction) { this.userAction = userAction; }

    public Double getTimeSpentSec() { return timeSpentSec; }
    public void setTimeSpentSec(Double timeSpentSec) { this.timeSpentSec = timeSpentSec; }

    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
}
