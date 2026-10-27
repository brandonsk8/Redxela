package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoMercancia;

import java.util.Random;

/**
 * Reposición automática de inventario: cada pocos segundos revisa qué productos
 * quedaron con pocas existencias y envía un camión con mercadería nueva, para que
 * la simulación pueda correr de forma indefinida sin intervención.
 *
 * <p>Se puede desactivar desde la interfaz para observar cómo los pedidos se
 * acumulan en "esperando stock" y cómo se destraban al registrar mercadería a mano.</p>
 */
public class GeneradorCamiones implements Runnable {

    /** Por debajo de estas existencias se pide reposición. */
    static final int UMBRAL_REPOSICION = 15;

    private final CentroDistribucion centro;
    private final Random azar = new Random();
    private volatile boolean activo = true;

    public GeneradorCamiones(CentroDistribucion centro) {
        this.centro = centro;
    }

    @Override
    public void run() {
        while (activo) {
            try {
                Thread.sleep(4000);
                if (!centro.reposicionAutomatica()) continue;
                for (Producto p : centro.catalogo().productos()) {
                    if (centro.almacen().stock(p.id()) < UMBRAL_REPOSICION && !centro.recepcion().hayCamionPara(p.id())) {
                        boolean grande = p.tipo() == TipoMercancia.PESADO || p.tipo() == TipoMercancia.VOLUMINOSO;
                        int cantidad = grande ? 25 + azar.nextInt(20) : 60 + azar.nextInt(60);
                        centro.recibirMercaderia(p, cantidad, -1, false);
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                centro.bitacora().error("Reposición automática: " + e.getMessage());
            }
        }
    }

    public void detener() { activo = false; }
}
