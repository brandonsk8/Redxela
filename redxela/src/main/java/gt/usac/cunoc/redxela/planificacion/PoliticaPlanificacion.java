package gt.usac.cunoc.redxela.planificacion;

import java.util.Comparator;

/**
 * Políticas de atención de la lista de espera (planificador de "largo plazo" del
 * centro). Cada política asigna un <b>puntaje</b> a cada pedido: menor puntaje =
 * se atiende antes. Mostrar el puntaje en la interfaz permite entender por qué la
 * lista está ordenada como está.
 *
 * <p>Equivalencias con la planificación de CPU vista en clase:</p>
 * <ul>
 *   <li>FIFO → FCFS.</li>
 *   <li>PRIORIDAD_ESTRICTA → prioridad no expropiativa (riesgo de inanición).</li>
 *   <li>ENVEJECIMIENTO → prioridad con <i>aging</i>, la solución clásica a la inanición.</li>
 *   <li>MENOS_ARTICULOS → SJF (Shortest Job First): minimiza la espera promedio.</li>
 *   <li>PLAZO_MAS_PROXIMO → EDF (Earliest Deadline First) de tiempo real.</li>
 * </ul>
 */
public enum PoliticaPlanificacion {

    FIFO("FIFO (orden de llegada)",
            "Se atiende primero al que llegó primero, sin importar su nivel.",
            "minuto de llegada") {
        @Override public double puntaje(Planificable p, double ahoraMin) {
            return p.llegadaMin();
        }
    },

    PRIORIDAD_ESTRICTA("Prioridad estricta por nivel",
            "Siempre gana el nivel más urgente (1 antes que 2...). Dentro del mismo nivel, por llegada. Puede causar inanición de los niveles 4 y 5.",
            "nivel, luego llegada") {
        @Override public double puntaje(Planificable p, double ahoraMin) {
            // El nivel domina: multiplicarlo por un número enorme hace que la llegada
            // solo sirva para desempatar dentro del mismo nivel.
            return p.nivelNumero() * 1_000_000d + p.llegadaMin();
        }
    },

    ENVEJECIMIENTO("Prioridad con envejecimiento (aging)",
            "Parte del nivel de servicio, pero cada " + (int) PoliticaPlanificacion.MINUTOS_POR_NIVEL
                    + " minutos de espera mejoran el pedido un nivel. Ningún pedido espera para siempre.",
            "nivel − espera/" + (int) PoliticaPlanificacion.MINUTOS_POR_NIVEL) {
        @Override public double puntaje(Planificable p, double ahoraMin) {
            double espera = Math.max(0, ahoraMin - p.llegadaMin());
            return p.nivelNumero() - espera / MINUTOS_POR_NIVEL;
        }
    },

    MENOS_ARTICULOS("Menos artículos primero (SJF)",
            "Primero los pedidos con menos unidades, porque liberan las estaciones más rápido. Empates por nivel.",
            "unidades, luego nivel") {
        @Override public double puntaje(Planificable p, double ahoraMin) {
            return p.unidades() * 10d + p.nivelNumero();
        }
    },

    PLAZO_MAS_PROXIMO("Plazo más próximo (EDF)",
            "Primero el pedido cuyo plazo máximo de espera vence antes (llegada + tiempo objetivo del nivel).",
            "minuto en que vence su plazo") {
        @Override public double puntaje(Planificable p, double ahoraMin) {
            return p.llegadaMin() + p.esperaMaximaMin();
        }
    };

    /** Minutos de espera que equivalen a subir un nivel en la política con envejecimiento. */
    public static final double MINUTOS_POR_NIVEL = 15.0;

    private final String nombre;
    private final String descripcion;
    private final String criterio;

    PoliticaPlanificacion(String nombre, String descripcion, String criterio) {
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.criterio = criterio;
    }

    /** Puntaje del pedido en el instante {@code ahoraMin}; menor = más urgente. */
    public abstract double puntaje(Planificable p, double ahoraMin);

    public String nombre() { return nombre; }
    public String descripcion() { return descripcion; }
    public String criterio() { return criterio; }

    /**
     * Comparador para ordenar la lista de espera en un instante dado. Los empates
     * se rompen por llegada y luego por id, de modo que el orden es total y estable.
     */
    public Comparator<Planificable> comparador(double ahoraMin) {
        return Comparator.<Planificable>comparingDouble(p -> puntaje(p, ahoraMin))
                .thenComparingDouble(Planificable::llegadaMin)
                .thenComparing(Planificable::id);
    }
}
