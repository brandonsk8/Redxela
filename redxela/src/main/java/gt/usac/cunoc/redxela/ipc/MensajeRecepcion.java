package gt.usac.cunoc.redxela.ipc;

import gt.usac.cunoc.redxela.modelo.Producto;

/**
 * Mensaje que Recepción envía a Inventario: "llegó esta mercadería, en esta cantidad".
 * Es inmutable, por lo que pasar la referencia de un hilo a otro a través del buffer
 * es seguro (el semáforo del buffer establece la relación happens-before).
 *
 * @param camionId camión del que proviene
 * @param producto producto recibido
 * @param cantidad unidades recibidas
 * @param enviadoMs instante en que Recepción lo depositó
 */
public record MensajeRecepcion(String camionId, Producto producto, int cantidad, long enviadoMs) {
}
