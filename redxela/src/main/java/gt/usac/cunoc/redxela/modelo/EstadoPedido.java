package gt.usac.cunoc.redxela.modelo;

/**
 * Ciclo de vida de un pedido. Es análogo al diagrama de estados de un proceso:
 * EN_ESPERA ≈ listo, ESPERANDO_* ≈ bloqueado por E/S o recurso,
 * EN_PROCESO ≈ en ejecución, COMPLETADO ≈ terminado.
 */
public enum EstadoPedido {
    EN_ESPERA("En espera"),
    ESPERANDO_STOCK("Esperando stock"),
    ESPERANDO_RECURSOS("Esperando recursos"),
    ADQUIRIENDO("Adquiriendo recursos"),
    BLOQUEADO("Bloqueado (interbloqueo)"),
    EN_PROCESO("En proceso"),
    COMPLETADO("Completado");

    private final String etiqueta;

    EstadoPedido(String etiqueta) { this.etiqueta = etiqueta; }

    public String etiqueta() { return etiqueta; }
}
