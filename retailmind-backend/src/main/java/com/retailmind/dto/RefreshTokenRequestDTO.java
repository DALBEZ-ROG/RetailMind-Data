package com.retailmind.dto;

public class RefreshTokenRequestDTO {
    private String refreshToken;

    public RefreshTokenRequestDTO() {}

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String v) { this.refreshToken = v; }
}
