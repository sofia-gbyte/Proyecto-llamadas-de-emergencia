package cl.codes.classifier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
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

    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("urgente", List.of(
                "dispar", "balazo", "balacera", "tiroteo", "baleado", "baleada",
                "escopeta", "pistola", "fusil", "ametralladora",
                "subfusil", "arma de fuego", "arma", "fierro", "chumbo", "cañón",
                "granada", "bomba", "explosion", "explosivo",
                "cuchillo", "puñal", "navaja", "cortaplumas", "apuñal",
                "cuchillada", "degollad",
                "incendi", "quema", "llamas", "humo denso",
                "fuego", "explosion de gas", "fuga de gas", "incendio forestal",
                "vehiculo incendiado", "auto incendiado", "casa en llamas", "vivienda en llamas",
                "muerto", "muerta", "cadaver", "cuerpo sin vida", "cuerpo inerte",
                "no respira", "no reacciona", "sin signos vitales", "desangrando",
                "desangrado", "mucha sangre", "convulsionando", "se ahoga",
                "atragantado", "paro cardiaco", "infarto masivo", "no puede respirar",
                "dificultad para respirar", "persona inconsciente", "no responde",
                "hemorragia", "sangrado abundante", "persona atrapada", "persona atrapado",
                "personas atrapadas", "personas atrapados",
                "monoxido de carbono", "material peligroso", "electrocutado", "electrocucion",
                "secuestro", "secuestrado", "rehenes", "toma de rehenes",
                "violacion", "violando", "abuso sexual", "agresion sexual",
                "suicidio", "se va a matar", "se quiere matar", "colgarse",
                "saltar al vacio", "se va a tirar",
                "derrumbe", "alud", "tsunami", "sismo fuerte", "colapso estructural",
                "carabinero herido", "policia herido", "funcionario baleado",
                "se esta quemando", "se esta incendiando", "esta en llamas", "ardiendo",
                "casa ardiendo", "vivienda ardiendo", "incendio en casa", "incendio en vivienda",
                "incendio en edificio", "incendio estructural", "incendio de estructura",
                "incendio en vehiculo", "auto en llamas", "vehiculo en llamas", "auto se quema",
                "incendio forestal", "bosque en llamas", "vegetacion ardiendo",
                "exploto", "estallo", "estallido", "explosion de gas", "explosion en edificio",
                "escape de gas", "gas escapando", "olor fuerte a gas", "fuga de gas",
                "sustancia peligrosa", "quimico derramado", "derrama quimica", "materiales peligrosos",
                "volcamiento", "volco", "volcado", "volcada", "volcaron", "vehiculo volcado",
                "persona atrapada", "persona atrapado", "personas atrapadas", "personas atrapados",
                "atrapado bajo", "atrapada bajo", "no puede salir", "quedo atrapado", "rescate",
                "inconsciente", "desmayado", "desmayada", "perdio el conocimiento", "no despierta",
                "convulsion", "convulsionando", "convulsiona", "no puede respirar", "le cuesta respirar",
                "dificultad respiratoria", "se queda sin aire", "se esta asfixiando", "se ahoga",
                "sangrado", "sangrando", "hemorragia", "pierde mucha sangre", "sangre abundante",
                "paro respiratorio", "paro cardiorrespiratorio", "no tiene pulso",
                "ataque al corazon", "derrame cerebral", "dolor en el pecho", "dolor de pecho",
                "dolor toracico", "presion en el pecho", "opresion en el pecho",
                "no le entra aire", "dificultad al respirar", "respira con dificultad",
                "anafilaxia", "edema de glotis", "ahogamiento", "hemorragia obstetrica",
                "parto inminente", "bebe no respira", "eclampsia", "intento de ahorcamiento",
                "emergencia medica", "urgencia medica", "intoxicacion", "envenenamiento",
                "derrumbe", "se derrumbo", "colapso de edificio", "edificio colapsado",
                "inundacion", "se inundo", "desborde de rio", "aluvion", "deslizamiento de tierra",
                "electrocutado", "electrocutada", "descarga electrica", "cable energizado",
                "accidente laboral grave", "trabajador atrapado", "caida de altura",
                "alumnos atrapados", "incendio en espacio publico",
                "robo con violencia", "robo con intimidacion", "amenaza de muerte",
                "violencia intrafamiliar", "persona en peligro", "situacion de riesgo",
                "choque multiple", "colision multiple", "persona atropellada"
        ));
        KEYWORDS.put("roja", List.of(
                "pele", "golpiza", "golpe", "puñete", "patada", "agresion",
                "agresivo", "violento", "forcejeo", "botellazo", "piedrazo",
                "cogotazo",
                "robo", "roba", "asalt", "portonazo", "encerrona", "lanzazo",
                "bajonear", "copamiento", "ladron", "delincuente", "sujeto armado",
                "encapuchado", "pasamontañas", "vehiculo robado", "auto robado",
                "patente clonada", "amenaza de arma",
                "accidente", "choque", "colision", "atropello", "atropellado",
                "choque entre vehiculos", "chocaron", "volcamiento", "volco", "volcado",
                "atropellada", "herido grave", "lesionado", "inconsciente",
                "convulsiones", "infarto", "paro cardiaco", "sobredosis",
                "intoxicacion grave", "persona lesionada", "dolor intenso",
                "persona atrapada", "personas atrapadas", "fuga de gas con personas afectadas",
                "ebrio", "manejando ebrio", "cocaina", "pasta base", "microtrafico",
                "violencia intrafamiliar", "vif", "maltrato", "me quiere matar",
                "me esta pegando", "encerrado", "privado de libertad",
                "gritos desgarradores",
                "colision", "colisionaron", "chocaron los vehiculos", "choque vehicular",
                "accidente vehicular", "accidente de transito", "auto chocado", "vehiculo chocado",
                "persona atropellada", "atropellaron", "atropello", "volcaron", "volcada",
                "se volco", "se volcó", "persona herida", "personas heridas", "lesionados",
                "heridos", "heridas", "emergencia medica", "urgencia medica", "fuga de gas",
                "derrame quimico", "sustancia toxica", "material peligroso", "alud", "inundacion",
                "desborde", "cable caido", "cable con corriente", "accidente laboral",
                "emergencia en colegio", "emergencia en edificio", "emergencia en espacio publico",
                "persona desaparecida", "amenaza de muerte", "asalto", "robo con violencia",
                "robo con intimidacion", "persona en riesgo", "persona en peligro"
        ));
        KEYWORDS.put("roja", new ArrayList<>(KEYWORDS.get("roja")));
        KEYWORDS.get("roja").add("rob");
        KEYWORDS.put("media", List.of(
                "discusion", "problema con", "vecino conflictivo", "ruido molesto",
                "musica fuerte", "musica muy fuerte", "musica alta", "fiesta",
                "escandalo", "gritos", "insultos",
                "amenaza verbal", "intimidando",
                "rayado", "pintada", "destrozos", "ventana rota", "vidrio roto",
                "hurto", "cartereo", "carterazo", "mochila robada",
                "celular perdido", "billetera perdida", "extraviado",
                "niño perdido", "adolescente perdido", "persona perdida",
                "fuga de agua", "anegamiento", "pozo abierto", "vereda rota",
                "inundacion", "desborde", "derrumbe", "cable caido", "cable electrico",
                "accidente laboral", "accidente en el trabajo", "emergencia en colegio",
                "emergencia en edificio", "persona desaparecida", "rescate",
                "semaforo dañado", "auto mal estacionado", "taco vehicular",
                "control de identidad", "sujeto sospechoso", "actitud sospechosa",
                "merodeando",
                "carrete", "funado", "quilombo",
                "humo", "humo saliendo", "humo en una casa", "humo en vivienda", "humo en edificio",
                "olor a quemado", "olor a humo", "pequeno incendio", "fuego pequeno",
                "llamas pequenas", "fuego en basurero", "quema de basura",
                "olor a gas", "posible fuga de gas", "fuga de agua", "inundacion en casa",
                "semaforo apagado", "choque sin lesionados", "colision sin heridos",
                "accidente de transito sin heridos", "cable cortado", "poste caido",
                "rama caida", "emergencia escolar", "accidente en colegio",
                "accidente en edificio", "persona extraviada", "adulto mayor perdido",
                "perdida de conciencia", "dificultad al respirar", "falta de aire",
                "desmayo", "herida leve", "corte", "sangrado leve", "quemadura leve",
                "resbalon", "caida", "golpe leve", "choque electrico", "corto circuito",
                "incidente de transito", "vehiculo detenido en la via", "auto en panne",
                "amenaza", "amenazando", "violencia", "agresion", "asalto",
                "emergencia en espacio publico", "persona en situacion de riesgo"
        ));
        KEYWORDS.put("verde", List.of(
                "informacion", "consulta", "horario de atencion", "documentos",
                "certificado", "denuncia por internet", "tramite", "reclamo",
                "sugerencia", "molestia menor", "basura acumulada", "escombros",
                "perro callejero", "mascota perdida", "ruido leve", "tv alta",
                "alarma vecinal", "llamada de prueba", "numero equivocado", "broma",
                "niño jugando", "fuego artificial lejano", "corte de luz",
                "poste inclinado", "bache", "cedula", "rut", "denuncia web",
                "citacion",
                "consulta general", "ruido de vecinos", "animal herido", "animal atrapado",
                "objeto sospechoso", "paquete sospechoso", "persona desorientada",
                "persona mayor desorientada", "persona sin hogar", "corte de agua",
                "corte de electricidad", "luminaria apagada", "arbol caido",
                "accidente menor", "sin personas lesionadas", "sin heridos",
                "sin lesionados", "nadie esta herido", "no hay personas heridas"
        ));
    }

    private static final Map<String, List<String>> INSTITUTION_TERMS = loadInstitutionTerms();
    private static final Map<String, List<String>> NORMALIZED_INSTITUTION_TERMS = normalizeInstitutionTerms();

    static {
        addDictionaryTermsToMediaKeywords();
    }

    private static final Map<String, Integer> CATEGORY_WEIGHTS = Map.of(
            "urgente", 10, "roja", 6, "media", 3, "verde", 1
    );

    private static final int RED_THRESHOLD = 5;
    private static final int MEDIUM_THRESHOLD = 3;

    private static final Set<String> NEGATIONS = Set.of(
            "no", "nunca", "jamas", "sin", "ningun", "ninguna", "ninguno", "nadie", "tampoco"
    );
    private static final int NEGATION_WINDOW = 3;
    private static final Set<String> STEM_KEYWORDS = Set.of(
            "dispar", "balead", "incendi", "quema", "degollad", "pele", "roba", "asalt",
            "apunal", "atropell", "volco", "rehen", "sangr", "desangrad", "atrapad", "intoxic",
            "caid", "quemad", "herido", "herida", "lesionado", "lesionada", "convulsion",
            "accidente", "emergencia", "incendio", "volcamiento", "desaparecida", "perdida",
            "ard", "hume", "explota", "explos", "fug", "escap", "choc", "colision",
            "llama",
            "desmay", "inconscient", "respir", "asfix", "hemorrag", "rescat", "derrum",
            "colaps", "inund", "electrocut", "amenaz", "violent", "agred", "desaparec",
            "extravi", "envenen", "desbord", "derram", "quem", "rob"
    );

    // Etiquetas legibles para las raíces usadas en el diccionario, solo
    // para mostrarle al operator; no afecta la lógica de coincidencia.
    private static final Map<String, String> DISPLAY_LABELS = Map.of(
            "dispar", "disparo",
            "apuñal", "apuñalamiento",
            "degollad", "degollamiento",
            "incendi", "incendio",
            "quema", "quemaduras / fuego",
            "pele", "pelea",
            "roba", "robo",
            "rob", "robo",
            "asalt", "asalto"
    );

    private static String displayLabel(String word) {
        return DISPLAY_LABELS.getOrDefault(word, word);
    }

    // Diccionario ya normalizado (sin tildes), construido una sola vez.
    private static final Map<String, List<String>> NORMALIZED_KEYWORDS = new LinkedHashMap<>();
    static {
        for (var entry : KEYWORDS.entrySet()) {
            List<String> normalizedTerms = new ArrayList<>();
            for (String term : entry.getValue()) {
                normalizedTerms.add(normalizeText(term));
            }
            NORMALIZED_KEYWORDS.put(entry.getKey(), normalizedTerms);
        }
    }

    private static Map<String, List<String>> loadInstitutionTerms() {
        try (InputStream input = Classifier.class.getResourceAsStream("/dictionary/emergency-terms-cl.json")) {
            if (input == null) {
                throw new IllegalStateException("No se encontró el diccionario de emergencias de CODES.");
            }
            Map<String, Map<String, List<String>>> source =
                    new ObjectMapper().readValue(input, new TypeReference<>() {});
            Map<String, List<String>> termsByInstitution = new LinkedHashMap<>();
            for (String institution : List.of("carabineros", "samu", "bomberos")) {
                Map<String, List<String>> categories = source.get(institution);
                if (categories == null || categories.isEmpty()) {
                    throw new IllegalStateException("El diccionario no contiene términos para " + institution + ".");
                }
                LinkedHashSet<String> terms = new LinkedHashSet<>();
                categories.values().stream()
                        .filter(Objects::nonNull)
                        .flatMap(Collection::stream)
                        .filter(Objects::nonNull)
                        .map(String::strip)
                        .filter(term -> !term.isBlank())
                        .forEach(terms::add);
                termsByInstitution.put(institution, List.copyOf(terms));
            }
            return Collections.unmodifiableMap(termsByInstitution);
        } catch (IOException e) {
            throw new ExceptionInInitializerError("No se pudo cargar el diccionario de emergencias: " + e.getMessage());
        }
    }

    private static Map<String, List<String>> normalizeInstitutionTerms() {
        Map<String, List<String>> normalizedTerms = new LinkedHashMap<>();
        INSTITUTION_TERMS.forEach((institution, terms) ->
                normalizedTerms.put(institution, terms.stream().map(Classifier::normalizeText).toList()));
        return Collections.unmodifiableMap(normalizedTerms);
    }

    private static void addDictionaryTermsToMediaKeywords() {
        Set<String> existingTerms = new HashSet<>();
        KEYWORDS.values().forEach(terms -> terms.forEach(term -> existingTerms.add(normalizeText(term))));
        List<String> mediaTerms = new ArrayList<>(KEYWORDS.get("media"));
        INSTITUTION_TERMS.values().stream()
                .flatMap(Collection::stream)
                .filter(term -> existingTerms.add(normalizeText(term)))
                .forEach(mediaTerms::add);
        KEYWORDS.put("media", List.copyOf(mediaTerms));
    }

    private static boolean matchesDictionaryTerms(String institution, String normalizedText) {
        return NORMALIZED_INSTITUTION_TERMS.getOrDefault(institution, List.of()).stream()
                .anyMatch(term -> containsUnnegatedPhrase(normalizedText, term));
    }

    // -----------------------------------------------------------------
    // 2. Utilidades de texto
    // -----------------------------------------------------------------

    private static String stripDiacritics(String text) {
        String nfkd = Normalizer.normalize(text, Normalizer.Form.NFD);
        return nfkd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    private static String normalizeText(String text) {
        String t = text.toLowerCase(new Locale("es", "CL"));
        t = stripDiacritics(t);
        t = t.replaceAll("[^a-z0-9ñ\\s]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    private static boolean containsUnnegatedPhrase(String normalizedText, String normalizedPhrase) {
        String suffix = STEM_KEYWORDS.contains(normalizedPhrase) ? "[\\p{L}\\p{N}]*" : "";
        Pattern pattern = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedPhrase) + suffix + "(?![\\p{L}\\p{N}])"
        );
        Matcher matcher = pattern.matcher(normalizedText);
        while (matcher.find()) {
            if (!isNegatedAt(normalizedText, matcher.start())) return true;
        }
        if (normalizedPhrase.indexOf(' ') < 0 && normalizedPhrase.length() >= 6) {
            Matcher words = Pattern.compile("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]+(?![\\p{L}\\p{N}])")
                    .matcher(normalizedText);
            while (words.find()) {
                if (!isNegatedAt(normalizedText, words.start())
                        && editDistanceAtMostOne(normalizedPhrase, words.group())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean editDistanceAtMostOne(String expected, String actual) {
        if (Math.abs(expected.length() - actual.length()) > 1) return false;
        int left = 0;
        int right = 0;
        int edits = 0;
        while (left < expected.length() && right < actual.length()) {
            if (expected.charAt(left) == actual.charAt(right)) {
                left++;
                right++;
                continue;
            }
            if (++edits > 1) return false;
            if (expected.length() > actual.length()) left++;
            else if (expected.length() < actual.length()) right++;
            else {
                left++;
                right++;
            }
        }
        return edits + (left < expected.length() || right < actual.length() ? 1 : 0) <= 1;
    }

    private static final Map<String, List<String>> INCIDENT_CATEGORY_TERMS = Map.ofEntries(
            Map.entry("STRUCTURAL_FIRE", List.of("incendi", "casa ardiendo", "vivienda en llamas", "edificio en llamas", "incendio en edificio", "incendio estructural")),
            Map.entry("VEHICLE_FIRE", List.of("vehiculo incendiado", "auto incendiado", "auto en llamas", "vehiculo en llamas", "auto se quema")),
            Map.entry("WILDFIRE", List.of("incendio forestal", "bosque en llamas", "vegetacion ardiendo", "pastizal en llamas")),
            Map.entry("FIRE", List.of("fuego", "llama", "quemando", "se quema", "ardiendo", "incendi")),
            Map.entry("SMOKE", List.of("humo", "humo denso", "humo saliendo", "olor a humo")),
            Map.entry("EXPLOSION", List.of("explosion", "exploto", "estallo", "estallido", "explosivo")),
            Map.entry("GAS_LEAK", List.of("fuga de gas", "escape de gas", "olor a gas", "gas escapando")),
            Map.entry("HAZARDOUS_MATERIALS", List.of("material peligroso", "materiales peligrosos", "sustancia peligrosa", "sustancia toxica", "derrame quimico", "quimico derramado")),
            Map.entry("VEHICLE_ACCIDENT", List.of("accidente vehicular", "accidente de transito", "choque vehicular", "accidente")),
            Map.entry("COLLISION", List.of("choque", "chocaron", "colision", "colisionaron")),
            Map.entry("PEDESTRIAN_STRIKE", List.of("atropello", "atropellado", "atropellada", "atropellaron", "persona atropellada")),
            Map.entry("ROLLOVER", List.of("volcamiento", "volco", "volcado", "volcada", "volcaron", "se volco")),
            Map.entry("TRAPPED_PERSON", List.of("atrapado", "atrapada", "atrapados", "atrapadas", "no puede salir", "quedo atrapado")),
            Map.entry("RESCUE", List.of("rescate", "rescatar", "necesita rescate", "requiere rescate")),
            Map.entry("UNCONSCIOUS_PERSON", List.of("inconsciente", "desmayado", "desmayada", "perdio el conocimiento", "no despierta")),
            Map.entry("BREATHING_DIFFICULTY", List.of("no puede respirar", "dificultad para respirar", "dificultad al respirar", "respira con dificultad", "dificultad respiratoria", "falta de aire", "le cuesta respirar", "se asfixia")),
            Map.entry("SEIZURE", List.of("convulsion", "convulsiona", "convulsionando", "ataque epileptico")),
            Map.entry("INJURED_PERSON", List.of("herido", "herida", "heridos", "heridas", "lesionado", "lesionada", "persona herida")),
            Map.entry("BLEEDING", List.of("sangrado", "sangrando", "hemorragia", "desangrando", "pierde sangre")),
            Map.entry("MEDICAL_EMERGENCY", List.of("emergencia medica", "urgencia medica", "paro cardiaco", "infarto", "sobredosis", "intoxicacion")),
            Map.entry("COLLAPSE", List.of("derrumbe", "se derrumbo", "colapso", "edificio colapsado", "alud", "deslizamiento de tierra")),
            Map.entry("FLOOD", List.of("inundacion", "se inundo", "desborde", "aluvion", "anegamiento")),
            Map.entry("ELECTRICAL_EMERGENCY", List.of("electrocutado", "electrocutada", "electrocucion", "descarga electrica", "cable energizado", "corto circuito")),
            Map.entry("WORKPLACE_ACCIDENT", List.of("accidente laboral", "accidente en el trabajo", "trabajador atrapado", "caida de altura")),
            Map.entry("SCHOOL_EMERGENCY", List.of("emergencia en colegio", "emergencia escolar", "accidente en colegio", "alumnos atrapados")),
            Map.entry("BUILDING_EMERGENCY", List.of("emergencia en edificio", "incendio en edificio", "derrumbe de edificio", "edificio colapsado")),
            Map.entry("PUBLIC_SPACE_EMERGENCY", List.of("emergencia en espacio publico", "persona en espacio publico", "incendio en espacio publico")),
            Map.entry("ROBBERY", List.of("robo", "roba", "asalto", "asalt", "portonazo", "encerrona")),
            Map.entry("ASSAULT", List.of("agresion", "golpiza", "golpe", "apuñal", "cuchillada")),
            Map.entry("THREAT", List.of("amenaza", "amenazando", "amenaza de muerte", "intimidando")),
            Map.entry("VIOLENCE", List.of("violencia", "violento", "violenta", "violencia intrafamiliar", "maltrato")),
            Map.entry("MISSING_PERSON", List.of("persona desaparecida", "menor desaparecido", "persona perdida", "persona extraviada")),
            Map.entry("RISK_SITUATION", List.of("situacion de riesgo", "persona en riesgo", "persona en peligro")),
            Map.entry("TRAFFIC_INCIDENT", List.of("incidente de transito", "accidente de transito", "vehiculo detenido en la via")),
            Map.entry("PERSON_UNRESPONSIVE", List.of("no reacciona", "no responde", "sin signos vitales", "no respira"))
    );

    private static List<String> detectIncidentCategories(String normalizedText) {
        List<String> categories = new ArrayList<>();
        for (var entry : INCIDENT_CATEGORY_TERMS.entrySet()) {
            if (containsAnyNotNegated(normalizedText, entry.getValue().toArray(String[]::new))) {
                categories.add(entry.getKey());
            }
        }
        boolean confirmedFire = categories.contains("FIRE");
        if (confirmedFire && containsAnyNotNegated(normalizedText,
                "casa", "vivienda", "edificio", "departamento", "local", "galpon")) {
            if (!categories.contains("STRUCTURAL_FIRE")) categories.add("STRUCTURAL_FIRE");
        }
        if (confirmedFire && containsAnyNotNegated(normalizedText,
                "auto", "vehiculo", "camion", "bus", "motocicleta", "camioneta")) {
            if (!categories.contains("VEHICLE_FIRE")) categories.add("VEHICLE_FIRE");
        }
        if (confirmedFire && containsAnyNotNegated(normalizedText,
                "bosque", "pastizal", "vegetacion", "cerro", "parque")) {
            if (!categories.contains("WILDFIRE")) categories.add("WILDFIRE");
        }
        return List.copyOf(categories);
    }

    private static boolean isNegatedAt(String normalizedText, int index) {
        String precedingText = normalizedText.substring(0, index).trim();
        if (precedingText.isEmpty()) return false;
        String[] tokens = precedingText.split("\\s+");
        int startIndex = Math.max(0, tokens.length - NEGATION_WINDOW);
        for (int i = startIndex; i < tokens.length; i++) {
            if (NEGATIONS.contains(tokens[i])) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 3. Extracción de dirección
    // -----------------------------------------------------------------

    private static final Pattern ADDRESS_PATTERN = Pattern.compile(
            "(calle|pasaje|avenida|av\\.?|sector|poblacion|villa|cerro|camino|ruta|carretera|caletera|autopista|callejon)" +
            "\\s+([\\p{L}0-9'’\\.\\-\\s]{3,40}?)" +
            "(?:\\s+(?:numero|n[uú]mero|#)?\\s*(\\d{1,5}))?" +
            "(?=[,.]| y | esquina | altura |$)",
            Pattern.CASE_INSENSITIVE
    );

    // Respaldo cuando el ASR omite la palabra "calle/avenida/..." (muy común):
    // nombre propio (una o más palabras con mayúscula, o cualquier palabra si
    // viene precedido de "en/por") seguido directamente de un número de casa.
    private static final Pattern NAME_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:en|por)\\s+)?"
                    + "([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]*(?:\\s+[A-ZÁÉÍÓÚÑ0-9][\\wáéíóúñ]*){0,3})"
                    + "\\s+(?:n[uú]mero\\s+|#\\s*)?(\\d{1,5})\\b"
    );

    public static String extractAddress(String text) {
        if (text == null) return null;
        // Convierte números dictados en palabras ("dieciséis treinta" -> "1630",
        // "mil seiscientos treinta" -> "1630") ANTES de aplicar los patrones de
        // abajo, que solo reconocen dígitos (\d). El resto del texto no cambia.
        text = NumberWords.convert(text);
        Matcher m = ADDRESS_PATTERN.matcher(text);
        if (m.find()) {
            String streetType = m.group(1).trim();
            String name = m.group(2).trim();
            String number = m.group(3);

            StringBuilder sb = new StringBuilder();
            sb.append(Character.toUpperCase(streetType.charAt(0))).append(streetType.substring(1));
            sb.append(' ').append(toTitleCase(name));
            if (number != null) sb.append(" #").append(number);
            return sb.toString().trim();
        }

        Matcher context = Pattern.compile(
            "(?i)(?:ubicad[ao]|queda|esta|está)\\s+(?:al|a la|en|por)\\s+([^,.]{3,70}?)(?=\\s*(?:,|\\by\\s+(?=(?:hay|necesita|requiere|presenta|se encuentra|est[aá]n?)\\b)|\\b(?:donde|porque|necesita|requiere|presenta|se encuentra)\\b|$))(?:\\s*,\\s*(?:en\\s+)?([^,.]{3,40}))?"
        ).matcher(text);
        if (context.find()) {
            String place = context.group(1).trim();
            String municipality = context.group(2) == null ? "" : context.group(2).trim();
            return toTitleCase(place) + (municipality.isBlank() ? "" : ", " + toTitleCase(municipality));
        }

        // Respaldo: sin la palabra clave de vía, pero con un nombre propio
        // seguido de un número ("Manuel Montt 1234").
        Matcher nombreNumero = NAME_NUMBER_PATTERN.matcher(text);
        while (nombreNumero.find()) {
            String candidate = nombreNumero.group(1).trim();
            if (candidate.split("\\s+").length > 4) continue;
            if (isNonAddressTerm(candidate)) continue;
            return toTitleCase(candidate) + " #" + nombreNumero.group(2);
        }
        return null;
    }

    private static final Set<String> NON_ADDRESS_TERMS = Set.of(
            "hola", "ayuda", "por favor", "gracias", "chile", "santiago"
    );

    private static boolean isNonAddressTerm(String candidate) {
        String normalizedText = normalizeText(candidate);
        return NON_ADDRESS_TERMS.contains(normalizedText);
    }

    private static String toTitleCase(String text) {
        String[] palabras = text.trim().split("\\s+");
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
    // varias palabras se repiten aquí aunque ya estén en KEYWORDS, y
    // varias (p. ej. "herido", "caida", "atrapado") no existen en ese
    // diccionario y solo viven acá.
    //
    // Antes esta lista era mucho más corta y dejaba fuera casos como
    // violación/abuso sexual, suicidio, secuestro o apuñalamientos, que por
    // lo tanto nunca sugerían ninguna institución pese a ser URGENTE. Si se
    // agrega una palabra nueva a KEYWORDS que también deba avisar a
    // una institución, hay que agregarla también acá.
    private static final List<String> CARABINEROS_TERMS = List.of(
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
            "accidente", "choque", "chocaron", "colision", "atropell", "volcamiento",
            "vehiculo volcado", "persona desaparecida", "emergencia de transito",
            "hurto", "cartereo", "carterazo",
            "control de identidad", "sujeto sospechoso", "actitud sospechosa", "merodeando",
            "niño perdido", "adolescente perdido", "persona perdida",
            "carabinero herido", "policia herido", "funcionario baleado"
    );
    private static final List<String> SAMU_TERMS = List.of(
            "no respira", "no reacciona", "sin signos vitales", "desangrando", "desangrado", "mucha sangre", "sangr",
            "convulsion", "se ahoga", "atragant", "paro cardiaco", "paro", "infarto",
            "muerto", "muerta", "cadaver", "cuerpo sin vida", "cuerpo inerte",
            "apuñal", "cuchillada", "degollad", "baleado", "baleada", "balazo",
            "herido", "herida", "lesionado", "inconsciente", "no responde",
            "no puede respirar", "dificultad para respirar", "dificultad al respirar",
            "respira con dificultad", "no le entra aire", "dolor intenso",
            "persona afectada", "personas afectadas", "intoxicado", "intoxicada",
            "accidente", "choque", "chocaron", "colision", "atropell", "volcamiento", "volco",
            "sobredosis", "intoxicacion", "intoxic", "quemadura", "caida",
            "persona atrapada", "persona atrapado", "personas atrapadas", "personas atrapados",
            "suicidio", "se va a matar", "se quiere matar", "colgarse", "saltar al vacio", "se va a tirar",
            "violacion", "abuso sexual", "agresion sexual",
            "golpiza", "botellazo", "piedrazo", "gritos desgarradores",
            "carabinero herido", "policia herido", "funcionario baleado"
    );
    private static final List<String> BOMBEROS_TERMS = List.of(
            "incendi", "quema", "llamas", "humo", "fuego", "explosion", "explosivo", "fuga de gas",
            "derrumbe", "alud", "colapso", "atrapad", "rescate", "sismo fuerte", "tsunami",
            "granada", "bomba", "incendio forestal", "vehiculo incendiado", "auto incendiado",
            "material peligroso", "inundacion", "electrocutado", "electrocucion"
    );

    // -----------------------------------------------------------------
    // 4. Resultado estructurado
    // -----------------------------------------------------------------

    public record InstitutionRecommendation(
            String institution,
            String role,
            String severity,
            String reason
    ) {}

    public record ClassificationResult(
            String priority,
            Map<String, Integer> scores,
            List<String> highlightedWords,
            List<String> allMatchedWords,
            String address,
            String originalText,
            List<String> reasons,
            List<String> suggestedInstitutions,
            List<String> incidentCategories,
            List<InstitutionRecommendation> institutionRecommendations
    ) {}

    public static boolean hasOperationalInformation(ClassificationResult result) {
        return result != null && (
                !result.highlightedWords().isEmpty()
                        || !result.suggestedInstitutions().isEmpty()
                        || (result.address() != null && !result.address().isBlank())
        );
    }

    // -----------------------------------------------------------------
    // 5. Función principal
    // -----------------------------------------------------------------

    public static ClassificationResult classifyCall(String text) {
        if (text == null || text.isBlank()) {
            Map<String, Integer> emptyScores = new LinkedHashMap<>();
            for (String cat : KEYWORDS.keySet()) emptyScores.put(cat, 0);
                return new ClassificationResult("VERDE", emptyScores, List.of(), List.of(), null,
                    text == null ? "" : text, List.of(), List.of(), List.of(), List.of());
        }

        String normalizedText = normalizeText(text);

        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String cat : KEYWORDS.keySet()) scores.put(cat, 0);

        // (palabra_original, categoria, peso)
        record Match(String word, String category, int weight) {}
        List<Match> matches = new ArrayList<>();

        for (String category : KEYWORDS.keySet()) {
            List<String> originalTerms = KEYWORDS.get(category);
            List<String> normalizedTerms = NORMALIZED_KEYWORDS.get(category);
            for (int i = 0; i < normalizedTerms.size(); i++) {
                String normalizedTerm = normalizedTerms.get(i);
                if (normalizedTerm.isEmpty()) continue;
                if (containsUnnegatedPhrase(normalizedText, normalizedTerm)) {
                    int weight = CATEGORY_WEIGHTS.get(category);
                    scores.merge(category, weight, Integer::sum);
                    matches.add(new Match(originalTerms.get(i), category, weight));
                }
            }
        }

        String priority;
        if (scores.get("urgente") > 0) {
            priority = "URGENTE";
        } else if (scores.get("roja") >= RED_THRESHOLD) {
            priority = "ROJA";
        } else if (scores.get("media") >= MEDIUM_THRESHOLD) {
            priority = "MEDIA";
        } else {
            priority = "VERDE";
        }

        // Top 5 por peso descendente, sin duplicados de etiqueta.
        matches.sort((a, b) -> Integer.compare(b.weight(), a.weight()));
        LinkedHashSet<String> top = new LinkedHashSet<>();
        for (Match e : matches) {
            top.add(displayLabel(e.word()));
            if (top.size() == 5) break;
        }

        TreeSet<String> allMatches = new TreeSet<>();
        for (Match e : matches) allMatches.add(displayLabel(e.word()));

        String address = extractAddress(text);
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        for (Match e : matches) {
            reasons.add(categoryLabel(e.category()) + ": " + displayLabel(e.word()));
        }

        List<InstitutionRecommendation> institutionRecommendations =
                institutionRecommendations(normalizedText, priority);
        List<String> suggestedInstitutions = institutionRecommendations.stream()
                .map(InstitutionRecommendation::institution)
                .toList();
        List<String> incidentCategories = detectIncidentCategories(normalizedText);

        return new ClassificationResult(
                priority,
                scores,
                new ArrayList<>(top),
                new ArrayList<>(allMatches),
                address,
                text,
                new ArrayList<>(reasons),
                suggestedInstitutions,
                incidentCategories,
                institutionRecommendations
        );
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "urgente" -> "Riesgo vital o crítico";
            case "roja" -> "Emergencia prioritaria";
            case "media" -> "Incidente operativo";
            default -> "Solicitud no crítica";
        };
    }

    private static List<String> legacySuggestedInstitutions(String normalizedText) {
        LinkedHashSet<String> institutions = new LinkedHashSet<>();
        boolean policeIncident = containsAnyNotNegated(normalizedText, CARABINEROS_TERMS.toArray(String[]::new));
        boolean medicalIncident = containsAnyNotNegated(normalizedText, SAMU_TERMS.toArray(String[]::new));
        boolean firefightersIncident = containsAnyNotNegated(normalizedText, BOMBEROS_TERMS.toArray(String[]::new));

        if (policeIncident || matchesDictionaryTerms("carabineros", normalizedText)) institutions.add("carabineros");
        if (medicalIncident || matchesDictionaryTerms("samu", normalizedText)) institutions.add("samu");
        if (firefightersIncident || matchesDictionaryTerms("bomberos", normalizedText)) institutions.add("bomberos");
        return List.copyOf(institutions);
    }

    private static List<InstitutionRecommendation> institutionRecommendations(String text, String priority) {
        List<String> categories = detectIncidentCategories(text);
        List<String> legacy = legacySuggestedInstitutions(text);
        boolean confirmedFire = categories.contains("FIRE");
        boolean smokeOnly = !confirmedFire && categories.contains("SMOKE");
        boolean gasLeak = categories.contains("GAS_LEAK");
        boolean trafficIncident = categories.stream().anyMatch(category -> Set.of(
                "VEHICLE_ACCIDENT", "COLLISION", "PEDESTRIAN_STRIKE", "ROLLOVER", "TRAFFIC_INCIDENT"
        ).contains(category));
        boolean trappedPerson = categories.contains("TRAPPED_PERSON");
        boolean lifeThreatening = containsAnyNotNegated(text,
                "no respira", "dificultad para respirar", "no puede respirar", "inconsciente",
                "convulsion", "paro cardiaco", "infarto", "hemorragia", "desangrando",
                "sangrado abundante", "sin signos vitales", "no responde",
                "se va a matar", "se quiere matar", "se va a tirar", "saltar al vacio",
                "colgarse", "intento de suicidio", "riesgo suicida", "se asfixia", "falta de aire",
                "le cuesta respirar", "dificultad al respirar", "respira con dificultad",
                "perdio el conocimiento", "no despierta", "no tiene pulso",
                "dejo de respirar", "paro cardiorrespiratorio", "ataque al corazon", "derrame cerebral",
                "anafilaxia", "edema de glotis", "ahogamiento", "hemorragia obstetrica",
                "parto inminente", "bebe no respira", "eclampsia", "intento de ahorcamiento");
        boolean injured = categories.contains("INJURED_PERSON") || categories.contains("BLEEDING")
                || containsAnyNotNegated(text,
                "herido", "herida", "lesionado", "lesionada", "quemadura", "sangr",
                "persona afectada", "personas afectadas", "intoxicado", "intoxicada",
                "violacion", "abuso sexual", "agresion sexual");
        boolean policeIncident = containsAnyNotNegated(text,
                "robo", "rob", "asalt", "arma", "dispar", "agresion", "violencia", "amenaza",
                "secuestro", "rehen", "persona desaparecida", "persona perdida",
                "violacion", "abuso sexual", "agresion sexual",
                "se va a matar", "se quiere matar", "riesgo suicida");
        boolean medicalDictionaryIncident = matchesDictionaryTerms("samu", text);
        boolean rescueOrHazard = trappedPerson || gasLeak || categories.stream().anyMatch(category -> Set.of(
                "RESCUE", "EXPLOSION", "HAZARDOUS_MATERIALS", "COLLAPSE", "FLOOD", "ELECTRICAL_EMERGENCY"
        ).contains(category));
        boolean pedestrianStrike = categories.contains("PEDESTRIAN_STRIKE");
        boolean hasMedicalNeed = lifeThreatening || injured || trappedPerson || pedestrianStrike
                || medicalDictionaryIncident;
        String medicalSeverity = lifeThreatening || trappedPerson || (confirmedFire && injured)
                ? "URGENTE" : injured || pedestrianStrike ? "ROJA"
                : medicalDictionaryIncident ? priority : "MEDIA";

        LinkedHashMap<String, InstitutionRecommendation> recommendations = new LinkedHashMap<>();

        if (confirmedFire || gasLeak || rescueOrHazard) {
            addRecommendation(recommendations, "bomberos", "PRIMARY",
                    confirmedFire || trappedPerson || lifeThreatening ? "URGENTE" : "ROJA",
                    confirmedFire ? "Incendio confirmado o fuego reportado."
                            : gasLeak ? "Fuga o escape de gas reportado."
                            : "Rescate o riesgo estructural/ambiental reportado.");
        } else if (trafficIncident && !hasMedicalNeed) {
            addRecommendation(recommendations, "carabineros", "PRIMARY", "ROJA",
                    "Incidente de tránsito reportado; no se detectaron personas afectadas.");
        } else if (policeIncident) {
            addRecommendation(recommendations, "carabineros", "PRIMARY",
                    lifeThreatening ? "URGENTE" : priorityForPolice(text),
                    "Señal de seguridad, delito o riesgo para terceros.");
        } else if (hasMedicalNeed) {
            addRecommendation(recommendations, "samu", "PRIMARY",
                    medicalSeverity,
                    "Señal de posible afectación de salud.");
        }

        if (legacy.contains("bomberos") && !recommendations.containsKey("bomberos")) {
            addRecommendation(recommendations, "bomberos",
                    recommendations.isEmpty() ? "PRIMARY" : "SECONDARY",
                    smokeOnly || "VERDE".equals(priority) ? "MEDIA" : priority,
                    smokeOnly ? "Humo reportado; el origen no está confirmado."
                            : "Riesgo que requiere evaluación de Bomberos.");
        }
        if (legacy.contains("samu") && !recommendations.containsKey("samu")
                && hasMedicalNeed) {
            addRecommendation(recommendations, "samu",
                    recommendations.isEmpty() ? "PRIMARY" : "SECONDARY",
                    medicalSeverity,
                    "Señal de posible afectación de salud.");
        }
        if (legacy.contains("carabineros") && !recommendations.containsKey("carabineros")) {
            addRecommendation(recommendations, "carabineros",
                    recommendations.isEmpty() ? "PRIMARY" : "SECONDARY",
                    trafficIncident ? "ROJA" : priorityForPolice(text),
                    trafficIncident ? "Apoyo de seguridad y gestión del tránsito."
                            : "Señal de seguridad o riesgo para terceros.");
        }

        if (hasMedicalNeed && !recommendations.containsKey("samu")) {
            addRecommendation(recommendations, "samu", "SECONDARY",
                    medicalSeverity,
                    "Señal de posible afectación de salud.");
        }
        if (((confirmedFire && (trappedPerson || hasMedicalNeed))
                || ((gasLeak || rescueOrHazard) && policeIncident))
                && !recommendations.containsKey("carabineros")) {
            addRecommendation(recommendations, "carabineros", "SECONDARY", "ROJA",
                    "Apoyo de seguridad por el contexto del incidente.");
        }
        if (confirmedFire && (categories.contains("STRUCTURAL_FIRE") || categories.contains("VEHICLE_FIRE"))
                && !recommendations.containsKey("carabineros")) {
            addRecommendation(recommendations, "carabineros", "SECONDARY", "MEDIA",
                    "Apoyo de seguridad y perímetro por incendio en inmueble o vehículo.");
        }
        if (trafficIncident && hasMedicalNeed && !recommendations.containsKey("carabineros")) {
            addRecommendation(recommendations, "carabineros", "SECONDARY", "ROJA",
                    "Apoyo de seguridad y gestión del tránsito.");
        }
        if (trappedPerson && !recommendations.containsKey("bomberos")) {
            addRecommendation(recommendations, "bomberos", "PRIMARY", "URGENTE",
                    "Persona atrapada; se sugiere evaluación de rescate.");
        }

        return List.copyOf(recommendations.values());
    }

    private static String priorityForPolice(String text) {
        if (containsAnyNotNegated(text, "arma", "dispar", "tiroteo", "rehen", "secuestro")) {
            return "URGENTE";
        }
        return containsAnyNotNegated(text,
                "robo", "asalt", "pele", "agresion", "violencia", "amenaza", "allanamiento",
                "rob", "intruso", "homicidio", "asesinato", "femicidio", "apuñal", "violacion",
                "abuso sexual", "persona armada", "delito en curso")
                ? "ROJA" : "MEDIA";
    }

    private static void addRecommendation(
            Map<String, InstitutionRecommendation> recommendations,
            String institution,
            String role,
            String severity,
            String reason
    ) {
        InstitutionRecommendation existing = recommendations.get(institution);
        if (existing == null) {
            recommendations.put(institution, new InstitutionRecommendation(institution, role, severity, reason));
            return;
        }

        String mergedRole = "PRIMARY".equals(role) ? role : existing.role();
        String mergedSeverity = severityRank(severity) > severityRank(existing.severity())
                ? severity : existing.severity();
        recommendations.put(institution, new InstitutionRecommendation(
                institution, mergedRole, mergedSeverity, existing.reason()));
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "URGENTE" -> 4;
            case "ROJA" -> 3;
            case "MEDIA" -> 2;
            default -> 1;
        };
    }

    private static boolean containsAnyNotNegated(String text, String... phrases) {
        for (String phrase : phrases) {
            String normalizedPhrase = normalizeText(phrase);
            if (containsUnnegatedPhrase(text, normalizedPhrase)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------
    // 6. Pruebas rápidas al ejecutar directamente
    // -----------------------------------------------------------------

    public static void main(String[] args) {
        List<String> sampleCases = List.of(
                "Ayuda, hay un disparo y un hombre con cuchillo en calle Los Alerces 123, hay mucha sangre",
                "Mi vecino está con la música muy fuerte en pasaje Las Rosas, ya van dos horas",
                "Quiero saber el horario de atención de la comisaría",
                "Nos están asaltando dos sujetos encapuchados en avenida Manquehue, uno tiene un arma",
                "No hay ningún arma, tranquilo, fue solo una discusión con un vecino",
                "Se está incendiando una casa en villa Esperanza, hay mucho humo"
        );

        for (String sampleText : sampleCases) {
            ClassificationResult result = classifyCall(sampleText);
            System.out.println("-".repeat(70));
            System.out.println("Texto: " + sampleText);
            System.out.println("Prioridad: " + result.priority() + " | Puntajes: " + result.scores());
            System.out.println("Destacadas: " + result.highlightedWords());
            System.out.println("Dirección: " + result.address());
        }
    }
}
