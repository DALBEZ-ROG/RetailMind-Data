package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "eventos")
@Getter @Setter @NoArgsConstructor
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "evento_id")
    private Long eventoId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Sesion sesion;

    @Column(name = "event_index")
    private Integer eventIndex;

    @Column(name = "user_action", length = 100)
    private String userAction;

    @Column(name = "time_spent_sec")
    private Double timeSpentSec;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Producto producto;
}
