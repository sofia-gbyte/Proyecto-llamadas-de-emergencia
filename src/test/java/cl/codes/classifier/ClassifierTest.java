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

    @Test
    void sugiereCarabinerosYSamuEnAgresionSexual() {
        var resultado = Classifier.classifyCall(
                "Mi vecina fue víctima de una violación hace unos minutos, está muy mal"
        );

        assertTrue(resultado.priority().equals("URGENTE"));
        assertTrue(resultado.institucionesSugeridas().containsAll(
                List.of("carabineros", "samu")
        ));
    }

    @Test
    void sugiereSamuYCarabinerosEnRiesgoSuicida() {
        var resultado = Classifier.classifyCall(
                "Mi hermano dice que se va a tirar del puente, está en la baranda ahora mismo"
        );

        assertTrue(resultado.priority().equals("URGENTE"));
        assertTrue(resultado.institucionesSugeridas().containsAll(
                List.of("samu", "carabineros")
        ));
    }

    @Test
    void sugiereCarabinerosEnApunalamientoSinPalabraArma() {
        var resultado = Classifier.classifyCall(
                "Un sujeto acaba de apuñalar a otro en la plaza, hay mucha sangre"
        );

        assertTrue(resultado.institucionesSugeridas().containsAll(
                List.of("carabineros", "samu")
        ));
    }

    @Test
    void identificaTranscripcionesSinInformacionOperacional() {
        assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall("")));
        assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall("texto sin sentido")));
    }

    @Test
    void conservaSolicitudesVerdesConInformacionOperacional() {
        assertTrue(Classifier.hasOperationalInformation(
                Classifier.classifyCall("Quiero saber el horario de atención de la comisaría")
        ));
    }
}
