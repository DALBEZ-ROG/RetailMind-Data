package com.retailmind.dto;

public class LoginResponseDTO {
    private String token;
    private String refreshToken;
    private String username;
    private String nombre;
    private String rol;
    private long   expiresIn;

    public LoginResponseDTO() {}

    public LoginResponseDTO(String token, String refreshToken, String username,
                             String nombre, String rol, long expiresIn) {
        this.token        = token;
        this.refreshToken = refreshToken;
        this.username     = username;
        this.nombre       = nombre;
        this.rol          = rol;
        this.expiresIn    = expiresIn;
    }

    public String getToken()         { return token; }
    public String getRefreshToken()  { return refreshToken; }
    public String getUsername()      { return username; }
    public String getNombre()        { return nombre; }
    public String getRol()           { return rol; }
    public long   getExpiresIn()     { return expiresIn; }
}
