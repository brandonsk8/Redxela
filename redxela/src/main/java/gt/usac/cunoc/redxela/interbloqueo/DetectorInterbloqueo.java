package gt.usac.cunoc.redxela.interbloqueo;

import gt.usac.cunoc.redxela.modelo.EstadoPedido;
import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;
import gt.usac.cunoc.redxela.recursos.Operacion;
import gt.usac.cunoc.redxela.recursos.Unidad;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hilo que detecta interbloqueos construyendo el <b>grafo de espera</b>
 * (wait-for graph) cada 500 ms.
 *
 * <p>Vértices: operaciones. Arista A → B: "A espera una unidad que retiene B".
 * Como cada espera bloqueante es por una unidad concreta (una instancia única),
 * un <b>ciclo</b> en este grafo es condición necesaria y suficiente de
 * interbloqueo (Silberschatz, cap. 7: recursos de una sola instancia).</p>
 *
 * <p>Cada operación espera a lo sumo una unidad, así que cada vértice tiene a lo
 * sumo una arista de salida y basta con seguir la cadena desde cada vértice hasta
 * que se corta (sin ciclo) o se repite un vértice (ciclo): O(V²) en el peor caso,
 * despreciable para decenas de operaciones.</p>
 *
 * <p>El detector <b>no</b> resuelve solo: publica el conflicto y espera a que el
 * usuario elija qué operación continúa (requisito del enunciado). La resolución es
 * por <i>expropiación con reversión</i>: a las demás se les interrumpe el hilo,
 * liberan sus recursos y regresan a su lista de espera.</p>
 */
public class DetectorInterbloqueo implements Runnable {

    private final CentroDistribucion centro;
    private final List<Conflicto> conflictos = new CopyOnWriteArrayList<>();
    private final AtomicInteger secuencia = new AtomicInteger();
    private volatile boolean activo = true;
    /**
     * Ciclos vistos en la pasada anterior. Un ciclo se reporta solo si aparece en dos
     * pasadas seguidas (≈0.5 s): el grafo se lee sin detener a los demás hilos, y
     * esto descarta una "foto" inconsistente tomada justo mientras cambiaba.
     */
    private Set<String> vistosAntes = new HashSet<>();

    public DetectorInterbloqueo(CentroDistribucion centro) {
        this.centro = centro;
    }

    @Override
    public void run() {
        while (activo) {
            try {
                Thread.sleep(500);
                detectar();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                centro.bitacora().error("Detector de interbloqueo: " + e);
            }
        }
    }

    /** Una pasada del algoritmo de detección. */
    void detectar() {
        Set<String> ciclosVigentes = new HashSet<>();
        for (Operacion inicio : centro.gestor().operaciones()) {
            if (inicio.esperandoPor() == null) continue;
            List<Operacion> camino = new ArrayList<>();
            Operacion actual = inicio;
            while (actual != null) {
                int idx = camino.indexOf(actual);
                if (idx >= 0) {
                    List<Operacion> ciclo = camino.subList(idx, camino.size());
                    String clave = clave(ciclo);
                    ciclosVigentes.add(clave);
                    if (vistosAntes.contains(clave) && buscarActivo(clave) == null) {
                        registrar(new ArrayList<>(ciclo), clave);
                    }
                    break;
                }
                camino.add(actual);
                Unidad espera = actual.esperandoPor();
                actual = espera == null ? null : espera.duenio();   // arista actual → dueño
            }
        }
        vistosAntes = ciclosVigentes;
        // Un conflicto activo cuyo ciclo ya no existe se dio por resuelto (p. ej. por apagado)
        for (Conflicto c : conflictos) {
            if (c.estado() == Conflicto.Estado.ACTIVO && !ciclosVigentes.contains(c.clave())) {
                c.resolver(null);
            }
        }
    }

    /** Identificador canónico del ciclo (independiente del vértice donde se empezó). */
    private static String clave(List<Operacion> ciclo) {
        TreeSet<String> ids = new TreeSet<>();
        for (Operacion o : ciclo) ids.add(o.id());
        return String.join("|", ids);
    }

    private Conflicto buscarActivo(String clave) {
        for (Conflicto c : conflictos) if (c.estado() == Conflicto.Estado.ACTIVO && c.clave().equals(clave)) return c;
        return null;
    }

    private void registrar(List<Operacion> ciclo, String clave) {
        List<Conflicto.Eslabon> eslabones = new ArrayList<>();
        StringBuilder exp = new StringBuilder();
        for (Operacion o : ciclo) {
            List<String> retiene = new ArrayList<>();
            for (Unidad u : o.retenidas()) retiene.add(u.nombre());
            Unidad espera = o.esperandoPor();
            Operacion dueno = espera == null ? null : espera.duenio();
            eslabones.add(new Conflicto.Eslabon(o, retiene, espera == null ? "-" : espera.nombre(),
                    dueno == null ? "-" : dueno.id()));
            if (exp.length() > 0) exp.append(" · ");
            exp.append(o.id()).append(" retiene ").append(espera != null ? clavesRetenidasRelevantes(o, ciclo) : "-")
                    .append(" y espera ").append(espera == null ? "-" : espera.nombre())
                    .append(" (que tiene ").append(dueno == null ? "-" : dueno.id()).append(")");
            if (o.pedido() != null) {
                o.pedido().estado(EstadoPedido.BLOQUEADO);
                o.pedido().motivoEspera("Interbloqueo: espera " + (espera == null ? "-" : espera.nombre()));
            }
        }
        exp.append(". Ninguna puede avanzar: cada una necesita lo que la otra retiene y no lo suelta (espera circular).");
        Conflicto c = new Conflicto(String.format("K-%03d", secuencia.incrementAndGet()), clave, eslabones, exp.toString());
        conflictos.add(c);
        centro.estadisticas().conflictoDetectado();
        centro.bitacora().conflicto("¡Interbloqueo " + c.id() + " detectado! " + exp);
    }

    /** Recursos que retiene {@code o} y que otra operación del ciclo está esperando. */
    private static String clavesRetenidasRelevantes(Operacion o, List<Operacion> ciclo) {
        List<String> r = new ArrayList<>();
        for (Unidad u : o.retenidas()) {
            for (Operacion otra : ciclo) {
                if (otra != o && otra.esperandoPor() == u) r.add(u.nombre());
            }
        }
        return r.isEmpty() ? "-" : String.join(", ", r);
    }

    /**
     * Resuelve un conflicto: {@code ganadorId} continúa con los recursos disputados
     * y todas las demás operaciones del ciclo se revierten.
     */
    public synchronized void resolver(String conflictoId, String ganadorId) {
        Conflicto c = null;
        for (Conflicto x : conflictos) if (x.id().equals(conflictoId)) c = x;
        if (c == null) throw ValidacionException.noEncontrado("El conflicto " + conflictoId);
        if (c.estado() != Conflicto.Estado.ACTIVO) throw new ValidacionException("El conflicto " + conflictoId + " ya fue resuelto");
        if (!c.contiene(ganadorId)) throw new ValidacionException(ganadorId + " no participa en el conflicto " + conflictoId);

        List<String> victimas = new ArrayList<>();
        for (Conflicto.Eslabon e : c.ciclo()) {
            Operacion op = e.op();
            if (op.id().equals(ganadorId)) continue;
            op.marcarVictima();
            Thread h = op.hilo();
            if (h != null) h.interrupt();   // sale de su espera con InterruptedException y libera todo
            victimas.add(op.id());
        }
        c.resolver(ganadorId);
        centro.estadisticas().conflictoResuelto();
        centro.bitacora().conflicto("Conflicto " + c.id() + " resuelto: continúa " + ganadorId
                + "; se revierte " + String.join(", ", victimas) + " y regresa a su cola de espera (pedidos → lista de espera, camiones → patio)");
    }

    public void detener() { activo = false; }

    public List<Conflicto> conflictos() { return conflictos; }

    public long activos() {
        return conflictos.stream().filter(c -> c.estado() == Conflicto.Estado.ACTIVO).count();
    }
}
