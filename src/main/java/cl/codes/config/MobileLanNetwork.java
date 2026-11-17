package cl.codes.config;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

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
                for (var interfaceAddress : network.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address instanceof Inet4Address && isPrivateIpv4(address.getHostAddress())) {
                        addresses.add(new Address(network.getDisplayName(), address.getHostAddress(),
                                interfaceAddress.getNetworkPrefixLength()));
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
        if (value == null || !value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
    }

    public static boolean isOnSameSubnet(String localAddress, String remoteAddress) {
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

    public record Address(String interfaceName, String ipAddress, short prefixLength) {
        @Override
        public String toString() {
            return interfaceName + " — " + ipAddress;
        }
    }
}
