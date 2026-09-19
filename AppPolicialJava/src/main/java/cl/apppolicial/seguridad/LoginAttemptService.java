package cl.apppolicial.seguridad;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bloqueo simple contra fuerza bruta en el login: tras
 * MAX_INTENTOS fallidos seguidos para un mismo usuario, se bloquea por
 * BLOQUEO_MINUTOS. Es en memoria (no persiste entre reinicios ni se
 * comparte entre instancias) — suficiente para un despliegue de una
 * sola PC en la comisaría, como está pensado este sistema.
 *
 * Nota: si en el futuro se corre más de una instancia de la API detrás
 * de un balanceador, esto debe moverse a un almacén compartido (p. ej.
 * la misma base de datos) para que el bloqueo sea efectivo entre nodos.
 */
@Service
public class LoginAttemptService {

    private static final int MAX_INTENTOS = 5;
    private static final long BLOQUEO_MINUTOS = 15;

    private record Estado(int intentosFallidos, Instant bloqueadoHasta) {}

    private final ConcurrentHashMap<String, Estado> estados = new ConcurrentHashMap<>();

    public boolean estaBloqueado(String usuario) {
        Estado e = estados.get(usuario.toLowerCase());
        return e != null && e.bloqueadoHasta() != null && Instant.now().isBefore(e.bloqueadoHasta());
    }

    public long minutosRestantesBloqueo(String usuario) {
        Estado e = estados.get(usuario.toLowerCase());
        if (e == null || e.bloqueadoHasta() == null) return 0;
        long segundos = Instant.now().until(e.bloqueadoHasta(), java.time.temporal.ChronoUnit.SECONDS);
        return Math.max(0, (segundos + 59) / 60);
    }

    public void registrarFallo(String usuario) {
        String clave = usuario.toLowerCase();
        estados.compute(clave, (k, actual) -> {
            int intentos = (actual == null ? 0 : actual.intentosFallidos()) + 1;
            Instant bloqueo = intentos >= MAX_INTENTOS
                    ? Instant.now().plusSeconds(BLOQUEO_MINUTOS * 60)
                    : null;
            return new Estado(intentos, bloqueo);
        });
    }

    public void registrarExito(String usuario) {
        estados.remove(usuario.toLowerCase());
    }
}
