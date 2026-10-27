package gt.usac.cunoc.redxela.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bitácora de eventos del sistema, visible en la interfaz.
 *
 * <p>La escriben muchos hilos a la vez (despachador, trabajadores, recepción,
 * inventario, detector). Se usa {@link ConcurrentLinkedDeque}, una cola
 * <i>no bloqueante</i> (basada en CAS) para que registrar un evento nunca detenga a
 * un hilo de trabajo. Se conservan solo los últimos {@value #MAX} eventos.</p>
 */
public final class Bitacora {

    public enum Nivel { INFO, OK, AVISO, ERROR, IPC, CONFLICTO }

    public record Evento(long seq, String hora, Nivel nivel, String mensaje) {}

    private static final int MAX = 300;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ConcurrentLinkedDeque<Evento> eventos = new ConcurrentLinkedDeque<>();
    private final AtomicInteger tamanio = new AtomicInteger();
    private final AtomicLong secuencia = new AtomicLong();

    public void registrar(Nivel nivel, String mensaje) {
        eventos.addFirst(new Evento(secuencia.incrementAndGet(), LocalTime.now().format(FMT), nivel, mensaje));
        if (tamanio.incrementAndGet() > MAX) {
            if (eventos.pollLast() != null) tamanio.decrementAndGet();
        }
        if (nivel == Nivel.ERROR) System.err.println("[ERROR] " + mensaje);
    }

    public void info(String m) { registrar(Nivel.INFO, m); }
    public void ok(String m) { registrar(Nivel.OK, m); }
    public void aviso(String m) { registrar(Nivel.AVISO, m); }
    public void error(String m) { registrar(Nivel.ERROR, m); }
    public void ipc(String m) { registrar(Nivel.IPC, m); }
    public void conflicto(String m) { registrar(Nivel.CONFLICTO, m); }

    /** Los {@code n} eventos más recientes (del más nuevo al más viejo). */
    public List<Evento> recientes(int n) {
        List<Evento> r = new ArrayList<>(n);
        Iterator<Evento> it = eventos.iterator();
        while (it.hasNext() && r.size() < n) r.add(it.next());
        return r;
    }
}
