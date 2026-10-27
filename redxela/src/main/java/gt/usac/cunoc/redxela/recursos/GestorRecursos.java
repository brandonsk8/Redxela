package gt.usac.cunoc.redxela.recursos;

import gt.usac.cunoc.redxela.modelo.TipoRecurso;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Administrador central de los recursos compartidos (el "asignador de recursos"
 * del sistema operativo del centro de distribución).
 *
 * <h2>Prevención de interbloqueo en modo seguro</h2>
 * <p>{@link #intentarAsignar} rompe dos de las cuatro condiciones de Coffman:</p>
 * <ul>
 *   <li><b>Retener y esperar:</b> la asignación es <i>todo o nada</i>. Si falta un
 *       solo recurso, se devuelven los que ya se habían tomado y la operación no
 *       retiene nada mientras espera.</li>
 *   <li><b>Espera circular:</b> los recursos se piden siempre en el orden global de
 *       {@link TipoRecurso} (el {@link EnumMap} itera en ese orden).</li>
 * </ul>
 * <p>Además, toda la asignación múltiple ocurre dentro de la sección crítica de
 * {@code cerrojo}, así dos asignaciones nunca se intercalan.</p>
 *
 * <h2>Modo incremental (sin prevención)</h2>
 * <p>{@link #tomarEspecifica} pide UNA unidad y espera bloqueada reteniendo lo que
 * ya tiene: es el comportamiento "ingenuo" que permite reproducir el interbloqueo
 * que luego detecta {@code DetectorInterbloqueo}.</p>
 */
public class GestorRecursos {

    private final Map<TipoRecurso, PoolRecurso> pools = new EnumMap<>(TipoRecurso.class);

    /** Sección crítica de las asignaciones múltiples. Justo para no favorecer a nadie. */
    private final ReentrantLock cerrojo = new ReentrantLock(true);
    /** Variable de condición: "se liberó algún recurso". */
    private final Condition seLiberoAlgo = cerrojo.newCondition();

    /** Operaciones que retienen o esperan recursos (vértices del grafo de espera). */
    private final Map<String, Operacion> operaciones = new ConcurrentHashMap<>();

    /**
     * Camiones esperando andén/escáner en modo seguro. Mientras haya alguno, los
     * pedidos no pueden tomar esos dos recursos: se le da prioridad a la entrada de
     * mercadería para que Recepción no sufra inanición (y porque sin mercadería los
     * pedidos tampoco avanzan).
     */
    private final AtomicInteger recepcionesEsperando = new AtomicInteger();

    /** Observadores a los que se avisa cuando se libera algo (el despachador). */
    private final List<Runnable> alLiberar = new CopyOnWriteArrayList<>();

    public GestorRecursos() {
        for (TipoRecurso t : TipoRecurso.values()) pools.put(t, new PoolRecurso(t));
    }

    public void alLiberar(Runnable r) { alLiberar.add(r); }

    // =========================================================================
    // Modo seguro: todo o nada, en orden global
    // =========================================================================

    /**
     * Intenta asignar a {@code op} todos los recursos de {@code requeridos} de una
     * sola vez y sin bloquearse.
     *
     * @param esRecepcion true si quien pide es Recepción (no aplica la reserva para camiones)
     * @return true si obtuvo todo; false si no obtuvo nada
     */
    public boolean intentarAsignar(Operacion op, Map<TipoRecurso, Integer> requeridos, boolean esRecepcion) {
        // EnumMap: garantiza que se recorren en el orden global (prevención de espera circular)
        Map<TipoRecurso, Integer> ordenados = new EnumMap<>(requeridos);
        cerrojo.lock();
        try {
            if (!esRecepcion && recepcionesEsperando.get() > 0
                    && (ordenados.containsKey(TipoRecurso.ANDEN) || ordenados.containsKey(TipoRecurso.ESCANER))) {
                return false; // andén/escáner reservados para el camión que espera
            }
            List<Unidad> tomadas = new ArrayList<>();
            for (Map.Entry<TipoRecurso, Integer> e : ordenados.entrySet()) {
                List<Unidad> parte = pools.get(e.getKey()).intentarTomar(e.getValue(), op);
                if (parte == null) {
                    // Falta este recurso: se devuelven los anteriores (no retener y esperar)
                    for (Unidad u : tomadas) pools.get(u.tipo()).liberar(u, op);
                    return false;
                }
                tomadas.addAll(parte);
            }
            return true;
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Versión bloqueante de {@link #intentarAsignar}: reintenta cada vez que se
     * libera algún recurso (espera en la variable de condición, sin espera activa).
     * Se usa en Recepción y en los recursos "no conflictivos" de un pedido.
     */
    public void esperarYAsignar(Operacion op, Map<TipoRecurso, Integer> requeridos, boolean esRecepcion)
            throws InterruptedException {
        if (esRecepcion) recepcionesEsperando.incrementAndGet();
        try {
            while (!intentarAsignar(op, requeridos, esRecepcion)) {
                if (op.victima()) throw new InterruptedException("Operación elegida como víctima");
                cerrojo.lock();
                try {
                    // await libera el cerrojo mientras espera y lo recupera al despertar.
                    // El tiempo límite protege contra avisos perdidos.
                    seLiberoAlgo.await(300, TimeUnit.MILLISECONDS);
                } finally {
                    cerrojo.unlock();
                }
            }
        } finally {
            if (esRecepcion) recepcionesEsperando.decrementAndGet();
        }
    }

    // =========================================================================
    // Modo incremental (sin prevención)
    // =========================================================================

    /** Toma una unidad concreta bloqueándose; puede causar interbloqueo. */
    public Unidad tomarEspecifica(Operacion op, TipoRecurso tipo, int indice) throws InterruptedException {
        return pools.get(tipo).tomarEspecifica(indice, op);
    }

    // =========================================================================
    // Liberación
    // =========================================================================

    /** Libera todas las unidades que retiene la operación y avisa a quien espera. */
    public void liberarTodo(Operacion op) {
        List<Unidad> copia = new ArrayList<>(op.retenidas());
        for (Unidad u : copia) {
            try {
                pools.get(u.tipo()).liberar(u, op);
            } catch (IllegalStateException ex) {
                System.err.println("[WARN] " + ex.getMessage());
            }
        }
        if (!copia.isEmpty()) avisarLiberacion();
    }

    private void avisarLiberacion() {
        cerrojo.lock();
        try {
            seLiberoAlgo.signalAll();
        } finally {
            cerrojo.unlock();
        }
        // Fuera del cerrojo: el despachador toma su propio candado (evita anidar candados).
        for (Runnable r : alLiberar) r.run();
    }

    // =========================================================================
    // Registro de operaciones y consultas
    // =========================================================================

    public void registrar(Operacion op) { operaciones.put(op.id(), op); }
    public void desregistrar(Operacion op) { operaciones.remove(op.id(), op); }
    public Collection<Operacion> operaciones() { return operaciones.values(); }
    public Operacion operacion(String id) { return operaciones.get(id); }

    public PoolRecurso pool(TipoRecurso t) { return pools.get(t); }
    public Collection<PoolRecurso> pools() { return pools.values(); }
    public int recepcionesEsperando() { return recepcionesEsperando.get(); }

    /** Describe qué recursos faltan ahora mismo para un requerimiento (para la interfaz). */
    public String describirFaltantes(Map<TipoRecurso, Integer> requeridos, boolean esRecepcion) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<TipoRecurso, Integer> e : new EnumMap<>(requeridos).entrySet()) {
            PoolRecurso p = pools.get(e.getKey());
            if (p.disponibles() < e.getValue()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey().prefijoUnidad()).append(" (").append(p.disponibles())
                        .append('/').append(p.capacidad()).append(" libres)");
            }
        }
        if (sb.length() == 0 && !esRecepcion && recepcionesEsperando.get() > 0
                && (requeridos.containsKey(TipoRecurso.ANDEN) || requeridos.containsKey(TipoRecurso.ESCANER))) {
            return "Andén/escáner reservados para un camión en recepción";
        }
        return sb.length() == 0 ? "Recursos disponibles" : "Falta: " + sb;
    }

    /** Reinicia la contabilidad de ocupación de todas las unidades. */
    public void reiniciarContabilidad() {
        for (PoolRecurso p : pools.values()) for (Unidad u : p.unidades()) u.reiniciarContabilidad();
    }
}
