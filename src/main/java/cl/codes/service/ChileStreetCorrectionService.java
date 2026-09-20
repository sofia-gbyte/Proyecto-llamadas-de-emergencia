package cl.codes.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Corrects ASR street names using the Chilean OSM street dictionary. */
@Service
public class ChileStreetCorrectionService {
    private static final Logger log = LoggerFactory.getLogger(ChileStreetCorrectionService.class);
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)(\\b(?:calle|pasaje|avenida|av\\.?|sector|poblacion|villa|cerro|camino)\\s+)"
                    + "([^,.]{3,55}?)(?=\\s+(?:(?:n[uú]mero|numero|#)\\s*)?\\d{1,5}\\b|[,.]|\\s+y\\s+|\\s+esquina\\b|\\s+altura\\b|$)");
    private static final double MIN_SCORE = 0.75;

    private final Path dictionaryPath;
    private final List<Street> streets = new ArrayList<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ChileStreetCorrectionService(
            @Value("${app.calles-diccionario:./data/calles_chile.txt}") String dictionaryPath
    ) {
        this.dictionaryPath = Path.of(dictionaryPath);
    }

    @PostConstruct
    void loadDictionary() {
        if (!Files.exists(dictionaryPath)) {
            log.info("Diccionario de calles no encontrado en {}. La correccion ASR queda desactivada.", dictionaryPath);
            return;
        }
        try (var lines = Files.lines(dictionaryPath)) {
            lines.map(String::strip)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .map(name -> new Street(name, phonetic(name), compact(name)))
                    .sorted(Comparator.comparing(Street::name))
                    .forEach(streets::add);
            log.info("Diccionario de calles chilenas cargado: {} nombres", streets.size());
        } catch (IOException e) {
            log.warn("No se pudo leer el diccionario de calles {}: {}", dictionaryPath, e.getMessage());
        }
    }

    public String correct(String transcription) {
        if (transcription == null || transcription.isBlank() || streets.isEmpty()) return transcription;
        Matcher matcher = ADDRESS.matcher(transcription);
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String original = matcher.group(2).strip();
            String corrected = correctStreetName(original);
            if (corrected != null && !corrected.equalsIgnoreCase(original)) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + corrected));
                changed = true;
            }
        }
        if (!changed) return transcription;
        matcher.appendTail(result);
        return result.toString();
    }

    String correctStreetName(String candidate) {
        String key = candidate.strip().toLowerCase(Locale.ROOT);
        String cached = cache.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;

        String candidatePhonetic = phonetic(candidate);
        String candidateCompact = compact(candidate);
        Street best = null;
        double bestScore = 0;
        for (Street street : streets) {
            double score = Math.max(
                    similarity(candidateCompact, street.compact()),
                    similarity(candidatePhonetic, street.phonetic())
            );
            if (score > bestScore) {
                bestScore = score;
                best = street;
            }
        }
        String corrected = best != null && bestScore >= MIN_SCORE ? best.name() : "";
        cache.put(key, corrected);
        return corrected.isEmpty() ? null : corrected;
    }

    private String phonetic(String value) {
        String text = normalize(value)
                .replaceAll("\\bv\\b", "b")
                .replace("z", "s")
                .replace("ll", "y")
                .replace("h", "")
                .replace("qu", "k")
                .replaceAll("c([aou])", "k$1");
        return text.replaceAll("\\s+", " ").strip();
    }

    private String compact(String value) {
        return phonetic(value).replace(" ", "");
    }

    private String normalize(String value) {
        String text = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return text.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9ñ\\s]", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.equals(right)) return 1;
        int max = Math.max(left.length(), right.length());
        return 1.0 - (double) distance(left, right) / max;
    }

    private int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int i = 0; i <= right.length(); i++) previous[i] = i;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private record Street(String name, String phonetic, String compact) {}
}