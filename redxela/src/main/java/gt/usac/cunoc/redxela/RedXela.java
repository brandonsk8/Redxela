package gt.usac.cunoc.redxela;

import gt.usac.cunoc.redxela.nucleo.CentroDistribucion;
import gt.usac.cunoc.redxela.web.ServidorWeb;

/**
 * Punto de entrada del simulador REDXela.
 *
 * <p>Uso: {@code java -jar redxela.jar [puerto]} (por defecto 8080) y abrir
 * {@code http://localhost:8080} en el navegador.</p>
 *
 * <p>Proyecto Final — Sistemas Operativos 2 — USAC CUNOC — Segundo semestre 2026.<br>
 * Autor: Brandon Gustavo Güinac Román — 201931217.</p>
 */
public final class RedXela {

    private RedXela() {}

    public static void main(String[] args) {
        // Ningún hilo debe morir sin dejar rastro: manejador global de excepciones no capturadas
        Thread.setDefaultUncaughtExceptionHandler((hilo, ex) -> {
            System.err.printf("[ERROR] El hilo '%s' terminó por una excepción no controlada: %s%n", hilo.getName(), ex);
            ex.printStackTrace();
        });

        int puerto = 8080;
        try {
            if (args.length > 0) puerto = Integer.parseInt(args[0]);
            else if (System.getenv("PORT") != null) puerto = Integer.parseInt(System.getenv("PORT"));
        } catch (NumberFormatException e) {
            System.err.println("Puerto inválido, se usará 8080");
        }

        CentroDistribucion centro = new CentroDistribucion();
        ServidorWeb web;
        try {
            web = new ServidorWeb(centro, puerto);
        } catch (java.net.BindException e) {
            System.err.println("El puerto " + puerto + " ya está en uso. Ejecute: java -jar redxela.jar <otro-puerto>");
            System.exit(1);
            return;
        } catch (Exception e) {
            System.err.println("No se pudo iniciar el servidor web: " + e.getMessage());
            System.exit(1);
            return;
        }

        centro.iniciar();
        web.iniciar();

        // Apagado ordenado con Ctrl+C: detiene generadores, hilos y servidor
        final ServidorWeb servidor = web;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nDeteniendo REDXela...");
            servidor.detener();
            centro.detener();
        }, "apagado"));

        System.out.println("""
                ==============================================================
                  REDXela — Simulador de centro de distribución
                  Sistemas Operativos 2 · USAC CUNOC · 2026
                --------------------------------------------------------------
                  Interfaz:  http://localhost:%d
                  Detener:   Ctrl + C
                ==============================================================""".formatted(puerto));
    }
}
