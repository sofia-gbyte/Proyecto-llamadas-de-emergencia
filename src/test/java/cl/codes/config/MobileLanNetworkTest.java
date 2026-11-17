package cl.codes.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobileLanNetworkTest {
    @Test
    void acceptsOnlyPrivateIpv4Addresses() {
        assertTrue(MobileLanNetwork.isPrivateIpv4("10.2.3.4"));
        assertTrue(MobileLanNetwork.isPrivateIpv4("172.16.0.1"));
        assertTrue(MobileLanNetwork.isPrivateIpv4("172.31.255.254"));
        assertTrue(MobileLanNetwork.isPrivateIpv4("192.168.1.25"));

        assertFalse(MobileLanNetwork.isPrivateIpv4("127.0.0.1"));
        assertFalse(MobileLanNetwork.isPrivateIpv4("169.254.1.4"));
        assertFalse(MobileLanNetwork.isPrivateIpv4("172.32.0.1"));
        assertFalse(MobileLanNetwork.isPrivateIpv4("8.8.8.8"));
        assertFalse(MobileLanNetwork.isPrivateIpv4("192.168.1.999"));
        assertFalse(MobileLanNetwork.isPrivateIpv4("codes.local"));
    }

    @Test
    void refusesToTreatNonPrivateOrInvalidPeersAsSameSubnet() {
        assertFalse(MobileLanNetwork.isOnSameSubnet("192.168.1.25", "8.8.8.8"));
        assertFalse(MobileLanNetwork.isOnSameSubnet("127.0.0.1", "127.0.0.1"));
        assertFalse(MobileLanNetwork.isOnSameSubnet("not-an-ip", "192.168.1.2"));
    }
}
