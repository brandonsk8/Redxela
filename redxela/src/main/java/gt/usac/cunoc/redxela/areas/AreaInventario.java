package gt.usac.cunoc.redxela.areas;

import gt.usac.cunoc.redxela.almacen.Lote;
import gt.usac.cunoc.redxela.ipc.BufferAcotado;
import gt.usac.cunoc.redxela.ipc.MensajeRecepcion;
import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Área de Control de Inventario: <b>consumidor</b> del buffer Recepción→Inventario.
 *
 * <p>Procesa los avisos a su propio ritmo (con un retardo configurable que simula
 * el registro en el sistema y la ubicación física de la mercadería). Por cada
 * aviso: guarda la mercadería en el almacén como un lote nuevo (asignando sus
 * ubicaciones) y avisa al despachador para que reevalúe los pedidos que esperaban
 * existencias.</p>
 *
 * <p>Si el almacén no tiene espacio para todo el lote, la parte que no cupo queda en
 * "pendientes de ubicar" (zona de muelle) y se reintenta cada vez que salen pedidos
 * y se liberan ubicaciones. Nada se pierde.</p>
 */
public class AreaInventario implements Runnable {

    private final CentroDistribucion centro;
    private final BufferAcotado<MensajeRecepcion> buffer;
    /** Mercadería recibida que todavía no cabe en el almacén. */
    private final ConcurrentLinkedQueue<MensajeRecepcion> pendientes = new ConcurrentLinkedQueue<>();
    private volatile boolean activo = true;
    private volatile boolean huboRetiro;
    private volatile MensajeRecepcion procesando;
    private volatile long procesados;

    public AreaInventario(CentroDistribucion centro, BufferAcotado<MensajeRecepcion> buffer) {
        this.centro = centro;
        this.buffer = buffer;
    }

    @Override
    public void run() {
        while (activo) {
            try {
                if (huboRetiro && !pendientes.isEmpty()) reintentarPendientes();
                MensajeRecepcion m = buffer.tomar(500);   // P(elementos) con tiempo límite
                if (m == null) continue;
                procesando = m;
                Thread.sleep(Math.round(centro.retardoInventarioMs() * centro.factorTiempo()));
                registrar(m);
                procesados++;
                procesando = null;
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                centro.bitacora().error("Inventario: " + e.getMessage());
                procesando = null;
            }
        }
    }

    /** Guarda la mercadería y reevalúa pedidos. */
    private void registrar(MensajeRecepcion m) {
        Lote lote = centro.almacen().almacenar(m.producto(), m.cantidad());
        int guardadas = lote == null ? 0 : lote.cantidadInicial();
        if (lote != null) {
            StringBuilder ubic = new StringBuilder();
            for (int i = 0; i < lote.tabla().size(); i++) {
                if (i > 0) ubic.append(' ');
                if (i == 4) { ubic.append("…"); break; }
                ubic.append(lote.tabla().get(i).codigo());
            }
            centro.bitacora().ipc("Inventario registró " + lote.id() + ": " + guardadas + " × " + m.producto().nombre()
                    + " en " + lote.tabla().size() + " ubicaciones" + (lote.esDisperso() ? " no contiguas" : "")
                    + " [" + ubic + "]");
        }
        if (guardadas < m.cantidad()) {
            int resto = m.cantidad() - guardadas;
            pendientes.add(new MensajeRecepcion(m.camionId(), m.producto(), resto, m.enviadoMs()));
            centro.bitacora().aviso("Almacén sin espacio: " + resto + " × " + m.producto().nombre()
                    + " quedan pendientes de ubicar en el muelle");
        }
        if (guardadas > 0) centro.lista().notificar(); // pedidos esperando stock pueden avanzar
    }

    private void reintentarPendientes() {
        huboRetiro = false;
        int n = pendientes.size();
        for (int i = 0; i < n; i++) {
            MensajeRecepcion m = pendientes.poll();
            if (m == null) break;
            registrar(m);
        }
    }

    /** El despachador avisa que salió mercadería (se liberaron ubicaciones). */
    public void notificarRetiro() { huboRetiro = true; }

    public void detener() { activo = false; }

    public MensajeRecepcion procesando() { return procesando; }
    public long procesados() { return procesados; }
    public List<MensajeRecepcion> pendientes() { return new ArrayList<>(pendientes); }
}
