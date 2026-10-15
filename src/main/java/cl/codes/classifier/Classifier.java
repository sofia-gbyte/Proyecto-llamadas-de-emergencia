package cl.codes.classifier;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies emergency call transcripts by priority
 * (URGENTE / ROJA / MEDIA / VERDE) using a weighted keyword dictionary,
 * simple negation handling, and address extraction.
 */
public final class Classifier {

    // -----------------------------------------------------------------
    // 1. Diccionario de palabras clave (pesos: urgente=10, roja=6,
    //    media=3, verde=1). Varias entradas son "raíces" (p. ej. "dispar")
    //    para cubrir conjugaciones por coincidencia de subcadena.
    // -----------------------------------------------------------------

    private static final Map<String, List<String>> PALABRAS_CLAVE = new LinkedHashMap<>();
    static {
        PALABRAS_CLAVE.put("urgente", List.of(
                "dispar", "balazo", "balacera", "tiroteo", "baleado", "baleada",
                "escopeta", "pistola", "fusil", "ametralladora",
                "subfusil", "arma de fuego", "arma", "fierro", "chumbo", "cañón",
                "granada", "bomba", "explosion", "explosivo",
                "cuchillo", "puñal", "navaja", "cortaplumas", "apuñal",
                "cuchillada", "degollad",
                "incendi", "quema", "llamas", "humo denso",
                "fuego", "explosion de gas", "fuga de gas",
                "muerto", "muerta", "cadaver", "cuerpo sin vida", "cuerpo inerte",
                "no respira", "no reacciona", "sin signos vitales", "desangrando",
                "desangrado", "mucha sangre", "convulsionando", "se ahoga",
                "atragantado", "paro cardiaco", "infarto masivo",
                "secuestro", "secuestrado", "rehenes", "toma de rehenes",
                "violacion", "violando", "abuso sexual", "agresion sexual",
                "suicidio", "se va a matar", "se quiere matar", "colgarse",
                "saltar al vacio", "se va a tirar",
                "derrumbe", "alud", "tsunami", "sismo fuerte", "colapso estructural",
                "carabinero herido", "policia herido", "funcionario baleado"
        ));
        PALABRAS_CLAVE.put("roja", List.of(
                "pele", "golpiza", "golpe", "puñete", "patada", "agresion",
                "agresivo", "violento", "forcejeo", "botellazo", "piedrazo",
                "cogotazo",
                "robo", "roba", "asalt", "portonazo", "encerrona", "lanzazo",
                "bajonear", "copamiento", "ladron", "delincuente", "sujeto armado",
                "encapuchado", "pasamontañas", "vehiculo robado", "auto robado",
                "patente clonada", "amenaza de arma",
                "accidente", "choque", "colision", "atropello", "atropellado",
                "volcamiento", "volco", "herido grave", "lesionado", "inconsciente",
                "convulsiones", "infarto", "paro cardiaco", "sobredosis",
                "intoxicacion grave",
                "ebrio", "manejando ebrio", "cocaina", "pasta base", "microtrafico",
                "violencia intrafamiliar", "vif", "maltrato", "me quiere matar",
                "me esta pegando", "encerrado", "privado de libertad",
                "gritos desgarradores"
        ));
        PALABRAS_CLAVE.put("media", List.of(
                "discusion", "problema con", "vecino conflictivo", "ruido molesto",
                "musica fuerte", "musica muy fuerte", "musica alta", "fiesta",
                "escandalo", "gritos", "insultos",
                "amenaza verbal", "intimidando",
                "rayado", "pintada", "destrozos", "ventana rota", "vidrio roto",
                "hurto", "cartereo", "carterazo", "mochila robada",
                "celular perdido", "billetera perdida", "extraviado",
                "niño perdido", "adolescente perdido", "persona perdida",
                "fuga de agua", "anegamiento", "pozo abierto", "vereda rota",
                "semaforo dañado", "auto mal estacionado", "taco vehicular",
                "control de identidad", "sujeto sospechoso", "actitud sospechosa",
                "merodeando",
                "carrete", "funado", "quilombo"
        ));
        PALABRAS_CLAVE.put("verde", List.of(
                "informacion", "consulta", "horario de atencion", "documentos",
                "certificado", "denuncia por internet", "tramite", "reclamo",
                "sugerencia", "molestia menor", "basura acumulada", "escombros",
                "perro callejero", "mascota perdida", "ruido leve", "tv alta",
                "alarma vecinal", "llamada de prueba", "numero equivocado", "broma",
                "niño jugando", "fuego artificial lejano", "corte de luz",
                "poste inclinado", "bache", "cedula", "rut", "denuncia web",
                "citacion"
        ));
    }

    private static final Map<String, Integer> PESOS = Map.of(
            "urgente", 10, "roja", 6, "media", 3, "verde", 1
    );

    private static final int UMBRAL_ROJA = 5;
    private static final int UMBRAL_MEDIA = 3;

    private static final Set<String> NEGACIONES = Set.of(
            "no", "nunca", "jamas", "sin", "ningun", "ninguna", "tampoco"
    );
    private static final int VENTANA_NEGACION = 3;

    // Etiquetas legibles para las raíces usadas en el diccionario, solo
    // para mostrarle al operator; no afecta la lógica de coincidencia.
    private static final Map<String, String> ETIQUETAS_DISPLAY = Map.of(
            "dispar", "disparo",
            "apuñal", "apuñalamiento",
            "degollad", "degollamiento",
            "incendi", "incendio",
            "quema", "quemaduras / fuego",
            "pele", "pelea",
            "roba", "robo",
            "asalt", "asalto"
    );

    private static String etiqueta(String palabra) {
        return ETIQUETAS_DISPLAY.getOrDefault(palabra, palabra);
    }

    // Diccionario ya normalizado (sin tildes), construido una sola vez.
    private static final Map<String, List<String>> PALABRAS_NORM = new LinkedHashMap<>();
    static {
        for (var entrada : PALABRAS_CLAVE.entrySet()) {
            List<String> normalizadas = new ArrayList<>();
            for (String p : entrada.getValue()) {
                normalizadas.add(normalizar(p));
            }
            PALABRAS_NORM.put(entrada.getKey(), normalizadas);
        }
    }

    // -----------------------------------------------------------------
    // 2. Utilidades de texto
    // -----------------------------------------------------------------

    private static String quitarTildes(String texto) {
        String nfkd = Normalizer.normalize(texto, Normalizer.Form.NFD);
        return nfkd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static String normalizar(String texto) {
        String t = texto.toLowerCase(new Locale("es", "CL"));
        t = quitarTildes(t);
        t = t.replaceAll("[^a-z0-9ñ\\s]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static boolean estaNegada(String textoNorm, String palabraNorm) {
        int idx = textoNorm.indexOf(palabraNorm);
        if (idx == -1) return false;
        String antes = textoNorm.substring(0, idx).trim();
        if (antes.isEmpty()) return false;
        String[] tokens = antes.split("\\s+");
        int desde = Math.max(0, tokens.length - VENTANA_NEGACION);
        for (int i = desde; i < tokens.length; i++) {
            if (NEGACIONES.contains(tokens[i])) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 3. Extracción de dirección
    // -----------------------------------------------------------------

    private static final Pattern PATRON_DIRECCION = Pattern.compile(
            "(calle|pasaje|avenida|av\\.?|sector|poblacion|villa|cerro|camino|ruta|carretera|caletera|autopista|callejon)" +
            "\\s+([a-zñáéíóú0-9\\s]{3,40}?)" +
            "(?:\\s+(?:numero|n[uú]mero|#)?\\s*(\\d{1,5}))?" +
            "(?=[,.]| y | esquina | altura |$)",
            Pattern.CASE_INSENSITIVE
    );

    // Respaldo cuando el ASR omite la palabra "calle/avenida/..." (muy común):
    // nombre propio (una o más palabras con mayúscula, o cualquier palabra si
    // viene precedido de "en/por") seguido directamente de un número de casa.
    private static final Pattern PATRON_NOMBRE_Y_NUMERO = Pattern.compile(
            "(?i)(?:\\b(?:en|por)\\s+)?"
                    + "([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]*(?:\\s+[A-ZÁÉÍÓÚÑ0-9][\\wáéíóúñ]*){0,3})"
                    + "\\s+(?:n[uú]mero\\s+|#\\s*)?(\\d{1,5})\\b"
    );

    public static String extraerDireccion(String texto) {
        if (texto == null) return null;
        // Convierte números dictados en palabras ("dieciséis treinta" -> "1630",
        // "mil seiscientos treinta" -> "1630") ANTES de aplicar los patrones de
        // abajo, que solo reconocen dígitos (\d). El resto del texto no cambia.
        texto = NumeroEnPalabras.convertir(texto);
        Matcher m = PATRON_DIRECCION.matcher(texto);
        if (m.find()) {
            String tipo = m.group(1).trim();
            String nombre = m.group(2).trim();
            String numero = m.group(3);

            StringBuilder sb = new StringBuilder();
            sb.append(Character.toUpperCase(tipo.charAt(0))).append(tipo.substring(1));
            sb.append(' ').append(tituloCase(nombre));
            if (numero != null) sb.append(" #").append(numero);
            return sb.toString().trim();
        }

        Matcher contexto = Pattern.compile(
            "(?i)(?:ubicad[ao]|queda|esta|está)\\s+(?:al|a la|en|por)\\s+([^,.]{3,70}?)(?=\\s*(?:,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|se encuentra|est[aá]n?)\\b)|\\b(?:donde|porque|necesita|requiere|presenta|se encuentra)\\b|$))(?:\\s*,\\s*(?:en\\s+)?([^,.]{3,40}))?"
        ).matcher(texto);
        if (contexto.find()) {
            String lugar = contexto.group(1).trim();
            String comuna = contexto.group(2) == null ? "" : contexto.group(2).trim();
            return tituloCase(lugar) + (comuna.isBlank() ? "" : ", " + tituloCase(comuna));
        }

        // Respaldo: sin la palabra clave de vía, pero con un nombre propio
        // seguido de un número ("Manuel Montt 1234").
        Matcher nombreNumero = PATRON_NOMBRE_Y_NUMERO.matcher(texto);
        while (nombreNumero.find()) {
            String candidato = nombreNumero.group(1).trim();
            if (candidato.split("\\s+").length > 4) continue;
            if (esPalabraDescartable(candidato)) continue;
            return tituloCase(candidato) + " #" + nombreNumero.group(2);
        }
        return null;
    }

    private static final Set<String> PALABRAS_DESCARTABLES_DIRECCION = Set.of(
            "hola", "ayuda", "por favor", "gracias", "chile", "santiago"
    );

    private static boolean esPalabraDescartable(String candidato) {
        String norm = normalizar(candidato);
        return PALABRAS_DESCARTABLES_DIRECCION.contains(norm);
    }

    private static String tituloCase(String texto) {
        String[] palabras = texto.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : palabras) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // 3.1 Palabras que activan cada institución (carabineros/samu/bomberos)
    // -----------------------------------------------------------------
    // Deliberadamente MÁS AMPLIAS que el diccionario de prioridad de arriba:
    // una llamada puede no alcanzar el umbral de una categoría de prioridad
    // y aun así necesitar que se avise a una institución concreta (p. ej.
    // "persona herida" sin más detalle igual debe llegar a SAMU). Por eso
    // varias palabras se repiten aquí aunque ya estén en PALABRAS_CLAVE, y
    // varias (p. ej. "herido", "caida", "atrapado") no existen en ese
    // diccionario y solo viven acá.
    //
    // Antes esta lista era mucho más corta y dejaba fuera casos como
    // violación/abuso sexual, suicidio, secuestro o apuñalamientos, que por
    // lo tanto nunca sugerían ninguna institución pese a ser URGENTE. Si se
    // agrega una palabra nueva a PALABRAS_CLAVE que también deba avisar a
    // una institución, hay que agregarla también acá.
    private static final List<String> PALABRAS_CARABINEROS = List.of(
            "dispar", "balazo", "balacera", "tiroteo", "baleado", "baleada",
            "arma", "escopeta", "pistola", "fusil", "ametralladora", "subfusil", "fierro", "chumbo",
            "cuchillo", "puñal", "navaja", "cortaplumas", "apuñal", "cuchillada", "degollad",
            "secuestro", "secuestrado", "rehen",
            "violacion", "violando", "abuso sexual", "agresion sexual",
            "pele", "golpiza", "golpe", "puñete", "patada", "agresion", "agresivo", "violento", "forcejeo",
            "botellazo", "piedrazo", "cogotazo",
            "robo", "roba", "asalt", "portonazo", "encerrona", "lanzazo", "bajonear", "copamiento",
            "ladron", "delincuente", "sujeto armado", "encapuchado", "pasamontañas",
            "vehiculo robado", "auto robado", "patente clonada", "amenaza de arma", "amenaza",
            "suicidio", "intento de suicidio", "riesgo suicida", "se va a matar", "se quiere matar",
            "colgarse", "saltar al vacio", "se va a tirar",
            "ebrio", "manejando ebrio", "cocaina", "pasta base", "microtrafico",
            "violencia intrafamiliar", "vif", "maltrato", "me quiere matar", "me esta pegando",
            "encerrado", "privado de libertad",
            "hurto", "cartereo", "carterazo",
            "control de identidad", "sujeto sospechoso", "actitud sospechosa", "merodeando",
            "niño perdido", "adolescente perdido", "persona perdida",
            "carabinero herido", "policia herido", "funcionario baleado"
    );
    private static final List<String> PALABRAS_SAMU = List.of(
            "no respira", "no reacciona", "sin signos vitales", "desangrando", "desangrado", "mucha sangre", "sangr",
            "convulsion", "se ahoga", "atragant", "paro cardiaco", "paro", "infarto",
            "muerto", "muerta", "cadaver", "cuerpo sin vida", "cuerpo inerte",
            "apuñal", "cuchillada", "degollad", "baleado", "baleada", "balazo",
            "herido", "herida", "lesionado", "inconsciente",
            "accidente", "choque", "colision", "atropell", "volcamiento", "volco",
            "sobredosis", "intoxicacion", "intoxic", "quemadura", "caida",
            "suicidio", "se va a matar", "se quiere matar", "colgarse", "saltar al vacio", "se va a tirar",
            "violacion", "abuso sexual", "agresion sexual",
            "golpiza", "botellazo", "piedrazo", "gritos desgarradores",
            "carabinero herido", "policia herido", "funcionario baleado"
    );
    private static final List<String> PALABRAS_BOMBEROS = List.of(
            "incendi", "quema", "llamas", "humo", "fuego", "explosion", "explosivo", "fuga de gas",
            "derrumbe", "alud", "colapso", "atrapado", "rescate", "sismo fuerte", "tsunami",
            "granada", "bomba"
    );

    // -----------------------------------------------------------------
    // 4. Resultado estructurado
    // -----------------------------------------------------------------

    public record ResultadoClasificacion(
            String priority,
            Map<String, Integer> puntajes,
            List<String> highlightedWords,
            List<String> todasPalabras,
            String direccion,
            String textoOriginal,
            List<String> motivos,
            List<String> institucionesSugeridas
    ) {}

    public static boolean hasOperationalInformation(ResultadoClasificacion resultado) {
        return resultado != null && (
                !resultado.highlightedWords().isEmpty()
                        || !resultado.institucionesSugeridas().isEmpty()
                        || (resultado.direccion() != null && !resultado.direccion().isBlank())
        );
    }

    // -----------------------------------------------------------------
    // 5. Función principal
    // -----------------------------------------------------------------

    public static ResultadoClasificacion classifyCall(String texto) {
        if (texto == null || texto.isBlank()) {
            Map<String, Integer> vacio = new LinkedHashMap<>();
            for (String cat : PALABRAS_CLAVE.keySet()) vacio.put(cat, 0);
                return new ResultadoClasificacion("VERDE", vacio, List.of(), List.of(), null,
                    texto == null ? "" : texto, List.of(), List.of());
        }

        String textoNorm = normalizar(texto);

        Map<String, Integer> puntajes = new LinkedHashMap<>();
        for (String cat : PALABRAS_CLAVE.keySet()) puntajes.put(cat, 0);

        // (palabra_original, categoria, peso)
        record Encontrada(String palabra, String categoria, int peso) {}
        List<Encontrada> encontradas = new ArrayList<>();

        for (String categoria : PALABRAS_CLAVE.keySet()) {
            List<String> listaOrig = PALABRAS_CLAVE.get(categoria);
            List<String> listaNorm = PALABRAS_NORM.get(categoria);
            for (int i = 0; i < listaNorm.size(); i++) {
                String palabraNorm = listaNorm.get(i);
                if (palabraNorm.isEmpty()) continue;
                if (textoNorm.contains(palabraNorm)) {
                    if (estaNegada(textoNorm, palabraNorm)) continue;
                    int peso = PESOS.get(categoria);
                    puntajes.merge(categoria, peso, Integer::sum);
                    encontradas.add(new Encontrada(listaOrig.get(i), categoria, peso));
                }
            }
        }

        String priority;
        if (puntajes.get("urgente") > 0) {
            priority = "URGENTE";
        } else if (puntajes.get("roja") >= UMBRAL_ROJA) {
            priority = "ROJA";
        } else if (puntajes.get("media") >= UMBRAL_MEDIA) {
            priority = "MEDIA";
        } else {
            priority = "VERDE";
        }

        // Top 5 por peso descendente, sin duplicados de etiqueta.
        encontradas.sort((a, b) -> Integer.compare(b.peso(), a.peso()));
        LinkedHashSet<String> top = new LinkedHashSet<>();
        for (Encontrada e : encontradas) {
            top.add(etiqueta(e.palabra()));
            if (top.size() == 5) break;
        }

        TreeSet<String> todas = new TreeSet<>();
        for (Encontrada e : encontradas) todas.add(etiqueta(e.palabra()));

        String direccion = extraerDireccion(texto);
        LinkedHashSet<String> motivos = new LinkedHashSet<>();
        for (Encontrada e : encontradas) {
            motivos.add(categoriaLegible(e.categoria()) + ": " + etiqueta(e.palabra()));
        }

        return new ResultadoClasificacion(
                priority,
                puntajes,
                new ArrayList<>(top),
                new ArrayList<>(todas),
                direccion,
                texto,
                new ArrayList<>(motivos),
                institucionesSugeridas(textoNorm)
        );
    }

    private static String categoriaLegible(String categoria) {
        return switch (categoria) {
            case "urgente" -> "Riesgo vital o crítico";
            case "roja" -> "Emergencia prioritaria";
            case "media" -> "Incidente operativo";
            default -> "Solicitud no crítica";
        };
    }

    private static List<String> institucionesSugeridas(String textoNorm) {
        LinkedHashSet<String> instituciones = new LinkedHashSet<>();
        boolean policial = contieneAlgunaNoNegada(textoNorm, PALABRAS_CARABINEROS.toArray(String[]::new));
        boolean medico = contieneAlgunaNoNegada(textoNorm, PALABRAS_SAMU.toArray(String[]::new));
        boolean bomberos = contieneAlgunaNoNegada(textoNorm, PALABRAS_BOMBEROS.toArray(String[]::new));

        if (policial) instituciones.add("carabineros");
        if (medico) instituciones.add("samu");
        if (bomberos) instituciones.add("bomberos");
        return List.copyOf(instituciones);
    }

    private static boolean contieneAlgunaNoNegada(String texto, String... frases) {
        for (String frase : frases) {
            String fraseNorm = normalizar(frase);
            if (texto.contains(fraseNorm) && !estaNegada(texto, fraseNorm)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 6. Pruebas rápidas al ejecutar directamente
    // -----------------------------------------------------------------

    public static void main(String[] args) {
        List<String> casos = List.of(
                "Ayuda, hay un disparo y un hombre con cuchillo en calle Los Alerces 123, hay mucha sangre",
                "Mi vecino está con la música muy fuerte en pasaje Las Rosas, ya van dos horas",
                "Quiero saber el horario de atención de la comisaría",
                "Nos están asaltando dos sujetos encapuchados en avenida Manquehue, uno tiene un arma",
                "No hay ningún arma, tranquilo, fue solo una discusión con un vecino",
                "Se está incendiando una casa en villa Esperanza, hay mucho humo"
        );

        for (String c : casos) {
            ResultadoClasificacion r = classifyCall(c);
            System.out.println("-".repeat(70));
            System.out.println("Texto: " + c);
            System.out.println("Prioridad: " + r.priority() + " | Puntajes: " + r.puntajes());
            System.out.println("Destacadas: " + r.highlightedWords());
            System.out.println("Dirección: " + r.direccion());
        }
    }
}


