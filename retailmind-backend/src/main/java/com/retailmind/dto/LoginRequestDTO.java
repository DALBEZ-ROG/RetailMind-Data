package com.retailmind.dto;

public class LoginRequestDTO {
    private String username;
    private String password;

    public LoginRequestDTO() {}

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }

    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
}
