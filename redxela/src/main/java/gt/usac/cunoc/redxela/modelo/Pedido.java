package gt.usac.cunoc.redxela.modelo;

import gt.usac.cunoc.redxela.planificacion.Planificable;
import gt.usac.cunoc.redxela.planificacion.Reloj;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pedido de un cliente. Es la "unidad de trabajo" del sistema (el equivalente a un
 * proceso o tarea en un sistema operativo).
 *
 * <p><b>Concurrencia:</b> los datos que definen al pedido (cliente, líneas, nivel,
 * recursos) son inmutables y se fijan en el constructor. Los datos que cambian
 * durante la simulación (estado, progreso, marcas de tiempo) son {@code volatile}
 * porque los escribe un hilo trabajador y los lee el hilo que arma la vista para la
 * interfaz; {@code volatile} garantiza visibilidad inmediata entre hilos. No hace
 * falta exclusión mutua porque cada campo tiene un único escritor a la vez.</p>
 */
public class Pedido implements Planificable {

    /** Tiempo fijo de preparación de cualquier pedido (ms reales). */
    public static final long TIEMPO_BASE_MS = 1500;
    /** Tiempo por unidad de mercancía, antes de aplicar el factor del tipo (ms reales). */
    public static final long TIEMPO_POR_UNIDAD_MS = 300;

    public enum Origen { AUTOMATICO, MANUAL, ESCENARIO }

    // ---------- datos inmutables ----------
    private final String id;
    private final Cliente cliente;
    private final NivelServicio nivel;
    private final List<LineaPedido> lineas;
    private final int totalUnidades;
    private final Set<TipoMercancia> tipos;
    private final Map<TipoRecurso, Integer> recursosRequeridos;
    private final long duracionEstimadaMs;
    private final long llegadaMs;
    private final Origen origen;

    // ---------- datos mutables (un escritor a la vez) ----------
    private volatile EstadoPedido estado = EstadoPedido.EN_ESPERA;
    private volatile String motivoEspera = "Recién llegado";
    private volatile long inicioMs;            // cuándo obtuvo recursos (fin de la espera)
    private volatile long finMs;               // cuándo terminó
    private volatile double progreso;          // 0..1
    private volatile int reintentos;           // veces que regresó a la lista de espera
    private volatile boolean stockReservado;   // ya se retiró su mercancía del almacén
    private volatile double ultimoPuntaje;     // puntaje calculado por la política vigente

    public Pedido(String id, Cliente cliente, List<LineaPedido> lineas, Origen origen) {
        if (cliente == null) throw new IllegalArgumentException("El pedido debe pertenecer a un cliente");
        if (lineas == null || lineas.isEmpty()) throw new IllegalArgumentException("El pedido debe tener al menos un producto");
        this.id = id;
        this.cliente = cliente;
        this.nivel = cliente.nivel();
        this.lineas = List.copyOf(lineas);
        this.origen = origen;
        this.llegadaMs = System.currentTimeMillis();

        int unidades = 0;
        double tiempo = TIEMPO_BASE_MS;
        EnumSet<TipoMercancia> ts = EnumSet.noneOf(TipoMercancia.class);
        for (LineaPedido l : this.lineas) {
            unidades += l.cantidad();
            ts.add(l.producto().tipo());
            // La cantidad de unidades es la estimación base del trabajo (enunciado).
            tiempo += l.cantidad() * TIEMPO_POR_UNIDAD_MS * l.producto().tipo().factorTiempo();
        }
        this.totalUnidades = unidades;
        this.tipos = Collections.unmodifiableSet(ts);
        this.duracionEstimadaMs = Math.round(tiempo);
        this.recursosRequeridos = Collections.unmodifiableMap(combinarRecursos(ts));
    }

    /**
     * Si el pedido mezcla varios tipos de mercancía, necesita el máximo de cada
     * recurso entre sus tipos: se procesa como un solo trabajo y basta con una
     * estación de empaque aunque lleve un producto frágil y uno general.
     */
    private static Map<TipoRecurso, Integer> combinarRecursos(Set<TipoMercancia> tipos) {
        Map<TipoRecurso, Integer> m = new EnumMap<>(TipoRecurso.class);
        for (TipoMercancia t : tipos) {
            t.recursos().forEach((r, c) -> m.merge(r, c, Math::max));
        }
        return m;
    }

    /** Indica si alguno de sus productos requiere el par (Andén, Escáner) en el despacho. */
    public boolean usaParConflictivo() {
        return recursosRequeridos.containsKey(TipoRecurso.ANDEN)
                && recursosRequeridos.containsKey(TipoRecurso.ESCANER);
    }

    // ---------- Planificable ----------
    @Override public String id() { return id; }
    @Override public int nivelNumero() { return nivel.numero(); }
    @Override public double llegadaMin() { return Reloj.aMinutos(llegadaMs); }
    @Override public int unidades() { return totalUnidades; }
    @Override public int esperaMaximaMin() { return nivel.esperaMaximaMin(); }

    // ---------- tiempos derivados ----------
    /** Tiempo que lleva (o llevó) esperando, en ms. */
    public long esperaMs() {
        long fin = inicioMs > 0 ? inicioMs : System.currentTimeMillis();
        return fin - llegadaMs;
    }

    /** Indica si ya superó la espera máxima de su nivel de servicio. */
    public boolean plazoVencido() { return esperaMs() > nivel.esperaMaximaMs(); }

    /** Tiempo restante estimado de procesamiento, en ms. */
    public long restanteMs() { return Math.max(0, Math.round(duracionEstimadaMs * (1 - progreso))); }

    // ---------- getters / setters ----------
    public Cliente cliente() { return cliente; }
    public NivelServicio nivel() { return nivel; }
    public List<LineaPedido> lineas() { return lineas; }
    public Set<TipoMercancia> tipos() { return tipos; }
    public Map<TipoRecurso, Integer> recursosRequeridos() { return recursosRequeridos; }
    public long duracionEstimadaMs() { return duracionEstimadaMs; }
    public long llegadaMs() { return llegadaMs; }
    public Origen origen() { return origen; }

    public EstadoPedido estado() { return estado; }
    public void estado(EstadoPedido e) { this.estado = e; }
    public String motivoEspera() { return motivoEspera; }
    public void motivoEspera(String m) { this.motivoEspera = m; }
    public long inicioMs() { return inicioMs; }
    public void inicioMs(long t) { this.inicioMs = t; }
    public long finMs() { return finMs; }
    public void finMs(long t) { this.finMs = t; }
    public double progreso() { return progreso; }
    public void progreso(double p) { this.progreso = Math.max(0, Math.min(1, p)); }
    public int reintentos() { return reintentos; }
    public void incrementarReintentos() { this.reintentos++; }
    public boolean stockReservado() { return stockReservado; }
    public void stockReservado(boolean b) { this.stockReservado = b; }
    public double ultimoPuntaje() { return ultimoPuntaje; }
    public void ultimoPuntaje(double p) { this.ultimoPuntaje = p; }

    @Override public String toString() { return id + " (" + nivel.nombre() + ")"; }
}
