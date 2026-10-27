package gt.usac.cunoc.redxela.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializador y analizador JSON mínimo, escrito a mano para no depender de
 * bibliotecas externas.
 *
 * <ul>
 *   <li>{@link #escribir(Object)}: convierte Map, Collection, arreglos, String,
 *       Number, Boolean, Enum y null a texto JSON.</li>
 *   <li>{@link #leer(String)}: analizador descendente recursivo que devuelve
 *       Map / List / String / Double / Boolean / null.</li>
 * </ul>
 */
public final class Json {

    private Json() {}

    // =========================================================================
    // Escritura
    // =========================================================================

    public static String escribir(Object o) {
        StringBuilder sb = new StringBuilder(4096);
        escribir(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void escribir(Object o, StringBuilder sb) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            cadena(s, sb);
        } else if (o instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) sb.append("null");
            else sb.append(Math.round(d * 1000d) / 1000d);
        } else if (o instanceof Float f) {
            escribir(f.doubleValue(), sb);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Enum<?> e) {
            cadena(e.name(), sb);
        } else if (o instanceof Character c) {
            cadena(String.valueOf(c), sb);
        } else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) sb.append(',');
                primero = false;
                cadena(String.valueOf(e.getKey()), sb);
                sb.append(':');
                escribir(e.getValue(), sb);
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean primero = true;
            for (Object x : c) {
                if (!primero) sb.append(',');
                primero = false;
                escribir(x, sb);
            }
            sb.append(']');
        } else if (o instanceof Object[] arr) {
            escribir(List.of(arr), sb);
        } else if (o instanceof int[] arr) {
            sb.append('[');
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(','); sb.append(arr[i]); }
            sb.append(']');
        } else {
            cadena(o.toString(), sb);
        }
    }

    private static void cadena(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // =========================================================================
    // Lectura
    // =========================================================================

    /** Error de sintaxis en el JSON recibido. */
    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public JsonException(String m) { super(m); }
    }

    public static Object leer(String texto) {
        if (texto == null || texto.isBlank()) return new LinkedHashMap<String, Object>();
        Lector l = new Lector(texto);
        Object v = l.valor();
        l.espacios();
        if (l.pos < texto.length()) throw new JsonException("Caracteres sobrantes en la posición " + l.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> leerObjeto(String texto) {
        Object o = leer(texto);
        if (!(o instanceof Map)) throw new JsonException("Se esperaba un objeto JSON");
        return (Map<String, Object>) o;
    }

    private static final class Lector {
        final String s;
        int pos;

        Lector(String s) { this.s = s; }

        void espacios() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char ver() {
            espacios();
            if (pos >= s.length()) throw new JsonException("Fin inesperado del JSON");
            return s.charAt(pos);
        }

        Object valor() {
            char c = ver();
            return switch (c) {
                case '{' -> objeto();
                case '[' -> arreglo();
                case '"' -> cadena();
                case 't', 'f' -> booleano();
                case 'n' -> { esperar("null"); yield null; }
                default -> numero();
            };
        }

        Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            if (ver() == '}') { pos++; return m; }
            while (true) {
                if (ver() != '"') throw new JsonException("Se esperaba una clave en la posición " + pos);
                String k = cadena();
                if (ver() != ':') throw new JsonException("Se esperaba ':' en la posición " + pos);
                pos++;
                m.put(k, valor());
                char c = ver();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw new JsonException("Se esperaba ',' o '}' en la posición " + (pos - 1));
            }
        }

        List<Object> arreglo() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            if (ver() == ']') { pos++; return l; }
            while (true) {
                l.add(valor());
                char c = ver();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw new JsonException("Se esperaba ',' o ']' en la posición " + (pos - 1));
            }
        }

        String cadena() {
            StringBuilder sb = new StringBuilder();
            pos++; // "
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new JsonException("Escape \\u incompleto");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new JsonException("Cadena sin cerrar");
        }

        Boolean booleano() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Valor inválido en la posición " + pos);
        }

        void esperar(String lit) {
            if (!s.startsWith(lit, pos)) throw new JsonException("Se esperaba '" + lit + "' en la posición " + pos);
            pos += lit.length();
        }

        Double numero() {
            int ini = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (ini == pos) throw new JsonException("Valor inválido en la posición " + pos);
            try {
                return Double.parseDouble(s.substring(ini, pos));
            } catch (NumberFormatException e) {
                throw new JsonException("Número inválido: " + s.substring(ini, pos));
            }
        }
    }
}
