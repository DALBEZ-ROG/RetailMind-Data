package com.retailmind.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "canales")
@Getter @Setter @NoArgsConstructor
public class Canal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "channel_id")
    private Integer channelId;

    @Column(name = "channel_name", nullable = false, unique = true, length = 100)
    private String channelName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
}
