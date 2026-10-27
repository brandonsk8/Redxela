# REDXela — Simulador de centro de distribución

Proyecto final de **Sistemas Operativos 2** · USAC CUNOC · Segundo semestre 2026
Brandon Gustavo Güinac Román — 201931217

Simulación concurrente de un centro de distribución: planificación de pedidos con cinco
políticas, recursos compartidos con semáforos, prevención y detección de interbloqueo,
comunicación Recepción → Inventario con buffer acotado y administración paginada del almacén.
Interfaz web en tiempo real (Server-Sent Events).

## Requisitos
- JDK 17 o superior (sin dependencias externas)

## Compilar y ejecutar
```bash
# Con Maven
mvn package
java -jar target/redxela.jar

# Sin Maven
./compilar.sh          # Linux / macOS
compilar.bat           # Windows
java -jar redxela.jar
```
Abrir http://localhost:8080 (otro puerto: `java -jar redxela.jar 9090`).

## Estructura
```
src/main/java/gt/usac/cunoc/redxela/
  modelo/          Pedido, Cliente, Producto, niveles, tipos de mercancía, recursos
  recursos/        Unidad (semáforo binario), PoolRecurso (semáforo contador), GestorRecursos
  planificacion/   Políticas, ListaEspera (lock + condition), Reloj
  ipc/             BufferAcotado (productor-consumidor con 3 semáforos)
  areas/           AreaRecepcion (productor), AreaInventario (consumidor)
  almacen/         Almacen (paginación + ReadWriteLock), Ubicacion, Lote
  interbloqueo/    DetectorInterbloqueo (grafo de espera), Conflicto
  nucleo/          CentroDistribucion, Despachador, TrabajadorPedido, generadores,
                   EscenarioConflicto, ComparadorPoliticas
  web/             ServidorWeb (HTTP + API REST + SSE), VistaEstado
  util/            Json, Bitacora, ValidacionException
src/main/resources/web/   index.html, app.js, estilos.css
docs/                     Manual técnico y manual de usuario
```
