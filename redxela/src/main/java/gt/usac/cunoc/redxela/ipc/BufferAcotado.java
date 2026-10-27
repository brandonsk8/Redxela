package gt.usac.cunoc.redxela.ipc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffer circular de capacidad fija para la comunicación entre Recepción
 * (productor) e Inventario (consumidor): el problema clásico del
 * <b>productor-consumidor con buffer acotado</b>, resuelto con tres semáforos.
 *
 * <pre>
 *  espaciosLibres = Semaphore(N)  // cuántos huecos hay: el productor espera si es 0
 *  elementos      = Semaphore(0)  // cuántos mensajes hay: el consumidor espera si es 0
 *  mutex          = Semaphore(1)  // exclusión mutua sobre el arreglo y los índices
 *
 *  productor:  P(espaciosLibres); P(mutex); poner; V(mutex); V(elementos)
 *  consumidor: P(elementos);      P(mutex); sacar; V(mutex); V(espaciosLibres)
 * </pre>
 *
 * <p>Ningún área modifica las estructuras de la otra: Recepción solo deposita
 * mensajes y Inventario los procesa a su propio ritmo.</p>
 *
 * <ul>
 *   <li><b>Sin pérdida de datos:</b> si el buffer está lleno el productor espera; nunca
 *       se sobrescribe un mensaje no leído.</li>
 *   <li><b>Sin bloqueo indefinido:</b> {@link #ofrecer} y {@link #tomar} esperan con
 *       tiempo límite. Si vence, el llamador recupera el control, registra la
 *       situación y reintenta (o se detiene limpiamente al apagar el sistema).</li>
 *   <li>El orden {@code P(espacios)} antes de {@code P(mutex)} es obligatorio: al
 *       revés, un productor podría dormir con el mutex tomado y bloquear al consumidor.</li>
 * </ul>
 *
 * @param <T> tipo de mensaje
 */
public class BufferAcotado<T> {

    private final Object[] datos;
    private int entrada;   // próxima posición donde escribe el productor
    private int salida;    // próxima posición donde lee el consumidor

    private final Semaphore espaciosLibres;
    private final Semaphore elementos = new Semaphore(0, true);
    private final Semaphore mutex = new Semaphore(1, true);

    // Métricas (atómicas: se leen desde la interfaz sin tomar el mutex)
    private final AtomicLong producidos = new AtomicLong();
    private final AtomicLong consumidos = new AtomicLong();
    private final AtomicLong esperasPorLleno = new AtomicLong();

    public BufferAcotado(int capacidad) {
        if (capacidad <= 0) throw new IllegalArgumentException("Capacidad inválida");
        this.datos = new Object[capacidad];
        this.espaciosLibres = new Semaphore(capacidad, true);
    }

    /**
     * Deposita un mensaje. Espera como máximo {@code timeoutMs} si el buffer está lleno.
     *
     * @return true si se depositó; false si venció el tiempo (el mensaje NO se pierde:
     * sigue en manos del productor, que debe reintentar)
     */
    public boolean ofrecer(T mensaje, long timeoutMs) throws InterruptedException {
        if (!espaciosLibres.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {   // P(espacios)
            esperasPorLleno.incrementAndGet();
            return false;
        }
        mutex.acquire();                                                     // P(mutex)
        try {
            datos[entrada] = mensaje;
            entrada = (entrada + 1) % datos.length;
        } finally {
            mutex.release();                                                 // V(mutex)
        }
        producidos.incrementAndGet();
        elementos.release();                                                 // V(elementos)
        return true;
    }

    /**
     * Extrae el mensaje más antiguo. Espera como máximo {@code timeoutMs}.
     *
     * @return el mensaje o null si no llegó nada en ese tiempo
     */
    @SuppressWarnings("unchecked")
    public T tomar(long timeoutMs) throws InterruptedException {
        if (!elementos.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) return null; // P(elementos)
        T m;
        mutex.acquire();                                                          // P(mutex)
        try {
            m = (T) datos[salida];
            datos[salida] = null;
            salida = (salida + 1) % datos.length;
        } finally {
            mutex.release();                                                      // V(mutex)
        }
        consumidos.incrementAndGet();
        espaciosLibres.release();                                                 // V(espacios)
        return m;
    }

    /** Copia del contenido actual, del más antiguo al más nuevo (para la interfaz). */
    @SuppressWarnings("unchecked")
    public List<T> contenido() {
        List<T> r = new ArrayList<>();
        try {
            mutex.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return r;
        }
        try {
            // Las celdas ocupadas son contiguas a partir de 'salida' (al consumir se ponen en null)
            for (int i = 0; i < datos.length; i++) {
                Object o = datos[(salida + i) % datos.length];
                if (o == null) break;
                r.add((T) o);
            }
        } finally {
            mutex.release();
        }
        return r;
    }

    public int capacidad() { return datos.length; }
    /** Mensajes en el buffer (valor del semáforo de elementos). */
    public int ocupados() { return datos.length - espaciosLibres.availablePermits(); }
    public long producidos() { return producidos.get(); }
    public long consumidos() { return consumidos.get(); }
    public long esperasPorLleno() { return esperasPorLleno.get(); }
}
