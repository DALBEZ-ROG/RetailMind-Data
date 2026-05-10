package com.retailmind.dto;

public class EtlResponseDTO {

    private boolean success;
    private String  mensaje;
    private String  output;
    private long    duracionSegundos;

    public EtlResponseDTO() {}

    public EtlResponseDTO(boolean success, String mensaje, String output, long duracionSegundos) {
        this.success           = success;
        this.mensaje           = mensaje;
        this.output            = output;
        this.duracionSegundos  = duracionSegundos;
    }

    public boolean isSuccess()              { return success; }
    public void    setSuccess(boolean v)    { this.success = v; }

    public String  getMensaje()             { return mensaje; }
    public void    setMensaje(String v)     { this.mensaje = v; }

    public String  getOutput()              { return output; }
    public void    setOutput(String v)      { this.output = v; }

    public long    getDuracionSegundos()    { return duracionSegundos; }
    public void    setDuracionSegundos(long v) { this.duracionSegundos = v; }
}
