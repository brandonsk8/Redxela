package gt.usac.cunoc.redxela.almacen;

/**
 * Ubicación física del almacén (una posición de un rack): pasillo, nivel y posición.
 *
 * <p>Analogía con memoria: es un <b>marco de página</b>. Tiene tamaño fijo
 * ({@link Almacen#CAPACIDAD_UBICACION} unidades) y pertenece a lo sumo a un lote a
 * la vez. Si el lote no la llena, el espacio sobrante se desperdicia
 * (<b>fragmentación interna</b>).</p>
 *
 * <p>No tiene sincronización propia: solo se modifica dentro de la sección crítica
 * de escritura de {@link Almacen}.</p>
 */
public class Ubicacion implements Comparable<Ubicacion> {

    private final int indice;      // "dirección física" lineal 0..N-1
    private final char pasillo;    // A..F
    private final int nivel;       // 1..4
    private final int posicion;    // 1..6

    private Lote lote;             // null = libre
    private int unidades;

    Ubicacion(int indice, char pasillo, int nivel, int posicion) {
        this.indice = indice;
        this.pasillo = pasillo;
        this.nivel = nivel;
        this.posicion = posicion;
    }

    void ocupar(Lote l, int u) { this.lote = l; this.unidades = u; }
    void liberar() { this.lote = null; this.unidades = 0; }
    void restar(int u) { this.unidades -= u; }

    public int indice() { return indice; }
    public char pasillo() { return pasillo; }
    public int nivel() { return nivel; }
    public int posicion() { return posicion; }
    public Lote lote() { return lote; }
    public int unidades() { return unidades; }
    public boolean libre() { return lote == null; }

    /** Código legible, p. ej. "B-3-05" (pasillo-nivel-posición). */
    public String codigo() { return String.format("%c-%d-%02d", pasillo, nivel, posicion); }

    @Override public int compareTo(Ubicacion o) { return Integer.compare(indice, o.indice); }
    @Override public String toString() { return codigo(); }
}
