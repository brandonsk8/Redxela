package gt.usac.cunoc.redxela.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import gt.usac.cunoc.redxela.modelo.Cliente;
import gt.usac.cunoc.redxela.modelo.LineaPedido;
import gt.usac.cunoc.redxela.modelo.NivelServicio;
import gt.usac.cunoc.redxela.modelo.Pedido;
import gt.usac.cunoc.redxela.modelo.Producto;
import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;
import gt.usac.cunoc.redxela.nucleo.ComparadorPoliticas;
import gt.usac.cunoc.redxela.modelo.Camion;
import gt.usac.cunoc.redxela.planificacion.PoliticaPlanificacion;
import gt.usac.cunoc.redxela.util.Json;
import gt.usac.cunoc.redxela.util.ValidacionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor HTTP de la interfaz, construido con el servidor embebido del JDK
 * ({@code com.sun.net.httpserver}).
 *
 * <ul>
 *   <li>{@code /} — archivos estáticos de la interfaz (index.html, app.js, estilos.css).</li>
 *   <li>{@code /api/...} — API REST en JSON (consultas y acciones).</li>
 *   <li>{@code /api/stream} — <b>Server-Sent Events</b>: el servidor empuja el estado
 *       completo cada 700 ms por una conexión HTTP que queda abierta, así la
 *       interfaz se actualiza en tiempo real sin estar preguntando.</li>
 * </ul>
 *
 * <p>Cada petición se atiende en un hilo de un pool {@code newCachedThreadPool}: las
 * conexiones SSE quedan ocupando su hilo mientras el navegador está abierto, y un
 * pool que crece bajo demanda evita que una pestaña abierta bloquee la API.</p>
 *
 * <p><b>Manejo de excepciones:</b> toda petición pasa por {@link #atender}, que
 * convierte {@link ValidacionException} en 400/404 con un mensaje legible,
 * errores de JSON en 400 y cualquier otra excepción en 500, sin tumbar el servidor.</p>
 */
public class ServidorWeb {

    private static final long INTERVALO_SSE_MS = 700;

    private final CentroDistribucion centro;
    private final HttpServer servidor;
    private final ExecutorService hilos;
    private final AtomicInteger clientesSse = new AtomicInteger();

    // Caché del estado: con varias pestañas abiertas se calcula una sola vez por intervalo
    private final Object candadoCache = new Object();
    private String estadoCache = "{}";
    private long estadoCacheMs;

    public ServidorWeb(CentroDistribucion centro, int puerto) throws IOException {
        this.centro = centro;
        this.servidor = HttpServer.create(new InetSocketAddress(puerto), 50);
        AtomicInteger n = new AtomicInteger();
        this.hilos = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        servidor.setExecutor(hilos);
        servidor.createContext("/api/stream", this::stream);
        servidor.createContext("/api/", ex -> atender(ex, this::api));
        servidor.createContext("/", ex -> atender(ex, this::estatico));
    }

    public void iniciar() { servidor.start(); }

    public void detener() {
        servidor.stop(0);
        hilos.shutdownNow();
    }

    // =========================================================================
    // Infraestructura
    // =========================================================================

    @FunctionalInterface
    private interface Manejador {
        void manejar(HttpExchange ex) throws Exception;
    }

    /** Envoltura común: manejo uniforme de errores para todas las rutas. */
    private void atender(HttpExchange ex, Manejador m) {
        try {
            m.manejar(ex);
        } catch (ValidacionException e) {
            responderError(ex, e.codigoHttp(), e.getMessage());
        } catch (Json.JsonException | IllegalArgumentException e) {
            responderError(ex, 400, e.getMessage());
        } catch (Exception e) {
            centro.bitacora().error("Error en " + ex.getRequestURI() + ": " + e);
            e.printStackTrace();
            responderError(ex, 500, "Error interno: " + e.getMessage());
        } finally {
            ex.close();
        }
    }

    private void responderError(HttpExchange ex, int codigo, String mensaje) {
        try {
            responder(ex, codigo, Json.escribir(Map.of("error", mensaje == null ? "Error" : mensaje)));
        } catch (IOException ignorada) {
            // el navegador cerró la conexión; no hay a quién responder
        }
    }

    private static void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(codigo, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static Map<String, Object> cuerpo(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            String s = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return Json.leerObjeto(s);
        }
    }

    private String estadoJson() {
        synchronized (candadoCache) {
            long ahora = System.currentTimeMillis();
            if (ahora - estadoCacheMs > 300) {
                estadoCache = Json.escribir(VistaEstado.estado(centro));
                estadoCacheMs = ahora;
            }
            return estadoCache;
        }
    }

    // =========================================================================
    // Server-Sent Events
    // =========================================================================

    private void stream(HttpExchange ex) {
        clientesSse.incrementAndGet();
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.sendResponseHeaders(200, 0); // longitud 0 = respuesta por bloques (chunked), sin fin
            OutputStream os = ex.getResponseBody();
            while (!Thread.currentThread().isInterrupted()) {
                String datos = "data: " + estadoJson() + "\n\n";
                os.write(datos.getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(INTERVALO_SSE_MS);
            }
        } catch (IOException e) {
            // El navegador cerró la pestaña: es el fin normal de una conexión SSE
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("[WARN] SSE: " + e);
        } finally {
            clientesSse.decrementAndGet();
            ex.close();
        }
    }

    // =========================================================================
    // API REST
    // =========================================================================

    private void api(HttpExchange ex) throws Exception {
        String metodo = ex.getRequestMethod();
        String ruta = ex.getRequestURI().getPath();

        if (metodo.equals("GET")) {
            switch (ruta) {
                case "/api/estado" -> { responder(ex, 200, estadoJson()); return; }
                case "/api/catalogo" -> { responder(ex, 200, Json.escribir(VistaEstado.catalogo(centro))); return; }
                case "/api/clientes" -> { responder(ex, 200, Json.escribir(VistaEstado.clientes(centro))); return; }
                default -> throw new ValidacionException("Ruta " + ruta, 404);
            }
        }
        if (!metodo.equals("POST")) throw new ValidacionException("Método no permitido", 405);

        Map<String, Object> b = cuerpo(ex);
        Object resultado = switch (ruta) {
            case "/api/clientes" -> crearCliente(b);
            case "/api/pedidos" -> crearPedido(b);
            case "/api/mercaderia" -> registrarMercaderia(b);
            case "/api/mercaderia/rafaga" -> rafagaCamiones(b);
            case "/api/config" -> configurar(b);
            case "/api/conflictos/provocar" -> centro.escenario().provocar(entero(b, "anden", 1) - 1, texto(b, "productoId", null));
            case "/api/conflictos/resolver" -> {
                centro.detector().resolver(requerido(b, "conflictoId"), requerido(b, "ganadorId"));
                yield Map.of("ok", true);
            }
            case "/api/estadisticas/reiniciar" -> {
                centro.estadisticas().reiniciar();
                centro.gestor().reiniciarContabilidad();
                centro.bitacora().info("Estadísticas reiniciadas");
                yield Map.of("ok", true);
            }
            case "/api/comparar" -> new ComparadorPoliticas(centro.catalogo().listaProductos())
                    .comparar(entero(b, "pedidos", 600), numero(b, "tasa", 50), (long) numero(b, "semilla", 2026));
            default -> throw new ValidacionException("Ruta " + ruta, 404);
        };
        synchronized (candadoCache) { estadoCacheMs = 0; } // que el próximo envío muestre el cambio
        responder(ex, 200, Json.escribir(resultado));
    }

    private Object crearCliente(Map<String, Object> b) {
        Cliente c = centro.catalogo().crearCliente(requerido(b, "nombre"), texto(b, "correo", ""),
                nivel(entero(b, "nivel", 3)));
        centro.bitacora().info("Cuenta creada desde la interfaz: " + c.id() + " " + c.nombre() + " (" + c.nivel().nombre() + ")");
        return VistaEstado.mapa("id", c.id(), "nombre", c.nombre(), "nivel", c.nivel().numero(), "nivelNombre", c.nivel().nombre());
    }

    @SuppressWarnings("unchecked")
    private Object crearPedido(Map<String, Object> b) {
        Cliente cliente;
        Object nuevo = b.get("nuevoCliente");
        if (nuevo instanceof Map<?, ?> nc) {
            Map<String, Object> m = (Map<String, Object>) nc;
            cliente = centro.catalogo().crearCliente(requerido(m, "nombre"), texto(m, "correo", ""), nivel(entero(m, "nivel", 3)));
            centro.bitacora().info("Cuenta creada: " + cliente.id() + " " + cliente.nombre() + " (" + cliente.nivel().nombre() + ")");
        } else {
            cliente = centro.catalogo().cliente(requerido(b, "clienteId"));
        }
        Object ls = b.get("lineas");
        if (!(ls instanceof List<?> lista) || lista.isEmpty()) throw new ValidacionException("Agregue al menos un producto al pedido");
        List<LineaPedido> lineas = new ArrayList<>();
        for (Object o : lista) {
            if (!(o instanceof Map<?, ?>)) throw new ValidacionException("Línea de pedido inválida");
            Map<String, Object> l = (Map<String, Object>) o;
            int cant = entero(l, "cantidad", 0);
            if (cant <= 0 || cant > 200) throw new ValidacionException("La cantidad de cada producto debe estar entre 1 y 200");
            lineas.add(new LineaPedido(centro.catalogo().producto(requerido(l, "productoId")), cant));
        }
        Pedido p = centro.recibirPedido(cliente, lineas, true);
        return VistaEstado.mapa("id", p.id(), "cliente", cliente.nombre(), "clienteId", cliente.id(),
                "nivel", p.nivel().nombre(), "unidades", p.unidades());
    }

    private Object registrarMercaderia(Map<String, Object> b) {
        Producto p = centro.catalogo().producto(requerido(b, "productoId"));
        Camion c = centro.recibirMercaderia(p, entero(b, "cantidad", 0), entero(b, "anden", 0) - 1, true);
        return VistaEstado.mapa("id", c.id(), "producto", p.nombre(), "cantidad", c.cantidad(), "anden", c.anden() + 1);
    }

    /** Llegan varios camiones de golpe: sirve para llenar el buffer y ver al productor esperar. */
    private Object rafagaCamiones(Map<String, Object> b) {
        int n = entero(b, "camiones", 12);
        if (n < 1 || n > 50) throw new ValidacionException("Entre 1 y 50 camiones");
        Random r = new Random();
        List<Producto> productos = centro.catalogo().listaProductos();
        List<Object> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Producto p = productos.get(r.nextInt(productos.size()));
            ids.add(centro.recibirMercaderia(p, 10 + r.nextInt(30), -1, true).id());
        }
        centro.bitacora().ipc("Ráfaga de " + n + " camiones en el patio de Recepción");
        return Map.of("camiones", ids);
    }

    private Object configurar(Map<String, Object> b) {
        if (b.containsKey("politica")) {
            try {
                centro.politica(PoliticaPlanificacion.valueOf(requerido(b, "politica")));
            } catch (IllegalArgumentException e) {
                throw new ValidacionException("Política desconocida: " + b.get("politica"));
            }
        }
        if (b.containsKey("prevencion")) centro.prevencionActiva(booleano(b, "prevencion"));
        if (b.containsKey("generador")) centro.generadorPedidosActivo(booleano(b, "generador"));
        if (b.containsKey("tasa")) centro.pedidosPorHora(numero(b, "tasa", 36));
        if (b.containsKey("reposicion")) centro.reposicionAutomatica(booleano(b, "reposicion"));
        if (b.containsKey("factorTiempo")) centro.factorTiempo(numero(b, "factorTiempo", 1));
        if (b.containsKey("retardoInventario")) centro.retardoInventarioMs((long) numero(b, "retardoInventario", 2500));
        return Map.of("ok", true);
    }

    // ---- lectura segura de campos del JSON ----

    private static String requerido(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null || v.toString().isBlank()) throw new ValidacionException("Falta el campo '" + k + "'");
        return v.toString().trim();
    }

    private static String texto(Map<String, Object> m, String k, String def) {
        Object v = m.get(k);
        return v == null || v.toString().isBlank() ? def : v.toString().trim();
    }

    private static double numero(Map<String, Object> m, String k, double def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            throw new ValidacionException("El campo '" + k + "' debe ser numérico");
        }
    }

    private static int entero(Map<String, Object> m, String k, int def) {
        return (int) Math.round(numero(m, k, def));
    }

    private static boolean booleano(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean bo) return bo;
        return v != null && Boolean.parseBoolean(v.toString());
    }

    private static NivelServicio nivel(int n) {
        try {
            return NivelServicio.porNumero(n);
        } catch (IllegalArgumentException e) {
            throw new ValidacionException("Nivel de servicio inválido: " + n);
        }
    }

    // =========================================================================
    // Archivos estáticos
    // =========================================================================

    private void estatico(HttpExchange ex) throws IOException {
        String ruta = ex.getRequestURI().getPath();
        if (ruta.equals("/") || ruta.isEmpty()) ruta = "/index.html";
        if (ruta.contains("..")) throw new ValidacionException("Ruta inválida", 400);
        try (InputStream is = ServidorWeb.class.getResourceAsStream("/web" + ruta)) {
            if (is == null) throw new ValidacionException("Archivo " + ruta, 404);
            byte[] b = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", tipoMime(ruta));
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private static String tipoMime(String ruta) {
        if (ruta.endsWith(".html")) return "text/html; charset=utf-8";
        if (ruta.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (ruta.endsWith(".css")) return "text/css; charset=utf-8";
        if (ruta.endsWith(".svg")) return "image/svg+xml";
        if (ruta.endsWith(".png")) return "image/png";
        if (ruta.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    public int clientesConectados() { return clientesSse.get(); }
}
