package cl.codes.controller;

import cl.codes.service.GeoapifyMapTileService;
import cl.codes.service.GeoapifyRoutingService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapControllerTest {
    @Test
    void tileProxyIsRestrictedToSameOriginLoopbackRequests() {
        GeoapifyMapTileService tiles = mock(GeoapifyMapTileService.class);
        when(tiles.getTile("osm-carto", 2, 1, 3)).thenReturn(new byte[]{1, 2, 3});
        MapController controller = new MapController(tiles, mock(GeoapifyRoutingService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/maps/tiles/osm-carto/2/1/3.png");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8000);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Referer", "http://127.0.0.1:8000/");

        var response = controller.tile("osm-carto", 2, 1, 3, request);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
    }

    @Test
    void tileProxyRejectsRemoteAndCrossOriginRequests() {
        MapController controller = new MapController(
                mock(GeoapifyMapTileService.class), mock(GeoapifyRoutingService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/maps/tiles/osm-carto/2/1/3.png");
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8000);
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Referer", "http://127.0.0.1:8000/");

        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.tile("osm-carto", 2, 1, 3, request)).getStatusCode().value());

        MockHttpServletRequest crossOriginRequest = new MockHttpServletRequest("GET",
                "/api/maps/tiles/osm-carto/2/1/3.png");
        crossOriginRequest.setScheme("http");
        crossOriginRequest.setServerName("127.0.0.1");
        crossOriginRequest.setServerPort(8000);
        crossOriginRequest.setRemoteAddr("127.0.0.1");
        crossOriginRequest.addHeader("Referer", "https://attacker.example/");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.tile("osm-carto", 2, 1, 3, crossOriginRequest)).getStatusCode().value());
    }
}
