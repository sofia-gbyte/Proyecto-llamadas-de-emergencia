package cl.codes.classifier;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte números dictados en palabras ("dieciséis", "mil seiscientos
 * treinta") a dígitos, para que las direcciones que el operador escribe o
 * el ASR transcribe con el número "hablado" igual se puedan extraer con
 * las expresiones regulares de {@link Classifier} (que buscan \d{1,5}).
 *
 * Cubre dos formas de decir un número de casa:
 *
 *  1) Grafía estándar: "mil seiscientos treinta" -> 1630.
 *  2) Grafía "de a pares", muy común en Chile para direcciones (se lee
 *     como una hora o como un número de teléfono, de dos en dos):
 *     "dieciséis treinta" -> 1630. Gramaticalmente "dieciséis" y "treinta"
 *     son dos números independientes (no hay forma estándar de unirlos
 *     con una palabra de enlace), así que cuando aparecen dos números
 *     de palabras pegados, sin nada entre medio salvo un espacio, y cada
 *     uno queda entre 0 y 99, se interpretan como dos bloques de dos
 *     dígitos concatenados.
 *
 * Solo reemplaza los tramos de texto que efectivamente son palabras
 * numéricas: el resto de la transcripción queda intacto.
 */
public final class NumeroEnPalabras {

    private NumeroEnPalabras() {}

    private static final Map<String, Integer> UNIDADES = new HashMap<>();
    private static final Map<String, Integer> ESPECIALES = new HashMap<>(); // 10-29
    private static final Map<String, Integer> DECENAS = new HashMap<>();    // 30,40..90
    private static final Map<String, Integer> CENTENAS = new HashMap<>();  // 200..900
    private static final java.util.Set<String> CIEN = java.util.Set.of("cien", "ciento");
    private static final java.util.Set<String> MIL = java.util.Set.of("mil");
    private static final java.util.Set<String> GLUE = java.util.Set.of("y");

    static {
        String[][] u = {{"cero","0"},{"un","1"},{"uno","1"},{"una","1"},{"dos","2"},{"tres","3"},
                {"cuatro","4"},{"cinco","5"},{"seis","6"},{"siete","7"},{"ocho","8"},{"nueve","9"}};
        for (String[] p : u) UNIDADES.put(p[0], Integer.parseInt(p[1]));

        String[][] e = {
                {"diez","10"},{"once","11"},{"doce","12"},{"trece","13"},{"catorce","14"},{"quince","15"},
                {"dieciseis","16"},{"dieciséis","16"},{"diecisiete","17"},{"dieciocho","18"},{"diecinueve","19"},
                {"veinte","20"},
                {"veintiun","21"},{"veintiún","21"},{"veintiuno","21"},{"veintiuna","21"},
                {"veintidos","22"},{"veintidós","22"},{"veintitres","23"},{"veintitrés","23"},
                {"veinticuatro","24"},{"veinticinco","25"},{"veintiseis","26"},{"veintiséis","26"},
                {"veintisiete","27"},{"veintiocho","28"},{"veintinueve","29"}
        };
        for (String[] p : e) ESPECIALES.put(p[0], Integer.parseInt(p[1]));

        String[][] d = {{"treinta","30"},{"cuarenta","40"},{"cincuenta","50"},
                {"sesenta","60"},{"setenta","70"},{"ochenta","80"},{"noventa","90"}};
        for (String[] p : d) DECENAS.put(p[0], Integer.parseInt(p[1]));

        String[][] c = {
                {"doscientos","200"},{"doscientas","200"},{"trescientos","300"},{"trescientas","300"},
                {"cuatrocientos","400"},{"cuatrocientas","400"},{"quinientos","500"},{"quinientas","500"},
                {"seiscientos","600"},{"seiscientas","600"},{"setecientos","700"},{"setecientas","700"},
                {"ochocientos","800"},{"ochocientas","800"},{"novecientos","900"},{"novecientas","900"}
        };
        for (String[] p : c) CENTENAS.put(p[0], Integer.parseInt(p[1]));
    }

    private static boolean esPalabraNumerica(String palabraNorm) {
        return UNIDADES.containsKey(palabraNorm) || ESPECIALES.containsKey(palabraNorm)
                || DECENAS.containsKey(palabraNorm) || CENTENAS.containsKey(palabraNorm)
                || CIEN.contains(palabraNorm) || MIL.contains(palabraNorm);
    }

    private record Token(String texto, int inicio, int fin) {}

    /** Resultado de intentar leer un número (0-999, sin miles) a partir de una posición de la lista de tokens. */
    private record Lectura(int valor, int siguienteIndice) {}

    private static Lectura leerCientos(java.util.List<Token> tokens, int i) {
        int valor = 0;
        boolean consumioAlgo = false;
        if (i < tokens.size()) {
            String t = tokens.get(i).texto();
            if (CIEN.contains(t)) { valor = 100; i++; consumioAlgo = true; }
            else if (CENTENAS.containsKey(t)) { valor = CENTENAS.get(t); i++; consumioAlgo = true; }
        }
        // decenas/unidades/especiales (0-99), con "y" opcional entre decena y unidad
        if (i < tokens.size()) {
            String t = tokens.get(i).texto();
            if (ESPECIALES.containsKey(t)) {
                valor += ESPECIALES.get(t); i++; consumioAlgo = true;
            } else if (DECENAS.containsKey(t)) {
                valor += DECENAS.get(t); i++; consumioAlgo = true;
                if (i + 1 < tokens.size() && GLUE.contains(tokens.get(i).texto())
                        && UNIDADES.containsKey(tokens.get(i + 1).texto())) {
                    valor += UNIDADES.get(tokens.get(i + 1).texto());
                    i += 2;
                }
            } else if (UNIDADES.containsKey(t) && valor > 0) {
                // Solo como unidad suelta después de una centena ("ciento tres" = 103).
                // Suelta y sola (sin centena antes) NO se consume aquí para no comerse
                // números de un dígito que en realidad son otra cosa (p.ej. "una persona").
                valor += UNIDADES.get(t); i++; consumioAlgo = true;
            }
        }
        return consumioAlgo ? new Lectura(valor, i) : null;
    }

    /** Intenta leer un número completo (0-999999) desde tokens[i]. Devuelve null si no hay número allí. */
    private static Lectura leerNumero(java.util.List<Token> tokens, int i) {
        int inicio = i;
        int miles = 0;
        boolean huboMiles = false;

        if (i < tokens.size() && MIL.contains(tokens.get(i).texto())) {
            miles = 1000; i++; huboMiles = true;
        } else {
            Lectura previoMil = leerCientos(tokens, i);
            if (previoMil != null && previoMil.siguienteIndice() < tokens.size()
                    && MIL.contains(tokens.get(previoMil.siguienteIndice()).texto())) {
                miles = previoMil.valor() * 1000;
                i = previoMil.siguienteIndice() + 1;
                huboMiles = true;
            }
        }

        Lectura resto = leerCientos(tokens, i);
        int valorResto = resto != null ? resto.valor() : 0;
        int siguiente = resto != null ? resto.siguienteIndice() : i;

        if (!huboMiles && resto == null) return null; // no había ningún número aquí
        // Un solo token suelto que es unidad (0-9) sin nada de contexto SÍ cuenta:
        // "una persona" no debe convertirse, pero direcciones como "calle Diez 5"
        // ya las cubre "leerCientos" vía ESPECIALES/DECENAS; unidades sueltas de un
        // solo dígito casi nunca son un número de casa por sí solas, así que
        // deliberadamente no se leen aquí para evitar falsos positivos.
        if (!huboMiles && valorResto == 0 && resto != null && resto.siguienteIndice() == inicio) return null;

        return new Lectura(miles + valorResto, siguiente);
    }

    private static final Pattern PALABRA = Pattern.compile("[A-Za-zÁÉÍÓÚÑáéíóúñ]+");

    /**
     * Reemplaza en {@code texto} cada tramo de palabras numéricas por su
     * valor en dígitos. El resto del texto (puntuación, mayúsculas,
     * palabras no numéricas) queda intacto.
     */
    public static String convertir(String texto) {
        if (texto == null || texto.isBlank()) return texto;

        java.util.List<Token> tokens = new java.util.ArrayList<>();
        Matcher m = PALABRA.matcher(texto);
        while (m.find()) {
            String norm = quitarTildesMin(m.group());
            tokens.add(new Token(norm, m.start(), m.end()));
        }
        if (tokens.isEmpty()) return texto;

        // 1) Ubicar cada tramo numérico independiente (gramática estándar).
        record Tramo(int tokenInicio, int tokenFin, int valor) {}
        java.util.List<Tramo> tramos = new java.util.ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (!esPalabraNumerica(tokens.get(i).texto())) { i++; continue; }
            Lectura lectura = leerNumero(tokens, i);
            if (lectura == null) { i++; continue; }
            tramos.add(new Tramo(i, lectura.siguienteIndice(), lectura.valor()));
            i = lectura.siguienteIndice();
        }
        if (tramos.isEmpty()) return texto;

        // 2) Combinar pares de tramos adyacentes (sin ninguna palabra entre
        //    medio, solo espacio) cuando ambos quedan en 0-99: es la forma
        //    "dieciséis treinta" -> 1630 de dictar direcciones de a pares.
        java.util.List<Tramo> combinados = new java.util.ArrayList<>();
        i = 0;
        while (i < tramos.size()) {
            Tramo actual = tramos.get(i);
            if (i + 1 < tramos.size()) {
                Tramo siguiente = tramos.get(i + 1);
                boolean adyacentes = siguiente.tokenInicio() == actual.tokenFin();
                boolean soloEspacioEntreMedio = adyacentes && esSoloEspacio(
                        texto, tokens.get(actual.tokenFin() - 1).fin(), tokens.get(siguiente.tokenInicio()).inicio());
                if (soloEspacioEntreMedio && actual.valor() <= 99 && siguiente.valor() <= 99) {
                    combinados.add(new Tramo(actual.tokenInicio(), siguiente.tokenFin(),
                            actual.valor() * 100 + siguiente.valor()));
                    i += 2;
                    continue;
                }
            }
            combinados.add(actual);
            i++;
        }

        // 3) Reconstruir el texto reemplazando cada tramo por sus dígitos.
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (Tramo t : combinados) {
            int desde = tokens.get(t.tokenInicio()).inicio();
            int hasta = tokens.get(t.tokenFin() - 1).fin();
            sb.append(texto, cursor, desde);
            sb.append(t.valor());
            cursor = hasta;
        }
        sb.append(texto, cursor, texto.length());
        return sb.toString();
    }

    private static boolean esSoloEspacio(String texto, int desde, int hasta) {
        if (desde >= hasta) return true;
        for (int i = desde; i < hasta; i++) {
            if (!Character.isWhitespace(texto.charAt(i))) return false;
        }
        return true;
    }

    private static String quitarTildesMin(String s) {
        String t = java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t;
    }
}
