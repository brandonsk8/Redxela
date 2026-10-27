package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.EstadoPedido;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.recursos.Operacion;
import gt.usac.cunoc.redxela.recursos.Unidad;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hilo despachador: el planificador del centro de distribución.
 *
 * <p>En cada vuelta toma la lista de espera ordenada por la política vigente y
 * recorre los pedidos en ese orden. Al primero que tenga existencias y cuyos
 * recursos estén todos disponibles se los asigna (todo o nada), retira su
 * mercadería del almacén y lo entrega a un hilo trabajador. Si ningún pedido puede
 * avanzar, duerme en la variable de condición de la lista hasta el siguiente aviso.</p>
 *
 * <p><b>Decisión de diseño — "relleno":</b> si el primer pedido de la lista no puede
 * avanzar por falta de recursos, se intenta con el siguiente en vez de dejar todo
 * detenido. Esto aumenta el uso de los recursos (un pedido GENERAL no tiene por qué
 * esperar a que se libere un andén que solo necesita un PESADO), pero puede retrasar
 * pedidos grandes; la política con envejecimiento compensa ese efecto.</p>
 *
 * <p><b>Pool de trabajadores:</b> {@link ThreadPoolExecutor} de 10 hilos. El tamaño no
 * es arbitrario: todo tipo de mercancía necesita al menos un encargado de bodega y
 * hay 10, así que nunca puede haber más de 10 pedidos en proceso simultáneo.</p>
 */
public class Despachador implements Runnable {

    private final CentroDistribucion centro;
    private final ExecutorService trabajadores;
    private volatile boolean activo = true;
    private volatile Thread hilo;

    public Despachador(CentroDistribucion centro) {
        this.centro = centro;
        int n = TipoRecurso.ENCARGADO.capacidad();
        AtomicInteger contador = new AtomicInteger();
        ThreadFactory fabrica = r -> {
            Thread t = new Thread(r, "trabajador-" + contador.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.trabajadores = new ThreadPoolExecutor(n, n, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), fabrica);
    }

    @Override
    public void run() {
        hilo = Thread.currentThread();
        centro.bitacora().info("Despachador iniciado con política: " + centro.politica().nombre());
        while (activo) {
            try {
                if (!despacharUno()) {
                    centro.lista().esperarCambios(400);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Un error con un pedido no debe detener al planificador completo
                centro.bitacora().error("Despachador: " + e);
                e.printStackTrace();
            }
        }
    }

    /**
     * Intenta despachar un pedido.
     *
     * @return true si despachó alguno (se vuelve a intentar enseguida)
     */
    private boolean despacharUno() {
        PoliticaPlanificacion politica = centro.politica();
        List<Pedido> orden = centro.lista().ordenada(politica);
        boolean incremental = !centro.prevencionActiva();

        for (Pedido p : orden) {
            // 1) ¿Hay mercadería? (consulta sin reservar)
            if (!p.stockReservado() && !centro.almacen().hayExistencias(p.lineas())) {
                p.estado(EstadoPedido.ESPERANDO_STOCK);
                p.motivoEspera("Sin existencias: " + String.join(", ", centro.almacen().faltantes(p.lineas())));
                continue;
            }
            // 2) ¿Hay recursos? Asignación todo o nada en orden global
            Map<TipoRecurso, Integer> req = requerimientoInicial(p, incremental);
            Operacion op = Operacion.dePedido(p);
            if (!centro.gestor().intentarAsignar(op, req, false)) {
                p.estado(EstadoPedido.ESPERANDO_RECURSOS);
                p.motivoEspera(centro.gestor().describirFaltantes(req, false));
                continue;
            }
            // 3) Retirar la mercadería del almacén (atómico)
            if (!p.stockReservado()) {
                if (!centro.almacen().retirar(p.lineas())) {
                    // Otro hilo la tomó entre la consulta y el retiro: se devuelve todo
                    centro.gestor().liberarTodo(op);
                    p.estado(EstadoPedido.ESPERANDO_STOCK);
                    continue;
                }
                p.stockReservado(true);
                centro.inventario().notificarRetiro();
            }
            // 4) Sacarlo de la lista y entregarlo a un trabajador
            centro.lista().quitar(p);
            p.inicioMs(System.currentTimeMillis());
            p.estado(incremental && p.usaParConflictivo() ? EstadoPedido.ADQUIRIENDO : EstadoPedido.EN_PROCESO);
            p.motivoEspera("");
            centro.gestor().registrar(op);
            int anden = elegirAnden(p);
            trabajadores.submit(new TrabajadorPedido(centro, op, politica, incremental && p.usaParConflictivo(),
                    anden, null));
            return true;
        }
        return false;
    }

    /**
     * Recursos que se asignan al despachar. En modo seguro, todos. En modo
     * incremental (prevención desactivada) los pedidos que usan el par
     * (Andén, Escáner) reciben aquí solo el resto; el trabajador pedirá el andén y
     * luego el escáner uno por uno, reteniendo lo que ya tiene.
     */
    static Map<TipoRecurso, Integer> requerimientoInicial(Pedido p, boolean incremental) {
        Map<TipoRecurso, Integer> req = new EnumMap<>(p.recursosRequeridos());
        if (incremental && p.usaParConflictivo()) {
            req.remove(TipoRecurso.ANDEN);
            req.remove(TipoRecurso.ESCANER);
        }
        return req;
    }

    /** Andén al que irá un pedido en modo incremental: uno libre si hay, si no el menos solicitado. */
    private int elegirAnden(Pedido p) {
        Unidad[] andenes = centro.gestor().pool(TipoRecurso.ANDEN).unidades();
        for (Unidad u : andenes) if (u.libre() && u.esperando().isEmpty()) return u.indice();
        // Ninguno libre: el que tenga menos operaciones esperando
        Unidad mejor = andenes[0];
        for (Unidad u : andenes) if (u.esperando().size() < mejor.esperando().size()) mejor = u;
        return mejor.indice();
    }

    /** Ejecuta un trabajador fuera del pool (lo usa el escenario de conflicto). */
    public void ejecutar(TrabajadorPedido t, String nombreHilo) {
        Thread th = new Thread(t, nombreHilo);
        th.setDaemon(true);
        th.start();
    }

    public void detener() {
        activo = false;
        Thread h = hilo;
        if (h != null) h.interrupt();
        trabajadores.shutdownNow();
    }

    /** Pedidos en proceso en este momento (hilos del pool ocupados). */
    public int trabajadoresActivos() {
        return ((ThreadPoolExecutor) trabajadores).getActiveCount();
    }
}
