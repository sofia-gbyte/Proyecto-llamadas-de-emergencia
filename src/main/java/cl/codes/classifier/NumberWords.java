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
public final class NumberWords {

    private NumberWords() {}

    private static final Map<String, Integer> UNITS = new HashMap<>();
    private static final Map<String, Integer> TEENS = new HashMap<>(); // 10-29
    private static final Map<String, Integer> TENS = new HashMap<>();    // 30,40..90
    private static final Map<String, Integer> HUNDREDS = new HashMap<>();  // 200..900
    private static final java.util.Set<String> HUNDRED_TERMS = java.util.Set.of("cien", "ciento");
    private static final java.util.Set<String> THOUSAND_TERMS = java.util.Set.of("mil");
    private static final java.util.Set<String> CONJUNCTIONS = java.util.Set.of("y");

    static {
        String[][] u = {{"cero","0"},{"un","1"},{"uno","1"},{"una","1"},{"dos","2"},{"tres","3"},
                {"cuatro","4"},{"cinco","5"},{"seis","6"},{"siete","7"},{"ocho","8"},{"nueve","9"}};
        for (String[] p : u) UNITS.put(p[0], Integer.parseInt(p[1]));

        String[][] e = {
                {"diez","10"},{"once","11"},{"doce","12"},{"trece","13"},{"catorce","14"},{"quince","15"},
                {"dieciseis","16"},{"dieciséis","16"},{"diecisiete","17"},{"dieciocho","18"},{"diecinueve","19"},
                {"veinte","20"},
                {"veintiun","21"},{"veintiún","21"},{"veintiuno","21"},{"veintiuna","21"},
                {"veintidos","22"},{"veintidós","22"},{"veintitres","23"},{"veintitrés","23"},
                {"veinticuatro","24"},{"veinticinco","25"},{"veintiseis","26"},{"veintiséis","26"},
                {"veintisiete","27"},{"veintiocho","28"},{"veintinueve","29"}
        };
        for (String[] p : e) TEENS.put(p[0], Integer.parseInt(p[1]));

        String[][] d = {{"treinta","30"},{"cuarenta","40"},{"cincuenta","50"},
                {"sesenta","60"},{"setenta","70"},{"ochenta","80"},{"noventa","90"}};
        for (String[] p : d) TENS.put(p[0], Integer.parseInt(p[1]));

        String[][] c = {
                {"doscientos","200"},{"doscientas","200"},{"trescientos","300"},{"trescientas","300"},
                {"cuatrocientos","400"},{"cuatrocientas","400"},{"quinientos","500"},{"quinientas","500"},
                {"seiscientos","600"},{"seiscientas","600"},{"setecientos","700"},{"setecientas","700"},
                {"ochocientos","800"},{"ochocientas","800"},{"novecientos","900"},{"novecientas","900"}
        };
        for (String[] p : c) HUNDREDS.put(p[0], Integer.parseInt(p[1]));
    }

    private static boolean isNumberWord(String normalizedWord) {
        return UNITS.containsKey(normalizedWord) || TEENS.containsKey(normalizedWord)
                || TENS.containsKey(normalizedWord) || HUNDREDS.containsKey(normalizedWord)
                || HUNDRED_TERMS.contains(normalizedWord) || THOUSAND_TERMS.contains(normalizedWord);
    }

    private record Token(String text, int start, int end) {}

    /** Resultado de intentar leer un número (0-999, sin miles) a partir de una posición de la lista de tokens. */
    private record NumberReading(int value, int nextIndex) {}

    private static NumberReading readHundreds(java.util.List<Token> tokens, int i) {
        int value = 0;
        boolean consumedAnything = false;
        if (i < tokens.size()) {
            String t = tokens.get(i).text();
            if (HUNDRED_TERMS.contains(t)) { value = 100; i++; consumedAnything = true; }
            else if (HUNDREDS.containsKey(t)) { value = HUNDREDS.get(t); i++; consumedAnything = true; }
        }
        // decenas/unidades/especiales (0-99), con "y" opcional entre decena y unidad
        if (i < tokens.size()) {
            String t = tokens.get(i).text();
            if (TEENS.containsKey(t)) {
                value += TEENS.get(t); i++; consumedAnything = true;
            } else if (TENS.containsKey(t)) {
                value += TENS.get(t); i++; consumedAnything = true;
                if (i + 1 < tokens.size() && CONJUNCTIONS.contains(tokens.get(i).text())
                        && UNITS.containsKey(tokens.get(i + 1).text())) {
                    value += UNITS.get(tokens.get(i + 1).text());
                    i += 2;
                }
            } else if (UNITS.containsKey(t) && value > 0) {
                // Solo como unidad suelta después de una centena ("ciento tres" = 103).
                // Suelta y sola (sin centena antes) NO se consume aquí para no comerse
                // números de un dígito que en realidad son otra cosa (p.ej. "una persona").
                value += UNITS.get(t); i++; consumedAnything = true;
            }
        }
        return consumedAnything ? new NumberReading(value, i) : null;
    }

    /** Intenta leer un número completo (0-999999) desde tokens[i]. Devuelve null si no hay número allí. */
    private static NumberReading readNumber(java.util.List<Token> tokens, int i) {
        int start = i;
        int thousands = 0;
        boolean hasThousands = false;

        if (i < tokens.size() && THOUSAND_TERMS.contains(tokens.get(i).text())) {
            thousands = 1000; i++; hasThousands = true;
        } else {
            NumberReading previousThousands = readHundreds(tokens, i);
            if (previousThousands != null && previousThousands.nextIndex() < tokens.size()
                    && THOUSAND_TERMS.contains(tokens.get(previousThousands.nextIndex()).text())) {
                thousands = previousThousands.value() * 1000;
                i = previousThousands.nextIndex() + 1;
                hasThousands = true;
            }
        }

        NumberReading remainder = readHundreds(tokens, i);
        int remainderValue = remainder != null ? remainder.value() : 0;
        int next = remainder != null ? remainder.nextIndex() : i;

        if (!hasThousands && remainder == null) return null; // no había ningún número aquí
        // Un solo token suelto que es unidad (0-9) sin nada de contexto SÍ cuenta:
        // "una persona" no debe convertirse, pero direcciones como "calle Diez 5"
        // ya las cubre "leerCientos" vía ESPECIALES/DECENAS; unidades sueltas de un
        // solo dígito casi nunca son un número de casa por sí solas, así que
        // deliberadamente no se leen aquí para evitar falsos positivos.
        if (!hasThousands && remainderValue == 0 && remainder != null && remainder.nextIndex() == start) return null;

        return new NumberReading(thousands + remainderValue, next);
    }

    private static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-zÁÉÍÓÚÑáéíóúñ]+");

    /**
     * Reemplaza en {@code texto} cada tramo de palabras numéricas por su
     * valor en dígitos. El resto del texto (puntuación, mayúsculas,
     * palabras no numéricas) queda intacto.
     */
    public static String convert(String text) {
        if (text == null || text.isBlank()) return text;

        java.util.List<Token> tokens = new java.util.ArrayList<>();
        Matcher m = WORD_PATTERN.matcher(text);
        while (m.find()) {
            String normalized = stripDiacriticsLowercase(m.group());
            tokens.add(new Token(normalized, m.start(), m.end()));
        }
        if (tokens.isEmpty()) return text;

        // 1) Ubicar cada tramo numérico independiente (gramática estándar).
        record Span(int startToken, int endToken, int value) {}
        java.util.List<Span> spans = new java.util.ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (!isNumberWord(tokens.get(i).text())) { i++; continue; }
            NumberReading lectura = readNumber(tokens, i);
            if (lectura == null) { i++; continue; }
            spans.add(new Span(i, lectura.nextIndex(), lectura.value()));
            i = lectura.nextIndex();
        }
        if (spans.isEmpty()) return text;

        // 2) Combinar pares de tramos adyacentes (sin ninguna palabra entre
        //    medio, solo espacio) cuando ambos quedan en 0-99: es la forma
        //    "dieciséis treinta" -> 1630 de dictar direcciones de a pares.
        java.util.List<Span> combinedSpans = new java.util.ArrayList<>();
        i = 0;
        while (i < spans.size()) {
            Span current = spans.get(i);
            if (i + 1 < spans.size()) {
                Span next = spans.get(i + 1);
                boolean adjacent = next.startToken() == current.endToken();
                boolean onlyWhitespaceBetween = adjacent && isOnlyWhitespace(
                        text, tokens.get(current.endToken() - 1).end(), tokens.get(next.startToken()).start());
                if (onlyWhitespaceBetween && current.value() <= 99 && next.value() <= 99) {
                    combinedSpans.add(new Span(current.startToken(), next.endToken(),
                            current.value() * 100 + next.value()));
                    i += 2;
                    continue;
                }
            }
            combinedSpans.add(current);
            i++;
        }

        // 3) Reconstruir el texto reemplazando cada tramo por sus dígitos.
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        for (Span t : combinedSpans) {
            int startIndex = tokens.get(t.startToken()).start();
            int endIndex = tokens.get(t.endToken() - 1).end();
            sb.append(text, cursor, startIndex);
            sb.append(t.value());
            cursor = endIndex;
        }
        sb.append(text, cursor, text.length());
        return sb.toString();
    }

    private static boolean isOnlyWhitespace(String text, int startIndex, int endIndex) {
        if (startIndex >= endIndex) return true;
        for (int i = startIndex; i < endIndex; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    private static String stripDiacriticsLowercase(String s) {
        String t = java.text.Normalizer.normalize(s.toLowerCase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t;
    }
}
