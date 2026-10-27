package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoMercancia;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Catálogo de productos (fijo, definido por el estudiante) y registro de cuentas
 * de cliente (crece durante la simulación).
 *
 * <p>Los productos se guardan en un mapa inmutable: se leen desde muchos hilos y
 * nunca cambian, por lo que no necesitan sincronización. Los clientes se guardan
 * en un {@link ConcurrentHashMap} porque los crean tanto el generador automático
 * como la interfaz al mismo tiempo; el contador atómico asegura ids únicos.</p>
 */
public final class Catalogo {

    private final Map<String, Producto> productos;
    private final Map<String, Cliente> clientes = new ConcurrentHashMap<>();
    private final List<Cliente> listaClientes = new CopyOnWriteArrayList<>();
    private final AtomicInteger secuenciaClientes = new AtomicInteger();

    public Catalogo() {
        Map<String, Producto> m = new LinkedHashMap<>();
        agregar(m, "GEN-01", "Camiseta de algodón", TipoMercancia.GENERAL);
        agregar(m, "GEN-02", "Libro de texto", TipoMercancia.GENERAL);
        agregar(m, "GEN-03", "Juguete armable", TipoMercancia.GENERAL);
        agregar(m, "GEN-04", "Mochila escolar", TipoMercancia.GENERAL);
        agregar(m, "FRA-01", "Juego de vasos de vidrio", TipoMercancia.FRAGIL);
        agregar(m, "FRA-02", "Set de perfumes", TipoMercancia.FRAGIL);
        agregar(m, "FRA-03", "Lámpara de cerámica", TipoMercancia.FRAGIL);
        agregar(m, "PES-01", "Refrigeradora", TipoMercancia.PESADO);
        agregar(m, "PES-02", "Saco de cemento 42.5 kg", TipoMercancia.PESADO);
        agregar(m, "PES-03", "Lavadora", TipoMercancia.PESADO);
        agregar(m, "VOL-01", "Colchón matrimonial", TipoMercancia.VOLUMINOSO);
        agregar(m, "VOL-02", "Sofá de 3 plazas", TipoMercancia.VOLUMINOSO);
        agregar(m, "PER-01", "Lácteos refrigerados", TipoMercancia.PERECEDERO);
        agregar(m, "PER-02", "Vacunas veterinarias", TipoMercancia.PERECEDERO);
        agregar(m, "PER-03", "Fruta de exportación", TipoMercancia.PERECEDERO);
        agregar(m, "AV-01", "Laptop", TipoMercancia.ALTO_VALOR);
        agregar(m, "AV-02", "Teléfono inteligente", TipoMercancia.ALTO_VALOR);
        agregar(m, "AV-03", "Reloj de oro", TipoMercancia.ALTO_VALOR);
        this.productos = Collections.unmodifiableMap(m);

        // Cuentas iniciales repartidas en los cinco niveles de servicio
        crearCliente("Farmacia San Rafael", "compras@sanrafael.gt", NivelServicio.EXPRES);
        crearCliente("Hospital Los Altos", "logistica@losaltos.gt", NivelServicio.EXPRES);
        crearCliente("Tienda Electrónica Xela", "ventas@electroxela.gt", NivelServicio.PRIORITARIO);
        crearCliente("María Fernanda López", "mflopez@correo.gt", NivelServicio.PRIORITARIO);
        crearCliente("Supermercado La Democracia", "pedidos@lademocracia.gt", NivelServicio.ESTANDAR);
        crearCliente("José Antonio Pérez", "japerez@correo.gt", NivelServicio.ESTANDAR);
        crearCliente("Librería Cervantes", "info@cervantes.gt", NivelServicio.ESTANDAR);
        crearCliente("Muebles El Roble", "bodega@elroble.gt", NivelServicio.PROGRAMADO);
        crearCliente("Ferretería Totonicapán", "ventas@ferretoto.gt", NivelServicio.PROGRAMADO);
        crearCliente("Ana Lucía Xicará", "alxicara@correo.gt", NivelServicio.ECONOMICO);
        crearCliente("Colegio Liceo Quetzalteco", "admin@liceoq.gt", NivelServicio.ECONOMICO);
        crearCliente("Carlos Ixcoy", "cixcoy@correo.gt", NivelServicio.ECONOMICO);
    }

    private static void agregar(Map<String, Producto> m, String id, String nombre, TipoMercancia t) {
        m.put(id, new Producto(id, nombre, t));
    }

    /** Crea una cuenta nueva. Valida los datos antes de registrarla. */
    public Cliente crearCliente(String nombre, String correo, NivelServicio nivel) {
        if (nombre == null || nombre.isBlank()) throw new ValidacionException("El nombre del cliente es obligatorio");
        if (nivel == null) throw new ValidacionException("Debe seleccionar un nivel de servicio");
        String c = (correo == null || correo.isBlank()) ? "sin-correo" : correo.trim();
        if (!c.equals("sin-correo") && !c.contains("@")) throw new ValidacionException("El correo no es válido");
        Cliente cl = new Cliente(String.format("C-%03d", secuenciaClientes.incrementAndGet()), nombre.trim(), c, nivel);
        clientes.put(cl.id(), cl);
        listaClientes.add(cl);
        return cl;
    }

    public Producto producto(String id) {
        Producto p = productos.get(id);
        if (p == null) throw ValidacionException.noEncontrado("El producto " + id);
        return p;
    }

    public Cliente cliente(String id) {
        Cliente c = clientes.get(id);
        if (c == null) throw ValidacionException.noEncontrado("El cliente " + id);
        return c;
    }

    public Collection<Producto> productos() { return productos.values(); }
    public List<Producto> listaProductos() { return new ArrayList<>(productos.values()); }
    public List<Cliente> clientes() { return listaClientes; }
}
