package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoMercancia;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.recursos.Operacion;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Provoca deliberadamente el interbloqueo clásico de dos operaciones que piden los
 * mismos dos recursos en orden inverso:
 *
 * <pre>
 *   Recepción (camión urgente):  toma ESCÁNER  → pide ANDÉN k
 *   Despacho ALTO VALOR:         toma ANDÉN k  → pide ESCÁNER
 * </pre>
 *
 * <p>Un {@link CountDownLatch} inicializado en 2 actúa como <b>barrera</b>: cada
 * operación, después de tomar su primer recurso, hace {@code countDown()} y espera
 * a que la otra también lo haya tomado. Así se garantiza el cruce (sin la barrera
 * el interbloqueo dependería de la suerte en la planificación de hilos). A partir
 * de ahí ambas piden el segundo recurso y quedan bloqueadas para siempre, hasta que
 * el detector lo reporta y el usuario elige cuál continúa.</p>
 *
 * <p>Las dos operaciones corren en hilos propios en modo incremental aunque la
 * prevención global esté activa; el resto del centro sigue funcionando.</p>
 */
public class EscenarioConflicto {

    private final CentroDistribucion centro;

    public EscenarioConflicto(CentroDistribucion centro) {
        this.centro = centro;
    }

    /**
     * @param andenIndice  andén en disputa (0 o 1)
     * @param productoAvId producto de alto valor del pedido (null = uno al azar con existencias)
     * @return ids de las dos operaciones creadas
     */
    public Map<String, String> provocar(int andenIndice, String productoAvId) {
        if (andenIndice < 0 || andenIndice >= TipoRecurso.ANDEN.capacidad()) {
            throw new ValidacionException("Andén inválido: " + (andenIndice + 1));
        }
        Producto av = productoAvId != null ? centro.catalogo().producto(productoAvId) : elegirAltoValor();
        if (av.tipo() != TipoMercancia.ALTO_VALOR) {
            throw new ValidacionException("El pedido del escenario debe ser de tipo Alto valor (usa andén y escáner)");
        }

        // --- Operación B: pedido de alto valor (cliente exprés, para que la elección sea interesante) ---
        Cliente cliente = centro.catalogo().clientes().stream()
                .filter(c -> c.nivel() == NivelServicio.EXPRES).findFirst()
                .orElse(centro.catalogo().clientes().get(0));
        Pedido pedido = new Pedido(centro.siguienteIdPedido(), cliente, List.of(new LineaPedido(av, 2)), Pedido.Origen.ESCENARIO);
        centro.estadisticas().pedidoGenerado(true);
        if (centro.almacen().retirar(pedido.lineas())) {
            pedido.stockReservado(true);
        } else {
            // Para la demostración no importa el stock; se marca como reservado y se avisa
            pedido.stockReservado(true);
            centro.bitacora().aviso("Escenario: " + av.nombre() + " sin existencias; se asume reservado para la demostración");
        }
        pedido.inicioMs(System.currentTimeMillis());
        Operacion opPedido = Operacion.dePedido(pedido);
        centro.gestor().registrar(opPedido);

        // --- Operación A: camión urgente asignado al mismo andén ---
        Producto carga = centro.catalogo().producto("GEN-02");
        Camion camion = new Camion(centro.siguienteIdCamion(), carga, 40, andenIndice, System.currentTimeMillis(), true);

        CountDownLatch cruce = new CountDownLatch(2);

        Map<TipoRecurso, Integer> base = Despachador.requerimientoInicial(pedido, true);
        TrabajadorPedido trabajador = new TrabajadorPedido(centro, opPedido, centro.politica(), true, andenIndice, cruce)
                .conBase(base);
        centro.despachador().ejecutar(trabajador, "escenario-" + pedido.id());

        Thread hiloRecepcion = new Thread(() -> {
            try {
                centro.recepcion().procesarCamion(camion, true, cruce);
            } catch (InterruptedException e) {
                // Fue la víctima: el camión ya regresó al patio y lo descargará la cuadrilla normal
            } catch (Exception e) {
                centro.bitacora().error("Escenario (recepción): " + e.getMessage());
            }
        }, "escenario-" + camion.id());
        hiloRecepcion.setDaemon(true);
        hiloRecepcion.start();

        String anden = centro.gestor().pool(TipoRecurso.ANDEN).unidad(andenIndice).nombre();
        centro.bitacora().conflicto("Escenario de conflicto iniciado: " + camion.id() + " tomará Escáner→" + anden
                + " mientras " + pedido.id() + " toma " + anden + "→Escáner");
        return Map.of("pedido", pedido.id(), "camion", camion.id());
    }

    private Producto elegirAltoValor() {
        Producto mejor = null;
        int mejorStock = -1;
        for (Producto p : centro.catalogo().productos()) {
            if (p.tipo() != TipoMercancia.ALTO_VALOR) continue;
            int s = centro.almacen().stock(p.id());
            if (s > mejorStock) { mejor = p; mejorStock = s; }
        }
        if (mejor == null) throw new ValidacionException("No hay productos de alto valor en el catálogo");
        return mejor;
    }
}
