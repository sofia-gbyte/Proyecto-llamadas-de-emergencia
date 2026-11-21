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
    private final String allowedTailscalePeerAddress;

    public MobileLanAccessFilter(
            @Value("${app.mobile.lan-enabled:false}") boolean enabled,
            @Value("${app.mobile.lan-address:}") String localAddress,
            @Value("${app.mobile.lan-peer-address:}") String allowedTailscalePeerAddress
    ) {
        this.enabled = enabled;
        this.localAddress = localAddress;
        this.allowedTailscalePeerAddress = allowedTailscalePeerAddress;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getLocalPort() == MobileLanTlsConfiguration.HTTPS_PORT) {
            boolean allowedPeer = MobileLanNetwork.isOnAllowedMobileNetwork(
                    localAddress, request.getRemoteAddr());
            if (MobileLanNetwork.isTailscaleIpv4(localAddress)) {
                allowedPeer &= MobileLanNetwork.isAllowedTailscalePeer(
                        request.getRemoteAddr(), allowedTailscalePeerAddress);
            }
            if (!enabled || !allowedPeer) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
