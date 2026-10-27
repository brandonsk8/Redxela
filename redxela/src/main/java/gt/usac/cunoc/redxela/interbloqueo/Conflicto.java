package gt.usac.cunoc.redxela.interbloqueo;

import gt.usac.cunoc.redxela.recursos.Operacion;

import java.util.List;

/**
 * Interbloqueo detectado: un ciclo en el grafo de espera.
 *
 * <p>Guarda, para cada operación del ciclo, qué recurso retiene que el siguiente
 * necesita y qué recurso espera. Con eso la interfaz explica <i>por qué</i> están
 * bloqueadas: "A retiene X y espera Y; B retiene Y y espera X".</p>
 */
public class Conflicto {

    public enum Estado { ACTIVO, RESUELTO }

    /** Una arista del ciclo: {@code op} retiene {@code retiene} y espera {@code espera} (de {@code duenoDeEspera}). */
    public record Eslabon(Operacion op, List<String> retiene, String espera, String duenoDeEspera) {}

    private final String id;
    private final String clave;
    private final long detectadoMs;
    private final List<Eslabon> ciclo;
    private final String explicacion;

    private volatile Estado estado = Estado.ACTIVO;
    private volatile String ganadorId;
    private volatile long resueltoMs;

    Conflicto(String id, String clave, List<Eslabon> ciclo, String explicacion) {
        this.id = id;
        this.clave = clave;
        this.ciclo = List.copyOf(ciclo);
        this.explicacion = explicacion;
        this.detectadoMs = System.currentTimeMillis();
    }

    void resolver(String ganador) {
        this.ganadorId = ganador;
        this.resueltoMs = System.currentTimeMillis();
        this.estado = Estado.RESUELTO;
    }

    public boolean contiene(String opId) {
        for (Eslabon e : ciclo) if (e.op().id().equals(opId)) return true;
        return false;
    }

    public String id() { return id; }
    public String clave() { return clave; }
    public long detectadoMs() { return detectadoMs; }
    public List<Eslabon> ciclo() { return ciclo; }
    public String explicacion() { return explicacion; }
    public Estado estado() { return estado; }
    public String ganadorId() { return ganadorId; }
    public long resueltoMs() { return resueltoMs; }
}
