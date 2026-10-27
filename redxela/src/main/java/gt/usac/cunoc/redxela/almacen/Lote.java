package gt.usac.cunoc.redxela.almacen;

import gt.usac.cunoc.redxela.modelo.Producto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lote de mercadería recibido en un mismo camión.
 *
 * <p>Su {@link #tabla()} es el equivalente a una <b>tabla de páginas</b>: lista, en
 * orden, las ubicaciones físicas donde quedó guardado. Esas ubicaciones no tienen
 * por qué ser contiguas (pueden estar en pasillos y niveles distintos), igual que
 * las páginas de un proceso pueden quedar en marcos dispersos de la memoria.</p>
 */
public class Lote {

    private final String id;
    private final Producto producto;
    private final long ingresoMs;
    private final int cantidadInicial;
    private final List<Ubicacion> tabla = new ArrayList<>();
    private int restantes;

    Lote(String id, Producto producto, int cantidadInicial) {
        this.id = id;
        this.producto = producto;
        this.cantidadInicial = cantidadInicial;
        this.restantes = cantidadInicial;
        this.ingresoMs = System.currentTimeMillis();
    }

    void agregarUbicacion(Ubicacion u) { tabla.add(u); }
    void quitarUbicacion(Ubicacion u) { tabla.remove(u); }
    void restar(int n) { restantes -= n; }
    /** Acceso interno modificable (solo dentro del candado de escritura del almacén). */
    List<Ubicacion> tablaInterna() { return tabla; }

    public String id() { return id; }
    public Producto producto() { return producto; }
    public long ingresoMs() { return ingresoMs; }
    public int cantidadInicial() { return cantidadInicial; }
    public int restantes() { return restantes; }
    public List<Ubicacion> tabla() { return Collections.unmodifiableList(tabla); }

    /** Indica si sus ubicaciones NO son direcciones consecutivas. */
    public boolean esDisperso() {
        for (int i = 1; i < tabla.size(); i++) {
            if (tabla.get(i).indice() != tabla.get(i - 1).indice() + 1) return true;
        }
        return false;
    }
}
