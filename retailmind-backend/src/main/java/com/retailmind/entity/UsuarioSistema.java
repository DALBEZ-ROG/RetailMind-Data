package com.retailmind.entity;

/**
 * POJO para usuarios del sistema (almacenado en ClickHouse).
 * Sin anotaciones JPA.
 */
public class UsuarioSistema {

    private String id;
    private String username;
    private String password;
    private String nombre;
    private Rol rol;
    private Boolean activo;
    private String fechaCreacion;

    public enum Rol { ADMIN, CLIENTE, VIEWER }

    public UsuarioSistema() {}

    public String getId()                    { return id; }
    public void setId(String id)             { this.id = id; }

    public String getUsername()              { return username; }
    public void setUsername(String v)        { this.username = v; }

    public String getPassword()             { return password; }
    public void setPassword(String v)       { this.password = v; }

    public String getNombre()               { return nombre; }
    public void setNombre(String v)         { this.nombre = v; }

    public Rol getRol()                     { return rol; }
    public void setRol(Rol v)               { this.rol = v; }

    public Boolean getActivo()              { return activo; }
    public void setActivo(Boolean v)        { this.activo = v; }

    public String getFechaCreacion()        { return fechaCreacion; }
    public void setFechaCreacion(String v)  { this.fechaCreacion = v; }
}
