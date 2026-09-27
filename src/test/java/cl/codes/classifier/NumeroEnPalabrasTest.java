package cl.codes.classifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumeroEnPalabrasTest {

    @Test
    void convierteParDeNumerosAlEstiloDireccionChilena() {
        assertEquals("vivo en 1630", NumeroEnPalabras.convertir("vivo en dieciseis treinta"));
        assertEquals("vivo en 1630", NumeroEnPalabras.convertir("vivo en dieciséis treinta"));
        assertEquals("pasaje Las Rosas 1920", NumeroEnPalabras.convertir("pasaje Las Rosas diecinueve veinte"));
    }

    @Test
    void convierteNumerosEnGrafiaEstandar() {
        assertEquals("avenida Providencia 1200", NumeroEnPalabras.convertir("avenida Providencia mil doscientos"));
        assertEquals("avenida Providencia 1234",
                NumeroEnPalabras.convertir("avenida Providencia mil doscientos treinta y cuatro"));
        assertEquals("calle Manuel Montt 745",
                NumeroEnPalabras.convertir("calle Manuel Montt setecientos cuarenta y cinco"));
        assertEquals("el numero es 1900", NumeroEnPalabras.convertir("el numero es mil novecientos"));
    }

    @Test
    void noConvierteUnidadesSueltasParaEvitarFalsosPositivos() {
        // "dos" y "tres" sueltos, sin decena/centena antes, no son un número de casa:
        // convertirlos rompería frases normales como "dos heridos".
        String texto = "hay dos heridos y tres testigos";
        assertEquals(texto, NumeroEnPalabras.convertir(texto));
    }

    @Test
    void noCombinaNumerosSeparadosPorOtraPalabraOPuntuacion() {
        // Con "y" entre medio, o con coma, NO se combinan como par: son dos
        // menciones separadas, no un número de dirección leído de a dos.
        String conY = NumeroEnPalabras.convertir("dieciseis y treinta funcionarios respondieron");
        assertTrue(conY.contains("16") && conY.contains("30"));
        assertTrue(!conY.contains("1630"));
    }

    @Test
    void extraerDireccionUsaLaConversionDeNumeros() {
        var resultado = Classifier.classifyCall("Hay un robo en pasaje Los Aromos dieciseis treinta");
        assertTrue(resultado.direccion() != null && resultado.direccion().contains("1630"));
    }
}
