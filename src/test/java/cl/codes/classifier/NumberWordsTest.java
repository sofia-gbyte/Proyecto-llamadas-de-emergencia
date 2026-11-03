package cl.codes.classifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberWordsTest {

    @Test
    void convertsNumberPairsInChileanAddressStyle() {
        assertEquals("vivo en 1630", NumberWords.convert("vivo en dieciseis treinta"));
        assertEquals("vivo en 1630", NumberWords.convert("vivo en dieciséis treinta"));
        assertEquals("pasaje Las Rosas 1920", NumberWords.convert("pasaje Las Rosas diecinueve veinte"));
    }

    @Test
    void convertsNumberWordsToStandardDigits() {
        assertEquals("avenida Providencia 1200", NumberWords.convert("avenida Providencia mil doscientos"));
        assertEquals("avenida Providencia 1234",
                NumberWords.convert("avenida Providencia mil doscientos treinta y cuatro"));
        assertEquals("calle Manuel Montt 745",
                NumberWords.convert("calle Manuel Montt setecientos cuarenta y cinco"));
        assertEquals("el numero es 1900", NumberWords.convert("el numero es mil novecientos"));
    }

    @Test
    void avoidsFalsePositivesFromIsolatedNumberWords() {
        // "dos" y "tres" sueltos, sin decena/centena antes, no son un número de casa:
        // convertirlos rompería frases normales como "dos heridos".
        String text = "hay dos heridos y tres testigos";
        assertEquals(text, NumberWords.convert(text));
    }

    @Test
    void doesNotCombineNumbersSeparatedByWordsOrPunctuation() {
        // Con "y" entre medio, o con coma, NO se combinan como par: son dos
        // menciones separadas, no un número de dirección leído de a dos.
        String conY = NumberWords.convert("dieciseis y treinta funcionarios respondieron");
        assertTrue(conY.contains("16") && conY.contains("30"));
        assertTrue(!conY.contains("1630"));
    }

    @Test
    void addressExtractionUsesNumberWordConversion() {
        var result = Classifier.classifyCall("Hay un robo en pasaje Los Aromos dieciseis treinta");
        assertTrue(result.address() != null && result.address().contains("1630"));
    }
}
