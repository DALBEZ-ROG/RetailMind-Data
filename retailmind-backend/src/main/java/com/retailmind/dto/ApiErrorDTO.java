package com.retailmind.dto;

import java.time.LocalDateTime;

public class ApiErrorDTO {
    private String timestamp;
    private int    status;
    private String error;
    private String mensaje;
    private String path;

    public ApiErrorDTO(int status, String error, String mensaje, String path) {
        this.timestamp = LocalDateTime.now().toString();
        this.status    = status;
        this.error     = error;
        this.mensaje   = mensaje;
        this.path      = path;
    }

    public String getTimestamp() { return timestamp; }
    public int    getStatus()   { return status; }
    public String getError()    { return error; }
    public String getMensaje()  { return mensaje; }
    public String getPath()     { return path; }
}
