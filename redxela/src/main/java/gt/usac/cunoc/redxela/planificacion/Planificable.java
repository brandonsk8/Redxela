package gt.usac.cunoc.redxela.planificacion;

/**
 * Vista mínima de un trabajo que una política de planificación necesita para
 * ordenarlo. La implementan tanto {@code Pedido} (simulación en vivo, con hilos)
 * como los trabajos del {@code nucleo.ComparadorPoliticas} (simulación de eventos
 * discretos); así ambas usan exactamente las mismas políticas.
 */
public interface Planificable {
    String id();
    /** Nivel de servicio 1 (Exprés) .. 5 (Económico). */
    int nivelNumero();
    /** Instante de llegada, en minutos simulados. */
    double llegadaMin();
    /** Total de unidades (estimación del trabajo, análoga a la ráfaga de CPU). */
    int unidades();
    /** Espera máxima tolerada por su nivel, en minutos simulados. */
    int esperaMaximaMin();
}
