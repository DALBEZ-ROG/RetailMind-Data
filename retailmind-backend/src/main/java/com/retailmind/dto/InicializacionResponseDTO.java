package com.retailmind.dto;

public class InicializacionResponseDTO {

    private boolean success;
    private String  mensaje;
    private String  output;
    private long    duracionSegundos;
    private long    registrosCargados;

    public InicializacionResponseDTO() {}

    public InicializacionResponseDTO(boolean success, String mensaje, String output,
                                     long duracionSegundos, long registrosCargados) {
        this.success           = success;
        this.mensaje           = mensaje;
        this.output            = output;
        this.duracionSegundos  = duracionSegundos;
        this.registrosCargados = registrosCargados;
    }

    public boolean isSuccess()                    { return success; }
    public void    setSuccess(boolean v)          { this.success = v; }

    public String  getMensaje()                   { return mensaje; }
    public void    setMensaje(String v)           { this.mensaje = v; }

    public String  getOutput()                    { return output; }
    public void    setOutput(String v)            { this.output = v; }

    public long    getDuracionSegundos()          { return duracionSegundos; }
    public void    setDuracionSegundos(long v)    { this.duracionSegundos = v; }

    public long    getRegistrosCargados()         { return registrosCargados; }
    public void    setRegistrosCargados(long v)   { this.registrosCargados = v; }
}
