package gt.usac.cunoc.redxela.recursos;

import gt.usac.cunoc.redxela.modelo.TipoRecurso;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Conjunto de unidades de un mismo tipo de recurso (las 6 estaciones de empaque,
 * los 3 montacargas, etc.).
 *
 * <p>Combina dos niveles de semáforos:</p>
 * <ol>
 *   <li>Un <b>semáforo contador</b> {@code permisos} inicializado con la capacidad
 *       del recurso. Su valor es exactamente "cuántas unidades quedan libres", la
 *       definición clásica de Dijkstra para un recurso con N instancias.</li>
 *   <li>Un <b>semáforo binario por unidad</b> (en {@link Unidad}) que identifica
 *       <i>cuál</i> unidad concreta se usa, para poder mostrar en la interfaz
 *       "Andén 2 → pedido P-0031".</li>
 * </ol>
 *
 * <p>Regla de consistencia: toda unidad asignada consume un permiso del contador;
 * al liberarla se devuelven ambos.</p>
 */
public class PoolRecurso {

    private final TipoRecurso tipo;
    private final Unidad[] unidades;
    private final Semaphore permisos;

    public PoolRecurso(TipoRecurso tipo) {
        this.tipo = tipo;
        this.unidades = new Unidad[tipo.capacidad()];
        for (int i = 0; i < unidades.length; i++) unidades[i] = new Unidad(tipo, i);
        this.permisos = new Semaphore(tipo.capacidad(), true);
    }

    /**
     * Intenta tomar {@code k} unidades cualesquiera <b>sin bloquearse</b>.
     * Si no hay suficientes, no se queda con ninguna (todo o nada para este recurso).
     *
     * @return unidades tomadas, o {@code null} si no alcanzó
     */
    List<Unidad> intentarTomar(int k, Operacion op) {
        if (!permisos.tryAcquire(k)) return null;
        List<Unidad> tomadas = new ArrayList<>(k);
        for (Unidad u : unidades) {
            if (tomadas.size() == k) break;
            if (u.intentarTomar()) tomadas.add(u);
        }
        if (tomadas.size() < k) {
            // Caso transitorio: una operación incremental reservó una unidad pero aún
            // no su permiso. Se deshace todo para no quedar con una asignación parcial.
            for (Unidad u : tomadas) u.devolverSinAsignar();
            permisos.release(k);
            return null;
        }
        for (Unidad u : tomadas) u.asignar(op);
        return tomadas;
    }

    /**
     * Toma una unidad <b>específica</b> esperando lo que haga falta (modo incremental,
     * sin prevención). Es aquí donde una operación puede quedar bloqueada para
     * siempre si otra retiene la unidad y a su vez espera algo que esta tiene.
     */
    Unidad tomarEspecifica(int indice, Operacion op) throws InterruptedException {
        Unidad u = unidades[indice];
        u.tomarBloqueante(op);         // posible punto de interbloqueo
        try {
            permisos.acquire();        // siempre queda al menos un permiso para quien tiene la unidad
        } catch (InterruptedException e) {
            u.devolverSinAsignar();    // no dejar la unidad tomada si nos interrumpen aquí
            throw e;
        }
        u.asignar(op);
        return u;
    }

    /** Libera una unidad retenida por la operación. */
    void liberar(Unidad u, Operacion op) {
        u.liberar(op);
        permisos.release();
    }

    public TipoRecurso tipo() { return tipo; }
    public Unidad[] unidades() { return unidades; }
    public Unidad unidad(int i) { return unidades[i]; }
    public int capacidad() { return unidades.length; }

    /** Unidades libres según el semáforo contador. */
    public int disponibles() { return permisos.availablePermits(); }

    /** Unidades ocupadas en este instante. */
    public int ocupadas() {
        int n = 0;
        for (Unidad u : unidades) if (!u.libre()) n++;
        return n;
    }
}
