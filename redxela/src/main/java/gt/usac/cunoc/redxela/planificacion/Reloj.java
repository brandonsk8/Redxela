package gt.usac.cunoc.redxela.planificacion;

import gt.usac.cunoc.redxela.modelo.NivelServicio;

/**
 * Reloj de la simulación en <b>minutos simulados</b> contados desde que arrancó el
 * programa (1 minuto simulado = 1 segundo real). Usar un origen propio en lugar del
 * epoch de Unix mantiene los puntajes de planificación en números pequeños y legibles
 * ("llegó en el minuto 42.5").
 */
public final class Reloj {

    /** Instante real en que arrancó la simulación. */
    public static final long INICIO_MS = System.currentTimeMillis();

    private Reloj() {}

    /** Convierte un instante real (ms) a minuto simulado. */
    public static double aMinutos(long instanteMs) {
        return (instanteMs - INICIO_MS) / (double) NivelServicio.MS_POR_MINUTO_SIMULADO;
    }

    /** Minuto simulado actual. */
    public static double ahora() {
        return aMinutos(System.currentTimeMillis());
    }
}
