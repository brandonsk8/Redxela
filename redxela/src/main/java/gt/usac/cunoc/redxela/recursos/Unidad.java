package gt.usac.cunoc.redxela.recursos;

import gt.usac.cunoc.redxela.modelo.TipoRecurso;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Una unidad física de un recurso: "Andén 2", "Montacargas 1", "Escáner"...
 *
 * <p>Cada unidad está protegida por un <b>semáforo binario</b> (inicializado en 1)
 * que garantiza la <b>exclusión mutua</b>: solo una operación puede usar la unidad
 * a la vez. Se usa un semáforo y no un {@code ReentrantLock} porque la unidad la
 * adquiere el hilo despachador y la libera un hilo trabajador distinto; un
 * semáforo no tiene "dueño" y permite esa liberación cruzada, un candado no.</p>
 *
 * <p>El semáforo se crea <i>justo</i> (fair = true): las operaciones que esperan
 * la unidad la reciben en orden FIFO, lo que evita inanición en la espera.</p>
 */
public class Unidad {

    private final TipoRecurso tipo;
    private final int indice;
    private final String nombre;
    private final Semaphore mutex = new Semaphore(1, true);

    private volatile Operacion duenio;
    /** Operaciones bloqueadas esperando específicamente esta unidad. */
    private final Set<Operacion> esperando = ConcurrentHashMap.newKeySet();

    // Contabilidad de ocupación (para la estadística de uso de recursos)
    private volatile long ocupadaDesdeMs;
    private long ocupadoAcumuladoMs;

    Unidad(TipoRecurso tipo, int indice) {
        this.tipo = tipo;
        this.indice = indice;
        this.nombre = tipo.capacidad() == 1 ? tipo.prefijoUnidad() : tipo.prefijoUnidad() + " " + (indice + 1);
    }

    /**
     * Intento no bloqueante de tomar la unidad. {@code tryAcquire()} sin tiempo
     * ignora la justicia del semáforo ("se cuela"), por eso primero se revisa si hay
     * hilos en cola: si alguien espera esta unidad, se le respeta el turno.
     */
    boolean intentarTomar() { return !mutex.hasQueuedThreads() && mutex.tryAcquire(); }

    /**
     * Toma la unidad esperando lo necesario. La espera es <b>interrumpible</b>: si el
     * usuario elige a esta operación como víctima de un interbloqueo, su hilo es
     * interrumpido y sale de aquí con {@link InterruptedException}.
     *
     * <p>Se usa {@code acquire()} sin tiempo límite sobre un semáforo justo: la
     * operación conserva su lugar en la cola FIFO del semáforo. Así, cuando la
     * víctima libera la unidad, la recibe la operación que la estaba esperando
     * (la ganadora) y no otra que llegue después.</p>
     */
    void tomarBloqueante(Operacion op) throws InterruptedException {
        esperando.add(op);
        op.esperandoPor(this);
        try {
            if (op.victima()) throw new InterruptedException("Operación elegida como víctima");
            mutex.acquire();
        } finally {
            esperando.remove(op);
            op.esperandoPor(null);
        }
    }

    /** Registra a la operación como dueña (se llama ya con el semáforo tomado). */
    synchronized void asignar(Operacion op) {
        this.duenio = op;
        this.ocupadaDesdeMs = System.currentTimeMillis();
        op.agregarRetenida(this);
    }

    /** Libera la unidad. Solo la dueña puede liberarla. */
    synchronized void liberar(Operacion op) {
        if (duenio != op) {
            throw new IllegalStateException(op + " intentó liberar " + nombre + " que pertenece a " + duenio);
        }
        ocupadoAcumuladoMs += System.currentTimeMillis() - ocupadaDesdeMs;
        duenio = null;
        op.quitarRetenida(this);
        mutex.release();
    }

    /** Liberación de una unidad tomada que todavía no se asignó (rollback interno). */
    void devolverSinAsignar() { mutex.release(); }

    /** Tiempo total ocupada hasta este instante (ms). */
    public synchronized long ocupadoTotalMs() {
        long t = ocupadoAcumuladoMs;
        if (duenio != null) t += System.currentTimeMillis() - ocupadaDesdeMs;
        return t;
    }

    public synchronized void reiniciarContabilidad() {
        ocupadoAcumuladoMs = 0;
        if (duenio != null) ocupadaDesdeMs = System.currentTimeMillis();
    }

    public TipoRecurso tipo() { return tipo; }
    public int indice() { return indice; }
    public String nombre() { return nombre; }
    public Operacion duenio() { return duenio; }
    public Set<Operacion> esperando() { return esperando; }
    public boolean libre() { return duenio == null; }

    @Override public String toString() { return nombre; }
}
