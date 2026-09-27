package cl.codes.service;

import cl.codes.repository.CallRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonitorAudioServiceTest {
    @TempDir Path tempDir;

    private TranscriptionService transcriptionService;
    private GeocoderService geocoderService;
    private SecurityService securityService;
    private CallRepository repository;
    private MonitorAudioService service;

    @BeforeEach
    void setUp() throws IOException {
        transcriptionService = mock(TranscriptionService.class);
        geocoderService = mock(GeocoderService.class);
        securityService = mock(SecurityService.class);
        repository = mock(CallRepository.class);
        ChileStreetCorrectionService streetCorrection = mock(ChileStreetCorrectionService.class);
        when(streetCorrection.correct(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new MonitorAudioService(
                tempDir.resolve("raw").toString(),
                tempDir.resolve("encrypted").toString(),
                transcriptionService,
                geocoderService,
                streetCorrection,
                securityService,
                repository,
                mock(OperationalSummaryService.class)
        );
    }

    @Test
    void deletesAudioWhenTranscriptionIsEmptyWithoutSavingCall() throws Exception {
        Path audio = rawAudio("empty.wav");
        when(transcriptionService.transcribe(audio)).thenReturn("");

        service.processAudio(audio);

        assertFalse(Files.exists(audio));
        verify(repository, never()).save(any());
        verifyNoInteractions(securityService, geocoderService);
    }

    @Test
    void deletesAudioWhenTranscriptionHasNoOperationalInformation() throws Exception {
        Path audio = rawAudio("unrecognized.wav");
        when(transcriptionService.transcribe(audio)).thenReturn("texto sin sentido");

        service.processAudio(audio);

        assertFalse(Files.exists(audio));
        verify(repository, never()).save(any());
        verifyNoInteractions(securityService, geocoderService);
    }

    @Test
    void deletesAudioWhenTranscriptionCannotBeObtained() throws Exception {
        Path audio = rawAudio("failed.wav");
        when(transcriptionService.transcribe(audio)).thenThrow(new IOException("ASR unavailable"));

        service.processAudio(audio);

        assertFalse(Files.exists(audio));
        verify(repository, never()).save(any());
        verifyNoInteractions(securityService, geocoderService);
    }

    private Path rawAudio(String name) throws IOException {
        Path audio = tempDir.resolve("raw").resolve(name);
        Files.writeString(audio, "audio");
        return audio;
    }
}