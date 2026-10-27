package gt.usac.cunoc.redxela.almacen;

import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.Producto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * Almacén físico de REDXela y administrador de su espacio.
 *
 * <h2>Capacidad (definida por el estudiante)</h2>
 * <p>{@value #PASILLOS} pasillos (A–F) × {@value #NIVELES} niveles ×
 * {@value #POSICIONES} posiciones = 144 ubicaciones de {@value #CAPACIDAD_UBICACION}
 * unidades cada una → 4 320 unidades en total.</p>
 *
 * <h2>Estrategia de asignación</h2>
 * <p>Es el esquema de <b>paginación</b> de memoria aplicado a bodega: un lote se
 * divide en bloques de tamaño fijo (la capacidad de una ubicación) y cada bloque se
 * coloca en cualquier ubicación libre, aunque no sea vecina de las demás. Se elige
 * la ubicación libre de menor dirección (primer ajuste sobre la lista de libres,
 * que se mantiene ordenada en un {@link TreeSet}). Ventajas: no hay fragmentación
 * externa (cualquier hueco sirve) y un lote grande cabe mientras haya ubicaciones
 * libres sumando lo necesario. Costo: fragmentación interna en la última ubicación
 * de cada lote y la necesidad de una tabla de ubicaciones por lote.</p>
 *
 * <h2>Concurrencia</h2>
 * <p>Lo escriben Inventario (al guardar) y el Despachador (al retirar mercadería de
 * un pedido); lo leen la interfaz y el despachador (consultas de stock). Por eso se
 * protege con un {@link ReentrantReadWriteLock}: muchas lecturas pueden ocurrir en
 * paralelo, y una escritura es exclusiva (problema de lectores-escritores).</p>
 */
public class Almacen {

    public static final int PASILLOS = 6;
    public static final int NIVELES = 4;
    public static final int POSICIONES = 6;
    public static final int CAPACIDAD_UBICACION = 30;

    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);
    private final Ubicacion[] ubicaciones;
    /** Lista de ubicaciones libres ordenada por dirección (primer ajuste). */
    private final TreeSet<Ubicacion> libres = new TreeSet<>();
    /** Lotes por producto en orden de ingreso: se despacha FIFO (lo más antiguo primero). */
    private final Map<String, Deque<Lote>> lotesPorProducto = new HashMap<>();
    private final AtomicInteger secuenciaLotes = new AtomicInteger();

    public Almacen() {
        ubicaciones = new Ubicacion[PASILLOS * NIVELES * POSICIONES];
        int i = 0;
        for (int p = 0; p < PASILLOS; p++)
            for (int n = 1; n <= NIVELES; n++)
                for (int pos = 1; pos <= POSICIONES; pos++) {
                    ubicaciones[i] = new Ubicacion(i, (char) ('A' + p), n, pos);
                    libres.add(ubicaciones[i]);
                    i++;
                }
    }

    /**
     * Guarda {@code cantidad} unidades de un producto como un nuevo lote.
     * Si el almacén no tiene espacio para todo, guarda lo que cabe.
     *
     * @return el lote creado (con su tabla de ubicaciones) o null si no cupo nada
     */
    public Lote almacenar(Producto producto, int cantidad) {
        rw.writeLock().lock();
        try {
            int capacidadLibre = libres.size() * CAPACIDAD_UBICACION;
            int aGuardar = Math.min(cantidad, capacidadLibre);
            if (aGuardar <= 0) return null;
            Lote lote = new Lote(String.format("L-%04d", secuenciaLotes.incrementAndGet()), producto, aGuardar);
            int pendiente = aGuardar;
            while (pendiente > 0) {
                Ubicacion u = libres.pollFirst();             // primer ajuste: la libre de menor dirección
                int enEsta = Math.min(pendiente, CAPACIDAD_UBICACION);
                u.ocupar(lote, enEsta);
                lote.agregarUbicacion(u);
                pendiente -= enEsta;
            }
            lotesPorProducto.computeIfAbsent(producto.id(), k -> new ArrayDeque<>()).addLast(lote);
            return lote;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /**
     * Retira del almacén la mercadería de todas las líneas de un pedido, de forma
     * <b>atómica</b>: o hay existencias para todas las líneas y se retiran todas, o
     * no se retira nada. Así nunca queda un pedido "a medias" acaparando existencias.
     *
     * @return true si se retiró todo
     */
    public boolean retirar(List<LineaPedido> lineas) {
        rw.writeLock().lock();
        try {
            // 1) Verificar (sumando por producto, por si un producto aparece en dos líneas)
            Map<String, Integer> necesario = new HashMap<>();
            for (LineaPedido l : lineas) necesario.merge(l.producto().id(), l.cantidad(), Integer::sum);
            for (Map.Entry<String, Integer> e : necesario.entrySet()) {
                if (stockSinCandado(e.getKey()) < e.getValue()) return false;
            }
            // 2) Retirar
            for (Map.Entry<String, Integer> e : necesario.entrySet()) retirarSinCandado(e.getKey(), e.getValue());
            return true;
        } finally {
            rw.writeLock().unlock();
        }
    }

    /** Retira unidades de los lotes más antiguos; libera las ubicaciones que quedan vacías. */
    private void retirarSinCandado(String productoId, int cantidad) {
        Deque<Lote> cola = lotesPorProducto.get(productoId);
        int pendiente = cantidad;
        while (pendiente > 0 && cola != null && !cola.isEmpty()) {
            Lote lote = cola.peekFirst();
            List<Ubicacion> tabla = lote.tablaInterna();
            // Dentro del lote se vacía primero la última ubicación (normalmente la
            // parcialmente llena), lo que reduce la fragmentación interna.
            while (pendiente > 0 && !tabla.isEmpty()) {
                Ubicacion u = tabla.get(tabla.size() - 1);
                int tomar = Math.min(pendiente, u.unidades());
                u.restar(tomar);
                lote.restar(tomar);
                pendiente -= tomar;
                if (u.unidades() == 0) {
                    tabla.remove(tabla.size() - 1);
                    u.liberar();
                    libres.add(u);  // vuelve a la lista de libres
                }
            }
            if (lote.restantes() == 0) cola.pollFirst();
        }
    }

    private int stockSinCandado(String productoId) {
        Deque<Lote> cola = lotesPorProducto.get(productoId);
        if (cola == null) return 0;
        int s = 0;
        for (Lote l : cola) s += l.restantes();
        return s;
    }

    /** Existencias actuales de un producto. */
    public int stock(String productoId) {
        rw.readLock().lock();
        try {
            return stockSinCandado(productoId);
        } finally {
            rw.readLock().unlock();
        }
    }

    /** Indica si hay existencias para todas las líneas (consulta, no reserva). */
    public boolean hayExistencias(List<LineaPedido> lineas) {
        rw.readLock().lock();
        try {
            Map<String, Integer> necesario = new HashMap<>();
            for (LineaPedido l : lineas) necesario.merge(l.producto().id(), l.cantidad(), Integer::sum);
            for (Map.Entry<String, Integer> e : necesario.entrySet()) {
                if (stockSinCandado(e.getKey()) < e.getValue()) return false;
            }
            return true;
        } finally {
            rw.readLock().unlock();
        }
    }

    /** Lista de productos de las líneas que no tienen existencias suficientes. */
    public List<String> faltantes(List<LineaPedido> lineas) {
        rw.readLock().lock();
        try {
            Map<String, Integer> necesario = new HashMap<>();
            Map<String, String> nombres = new HashMap<>();
            for (LineaPedido l : lineas) {
                necesario.merge(l.producto().id(), l.cantidad(), Integer::sum);
                nombres.put(l.producto().id(), l.producto().nombre());
            }
            List<String> r = new ArrayList<>();
            necesario.forEach((id, n) -> {
                int s = stockSinCandado(id);
                if (s < n) r.add(nombres.get(id) + " (" + s + "/" + n + ")");
            });
            return r;
        } finally {
            rw.readLock().unlock();
        }
    }

    /**
     * Ejecuta una lectura consistente del almacén completo bajo el candado de lectura
     * (lo usa la interfaz para dibujar el mapa sin ver estados intermedios).
     */
    public <T> T leer(Function<Almacen, T> lector) {
        rw.readLock().lock();
        try {
            return lector.apply(this);
        } finally {
            rw.readLock().unlock();
        }
    }

    // ---- accesos para lectores (llamar solo dentro de leer(...)) ----
    public Ubicacion[] ubicaciones() { return ubicaciones; }
    public int ubicacionesLibres() { return libres.size(); }
    public Map<String, Deque<Lote>> lotesPorProducto() { return lotesPorProducto; }

    public int totalUbicaciones() { return ubicaciones.length; }
    public int capacidadTotal() { return ubicaciones.length * CAPACIDAD_UBICACION; }
}
