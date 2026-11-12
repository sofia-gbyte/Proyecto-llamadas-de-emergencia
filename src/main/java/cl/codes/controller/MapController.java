package cl.codes.controller;

import cl.codes.service.GeoapifyMapTileService;
import cl.codes.service.GeoapifyRoutingService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/maps")
public class MapController {
    private final GeoapifyMapTileService mapTileService;
    private final GeoapifyRoutingService routingService;

    public MapController(GeoapifyMapTileService mapTileService, GeoapifyRoutingService routingService) {
        this.mapTileService = mapTileService;
        this.routingService = routingService;
    }

    @GetMapping("/tiles/{style}/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> tile(@PathVariable String style, @PathVariable int z,
                                       @PathVariable int x, @PathVariable int y,
                                       HttpServletRequest request) {
        requireLoopback(request.getRemoteAddr());
        requireSameOrigin(request);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(mapTileService.getTile(style, z, x, y));
    }

    @GetMapping("/route")
    public GeoapifyRoutingService.RouteResult route(
            @RequestParam double originLat,
            @RequestParam double originLng,
            @RequestParam double destinationLat,
            @RequestParam double destinationLng) {
        validateCoordinate(originLat, -90, 90, "latitud de origen");
        validateCoordinate(destinationLat, -90, 90, "latitud de destino");
        validateCoordinate(originLng, -180, 180, "longitud de origen");
        validateCoordinate(destinationLng, -180, 180, "longitud de destino");
        return routingService.route(originLat, originLng, destinationLat, destinationLng);
    }

    private void validateCoordinate(double value, double minimum, double maximum, String label) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Coordenada inválida: " + label + ".");
        }
    }

    private void requireLoopback(String remoteAddress) {
        try {
            if (remoteAddress == null || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Geoapify Maps solo está disponible desde este equipo.");
            }
        } catch (UnknownHostException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No se pudo validar el origen de la solicitud.");
        }
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String referrer = request.getHeader("Origin");
        if (referrer == null || referrer.isBlank()) referrer = request.getHeader("Referer");
        if (referrer == null || referrer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "La solicitud de teselas debe originarse desde CODES.");
        }
        try {
            URI source = URI.create(referrer);
            int sourcePort = source.getPort() < 0
                    ? ("https".equalsIgnoreCase(source.getScheme()) ? 443 : 80)
                    : source.getPort();
            if (!request.getScheme().equalsIgnoreCase(source.getScheme())
                    || !request.getServerName().equalsIgnoreCase(source.getHost())
                    || request.getServerPort() != sourcePort) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "La solicitud de teselas debe originarse desde CODES.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No se pudo validar el origen de la solicitud.");
        }
    }
}
