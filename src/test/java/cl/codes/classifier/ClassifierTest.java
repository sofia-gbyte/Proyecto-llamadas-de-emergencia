package cl.codes.classifier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClassifierTest {

    @Test
    void sugiereInstitucionesEnEmergenciaMultisectorial() {
        var resultado = Classifier.classifyCall(
                "Hay un incendio, una persona herida y dos sujetos con arma en avenida Providencia 1234"
        );

        assertTrue(resultado.priority().equals("URGENTE"));
        assertTrue(resultado.institucionesSugeridas().containsAll(
                List.of("carabineros", "samu", "bomberos")
        ));
        assertFalse(resultado.motivos().isEmpty());
        assertTrue(resultado.direccion().contains("Providencia"));
    }

    @Test
    void noActivaInstitucionPorAmenazaNegada() {
        var resultado = Classifier.classifyCall(
                "No hay ningún arma, fue solo una discusión con el vecino"
        );

        assertFalse(resultado.institucionesSugeridas().contains("carabineros"));
        assertTrue(resultado.priority().equals("MEDIA"));
    }
}
