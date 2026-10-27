package gt.usac.cunoc.redxela.web;

import gt.usac.cunoc.redxela.almacen.Almacen;
import gt.usac.cunoc.redxela.almacen.Lote;
import gt.usac.cunoc.redxela.almacen.Ubicacion;
import gt.usac.cunoc.redxela.interbloqueo.Conflicto;
import gt.usac.cunoc.redxela.ipc.BufferAcotado;
import gt.usac.cunoc.redxela.ipc.MensajeRecepcion;
import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.modelo.TipoMercancia;
import gt.usac.cunoc.redxela.modelo.TipoRecurso;
import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.recursos.Operacion;
import gt.usac.cunoc.redxela.recursos.PoolRecurso;
import gt.usac.cunoc.redxela.recursos.Unidad;
import gt.usac.cunoc.redxela.util.Bitacora;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Construye la "fotografía" del sistema que se envía a la interfaz.
 *
 * <p>Solo <b>lee</b>: nunca modifica el estado de la simulación. Lee campos
 * {@code volatile}, colecciones concurrentes y el almacén bajo su candado de lectura,
 * de modo que puede ejecutarse en el hilo del servidor web mientras los hilos de la
 * simulación siguen trabajando.</p>
 */
public final class VistaEstado {

    private VistaEstado() {}

    // =========================================================================
    // Catálogo (datos casi estáticos)
    // =========================================================================

    public static Map<String, Object> catalogo(CentroDistribucion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> productos = new ArrayList<>();
        for (Producto p : c.catalogo().productos()) {
            productos.add(mapa("id", p.id(), "nombre", p.nombre(), "tipo", p.tipo().name(), "tipoNombre", p.tipo().nombre()));
        }
        m.put("productos", productos);

        List<Object> tipos = new ArrayList<>();
        for (TipoMercancia t : TipoMercancia.values()) {
            Map<String, Object> r = new LinkedHashMap<>();
            t.recursos().forEach((k, v) -> r.put(k.name(), v));
            tipos.add(mapa("id", t.name(), "nombre", t.nombre(), "descripcion", t.descripcion(),
                    "factorTiempo", t.factorTiempo(), "recursos", r, "parConflictivo", t.usaParConflictivo()));
        }
        m.put("tipos", tipos);

        List<Object> niveles = new ArrayList<>();
        for (NivelServicio n : NivelServicio.values()) {
            niveles.add(mapa("id", n.name(), "numero", n.numero(), "nombre", n.nombre(),
                    "esperaMaximaMin", n.esperaMaximaMin(), "descripcion", n.descripcion()));
        }
        m.put("niveles", niveles);

        List<Object> politicas = new ArrayList<>();
        for (PoliticaPlanificacion p : PoliticaPlanificacion.values()) {
            politicas.add(mapa("id", p.name(), "nombre", p.nombre(), "descripcion", p.descripcion(), "criterio", p.criterio()));
        }
        m.put("politicas", politicas);

        List<Object> recursos = new ArrayList<>();
        for (TipoRecurso t : TipoRecurso.values()) {
            recursos.add(mapa("id", t.name(), "nombre", t.nombre(), "corto", t.prefijoUnidad(),
                    "capacidad", t.capacidad(), "orden", t.ordenGlobal() + 1));
        }
        m.put("recursos", recursos);
        m.put("clientes", clientes(c));
        m.put("almacen", mapa("pasillos", Almacen.PASILLOS, "niveles", Almacen.NIVELES, "posiciones", Almacen.POSICIONES,
                "capacidadUbicacion", Almacen.CAPACIDAD_UBICACION, "capacidadTotal", c.almacen().capacidadTotal()));
        m.put("capacidadBuffer", CentroDistribucion.CAPACIDAD_BUFFER);
        m.put("minutoSimuladoMs", NivelServicio.MS_POR_MINUTO_SIMULADO);
        return m;
    }

    public static List<Object> clientes(CentroDistribucion c) {
        List<Object> l = new ArrayList<>();
        for (Cliente cl : c.catalogo().clientes()) {
            l.add(mapa("id", cl.id(), "nombre", cl.nombre(), "correo", cl.correo(),
                    "nivel", cl.nivel().numero(), "nivelNombre", cl.nivel().nombre()));
        }
        return l;
    }

    // =========================================================================
    // Estado en vivo
    // =========================================================================

    public static Map<String, Object> estado(CentroDistribucion c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", System.currentTimeMillis());
        m.put("config", config(c));
        m.put("recursos", recursos(c));
        m.put("operaciones", operaciones(c));
        m.put("espera", espera(c));
        m.put("completados", completados(c));
        m.put("conflictos", conflictos(c));
        m.put("ipc", ipc(c));
        m.put("inventario", inventario(c));
        m.put("almacen", almacen(c));
        Map<String, Object> est = c.estadisticas().aMapa();
        est.put("usoRecursosPct", usoRecursos(c));
        est.put("enEspera", c.lista().tamanio());
        est.put("enProceso", c.despachador().trabajadoresActivos());
        est.put("clientes", c.catalogo().clientes().size());
        m.put("estadisticas", est);
        List<Object> eventos = new ArrayList<>();
        for (Bitacora.Evento e : c.bitacora().recientes(80)) {
            eventos.add(mapa("seq", e.seq(), "hora", e.hora(), "nivel", e.nivel().name(), "mensaje", e.mensaje()));
        }
        m.put("bitacora", eventos);
        m.put("numClientes", c.catalogo().clientes().size());
        return m;
    }

    private static Map<String, Object> config(CentroDistribucion c) {
        PoliticaPlanificacion p = c.politica();
        return mapa("politica", p.name(), "politicaNombre", p.nombre(), "politicaCriterio", p.criterio(),
                "politicaDescripcion", p.descripcion(),
                "prevencion", c.prevencionActiva(), "generador", c.generadorPedidosActivo(),
                "tasa", c.pedidosPorHora(), "reposicion", c.reposicionAutomatica(),
                "factorTiempo", c.factorTiempo(), "retardoInventario", c.retardoInventarioMs());
    }

    private static List<Object> recursos(CentroDistribucion c) {
        List<Object> l = new ArrayList<>();
        for (PoolRecurso pool : c.gestor().pools()) {
            List<Object> unidades = new ArrayList<>();
            for (Unidad u : pool.unidades()) {
                Operacion d = u.duenio();
                List<Object> esperando = new ArrayList<>();
                for (Operacion o : u.esperando()) esperando.add(o.id());
                unidades.add(mapa("nombre", u.nombre(), "libre", d == null,
                        "duenio", d == null ? null : d.id(), "etiqueta", d == null ? null : d.etiqueta(),
                        "tipoDuenio", d == null ? null : d.tipo().name(), "esperando", esperando));
            }
            TipoRecurso t = pool.tipo();
            l.add(mapa("tipo", t.name(), "nombre", t.nombre(), "corto", t.prefijoUnidad(),
                    "capacidad", pool.capacidad(), "disponibles", pool.disponibles(),
                    "ocupadas", pool.ocupadas(), "unidades", unidades));
        }
        return l;
    }

    /** Operaciones activas: pedidos en proceso y descargas de camiones. */
    private static List<Object> operaciones(CentroDistribucion c) {
        List<Operacion> ops = new ArrayList<>(c.gestor().operaciones());
        ops.sort(Comparator.comparing(Operacion::id));
        List<Object> l = new ArrayList<>();
        for (Operacion o : ops) {
            List<Object> retiene = new ArrayList<>();
            for (Unidad u : o.retenidas()) retiene.add(u.nombre());
            Unidad espera = o.esperandoPor();
            Map<String, Object> x = mapa("id", o.id(), "tipo", o.tipo().name(), "descripcion", o.descripcion(),
                    "fase", o.fase(), "retiene", retiene, "espera", espera == null ? null : espera.nombre(),
                    "victima", o.victima());
            Pedido p = o.pedido();
            if (p != null) {
                x.putAll(datosPedido(p));
                x.put("progreso", p.progreso());
                x.put("restanteMs", p.restanteMs());
                x.put("duracionMs", Math.round(p.duracionEstimadaMs() * c.factorTiempo()));
            } else {
                Camion cam = o.camion();
                x.put("producto", cam.producto().nombre());
                x.put("cantidad", cam.cantidad());
                x.put("anden", cam.anden() + 1);
            }
            l.add(x);
        }
        return l;
    }

    private static List<Object> espera(CentroDistribucion c) {
        List<Object> l = new ArrayList<>();
        int pos = 1;
        for (Pedido p : c.lista().ordenada(c.politica())) {
            Map<String, Object> x = datosPedido(p);
            x.put("posicion", pos++);
            x.put("puntaje", p.ultimoPuntaje());
            x.put("esperaMin", p.esperaMs() / (double) NivelServicio.MS_POR_MINUTO_SIMULADO);
            x.put("vencido", p.plazoVencido());
            x.put("motivo", p.motivoEspera());
            l.add(x);
        }
        return l;
    }

    private static List<Object> completados(CentroDistribucion c) {
        List<Object> l = new ArrayList<>();
        for (Pedido p : c.completados()) {
            if (l.size() >= 20) break;
            Map<String, Object> x = datosPedido(p);
            x.put("esperaMin", (p.inicioMs() - p.llegadaMs()) / (double) NivelServicio.MS_POR_MINUTO_SIMULADO);
            x.put("procesoMin", (p.finMs() - p.inicioMs()) / (double) NivelServicio.MS_POR_MINUTO_SIMULADO);
            x.put("enPlazo", (p.inicioMs() - p.llegadaMs()) <= p.nivel().esperaMaximaMs());
            l.add(x);
        }
        return l;
    }

    private static Map<String, Object> datosPedido(Pedido p) {
        List<Object> tipos = new ArrayList<>();
        for (TipoMercancia t : p.tipos()) tipos.add(t.nombre());
        List<Object> lineas = new ArrayList<>();
        for (LineaPedido l : p.lineas()) lineas.add(mapa("producto", l.producto().nombre(), "cantidad", l.cantidad()));
        Map<String, Object> req = new LinkedHashMap<>();
        p.recursosRequeridos().forEach((k, v) -> req.put(k.prefijoUnidad(), v));
        return mapa("id", p.id(), "cliente", p.cliente().nombre(), "clienteId", p.cliente().id(),
                "nivel", p.nivel().numero(), "nivelNombre", p.nivel().nombre(),
                "esperaMaxMin", p.nivel().esperaMaximaMin(), "unidades", p.unidades(), "tipos", tipos,
                "lineas", lineas, "requiere", req, "estado", p.estado().name(), "estadoNombre", p.estado().etiqueta(),
                "reintentos", p.reintentos(), "origen", p.origen().name());
    }

    private static List<Object> conflictos(CentroDistribucion c) {
        List<Object> l = new ArrayList<>();
        List<Conflicto> todos = new ArrayList<>(c.detector().conflictos());
        for (int i = todos.size() - 1; i >= 0 && l.size() < 10; i--) {
            Conflicto k = todos.get(i);
            List<Object> ciclo = new ArrayList<>();
            for (Conflicto.Eslabon e : k.ciclo()) {
                Operacion o = e.op();
                ciclo.add(mapa("id", o.id(), "tipo", o.tipo().name(), "descripcion", o.descripcion(),
                        "retiene", e.retiene(), "espera", e.espera(), "duenoDeEspera", e.duenoDeEspera(),
                        "nivel", o.pedido() == null ? null : o.pedido().nivel().nombre()));
            }
            l.add(mapa("id", k.id(), "estado", k.estado().name(), "detectadoMs", k.detectadoMs(),
                    "explicacion", k.explicacion(), "ganador", k.ganadorId(), "resueltoMs", k.resueltoMs(), "ciclo", ciclo));
        }
        return l;
    }

    private static Map<String, Object> ipc(CentroDistribucion c) {
        BufferAcotado<MensajeRecepcion> b = c.buffer();
        List<Object> contenido = new ArrayList<>();
        for (MensajeRecepcion m : b.contenido()) {
            contenido.add(mapa("camion", m.camionId(), "producto", m.producto().nombre(), "cantidad", m.cantidad()));
        }
        List<Object> patio = new ArrayList<>();
        for (Camion cam : c.recepcion().patio()) {
            patio.add(mapa("id", cam.id(), "producto", cam.producto().nombre(), "cantidad", cam.cantidad(),
                    "anden", cam.anden() + 1, "manual", cam.manual()));
        }
        List<Object> pendientes = new ArrayList<>();
        for (MensajeRecepcion m : c.inventario().pendientes()) {
            pendientes.add(mapa("camion", m.camionId(), "producto", m.producto().nombre(), "cantidad", m.cantidad()));
        }
        MensajeRecepcion proc = c.inventario().procesando();
        Operacion rec = c.recepcion().actual();
        return mapa("capacidad", b.capacidad(), "ocupados", b.ocupados(), "producidos", b.producidos(),
                "consumidos", b.consumidos(), "esperasPorLleno", b.esperasPorLleno(), "contenido", contenido,
                "patio", patio, "pendientes", pendientes, "procesadosInventario", c.inventario().procesados(),
                "inventarioProcesando", proc == null ? null : mapa("camion", proc.camionId(), "producto",
                        proc.producto().nombre(), "cantidad", proc.cantidad()),
                "recepcionActual", rec == null ? null : mapa("id", rec.id(), "fase", rec.fase(),
                        "producto", rec.camion().producto().nombre(), "cantidad", rec.camion().cantidad()));
    }

    private static List<Object> inventario(CentroDistribucion c) {
        return c.almacen().leer(a -> {
            List<Object> l = new ArrayList<>();
            for (Producto p : c.catalogo().productos()) {
                Deque<Lote> lotes = a.lotesPorProducto().get(p.id());
                List<Object> ls = new ArrayList<>();
                int stock = 0;
                if (lotes != null) {
                    for (Lote lote : lotes) {
                        stock += lote.restantes();
                        List<Object> ub = new ArrayList<>();
                        for (Ubicacion u : lote.tabla()) ub.add(mapa("codigo", u.codigo(), "unidades", u.unidades()));
                        ls.add(mapa("id", lote.id(), "restantes", lote.restantes(), "inicial", lote.cantidadInicial(),
                                "disperso", lote.esDisperso(), "ubicaciones", ub));
                    }
                }
                l.add(mapa("id", p.id(), "nombre", p.nombre(), "tipo", p.tipo().nombre(), "stock", stock, "lotes", ls));
            }
            return l;
        });
    }

    private static Map<String, Object> almacen(CentroDistribucion c) {
        return c.almacen().leer(a -> {
            List<Object> celdas = new ArrayList<>();
            int unidades = 0, usadas = 0;
            for (Ubicacion u : a.ubicaciones()) {
                if (u.libre()) {
                    celdas.add(mapa("c", u.codigo()));
                } else {
                    usadas++;
                    unidades += u.unidades();
                    Lote l = u.lote();
                    celdas.add(mapa("c", u.codigo(), "l", l.id(), "p", l.producto().id(), "n", l.producto().nombre(),
                            "t", l.producto().tipo().name(), "u", u.unidades()));
                }
            }
            int fragInterna = usadas * Almacen.CAPACIDAD_UBICACION - unidades;
            return mapa("celdas", celdas, "ubicacionesUsadas", usadas, "ubicacionesLibres", a.ubicacionesLibres(),
                    "unidades", unidades, "capacidadTotal", a.capacidadTotal(), "fragmentacionInterna", fragInterna,
                    "ocupacionPct", 100.0 * usadas / a.totalUbicaciones());
        });
    }

    /** Porcentaje de tiempo ocupado de cada recurso desde el último reinicio de estadísticas. */
    private static Map<String, Object> usoRecursos(CentroDistribucion c) {
        long transcurrido = Math.max(1, System.currentTimeMillis() - c.estadisticas().inicioMs());
        Map<String, Object> m = new LinkedHashMap<>();
        for (PoolRecurso p : c.gestor().pools()) {
            long ocupado = 0;
            for (Unidad u : p.unidades()) ocupado += u.ocupadoTotalMs();
            m.put(p.tipo().name(), Math.min(100.0, 100.0 * ocupado / (p.capacidad() * (double) transcurrido)));
        }
        return m;
    }

    /** Crea un mapa ordenado a partir de pares clave/valor. */
    static Map<String, Object> mapa(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
