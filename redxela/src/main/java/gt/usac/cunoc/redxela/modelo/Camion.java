package gt.usac.cunoc.redxela.modelo;

/**
 * Camión que llega al área de Recepción con mercadería nueva.
 *
 * @param id        identificador (p. ej. "R-0007")
 * @param producto  producto que trae
 * @param cantidad  unidades recibidas
 * @param anden     índice del andén asignado por patio (0 o 1); se usa cuando la
 *                  descarga se hace en modo incremental (sin prevención)
 * @param llegadaMs instante de llegada
 * @param manual    true si fue registrado desde la interfaz
 */
public record Camion(String id, Producto producto, int cantidad, int anden, long llegadaMs, boolean manual) {
    public Camion {
        if (producto == null) throw new IllegalArgumentException("El camión debe traer un producto");
        if (cantidad <= 0) throw new IllegalArgumentException("La cantidad recibida debe ser mayor que cero");
    }
}
