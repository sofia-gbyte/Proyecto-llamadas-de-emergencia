package cl.codes.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class TurnstileService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final RestClient restClient;
    private final String secretKey;
    private final String siteKey;

    public TurnstileService(
            @Value("${app.turnstile-secret-key:}") String secretKey,
            @Value("${app.turnstile-site-key:}") String siteKey
    ) {
        this.restClient = RestClient.builder().build();
        this.secretKey = secretKey;
        this.siteKey = siteKey;
    }

    public boolean isConfigured() {
        return !secretKey.isBlank() && !siteKey.isBlank();
    }

    public String getSiteKey() {
        return siteKey;
    }

    public boolean verify(String token, String remoteIp) {
        if (!isConfigured() || token == null || token.isBlank()) {
            return false;
        }
        try {
            LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secretKey);
            form.add("response", token);
            if (remoteIp != null && !remoteIp.isBlank()) {
                form.add("remoteip", remoteIp);
            }
            Map<String, Object> result = restClient.post()
                    .uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
