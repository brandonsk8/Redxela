package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.EstadoPedido;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.recursos.Operacion;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tarea que procesa un pedido en un hilo trabajador.
 *
 * <p>Simula el trabajo durmiendo en tramos cortos (100 ms) y actualizando el
 * progreso, de modo que la interfaz pueda mostrar avance y tiempo restante. El
 * tiempo total es proporcional a las unidades del pedido (ver {@link Pedido}).</p>
 *
 * <p><b>Modo incremental:</b> cuando la prevención está desactivada (o en el
 * escenario de conflicto), un pedido de ALTO VALOR pide primero el andén y, ya
 * con el andén tomado, pide el escáner. Recepción pide esos mismos dos recursos en
 * orden inverso. Esa espera reteniendo recursos es la que puede volverse circular.</p>
 *
 * <p><b>Reversión:</b> si el usuario lo elige como víctima de un interbloqueo, el hilo
 * es interrumpido mientras espera, libera todo lo que tiene y el pedido vuelve a la
 * lista de espera conservando su hora de llegada original (para que no pierda
 * antigüedad) y su mercadería ya reservada.</p>
 */
public class TrabajadorPedido implements Runnable {

    private static final long PASO_MS = 100;

    private final CentroDistribucion centro;
    private final Operacion op;
    private final PoliticaPlanificacion politica;
    private final boolean incremental;
    private final int anden;
    /** Sincronizador opcional del escenario de conflicto (garantiza el cruce). */
    private final CountDownLatch cruce;
    /** Si no es null, recursos base que el propio trabajador debe pedir primero. */
    private Map<TipoRecurso, Integer> baseAPedir;

    public TrabajadorPedido(CentroDistribucion centro, Operacion op, PoliticaPlanificacion politica,
                            boolean incremental, int anden, CountDownLatch cruce) {
        this.centro = centro;
        this.op = op;
        this.politica = politica;
        this.incremental = incremental;
        this.anden = anden;
        this.cruce = cruce;
    }

    public TrabajadorPedido conBase(Map<TipoRecurso, Integer> base) {
        this.baseAPedir = base;
        return this;
    }

    @Override
    public void run() {
        op.hilo(Thread.currentThread());
        Pedido p = op.pedido();
        boolean devolver = false;
        try {
            if (baseAPedir != null) {
                op.fase("Esperando recursos base");
                centro.gestor().esperarYAsignar(op, baseAPedir, false);
            }
            if (incremental) adquirirIncremental(p);

            p.estado(EstadoPedido.EN_PROCESO);
            op.fase("Procesando");
            long duracion = Math.max(PASO_MS, Math.round(p.duracionEstimadaMs() * centro.factorTiempo()));
            long inicio = System.currentTimeMillis();
            long transcurrido = 0;
            while (transcurrido < duracion) {
                Thread.sleep(Math.min(PASO_MS, duracion - transcurrido));
                transcurrido = System.currentTimeMillis() - inicio;
                p.progreso(transcurrido / (double) duracion);
            }
            p.progreso(1);
            p.finMs(System.currentTimeMillis());
            p.estado(EstadoPedido.COMPLETADO);
            centro.registrarCompletado(p, politica);
        } catch (InterruptedException e) {
            // Víctima de un interbloqueo (o apagado del sistema)
            devolver = op.victima();
            if (!devolver) Thread.currentThread().interrupt();
        } catch (Exception e) {
            centro.bitacora().error("Error procesando " + p.id() + ": " + e.getMessage());
            devolver = true;
        } finally {
            // Siempre se liberan los recursos, pase lo que pase (incluso con excepción)
            centro.gestor().liberarTodo(op);
            centro.gestor().desregistrar(op);
            if (devolver) devolverALista(p);
        }
    }

    /** Pide andén y luego escáner, reteniendo el andén mientras espera el escáner. */
    private void adquirirIncremental(Pedido p) throws InterruptedException {
        p.estado(EstadoPedido.ADQUIRIENDO);
        String nombreAnden = centro.gestor().pool(TipoRecurso.ANDEN).unidad(anden).nombre();
        op.fase("Esperando " + nombreAnden);
        centro.gestor().tomarEspecifica(op, TipoRecurso.ANDEN, anden);

        op.fase("Con " + nombreAnden + "; cargando antes de escanear");
        if (cruce != null) {
            // Escenario: esperar a que Recepción también tenga su primer recurso
            cruce.countDown();
            cruce.await(30, TimeUnit.SECONDS);
        }
        Thread.sleep(400); // tiempo de colocar la mercancía en el andén

        op.fase("Con " + nombreAnden + "; esperando escáner");
        centro.gestor().tomarEspecifica(op, TipoRecurso.ESCANER, 0);
    }

    private void devolverALista(Pedido p) {
        p.estado(EstadoPedido.EN_ESPERA);
        p.inicioMs(0);
        p.progreso(0);
        p.incrementarReintentos();
        p.motivoEspera("Devuelto a la lista tras resolver un conflicto (intento " + (p.reintentos() + 1) + ")");
        centro.estadisticas().devueltoPorConflicto();
        centro.bitacora().aviso(p.id() + " liberó sus recursos y regresó a la lista de espera");
        centro.lista().agregar(p);
    }
}
