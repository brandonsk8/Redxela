package gt.usac.cunoc.redxela.modelo;

/**
 * Niveles de servicio que un cliente puede contratar.
 *
 * <p>El nivel determina <b>cuándo</b> debe atenderse un pedido (su urgencia),
 * mientras que el {@link TipoMercancia} determina <b>qué recursos</b> necesita.
 * Ambos conceptos son independientes, tal como pide el enunciado.</p>
 *
 * <p>El tiempo objetivo se expresa en <i>minutos simulados</i>. En la simulación
 * 1 minuto simulado equivale a {@link #MS_POR_MINUTO_SIMULADO} ms reales, para que
 * los plazos de 10, 30, 60 o 120 minutos puedan observarse en una demostración.</p>
 */
public enum NivelServicio {

    EXPRES(1, "Exprés", 1, "Procesamiento casi instantáneo"),
    PRIORITARIO(2, "Prioritario", 10, "Máximo 10 minutos en espera"),
    ESTANDAR(3, "Estándar", 30, "Máximo 30 minutos en espera"),
    PROGRAMADO(4, "Programado", 60, "Máximo 60 minutos en espera"),
    ECONOMICO(5, "Económico", 120, "Máximo 120 minutos en espera");

    /** Escala de tiempo: 1 minuto simulado = 1 segundo real. */
    public static final long MS_POR_MINUTO_SIMULADO = 1000L;

    private final int numero;
    private final String nombre;
    /** Espera máxima tolerada, en minutos simulados (Exprés usa 1 como "casi inmediato"). */
    private final int esperaMaximaMin;
    private final String descripcion;

    NivelServicio(int numero, String nombre, int esperaMaximaMin, String descripcion) {
        this.numero = numero;
        this.nombre = nombre;
        this.esperaMaximaMin = esperaMaximaMin;
        this.descripcion = descripcion;
    }

    public int numero() { return numero; }
    public String nombre() { return nombre; }
    public int esperaMaximaMin() { return esperaMaximaMin; }
    public String descripcion() { return descripcion; }

    /** Espera máxima expresada en milisegundos reales. */
    public long esperaMaximaMs() { return esperaMaximaMin * MS_POR_MINUTO_SIMULADO; }

    /** Busca un nivel por su número (1..5). */
    public static NivelServicio porNumero(int n) {
        for (NivelServicio ns : values()) {
            if (ns.numero == n) return ns;
        }
        throw new IllegalArgumentException("Nivel de servicio inexistente: " + n);
    }
}
