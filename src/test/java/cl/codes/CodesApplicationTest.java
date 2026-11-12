package cl.codes;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodesApplicationTest {

    private static final URI APPLICATION_URI = URI.create("http://127.0.0.1:8000");

    @Test
    void usesWindowsRegisteredBrowserHandler() {
        assertEquals(
                List.of("rundll32.exe", "url.dll,FileProtocolHandler", APPLICATION_URI.toString()),
                CodesApplication.systemBrowserCommand(APPLICATION_URI, "Windows 11")
        );
    }

    @Test
    void usesNativeHandlersOnMacAndLinux() {
        assertEquals(
                List.of("open", APPLICATION_URI.toString()),
                CodesApplication.systemBrowserCommand(APPLICATION_URI, "Mac OS X")
        );
        assertEquals(
                List.of("xdg-open", APPLICATION_URI.toString()),
                CodesApplication.systemBrowserCommand(APPLICATION_URI, "Linux")
        );
    }

    @Test
    void rejectsUnknownOperatingSystemInsteadOfSilentlySkippingBrowserLaunch() {
        assertThrows(
                IllegalStateException.class,
                () -> CodesApplication.systemBrowserCommand(APPLICATION_URI, "Unknown OS")
        );
    }
}
