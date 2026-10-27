package gt.usac.cunoc.redxela.recursos;

import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.modelo.Pedido;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Operación del centro que retiene y/o espera recursos: el procesamiento de un
 * pedido o la descarga de un camión. Es el "proceso" del grafo de asignación de
 * recursos que analiza el detector de interbloqueo.
 *
 * <p>Guarda el hilo que la ejecuta para poder interrumpirlo cuando el usuario la
 * elige como víctima al resolver un conflicto. La lista de unidades retenidas es
 * {@link CopyOnWriteArrayList}: la escribe el hilo de la operación y la leen el
 * detector y la interfaz; esa colección permite recorrerla sin candado y sin
 * {@code ConcurrentModificationException}.</p>
 */
public class Operacion {

    public enum Tipo { PEDIDO, RECEPCION }

    private final String id;
    private final Tipo tipo;
    private final String descripcion;
    private final Pedido pedido;   // null si es recepción
    private final Camion camion;   // null si es pedido

    private volatile Thread hilo;
    private volatile Unidad esperandoPor;
    private volatile boolean victima;
    private volatile String fase = "Creada";
    private final List<Unidad> retenidas = new CopyOnWriteArrayList<>();

    private Operacion(String id, Tipo tipo, String descripcion, Pedido pedido, Camion camion) {
        this.id = id;
        this.tipo = tipo;
        this.descripcion = descripcion;
        this.pedido = pedido;
        this.camion = camion;
    }

    public static Operacion dePedido(Pedido p) {
        return new Operacion(p.id(), Tipo.PEDIDO,
                "Despacho del pedido " + p.id() + " (" + p.nivel().nombre() + ", " + p.unidades() + " u.)",
                p, null);
    }

    public static Operacion deRecepcion(Camion c) {
        return new Operacion(c.id(), Tipo.RECEPCION,
                "Recepción del camión " + c.id() + " (" + c.cantidad() + " × " + c.producto().nombre() + ")",
                null, c);
    }

    // Registro de unidades (lo usan PoolRecurso / GestorRecursos)
    void agregarRetenida(Unidad u) { retenidas.add(u); }
    void quitarRetenida(Unidad u) { retenidas.remove(u); }
    void esperandoPor(Unidad u) { this.esperandoPor = u; }

    public String id() { return id; }
    public Tipo tipo() { return tipo; }
    public String descripcion() { return descripcion; }
    public Pedido pedido() { return pedido; }
    public Camion camion() { return camion; }
    public List<Unidad> retenidas() { return retenidas; }
    public Unidad esperandoPor() { return esperandoPor; }
    public Thread hilo() { return hilo; }
    public void hilo(Thread h) { this.hilo = h; }
    public boolean victima() { return victima; }
    public void marcarVictima() { this.victima = true; }
    public String fase() { return fase; }
    public void fase(String f) { this.fase = f; }

    /** Etiqueta corta para mostrar como "dueño" de un recurso. */
    public String etiqueta() {
        if (tipo == Tipo.PEDIDO) return pedido.id() + " · " + pedido.nivel().nombre();
        return camion.id() + " · Recepción";
    }

    @Override public String toString() { return id; }
}
