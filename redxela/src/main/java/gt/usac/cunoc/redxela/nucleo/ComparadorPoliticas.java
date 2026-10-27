package gt.usac.cunoc.redxela.nucleo;

import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.planificacion.Planificable;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Banco de pruebas de políticas: compara todas las políticas sobre <b>la misma
 * carga de trabajo</b> antes de elegir una para producción.
 *
 * <p>Comparar en la simulación en vivo no es justo: cada política ve pedidos
 * distintos, en momentos distintos. Aquí se genera una sola secuencia de pedidos
 * con una semilla fija (mismos tipos, cantidades y tiempos de llegada) y se ejecuta
 * una <b>simulación de eventos discretos</b> por política: el reloj salta de evento
 * en evento (llegada o fin de un pedido) sin dormir, por eso cientos de pedidos se
 * simulan en milisegundos. El despacho usa las mismas reglas que el
 * {@link Despachador}: orden por política, asignación todo o nada y relleno.</p>
 *
 * <p>Es una herramienta de análisis de un solo hilo: no comparte estado con la
 * simulación concurrente, así que no necesita sincronización.</p>
 */
public class ComparadorPoliticas {

    /** Pedido "en papel" para la simulación de eventos discretos. */
    record Trabajo(String id, int nivelNumero, double llegadaMin, int unidades, int esperaMaximaMin,
                   Map<TipoRecurso, Integer> req, double duracionMin) implements Planificable {}

    /** Pedido en ejecución dentro de la simulación. */
    private record EnCurso(Trabajo t, double fin) {}

    private final List<Producto> catalogo;

    public ComparadorPoliticas(List<Producto> catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * @param cantidad      pedidos a simular (10..5000)
     * @param tasaPorHora   llegadas por hora simulada (= por minuto real)
     * @param semilla       semilla del generador (misma semilla = misma carga)
     */
    public Map<String, Object> comparar(int cantidad, double tasaPorHora, long semilla) {
        if (cantidad < 10 || cantidad > 5000) throw new ValidacionException("La cantidad de pedidos debe estar entre 10 y 5000");
        if (tasaPorHora <= 0 || tasaPorHora > 600) throw new ValidacionException("La tasa debe estar entre 1 y 600 pedidos por hora");

        List<Trabajo> carga = generarCarga(cantidad, tasaPorHora, semilla);
        List<Map<String, Object>> resultados = new ArrayList<>();
        for (PoliticaPlanificacion pol : PoliticaPlanificacion.values()) {
            resultados.add(simular(pol, carga));
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pedidos", cantidad);
        r.put("tasaPorHora", tasaPorHora);
        r.put("semilla", semilla);
        r.put("resultados", resultados);
        r.put("mejorEspera", mejor(resultados, "esperaPromedioMin", true));
        r.put("mejorCumplimiento", mejor(resultados, "cumplimientoPct", false));
        r.put("mejorEsperaMaxima", mejor(resultados, "esperaMaximaMin", true));
        return r;
    }

    /** Genera la carga reutilizando las mismas reglas del generador automático. */
    private List<Trabajo> generarCarga(int n, double tasa, long semilla) {
        Random azar = new Random(semilla);
        Map<NivelServicio, Cliente> clientes = new EnumMap<>(NivelServicio.class);
        for (NivelServicio ns : NivelServicio.values()) {
            clientes.put(ns, new Cliente("SIM-" + ns.numero(), "Simulado", "-", ns));
        }
        List<Trabajo> carga = new ArrayList<>(n);
        double t = 0;
        for (int i = 0; i < n; i++) {
            t += -Math.log(1 - azar.nextDouble()) * 60.0 / tasa;   // llegada de Poisson (tasa por hora simulada)
            NivelServicio ns = NivelServicio.values()[GeneradorPedidos.sortear(azar, GeneradorPedidos.PESO_NIVEL)];
            List<LineaPedido> lineas = GeneradorPedidos.generarLineas(azar, catalogo);
            // Se construye un Pedido real solo para reutilizar su cálculo de recursos y duración
            Pedido p = new Pedido("S" + i, clientes.get(ns), lineas, Pedido.Origen.ESCENARIO);
            carga.add(new Trabajo(String.format("S%05d", i), ns.numero(), t, p.unidades(), ns.esperaMaximaMin(),
                    p.recursosRequeridos(), p.duracionEstimadaMs() / (double) NivelServicio.MS_POR_MINUTO_SIMULADO));
        }
        return carga;
    }

    /** Simulación de eventos discretos de una política. */
    private Map<String, Object> simular(PoliticaPlanificacion pol, List<Trabajo> carga) {
        Map<TipoRecurso, Integer> libres = new EnumMap<>(TipoRecurso.class);
        Map<TipoRecurso, Double> ocupadoMin = new EnumMap<>(TipoRecurso.class);
        for (TipoRecurso r : TipoRecurso.values()) { libres.put(r, r.capacidad()); ocupadoMin.put(r, 0.0); }

        PriorityQueue<EnCurso> enCurso = new PriorityQueue<>((a, b) -> Double.compare(a.fin(), b.fin()));
        List<Trabajo> espera = new ArrayList<>();
        int sig = 0;
        double reloj = 0;

        double sumaEspera = 0, maxEspera = 0, sumaRetorno = 0;
        int dentro = 0;
        double[] esperaNivel = new double[6];
        int[] cuentaNivel = new int[6];
        int[] dentroNivel = new int[6];

        while (sig < carga.size() || !espera.isEmpty() || !enCurso.isEmpty()) {
            double tLlegada = sig < carga.size() ? carga.get(sig).llegadaMin() : Double.POSITIVE_INFINITY;
            double tFin = enCurso.isEmpty() ? Double.POSITIVE_INFINITY : enCurso.peek().fin();
            reloj = Math.min(tLlegada, tFin);

            // 1) Terminan los pedidos cuyo fin es ahora: devuelven recursos
            while (!enCurso.isEmpty() && enCurso.peek().fin() <= reloj) {
                EnCurso e = enCurso.poll();
                e.t().req().forEach((r, c) -> libres.merge(r, c, Integer::sum));
            }
            // 2) Llegan los pedidos cuyo instante es ahora
            while (sig < carga.size() && carga.get(sig).llegadaMin() <= reloj) espera.add(carga.get(sig++));

            // 3) Despacho: mismo criterio que el despachador real
            espera.sort(pol.comparador(reloj));
            Iterator<Trabajo> it = espera.iterator();
            while (it.hasNext()) {
                Trabajo t = it.next();
                if (!cabe(libres, t.req())) continue;            // relleno: se prueba el siguiente
                t.req().forEach((r, c) -> {
                    libres.merge(r, -c, Integer::sum);
                    ocupadoMin.merge(r, c * t.duracionMin(), Double::sum);
                });
                enCurso.add(new EnCurso(t, reloj + t.duracionMin()));
                it.remove();
                double w = reloj - t.llegadaMin();
                sumaEspera += w;
                maxEspera = Math.max(maxEspera, w);
                sumaRetorno += w + t.duracionMin();
                boolean ok = w <= t.esperaMaximaMin();
                if (ok) dentro++;
                esperaNivel[t.nivelNumero()] += w;
                cuentaNivel[t.nivelNumero()]++;
                if (ok) dentroNivel[t.nivelNumero()]++;
            }
        }

        int n = carga.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("politica", pol.name());
        m.put("nombre", pol.nombre());
        m.put("esperaPromedioMin", sumaEspera / n);
        m.put("esperaMaximaMin", maxEspera);
        m.put("retornoPromedioMin", sumaRetorno / n);
        m.put("cumplimientoPct", 100.0 * dentro / n);
        m.put("duracionTotalMin", reloj);
        m.put("throughputPorHora", n * 60.0 / Math.max(reloj, 1e-9));
        Map<String, Object> niveles = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("pedidos", cuentaNivel[i]);
            x.put("esperaPromedioMin", cuentaNivel[i] == 0 ? 0.0 : esperaNivel[i] / cuentaNivel[i]);
            x.put("cumplimientoPct", cuentaNivel[i] == 0 ? 100.0 : 100.0 * dentroNivel[i] / cuentaNivel[i]);
            niveles.put(String.valueOf(i), x);
        }
        m.put("porNivel", niveles);
        Map<String, Object> uso = new LinkedHashMap<>();
        for (TipoRecurso r : TipoRecurso.values()) {
            uso.put(r.name(), 100.0 * ocupadoMin.get(r) / (r.capacidad() * Math.max(reloj, 1e-9)));
        }
        m.put("usoRecursosPct", uso);
        return m;
    }

    private static boolean cabe(Map<TipoRecurso, Integer> libres, Map<TipoRecurso, Integer> req) {
        for (Map.Entry<TipoRecurso, Integer> e : req.entrySet()) {
            if (libres.get(e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    private static String mejor(List<Map<String, Object>> res, String campo, boolean menor) {
        String mejor = null;
        double valor = menor ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (Map<String, Object> r : res) {
            double v = (Double) r.get(campo);
            if (menor ? v < valor : v > valor) { valor = v; mejor = (String) r.get("politica"); }
        }
        return mejor;
    }
}
