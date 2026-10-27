package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoMercancia;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generador automático de pedidos: simula la llegada continua y sin límite de
 * clientes de todos los niveles.
 *
 * <p>Las llegadas siguen un <b>proceso de Poisson</b>: el tiempo entre un pedido y
 * el siguiente se sortea con distribución exponencial de media
 * {@code 60 s / tasa} (la tasa se expresa en pedidos por hora simulada; como
 * 1 minuto simulado = 1 s real, una hora simulada dura 1 minuto real). Es el modelo estándar de llegadas aleatorias e
 * independientes (teoría de colas) y produce ráfagas y huecos realistas, en lugar
 * de un pedido cada N segundos exactos.</p>
 *
 * <p>De vez en cuando (10 %) el pedido viene de un cliente nuevo que primero crea
 * su cuenta y elige su nivel de servicio.</p>
 */
public class GeneradorPedidos implements Runnable {

    /** Pesos para sortear el tipo de mercancía de cada línea. */
    static final double[] PESO_TIPO = {
            30, // GENERAL
            20, // FRAGIL
            9,  // PESADO
            9,  // VOLUMINOSO
            16, // PERECEDERO
            16  // ALTO_VALOR
    };
    /** Pesos para el nivel de servicio de los clientes nuevos (1..5). */
    static final double[] PESO_NIVEL = {10, 20, 35, 20, 15};

    private static final String[] NOMBRES = {"Andrea", "Luis", "Sofía", "Diego", "Valeria", "Kevin", "Gabriela",
            "Josué", "Daniela", "Fernando", "Lucía", "Edgar", "Paola", "Byron", "Ximena", "Walter"};
    private static final String[] APELLIDOS = {"Ajanel", "Barrios", "Cifuentes", "De León", "Escobar", "Fuentes",
            "García", "Hernández", "Ixtabalán", "Juárez", "López", "Monterroso", "Orozco", "Pérez", "Quemé", "Sapón"};

    private final CentroDistribucion centro;
    private final Random azar = new Random();
    private volatile boolean activo = true;

    public GeneradorPedidos(CentroDistribucion centro) {
        this.centro = centro;
    }

    @Override
    public void run() {
        while (activo) {
            try {
                double tasa = Math.max(1, centro.pedidosPorHora());
                long espera = Math.round(-Math.log(1 - azar.nextDouble()) * 60_000.0 / tasa);
                Thread.sleep(Math.min(Math.max(espera, 50), 20_000));
                if (!centro.generadorPedidosActivo()) continue;
                Cliente c = elegirCliente();
                centro.recibirPedido(c, generarLineas(azar, centro.catalogo().listaProductos()), false);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                centro.bitacora().error("Generador de pedidos: " + e.getMessage());
            }
        }
    }

    private Cliente elegirCliente() {
        List<Cliente> clientes = centro.catalogo().clientes();
        if (azar.nextDouble() < 0.10 || clientes.isEmpty()) {
            String nombre = NOMBRES[azar.nextInt(NOMBRES.length)] + " " + APELLIDOS[azar.nextInt(APELLIDOS.length)];
            NivelServicio n = NivelServicio.values()[sortear(azar, PESO_NIVEL)];
            Cliente nuevo = centro.catalogo().crearCliente(nombre,
                    nombre.toLowerCase().replace(" ", ".").replace("é", "e").replace("á", "a") + "@correo.gt", n);
            centro.bitacora().info("Nueva cuenta " + nuevo.id() + ": " + nombre + " (" + n.nombre() + ")");
            return nuevo;
        }
        return clientes.get(azar.nextInt(clientes.size()));
    }

    /**
     * Sortea de 1 a 3 líneas. Los productos pesados y voluminosos se piden en menor
     * cantidad (1–2) que los demás (1–5). Es estático para que el comparador de
     * políticas genere exactamente el mismo tipo de carga.
     */
    static List<LineaPedido> generarLineas(Random azar, List<Producto> catalogo) {
        int nLineas = 1 + (azar.nextDouble() < 0.55 ? 0 : (azar.nextDouble() < 0.7 ? 1 : 2));
        List<LineaPedido> lineas = new ArrayList<>();
        List<String> usados = new ArrayList<>();
        for (int i = 0; i < nLineas; i++) {
            TipoMercancia t = TipoMercancia.values()[sortear(azar, PESO_TIPO)];
            List<Producto> deTipo = catalogo.stream().filter(p -> p.tipo() == t && !usados.contains(p.id())).toList();
            if (deTipo.isEmpty()) continue;
            Producto p = deTipo.get(azar.nextInt(deTipo.size()));
            usados.add(p.id());
            int max = (t == TipoMercancia.PESADO || t == TipoMercancia.VOLUMINOSO) ? 2 : 5;
            lineas.add(new LineaPedido(p, 1 + azar.nextInt(max)));
        }
        if (lineas.isEmpty()) lineas.add(new LineaPedido(catalogo.get(0), 1));
        return lineas;
    }

    /** Elige un índice con probabilidad proporcional a su peso. */
    static int sortear(Random azar, double[] pesos) {
        double total = 0;
        for (double w : pesos) total += w;
        double x = azar.nextDouble() * total;
        for (int i = 0; i < pesos.length; i++) {
            x -= pesos[i];
            if (x < 0) return i;
        }
        return pesos.length - 1;
    }

    public void detener() { activo = false; }
}
