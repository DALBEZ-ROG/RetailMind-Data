package com.retailmind.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "usuarios_sistema")
public class UsuarioSistema {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String nombre;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Rol rol;

    @Column(nullable = false)
    private Boolean activo = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum Rol { ADMIN, VIEWER }

    public UsuarioSistema() {}

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(); }

    public Long getId()                    { return id; }
    public void setId(Long id)             { this.id = id; }

    public String getUsername()            { return username; }
    public void setUsername(String v)      { this.username = v; }

    public String getPassword()            { return password; }
    public void setPassword(String v)      { this.password = v; }

    public String getNombre()              { return nombre; }
    public void setNombre(String v)        { this.nombre = v; }

    public Rol getRol()                    { return rol; }
    public void setRol(Rol v)              { this.rol = v; }

    public Boolean getActivo()             { return activo; }
    public void setActivo(Boolean v)       { this.activo = v; }

    public LocalDateTime getCreatedAt()    { return createdAt; }
    public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
