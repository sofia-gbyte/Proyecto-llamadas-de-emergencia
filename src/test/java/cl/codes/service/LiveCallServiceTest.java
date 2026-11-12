package cl.codes.service;

import cl.codes.controller.dto.CallResponse;
import cl.codes.model.Call;
import cl.codes.model.User;
import cl.codes.repository.CallRepository;
import cl.codes.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class LiveCallServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsNonFirePoliceCaseRecognizedByTheEmergencyDictionary() throws Exception {
        CallRepository callRepository = mock(CallRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GeocoderService geocoder = mock(GeocoderService.class);
        User user = new User();
        user.setUsername("operador");
        user.setInstitution("carabineros");
        when(userRepository.findByUsername("operador")).thenReturn(Optional.of(user));
        when(geocoder.geocodeForLiveCall(nullable(String.class), anyString(), any(), any()))
                .thenReturn(new GeocoderService.GeocodingResult(
                        new GeocoderService.Coordinates(-33.42, -70.61, null), false, 8));
        when(callRepository.save(any(Call.class))).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0);
            call.setId(42L);
            return call;
        });

        LiveCallService service = new LiveCallService(
                callRepository,
                userRepository,
                geocoder,
                mock(SecurityService.class),
                new OperationalSummaryService(),
                mock(TranscriptionService.class),
                tempDir.resolve("encrypted").toString()
        );

        Call saved = service.create("Me robaron el celular en el centro.", null, "operador", "127.0.0.1");

        assertEquals(42L, saved.getId());
        assertEquals("ROJA", saved.getPriority());
        assertTrue(saved.getSuggestedInstitutions().contains("carabineros"));
        verify(callRepository).save(saved);
    }

    @Test
    void persistsGroqTranscriptGeoapifyAddressAndSummaryInCallResponse() throws Exception {
        String transcriptText = "Se reporta incendio en el Hospital Barros Luco, hay humo y necesitamos bomberos.";
        String geoapifyAddress = "Hospital Barros Luco, San Miguel, Santiago, Chile";
        CallRepository callRepository = mock(CallRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GeocoderService geocoder = mock(GeocoderService.class);
        SecurityService security = mock(SecurityService.class);
        TranscriptionService transcription = mock(TranscriptionService.class);
        User user = new User();
        user.setUsername("operador");
        user.setInstitution("bomberos");
        when(userRepository.findByUsername("operador")).thenReturn(Optional.of(user));
        when(transcription.transcribe(any(Path.class))).thenReturn(transcriptText);
        when(geocoder.geocodeForLiveCall(nullable(String.class), anyString(), any(), any()))
                .thenReturn(new GeocoderService.GeocodingResult(
                        new GeocoderService.Coordinates(-33.42, -70.61, geoapifyAddress), false, 8));
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(1), "encrypted-audio-data");
            return null;
        }).when(security).encryptFile(any(Path.class), any(Path.class));
        when(callRepository.save(any(Call.class))).thenAnswer(invocation -> {
            Call call = invocation.getArgument(0);
            call.setId(41L);
            return call;
        });

        LiveCallService service = new LiveCallService(
                callRepository,
                userRepository,
                geocoder,
                security,
                new OperationalSummaryService(),
                transcription,
                tempDir.resolve("encrypted").toString()
        );
        MockMultipartFile audio = new MockMultipartFile(
                "audio", "llamada.webm", "audio/webm", new byte[]{1, 2, 3}
        );

        Call saved = service.create("", audio, "operador", "127.0.0.1");
        CallResponse response = CallResponse.from(saved);

        assertNull(cl.codes.classifier.Classifier.classifyCall(transcriptText).address());
        assertEquals(transcriptText, saved.getTranscription());
        assertEquals(geoapifyAddress, saved.getDetectedAddress());
        assertEquals(-33.42, saved.getLatitude());
        assertEquals(-70.61, saved.getLongitude());
        assertEquals("Incendio reportado en " + geoapifyAddress + ". Se solicita apoyo de Bomberos.",
                saved.getOperationalSummary());
        assertEquals(transcriptText, response.transcription());
        assertEquals(geoapifyAddress, response.address());
        assertEquals(saved.getOperationalSummary(), response.operationalSummary());
        assertEquals("bomberos", response.suggestedInstitutions().get(0));
        assertEquals("PRIMARY", response.institutionRecommendations().get(0).role());
        assertTrue(response.institutionRecommendations().stream()
                .noneMatch(item -> item.institution().equals("samu")));
        verify(transcription).transcribe(any(Path.class));
        verify(geocoder).geocodeForLiveCall(nullable(String.class), anyString(), any(), any());
        verify(callRepository).save(saved);
    }

    @Test
    void rejectsCallWhenItsResolvedLocationIsOutsideCoverage() throws Exception {
        CallRepository callRepository = mock(CallRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        GeocoderService geocoder = mock(GeocoderService.class);
        User user = new User();
        user.setUsername("operador");
        user.setInstitution("carabineros");
        when(userRepository.findByUsername("operador")).thenReturn(Optional.of(user));
        when(geocoder.geocodeForLiveCall(nullable(String.class), anyString(), any(), any()))
                .thenReturn(new GeocoderService.GeocodingResult(
                        GeocoderService.Coordinates.EMPTY, true, 8));

        LiveCallService service = new LiveCallService(
                callRepository,
                userRepository,
                geocoder,
                mock(SecurityService.class),
                new OperationalSummaryService(),
                mock(TranscriptionService.class),
                tempDir.resolve("encrypted").toString()
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.create("Me robaron el celular en el centro.", null,
                        "operador", "127.0.0.1", -33.44, -70.61));

        assertTrue(error.getMessage().contains("fuera del radio de cobertura de 8 km"));
        verify(callRepository, never()).save(any());
    }
}
