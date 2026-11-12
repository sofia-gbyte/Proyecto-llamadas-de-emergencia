package cl.codes.controller.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateLiveCallRequestTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        FACTORY.close();
    }

    @Test
    void acceptsPunctuationUnicodeAndEmptyTranscriptForAudioOnlyRequests() {
        assertTrue(VALIDATOR.validate(new CreateLiveCallRequest(
                "Av. O'Higgins, Ñuñoa: áéíóú 😀; '\"", -33.4, -70.6)).isEmpty());
        assertTrue(VALIDATOR.validate(new CreateLiveCallRequest(null, null, null)).isEmpty());
        assertTrue(VALIDATOR.validate(new CreateLiveCallRequest("   ", null, null)).isEmpty());
    }

    @Test
    void rejectsOversizedTranscriptsAndOutOfRangeCoordinates() {
        assertFalse(VALIDATOR.validate(new CreateLiveCallRequest("x".repeat(20_001), null, null)).isEmpty());
        assertFalse(VALIDATOR.validate(new CreateLiveCallRequest("valid", -91.0, -70.0)).isEmpty());
        assertFalse(VALIDATOR.validate(new CreateLiveCallRequest("valid", -33.0, 181.0)).isEmpty());
    }

    @Test
    void preservesSpanishCoordinateNamesInPublicJsonContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CreateLiveCallRequest request = mapper.readValue(
                """
                        {"transcription":"Incendio","latitudOperador":-33.4,"longitudOperador":-70.6}
                        """,
                CreateLiveCallRequest.class);

        assertEquals(-33.4, request.operatorLatitude());
        assertEquals(-70.6, request.operatorLongitude());
        assertTrue(mapper.writeValueAsString(request).contains("\"latitudOperador\""));
        assertTrue(mapper.writeValueAsString(request).contains("\"longitudOperador\""));
    }

    @Test
    void preservesSpanishRegistrationNamesInPublicJsonContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RegisterRequest request = mapper.readValue(
                """
                        {
                          "username":"operator1",
                          "password":"Strong-Password-123",
                          "nombre":"Ana",
                          "apellido":"Pérez",
                          "correo":"ana@example.com",
                          "institucion":"bomberos",
                          "captchaToken":"token"
                        }
                        """,
                RegisterRequest.class);

        assertEquals("Ana", request.firstName());
        assertEquals("Pérez", request.lastName());
        assertEquals("ana@example.com", request.email());
        assertEquals("bomberos", request.institution());
        String json = mapper.writeValueAsString(request);
        assertTrue(json.contains("\"nombre\""));
        assertTrue(json.contains("\"apellido\""));
        assertTrue(json.contains("\"correo\""));
        assertTrue(json.contains("\"institucion\""));
    }
}
