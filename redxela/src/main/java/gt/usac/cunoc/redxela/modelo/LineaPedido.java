package gt.usac.cunoc.redxela.modelo;

/**
 * Una línea del pedido: un producto y la cantidad de unidades solicitadas.
 *
 * @param producto producto solicitado (incluye su tipo de mercancía)
 * @param cantidad unidades solicitadas (mayor que cero)
 */
public record LineaPedido(Producto producto, int cantidad) {
    public LineaPedido {
        if (producto == null) throw new IllegalArgumentException("La línea debe tener un producto");
        if (cantidad <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero");
    }
}
