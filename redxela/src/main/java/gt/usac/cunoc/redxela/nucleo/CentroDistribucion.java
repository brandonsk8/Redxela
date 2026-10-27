package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.almacen.Almacen;
import gt.usac.cunoc.redxela.areas.AreaInventario;
import gt.usac.cunoc.redxela.areas.AreaRecepcion;
import gt.usac.cunoc.redxela.estadisticas.Estadisticas;
import gt.usac.cunoc.redxela.interbloqueo.DetectorInterbloqueo;
import gt.usac.cunoc.redxela.ipc.BufferAcotado;
import gt.usac.cunoc.redxela.ipc.MensajeRecepcion;
import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.planificacion.ListaEspera;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.recursos.GestorRecursos;
import gt.usac.cunoc.redxela.util.Bitacora;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fachada del centro de distribución: crea, conecta, arranca y detiene todos los
 * componentes, y guarda la configuración que se puede cambiar en caliente.
 *
 * <h2>Hilos del sistema</h2>
 * <pre>
 *  despachador        planificador: lista de espera → recursos → trabajador
 *  trabajador-1..10   pool que procesa pedidos (uno por pedido en curso)
 *  recepcion          productor: descarga camiones, escribe en el buffer
 *  inventario         consumidor: lee el buffer, ubica mercadería en el almacén
 *  detector           busca ciclos en el grafo de espera cada 500 ms
 *  generador-pedidos  llegadas automáticas (proceso de Poisson)
 *  reposicion         camiones automáticos cuando baja el stock
 *  http-*             servidor web (interfaz y API)
 * </pre>
 *
 * <p>La configuración se guarda en campos {@code volatile}: la escribe un hilo del
 * servidor web y la leen los hilos de la simulación; {@code volatile} basta porque
 * cada valor es independiente y se escribe de forma atómica.</p>
 */
public final class CentroDistribucion {

    /** Capacidad del buffer Recepción → Inventario. */
    public static final int CAPACIDAD_BUFFER = 8;

    // ----- componentes -----
    private final Bitacora bitacora = new Bitacora();
    private final Catalogo catalogo = new Catalogo();
    private final GestorRecursos gestor = new GestorRecursos();
    private final Almacen almacen = new Almacen();
    private final ListaEspera lista = new ListaEspera();
    private final Estadisticas estadisticas = new Estadisticas();
    private final BufferAcotado<MensajeRecepcion> buffer = new BufferAcotado<>(CAPACIDAD_BUFFER);
    private final AreaRecepcion recepcion;
    private final AreaInventario inventario;
    private final Despachador despachador;
    private final DetectorInterbloqueo detector;
    private final GeneradorPedidos generadorPedidos;
    private final GeneradorCamiones generadorCamiones;
    private final EscenarioConflicto escenario;
    private final List<Thread> hilos = new ArrayList<>();

    private final AtomicInteger secuenciaPedidos = new AtomicInteger();
    private final AtomicInteger secuenciaCamiones = new AtomicInteger();
    /** Últimos pedidos terminados (para la interfaz). */
    private final ConcurrentLinkedDeque<Pedido> completados = new ConcurrentLinkedDeque<>();
    private final AtomicInteger cantidadCompletadosGuardados = new AtomicInteger();

    // ----- configuración (modificable en caliente) -----
    private volatile PoliticaPlanificacion politica = PoliticaPlanificacion.ENVEJECIMIENTO;
    private volatile boolean prevencionActiva = true;
    private volatile boolean generadorPedidosActivo = true;
    private volatile double pedidosPorHora = 42;
    private volatile boolean reposicionAutomatica = true;
    private volatile double factorTiempo = 1.0;
    private volatile long retardoInventarioMs = 2500;

    public CentroDistribucion() {
        recepcion = new AreaRecepcion(this, buffer);
        inventario = new AreaInventario(this, buffer);
        despachador = new Despachador(this);
        detector = new DetectorInterbloqueo(this);
        generadorPedidos = new GeneradorPedidos(this);
        generadorCamiones = new GeneradorCamiones(this);
        escenario = new EscenarioConflicto(this);
        // Cada vez que se libera un recurso, el despachador debe reevaluar la lista
        gestor.alLiberar(lista::notificar);
        cargarInventarioInicial();
    }

    /** Existencias iniciales para que la simulación arranque con mercadería. */
    private void cargarInventarioInicial() {
        Random r = new Random(2026);
        for (Producto p : catalogo.productos()) {
            int n = switch (p.tipo()) {
                case PESADO, VOLUMINOSO -> 30 + r.nextInt(15);
                default -> 70 + r.nextInt(50);
            };
            almacen.almacenar(p, n);
        }
        bitacora.info("Inventario inicial cargado: " + catalogo.productos().size() + " productos");
    }

    /** Arranca todos los hilos de la simulación. */
    public void iniciar() {
        lanzar(despachador, "despachador");
        lanzar(recepcion, "recepcion");
        lanzar(inventario, "inventario");
        lanzar(detector, "detector-interbloqueo");
        lanzar(generadorPedidos, "generador-pedidos");
        lanzar(generadorCamiones, "reposicion");
        bitacora.ok("Centro de distribución REDXela en operación");
    }

    private void lanzar(Runnable r, String nombre) {
        Thread t = new Thread(r, nombre);
        t.setDaemon(true);
        hilos.add(t);
        t.start();
    }

    /** Detiene la simulación de forma ordenada (gancho de apagado). */
    public void detener() {
        generadorPedidos.detener();
        generadorCamiones.detener();
        recepcion.detener();
        inventario.detener();
        detector.detener();
        despachador.detener();
        for (Thread t : hilos) t.interrupt();
        for (Thread t : hilos) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================================================
    // Operaciones que vienen de la interfaz o de los generadores
    // =========================================================================

    /** Registra un pedido nuevo y lo pone en la lista de espera. */
    public Pedido recibirPedido(Cliente cliente, List<LineaPedido> lineas, boolean manual) {
        if (lineas == null || lineas.isEmpty()) throw new ValidacionException("El pedido debe tener al menos un producto");
        Pedido p = new Pedido(siguienteIdPedido(), cliente, lineas, manual ? Pedido.Origen.MANUAL : Pedido.Origen.AUTOMATICO);
        estadisticas.pedidoGenerado(manual);
        lista.agregar(p);
        if (manual) {
            bitacora.info("Pedido manual " + p.id() + " de " + cliente.nombre() + " (" + p.nivel().nombre() + ", "
                    + p.unidades() + " u.)");
        }
        return p;
    }

    /**
     * Registra la llegada de un camión.
     *
     * @param anden andén asignado (0..1) o -1 para que lo asigne el patio
     */
    public Camion recibirMercaderia(Producto producto, int cantidad, int anden, boolean manual) {
        if (cantidad <= 0 || cantidad > 2000) throw new ValidacionException("La cantidad debe estar entre 1 y 2000 unidades");
        int a = anden;
        if (a < 0) a = secuenciaCamiones.get() % TipoRecurso.ANDEN.capacidad();
        if (a >= TipoRecurso.ANDEN.capacidad()) throw new ValidacionException("Andén inválido");
        Camion c = new Camion(siguienteIdCamion(), producto, cantidad, a, System.currentTimeMillis(), manual);
        recepcion.llegaCamion(c);
        return c;
    }

    /** Lo llama el trabajador al terminar un pedido. */
    void registrarCompletado(Pedido p, PoliticaPlanificacion pol) {
        estadisticas.pedidoCompletado(p, pol);
        completados.addFirst(p);
        if (cantidadCompletadosGuardados.incrementAndGet() > 40) {
            if (completados.pollLast() != null) cantidadCompletadosGuardados.decrementAndGet();
        }
    }

    public void politica(PoliticaPlanificacion p) {
        if (p == null) throw new ValidacionException("Política inválida");
        if (p != this.politica) {
            this.politica = p;
            bitacora.info("Política de atención cambiada a: " + p.nombre());
            lista.notificar();
        }
    }

    public void prevencionActiva(boolean b) {
        if (b != prevencionActiva) {
            prevencionActiva = b;
            if (b) bitacora.ok("Prevención de interbloqueo ACTIVADA: asignación todo o nada en orden global");
            else bitacora.aviso("Prevención de interbloqueo DESACTIVADA: Recepción y Alto valor piden andén/escáner uno por uno");
        }
    }

    // ----- getters de componentes -----
    public Bitacora bitacora() { return bitacora; }
    public Catalogo catalogo() { return catalogo; }
    public GestorRecursos gestor() { return gestor; }
    public Almacen almacen() { return almacen; }
    public ListaEspera lista() { return lista; }
    public Estadisticas estadisticas() { return estadisticas; }
    public BufferAcotado<MensajeRecepcion> buffer() { return buffer; }
    public AreaRecepcion recepcion() { return recepcion; }
    public AreaInventario inventario() { return inventario; }
    public Despachador despachador() { return despachador; }
    public DetectorInterbloqueo detector() { return detector; }
    public EscenarioConflicto escenario() { return escenario; }
    public List<Pedido> completados() { return new ArrayList<>(completados); }

    public String siguienteIdPedido() { return String.format("P-%05d", secuenciaPedidos.incrementAndGet()); }
    public String siguienteIdCamion() { return String.format("R-%04d", secuenciaCamiones.incrementAndGet()); }

    // ----- configuración -----
    public PoliticaPlanificacion politica() { return politica; }
    public boolean prevencionActiva() { return prevencionActiva; }
    public boolean generadorPedidosActivo() { return generadorPedidosActivo; }
    public void generadorPedidosActivo(boolean b) { generadorPedidosActivo = b; }
    public double pedidosPorHora() { return pedidosPorHora; }
    public void pedidosPorHora(double v) {
        if (v < 1 || v > 300) throw new ValidacionException("La tasa debe estar entre 1 y 300 pedidos por hora simulada");
        pedidosPorHora = v;
    }
    public boolean reposicionAutomatica() { return reposicionAutomatica; }
    public void reposicionAutomatica(boolean b) { reposicionAutomatica = b; }
    public double factorTiempo() { return factorTiempo; }
    public void factorTiempo(double f) {
        if (f < 0.1 || f > 5) throw new ValidacionException("El factor de tiempo debe estar entre 0.1 y 5");
        factorTiempo = f;
    }
    public long retardoInventarioMs() { return retardoInventarioMs; }
    public void retardoInventarioMs(long ms) {
        if (ms < 0 || ms > 20000) throw new ValidacionException("El retardo de Inventario debe estar entre 0 y 20000 ms");
        retardoInventarioMs = ms;
    }
}
