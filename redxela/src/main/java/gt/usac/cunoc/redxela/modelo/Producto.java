package gt.usac.cunoc.redxela.modelo;

/**
 * Producto del catálogo predefinido. Es inmutable (record), por lo que puede
 * compartirse entre hilos sin ninguna sincronización.
 *
 * @param id     código del producto (p. ej. "GEN-01")
 * @param nombre nombre comercial
 * @param tipo   tipo de mercancía, que determina los recursos necesarios
 */
public record Producto(String id, String nombre, TipoMercancia tipo) {
}
