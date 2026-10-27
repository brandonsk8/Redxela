package gt.usac.cunoc.redxela.modelo;

/**
 * Tipos de recurso compartido del centro de distribución y su cantidad de unidades.
 *
 * <p><b>El orden de declaración NO es casual:</b> define el <i>orden global</i> de
 * adquisición de recursos. Toda operación que pide varios recursos en modo seguro
 * los solicita siempre en este orden (escáner → andén → montacargas → ...). Con un
 * orden total fijo es imposible que se forme una espera circular, que es una de las
 * cuatro condiciones de Coffman necesarias para el interbloqueo.</p>
 *
 * <p>Se colocan primero los recursos más escasos (el escáner es único, los andenes
 * son dos) porque son los que con mayor probabilidad provocan esperas.</p>
 */
public enum TipoRecurso {

    ESCANER("Sistema de escaneo", "Escáner", 1),
    ANDEN("Área de carga", "Andén", 2),
    MONTACARGAS("Montacargas", "Montacargas", 3),
    CONTROL_CALIDAD("Estación de control de calidad", "Control de calidad", 4),
    EMPAQUE("Estación de empaque", "Empaque", 6),
    ENCARGADO("Encargado de bodega", "Encargado", 10);

    private final String nombre;
    private final String prefijoUnidad;
    private final int capacidad;

    TipoRecurso(String nombre, String prefijoUnidad, int capacidad) {
        this.nombre = nombre;
        this.prefijoUnidad = prefijoUnidad;
        this.capacidad = capacidad;
    }

    public String nombre() { return nombre; }
    public String prefijoUnidad() { return prefijoUnidad; }
    /** Número de unidades físicas disponibles de este recurso. */
    public int capacidad() { return capacidad; }
    /** Posición en el orden global de adquisición (0 = se pide primero). */
    public int ordenGlobal() { return ordinal(); }
}
