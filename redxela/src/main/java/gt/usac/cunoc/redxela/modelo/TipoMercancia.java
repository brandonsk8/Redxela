package gt.usac.cunoc.redxela.modelo;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;


public enum TipoMercancia {

    GENERAL("General",
            "Artículos livianos y resistentes (ropa, libros, juguetes). Solo se empacan y se despachan por paquetería.",
            1.0,
            req(TipoRecurso.EMPAQUE, 1, TipoRecurso.ENCARGADO, 1)),

    FRAGIL("Frágil",
            "Vidrio, cerámica o cosméticos. Requieren empaque con protección y revisión en control de calidad.",
            1.3,
            req(TipoRecurso.EMPAQUE, 1, TipoRecurso.ENCARGADO, 1, TipoRecurso.CONTROL_CALIDAD, 1)),

    PESADO("Pesado",
            "Más de 25 kg por unidad (electrodomésticos, sacos). Se mueven en montacargas por dos encargados y se cargan directo al camión.",
            1.6,
            req(TipoRecurso.MONTACARGAS, 1, TipoRecurso.ENCARGADO, 2, TipoRecurso.ANDEN, 1)),

    VOLUMINOSO("Voluminoso",
            "Livianos pero grandes (colchones, muebles armados). Necesitan montacargas, empaque especial y andén.",
            1.4,
            req(TipoRecurso.MONTACARGAS, 1, TipoRecurso.ENCARGADO, 1, TipoRecurso.EMPAQUE, 1, TipoRecurso.ANDEN, 1)),

    PERECEDERO("Perecedero",
            "Alimentos y medicinas con cadena de frío. Se verifica temperatura en control de calidad y salen por andén refrigerado.",
            1.1,
            req(TipoRecurso.EMPAQUE, 1, TipoRecurso.ENCARGADO, 1, TipoRecurso.CONTROL_CALIDAD, 1, TipoRecurso.ANDEN, 1)),

    ALTO_VALOR("Alto valor",
            "Electrónica y joyería. Se valida en control de calidad, se carga en andén y se escanea al salir para trazabilidad (seguro de envío).",
            1.5,
            req(TipoRecurso.EMPAQUE, 1, TipoRecurso.ENCARGADO, 1, TipoRecurso.CONTROL_CALIDAD, 1,
                    TipoRecurso.ANDEN, 1, TipoRecurso.ESCANER, 1));

    private final String nombre;
    private final String descripcion;
    /** Multiplicador del tiempo de procesamiento por unidad. */
    private final double factorTiempo;
    private final Map<TipoRecurso, Integer> recursos;

    TipoMercancia(String nombre, String descripcion, double factorTiempo, Map<TipoRecurso, Integer> recursos) {
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.factorTiempo = factorTiempo;
        this.recursos = Collections.unmodifiableMap(recursos);
    }

    public String nombre() { return nombre; }
    public String descripcion() { return descripcion; }
    public double factorTiempo() { return factorTiempo; }

    /** Recursos que requiere este tipo (recurso → cantidad de unidades). */
    public Map<TipoRecurso, Integer> recursos() { return recursos; }

    /**
     * Indica si el despacho de este tipo usa el par (Andén, Escáner): es el tipo que
     * puede entrar en conflicto con Recepción, que usa el mismo par en orden inverso.
     */
    public boolean usaParConflictivo() {
        return recursos.containsKey(TipoRecurso.ANDEN) && recursos.containsKey(TipoRecurso.ESCANER);
    }

    /** Construye un mapa ordenado (EnumMap respeta el orden global) a partir de pares recurso/cantidad. */
    private static Map<TipoRecurso, Integer> req(Object... pares) {
        Map<TipoRecurso, Integer> m = new EnumMap<>(TipoRecurso.class);
        for (int i = 0; i < pares.length; i += 2) {
            m.put((TipoRecurso) pares[i], (Integer) pares[i + 1]);
        }
        return m;
    }
}
