package com.retailmind.auth;

/**
 * Intento de login rechazado, con el MOTIVO detallado para la bitácora
 * (log_acceso). El motivo NUNCA viaja al cliente: AuthController devuelve
 * siempre el mismo 401 genérico ("Credenciales incorrectas"), tanto si el
 * correo no existe como si la contraseña es incorrecta. El motivo (y el
 * usuario, cuando se identificó) solo alimentan el registro de acceso.
 */
public class LoginFallidoException extends RuntimeException {

    /** Espeja los valores de log_acceso.motivo_fallo. */
    public static final String EMAIL_NO_REGISTRADO = "email_no_registrado";
    public static final String PASSWORD_INCORRECTO = "password_incorrecto";
    public static final String USUARIO_INACTIVO    = "usuario_inactivo";
    public static final String FUERA_HORARIO       = "fuera_horario";

    private final String motivo;
    private final Long usuarioId;

    public LoginFallidoException(String motivo, Long usuarioId) {
        super("Intento de acceso rechazado: " + motivo);
        this.motivo = motivo;
        this.usuarioId = usuarioId;
    }

    public String getMotivo()   { return motivo; }
    public Long getUsuarioId()  { return usuarioId; }
}
