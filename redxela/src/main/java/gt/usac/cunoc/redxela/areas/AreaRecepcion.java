package gt.usac.cunoc.redxela.areas;

import gt.usac.cunoc.redxela.ipc.BufferAcotado;
import gt.usac.cunoc.redxela.ipc.MensajeRecepcion;
import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;
import gt.usac.cunoc.redxela.recursos.Operacion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Área de Recepción de Mercadería: descarga camiones y avisa a Inventario.
 *
 * <p>Es el <b>productor</b> del esquema productor-consumidor. Nunca toca las
 * estructuras de Inventario ni del almacén: solo deposita un
 * {@link MensajeRecepcion} en el {@link BufferAcotado} compartido.</p>
 *
 * <p>Los camiones que llegan forman una cola de patio ({@link LinkedBlockingDeque},
 * sin límite: un camión nunca se rechaza). Se usa una cola doble para poder
 * devolver al frente un camión cuya descarga fue revertida por un conflicto.</p>
 *
 * <p>Recursos por descarga: escáner, un andén, un montacargas y un encargado.
 * En modo seguro se piden todos juntos (todo o nada). En modo incremental se pide
 * primero la cuadrilla (encargado + montacargas), luego el <b>escáner</b> y por
 * último el <b>andén</b> asignado al camión; ese orden es el inverso al de un
 * despacho de ALTO VALOR.</p>
 */
public class AreaRecepcion implements Runnable {

    /** Tiempo base de descarga y escaneo, más un adicional por unidad. */
    private static final long DESCARGA_BASE_MS = 600;
    private static final long DESCARGA_POR_UNIDAD_MS = 12;

    private final CentroDistribucion centro;
    private final BufferAcotado<MensajeRecepcion> buffer;
    private final LinkedBlockingDeque<Camion> patio = new LinkedBlockingDeque<>();
    private volatile boolean activo = true;
    private volatile Operacion actual;

    public AreaRecepcion(CentroDistribucion centro, BufferAcotado<MensajeRecepcion> buffer) {
        this.centro = centro;
        this.buffer = buffer;
    }

    /** Un camión llega al patio. */
    public void llegaCamion(Camion c) {
        patio.offerLast(c);
        centro.bitacora().info("Llegó el camión " + c.id() + " con " + c.cantidad() + " × " + c.producto().nombre());
    }

    @Override
    public void run() {
        while (activo) {
            try {
                Camion c = patio.pollFirst(500, TimeUnit.MILLISECONDS);
                if (c == null) continue;
                procesarCamion(c, !centro.prevencionActiva(), null);
            } catch (InterruptedException e) {
                if (!activo) break;      // apagado del sistema
                Thread.interrupted();    // fue víctima: limpiar la marca y seguir
            } catch (Exception e) {
                centro.bitacora().error("Recepción: " + e.getMessage());
            }
        }
    }

    /**
     * Descarga un camión. Lo usa el hilo de Recepción y también el escenario de
     * conflicto (en un hilo propio, como una segunda cuadrilla de recepción).
     *
     * @param incremental true = pide escáner y andén uno por uno (sin prevención)
     * @param cruce       sincronizador del escenario de conflicto (puede ser null)
     */
    public void procesarCamion(Camion c, boolean incremental, CountDownLatch cruce) throws InterruptedException {
        Operacion op = Operacion.deRecepcion(c);
        op.hilo(Thread.currentThread());
        centro.gestor().registrar(op);
        actual = op;
        try {
            if (incremental) {
                op.fase("Esperando encargado y montacargas");
                centro.gestor().esperarYAsignar(op, req(TipoRecurso.ENCARGADO, TipoRecurso.MONTACARGAS), true);
                op.fase("Esperando escáner");
                centro.gestor().tomarEspecifica(op, TipoRecurso.ESCANER, 0);
                String anden = centro.gestor().pool(TipoRecurso.ANDEN).unidad(c.anden()).nombre();
                op.fase("Con escáner; escaneando antes de pasar a " + anden);
                if (cruce != null) {
                    cruce.countDown();
                    cruce.await(30, TimeUnit.SECONDS);
                }
                Thread.sleep(400);
                op.fase("Con escáner; esperando " + anden);
                centro.gestor().tomarEspecifica(op, TipoRecurso.ANDEN, c.anden());
            } else {
                op.fase("Esperando recursos (todo o nada)");
                centro.gestor().esperarYAsignar(op,
                        req(TipoRecurso.ESCANER, TipoRecurso.ANDEN, TipoRecurso.MONTACARGAS, TipoRecurso.ENCARGADO), true);
            }

            op.fase("Descargando y escaneando");
            long t = Math.round((DESCARGA_BASE_MS + c.cantidad() * DESCARGA_POR_UNIDAD_MS) * centro.factorTiempo());
            Thread.sleep(t);

            // Se liberan los recursos ANTES de enviar el aviso: si el buffer está lleno,
            // Recepción espera sin acaparar el escáner ni el andén.
            centro.gestor().liberarTodo(op);
            enviarAInventario(op, c);
            centro.estadisticas().camionRecibido(c.cantidad());
        } catch (InterruptedException e) {
            if (op.victima()) {
                patio.offerFirst(c); // vuelve al frente del patio, no se pierde
                centro.bitacora().aviso("La descarga de " + c.id() + " se revirtió; el camión regresa al patio");
            } else {
                patio.offerFirst(c);
            }
            throw e;
        } finally {
            centro.gestor().liberarTodo(op);
            centro.gestor().desregistrar(op);
            actual = null;
        }
    }

    /**
     * Deposita el aviso en el buffer. Si está lleno, espera en tramos de 1 s y
     * reintenta: el dato no se descarta y la espera nunca es indefinida (cada
     * tramo devuelve el control para registrar la situación o para apagarse).
     */
    private void enviarAInventario(Operacion op, Camion c) throws InterruptedException {
        MensajeRecepcion m = new MensajeRecepcion(c.id(), c.producto(), c.cantidad(), System.currentTimeMillis());
        boolean avisado = false;
        while (!buffer.ofrecer(m, 1000)) {
            op.fase("Buffer lleno: esperando a que Inventario procese");
            if (!avisado) {
                centro.bitacora().ipc("Buffer Recepción→Inventario lleno (" + buffer.capacidad()
                        + "/" + buffer.capacidad() + "). " + c.id() + " espera turno sin perder el aviso");
                avisado = true;
            }
            if (!activo) throw new InterruptedException("Apagado");
        }
        centro.bitacora().ipc("Recepción envió a Inventario: " + c.cantidad() + " × " + c.producto().nombre()
                + " (" + c.id() + ") — buffer " + buffer.ocupados() + "/" + buffer.capacidad());
    }

    private static Map<TipoRecurso, Integer> req(TipoRecurso... tipos) {
        Map<TipoRecurso, Integer> m = new EnumMap<>(TipoRecurso.class);
        for (TipoRecurso t : tipos) m.put(t, 1);
        return m;
    }

    public void detener() { activo = false; }

    /** Camiones esperando en el patio (para la interfaz). */
    public List<Camion> patio() { return new ArrayList<>(patio); }

    /** Indica si ya hay un camión en el patio o descargando para ese producto. */
    public boolean hayCamionPara(String productoId) {
        for (Camion c : patio) if (c.producto().id().equals(productoId)) return true;
        Operacion a = actual;
        return a != null && a.camion().producto().id().equals(productoId);
    }

    public Operacion actual() { return actual; }
}
