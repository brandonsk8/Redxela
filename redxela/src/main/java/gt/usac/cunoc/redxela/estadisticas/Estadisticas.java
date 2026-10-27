package gt.usac.cunoc.redxela.estadisticas;

import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estadísticas generales del sistema.
 *
 * <p>Cada pedido completado se acumula bajo la política que estaba vigente cuando
 * fue despachado; así se puede cambiar de política en caliente y comparar su
 * desempeño en la tabla "por política".</p>
 *
 * <p>Los contadores simples son {@link AtomicLong} (operaciones atómicas sin
 * candado). Los acumulados por política se actualizan en métodos
 * {@code synchronized} porque se modifican varios campos relacionados a la vez y
 * deben quedar consistentes entre sí (una lectura no debe ver la suma actualizada
 * y el conteo todavía no).</p>
 */
public class Estadisticas {

    /** Acumulado de un grupo de pedidos (una política o un nivel dentro de ella). */
    static final class Acumulado {
        long completados;
        long sumaEsperaMs;
        long sumaProcesoMs;
        long maxEsperaMs;
        long dentroDePlazo;
        long sumaUnidades;

        void sumar(Pedido p, long espera, long proceso) {
            completados++;
            sumaEsperaMs += espera;
            sumaProcesoMs += proceso;
            maxEsperaMs = Math.max(maxEsperaMs, espera);
            if (espera <= p.nivel().esperaMaximaMs()) dentroDePlazo++;
            sumaUnidades += p.unidades();
        }

        Map<String, Object> aMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("completados", completados);
            m.put("esperaPromedioMin", completados == 0 ? 0.0 : sumaEsperaMs / (double) completados / NivelServicio.MS_POR_MINUTO_SIMULADO);
            m.put("procesoPromedioMin", completados == 0 ? 0.0 : sumaProcesoMs / (double) completados / NivelServicio.MS_POR_MINUTO_SIMULADO);
            m.put("esperaMaximaMin", maxEsperaMs / (double) NivelServicio.MS_POR_MINUTO_SIMULADO);
            m.put("cumplimientoPct", completados == 0 ? 0.0 : 100.0 * dentroDePlazo / completados);
            m.put("unidades", sumaUnidades);
            return m;
        }
    }

    private volatile long inicioMs = System.currentTimeMillis();

    private final AtomicLong pedidosGenerados = new AtomicLong();
    private final AtomicLong pedidosManuales = new AtomicLong();
    private final AtomicLong devueltosPorConflicto = new AtomicLong();
    private final AtomicLong conflictosDetectados = new AtomicLong();
    private final AtomicLong conflictosResueltos = new AtomicLong();
    private final AtomicLong camionesRecibidos = new AtomicLong();
    private final AtomicLong unidadesRecibidas = new AtomicLong();

    private Acumulado global = new Acumulado();
    private final Map<PoliticaPlanificacion, Acumulado> porPolitica = new EnumMap<>(PoliticaPlanificacion.class);
    private final Map<NivelServicio, Acumulado> porNivel = new EnumMap<>(NivelServicio.class);
    private final Map<PoliticaPlanificacion, Map<NivelServicio, Acumulado>> nivelPorPolitica = new EnumMap<>(PoliticaPlanificacion.class);

    /** Registra un pedido terminado. */
    public synchronized void pedidoCompletado(Pedido p, PoliticaPlanificacion politica) {
        long espera = p.inicioMs() - p.llegadaMs();
        long proceso = p.finMs() - p.inicioMs();
        global.sumar(p, espera, proceso);
        porPolitica.computeIfAbsent(politica, k -> new Acumulado()).sumar(p, espera, proceso);
        porNivel.computeIfAbsent(p.nivel(), k -> new Acumulado()).sumar(p, espera, proceso);
        nivelPorPolitica.computeIfAbsent(politica, k -> new EnumMap<>(NivelServicio.class))
                .computeIfAbsent(p.nivel(), k -> new Acumulado()).sumar(p, espera, proceso);
    }

    public void pedidoGenerado(boolean manual) {
        pedidosGenerados.incrementAndGet();
        if (manual) pedidosManuales.incrementAndGet();
    }
    public void devueltoPorConflicto() { devueltosPorConflicto.incrementAndGet(); }
    public void conflictoDetectado() { conflictosDetectados.incrementAndGet(); }
    public void conflictoResuelto() { conflictosResueltos.incrementAndGet(); }
    public void camionRecibido(int unidades) {
        camionesRecibidos.incrementAndGet();
        unidadesRecibidas.addAndGet(unidades);
    }

    /** Vuelve a cero todos los contadores (para comenzar una nueva medición). */
    public synchronized void reiniciar() {
        inicioMs = System.currentTimeMillis();
        pedidosGenerados.set(0);
        pedidosManuales.set(0);
        devueltosPorConflicto.set(0);
        conflictosDetectados.set(0);
        conflictosResueltos.set(0);
        camionesRecibidos.set(0);
        unidadesRecibidas.set(0);
        global = new Acumulado();
        porPolitica.clear();
        porNivel.clear();
        nivelPorPolitica.clear();
    }

    public long inicioMs() { return inicioMs; }

    /** Vista para la interfaz. */
    public synchronized Map<String, Object> aMapa() {
        Map<String, Object> m = new LinkedHashMap<>();
        long transcurrido = Math.max(1, System.currentTimeMillis() - inicioMs);
        m.put("transcurridoMin", transcurrido / (double) NivelServicio.MS_POR_MINUTO_SIMULADO);
        m.put("generados", pedidosGenerados.get());
        m.put("manuales", pedidosManuales.get());
        m.put("devueltosPorConflicto", devueltosPorConflicto.get());
        m.put("conflictosDetectados", conflictosDetectados.get());
        m.put("conflictosResueltos", conflictosResueltos.get());
        m.put("camionesRecibidos", camionesRecibidos.get());
        m.put("unidadesRecibidas", unidadesRecibidas.get());
        m.put("global", global.aMapa());
        m.put("throughputPorHora", global.completados * 60.0 * NivelServicio.MS_POR_MINUTO_SIMULADO / transcurrido);

        Map<String, Object> pol = new LinkedHashMap<>();
        porPolitica.forEach((k, v) -> {
            Map<String, Object> x = v.aMapa();
            Map<String, Object> niveles = new LinkedHashMap<>();
            Map<NivelServicio, Acumulado> np = nivelPorPolitica.get(k);
            if (np != null) np.forEach((n, a) -> niveles.put(String.valueOf(n.numero()), a.aMapa()));
            x.put("porNivel", niveles);
            x.put("nombre", k.nombre());
            pol.put(k.name(), x);
        });
        m.put("porPolitica", pol);

        Map<String, Object> niv = new LinkedHashMap<>();
        porNivel.forEach((k, v) -> niv.put(String.valueOf(k.numero()), v.aMapa()));
        m.put("porNivel", niv);
        return m;
    }
}
