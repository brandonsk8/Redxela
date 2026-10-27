package gt.usac.cunoc.redxela.modelo;

/**
 * Cuenta de cliente. Al crear la cuenta el cliente elige el nivel de servicio que
 * desea recibir; todos sus pedidos heredan ese nivel.
 *
 * @param id     identificador (p. ej. "C-001")
 * @param nombre nombre del cliente
 * @param correo correo de contacto
 * @param nivel  nivel de servicio contratado
 */
public record Cliente(String id, String nombre, String correo, NivelServicio nivel) {
}
