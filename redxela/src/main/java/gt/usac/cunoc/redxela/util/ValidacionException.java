package gt.usac.cunoc.redxela.util;

/**
 * Error de datos de entrada (formulario incompleto, cantidad inválida, id que no
 * existe...). La capa web lo traduce a una respuesta HTTP 400 con el mensaje para
 * el usuario, en lugar de dejar que la excepción tumbe el hilo del servidor.
 */
public class ValidacionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int codigoHttp;

    public ValidacionException(String mensaje) {
        this(mensaje, 400);
    }

    public ValidacionException(String mensaje, int codigoHttp) {
        super(mensaje);
        this.codigoHttp = codigoHttp;
    }

    public int codigoHttp() { return codigoHttp; }

    public static ValidacionException noEncontrado(String que) {
        return new ValidacionException(que + " no existe", 404);
    }
}
