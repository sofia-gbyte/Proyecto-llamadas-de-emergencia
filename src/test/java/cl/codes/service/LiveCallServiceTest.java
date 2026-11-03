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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

class LiveCallServiceTest {
    @TempDir
    Path tempDir;

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
        when(geocoder.geocode(nullable(String.class), anyString(), any(), any()))
                .thenReturn(new GeocoderService.Coordinates(-33.42, -70.61, geoapifyAddress));
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
        verify(geocoder).geocode(nullable(String.class), anyString(), any(), any());
        verify(callRepository).save(saved);
    }
}
