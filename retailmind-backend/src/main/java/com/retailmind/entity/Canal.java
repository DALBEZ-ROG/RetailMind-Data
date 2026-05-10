package com.retailmind.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "canales")
public class Canal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "channel_id")
    private Integer channelId;

    @Column(name = "channel_name", nullable = false, unique = true, length = 100)
    private String channelName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    public Canal() {}

    public Integer getChannelId() { return channelId; }
    public void setChannelId(Integer channelId) { this.channelId = channelId; }

    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
