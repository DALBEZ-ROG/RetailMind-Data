package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "sesiones")
@Getter @Setter @NoArgsConstructor
public class Sesion {

    @Id
    @Column(name = "session_id", length = 100)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Usuario usuario;

    @Column(name = "timestamp_utc")
    private LocalDateTime timestampUtc;

    @Column(name = "session_length")
    private Double sessionLength;

    @Column(name = "interaction_count")
    private Integer interactionCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "channel_id")
    private Canal canal;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id")
    private FuenteTrafico fuenteTrafico;
}
