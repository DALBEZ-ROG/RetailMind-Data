package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversiones")
@Getter @Setter @NoArgsConstructor
public class Conversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversion_id")
    private Long conversionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private Sesion sesion;

    @Column(name = "is_conversion")
    private Boolean isConversion;

    @Column(name = "drop_off_flag")
    private Boolean dropOffFlag;

    @Column(name = "conversion_time")
    private LocalDateTime conversionTime;
}
