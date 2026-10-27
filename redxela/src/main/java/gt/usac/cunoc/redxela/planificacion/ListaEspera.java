package gt.usac.cunoc.redxela.planificacion;

import gt.usac.cunoc.redxela.modelo.Pedido;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lista de espera de pedidos (la "cola de listos" del planificador).
 *
 * <p>No se usa una {@code PriorityQueue} porque el orden depende del instante actual
 * (en ENVEJECIMIENTO la prioridad cambia mientras se espera) y de la política, que
 * puede cambiarse en caliente. En cada vuelta el despachador pide una copia
 * ordenada con la política vigente.</p>
 *
 * <p><b>Sincronización:</b> un {@link ReentrantLock} protege la lista. Una variable
 * de condición {@code huboCambios} permite que el despachador duerma sin consumir
 * CPU (sin espera activa) hasta que ocurra algo relevante: llega un pedido, se
 * libera un recurso o entra mercadería. La bandera {@code pendiente} evita la
 * pérdida de avisos: si el aviso llega mientras el despachador estaba revisando
 * (y no esperando), la próxima espera retorna de inmediato.</p>
 */
public class ListaEspera {

    private final ReentrantLock cerrojo = new ReentrantLock(true);
    private final Condition huboCambios = cerrojo.newCondition();
    private final List<Pedido> pedidos = new ArrayList<>();
    private boolean pendiente;

    /** Agrega un pedido (nuevo o devuelto tras un conflicto) y despierta al despachador. */
    public void agregar(Pedido p) {
        cerrojo.lock();
        try {
            pedidos.add(p);
            pendiente = true;
            huboCambios.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /** Quita un pedido de la lista (cuando el despachador lo asigna). */
    public boolean quitar(Pedido p) {
        cerrojo.lock();
        try {
            return pedidos.remove(p);
        } finally {
            cerrojo.unlock();
        }
    }

    /** Avisa al despachador que vale la pena volver a revisar la lista. */
    public void notificar() {
        cerrojo.lock();
        try {
            pendiente = true;
            huboCambios.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Duerme hasta que haya un aviso o pase {@code maxMs}. El límite de tiempo hace
     * que el despachador reevalúe periódicamente (los puntajes con envejecimiento
     * cambian solos con el paso del tiempo).
     */
    public void esperarCambios(long maxMs) throws InterruptedException {
        cerrojo.lock();
        try {
            if (!pendiente) huboCambios.await(maxMs, TimeUnit.MILLISECONDS);
            pendiente = false;
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Copia de la lista ordenada según la política (no altera la lista real).
     * También guarda en cada pedido su puntaje, para mostrarlo en la interfaz.
     */
    public List<Pedido> ordenada(PoliticaPlanificacion politica) {
        double ahoraMin = Reloj.ahora();
        List<Pedido> copia;
        cerrojo.lock();
        try {
            copia = new ArrayList<>(pedidos);
        } finally {
            cerrojo.unlock();
        }
        for (Pedido p : copia) p.ultimoPuntaje(politica.puntaje(p, ahoraMin));
        copia.sort(politica.comparador(ahoraMin));
        return copia;
    }

    public int tamanio() {
        cerrojo.lock();
        try {
            return pedidos.size();
        } finally {
            cerrojo.unlock();
        }
    }
}
