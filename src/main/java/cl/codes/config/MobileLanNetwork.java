package cl.codes.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public final class MobileLanNetwork {
    private MobileLanNetwork() {
    }

    public static List<Address> availableAddresses() {
        List<Address> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return List.of();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                boolean tailscale = isTailscaleInterface(network);
                for (var interfaceAddress : network.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (!(address instanceof Inet4Address)) continue;
                    String ipAddress = address.getHostAddress();
                    boolean tailscaleAddress = tailscale && isTailscaleIpv4(ipAddress);
                    if (isPrivateIpv4(ipAddress) || tailscaleAddress) {
                        addresses.add(new Address(
                                tailscaleAddress ? "Tailscale (VPN)" : network.getDisplayName(),
                                ipAddress,
                                interfaceAddress.getNetworkPrefixLength(),
                                tailscaleAddress));
                    }
                }
            }
        } catch (SocketException exception) {
            throw new IllegalStateException("No se pudieron consultar las interfaces de red locales.", exception);
        }
        return addresses.stream()
                .sorted(Comparator.comparing(Address::interfaceName).thenComparing(Address::ipAddress))
                .toList();
    }

    public static boolean isPrivateIpv4(String value) {
        int[] octets = parseIpv4(value);
        if (octets == null) return false;
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }

    public static boolean isTailscaleIpv4(String value) {
        int[] octets = parseIpv4(value);
        return octets != null && octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127;
    }

    public static boolean isMobileIpv4(String value) {
        return isPrivateIpv4(value) || isTailscaleIpv4(value);
    }

    public static boolean isAllowedTailscalePeer(String remoteAddress, String allowedPeerAddress) {
        if (!isTailscaleIpv4(remoteAddress) || !isValidTailscalePeerAddresses(allowedPeerAddress)) return false;
        for (String peerAddress : allowedPeerAddress.split(",")) {
            if (remoteAddress.equals(peerAddress.trim())) return true;
        }
        return false;
    }

    public static boolean isValidTailscalePeerAddresses(String peerAddresses) {
        if (peerAddresses == null || peerAddresses.isBlank()) return false;
        for (String peerAddress : peerAddresses.split(",", -1)) {
            if (!isTailscaleIpv4(peerAddress.trim())) return false;
        }
        return true;
    }

    public static boolean isOnAllowedMobileNetwork(String localAddress, String remoteAddress) {
        if (isTailscaleIpv4(localAddress) || isTailscaleIpv4(remoteAddress)) {
            return isTailscaleIpv4(localAddress)
                    && isTailscaleIpv4(remoteAddress)
                    && !localAddress.equals(remoteAddress)
                    && isAssignedToTailscaleInterface(localAddress);
        }
        return isOnSamePrivateSubnet(localAddress, remoteAddress);
    }

    private static boolean isOnSamePrivateSubnet(String localAddress, String remoteAddress) {
        if (!isPrivateIpv4(localAddress) || !isPrivateIpv4(remoteAddress)) return false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                for (var interfaceAddress : network.getInterfaceAddresses()) {
                    if (!localAddress.equals(interfaceAddress.getAddress().getHostAddress())) continue;
                    int prefix = interfaceAddress.getNetworkPrefixLength();
                    if (prefix < 1 || prefix > 32) return false;
                    byte[] local = InetAddress.getByName(localAddress).getAddress();
                    byte[] remote = InetAddress.getByName(remoteAddress).getAddress();
                    for (int bit = 0; bit < prefix; bit++) {
                        int mask = 1 << (7 - bit % 8);
                        int index = bit / 8;
                        if ((local[index] & mask) != (remote[index] & mask)) return false;
                    }
                    return true;
                }
            }
            return false;
        } catch (SocketException | java.net.UnknownHostException exception) {
            throw new IllegalStateException("No se pudo validar la red local del cliente móvil.", exception);
        }
    }

    private static int[] parseIpv4(String value) {
        if (value == null || !value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return null;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return null;
            }
        } catch (NumberFormatException exception) {
            return null;
        }
        return octets;
    }

    private static boolean isAssignedToTailscaleInterface(String address) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || !isTailscaleInterface(network)) continue;
                for (var interfaceAddress : network.getInterfaceAddresses()) {
                    if (address.equals(interfaceAddress.getAddress().getHostAddress())) return true;
                }
            }
            return false;
        } catch (SocketException exception) {
            throw new IllegalStateException("No se pudieron consultar las interfaces de red locales.", exception);
        }
    }

    private static boolean isTailscaleInterface(NetworkInterface network) {
        return containsTailscale(network.getName()) || containsTailscale(network.getDisplayName());
    }

    private static boolean containsTailscale(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("tailscale");
    }

    public record Address(String interfaceName, String ipAddress, short prefixLength, boolean tailscale) {
        @Override
        public String toString() {
            return interfaceName + " — " + ipAddress;
        }
    }
}
