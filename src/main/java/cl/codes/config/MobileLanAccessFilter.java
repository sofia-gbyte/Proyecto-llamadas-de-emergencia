package cl.codes.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MobileLanAccessFilter extends OncePerRequestFilter {
    private final boolean enabled;
    private final String localAddress;

    public MobileLanAccessFilter(
            @Value("${app.mobile.lan-enabled:false}") boolean enabled,
            @Value("${app.mobile.lan-address:}") String localAddress
    ) {
        this.enabled = enabled;
        this.localAddress = localAddress;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getLocalPort() == MobileLanTlsConfiguration.HTTPS_PORT
                && (!enabled || !MobileLanNetwork.isOnSameSubnet(localAddress, request.getRemoteAddr()))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
