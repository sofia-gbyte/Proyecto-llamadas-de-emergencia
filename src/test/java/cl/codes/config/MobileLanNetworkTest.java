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
    void recognizesOnlyTheTailscaleIpv4RangeAsOverlayAddresses() {
        assertTrue(MobileLanNetwork.isTailscaleIpv4("100.64.0.0"));
        assertTrue(MobileLanNetwork.isTailscaleIpv4("100.88.58.43"));
        assertTrue(MobileLanNetwork.isTailscaleIpv4("100.127.255.255"));

        assertFalse(MobileLanNetwork.isTailscaleIpv4("100.63.255.255"));
        assertFalse(MobileLanNetwork.isTailscaleIpv4("100.128.0.0"));
        assertFalse(MobileLanNetwork.isTailscaleIpv4("8.8.8.8"));
        assertFalse(MobileLanNetwork.isTailscaleIpv4("100.88.58.999"));
        assertFalse(MobileLanNetwork.isTailscaleIpv4("100.88.58.43.example"));
    }

    @Test
    void acceptsOnlyPrivateLanOrTailscaleAddressesForMobile() {
        assertTrue(MobileLanNetwork.isMobileIpv4("192.168.1.20"));
        assertTrue(MobileLanNetwork.isMobileIpv4("100.88.58.43"));
        assertFalse(MobileLanNetwork.isMobileIpv4("8.8.8.8"));
        assertFalse(MobileLanNetwork.isMobileIpv4("127.0.0.1"));
    }

    @Test
    void allowsOnlyConfiguredTailscalePeerAddresses() {
        assertTrue(MobileLanNetwork.isAllowedTailscalePeer("100.88.58.43", "100.88.58.43"));
        assertTrue(MobileLanNetwork.isAllowedTailscalePeer(
                "100.99.156.37", "100.88.58.43, 100.99.156.37"));
        assertFalse(MobileLanNetwork.isAllowedTailscalePeer(
                "100.88.58.44", "100.88.58.43, 100.99.156.37"));
        assertFalse(MobileLanNetwork.isAllowedTailscalePeer(
                "100.88.58.43", "100.88.58.43, 8.8.8.8"));
        assertFalse(MobileLanNetwork.isAllowedTailscalePeer("8.8.8.8", "100.88.58.43"));
        assertFalse(MobileLanNetwork.isAllowedTailscalePeer("100.88.58.43", ""));
    }

    @Test
    void validatesEveryTailscalePeerAddressInTheList() {
        assertTrue(MobileLanNetwork.isValidTailscalePeerAddresses("100.88.58.43"));
        assertTrue(MobileLanNetwork.isValidTailscalePeerAddresses("100.88.58.43, 100.99.156.37"));
        assertFalse(MobileLanNetwork.isValidTailscalePeerAddresses(""));
        assertFalse(MobileLanNetwork.isValidTailscalePeerAddresses("100.88.58.43,"));
        assertFalse(MobileLanNetwork.isValidTailscalePeerAddresses("100.88.58.43, 8.8.8.8"));
    }

    @Test
    void refusesToTreatNonPrivateOrInvalidPeersAsSameSubnet() {
        assertFalse(MobileLanNetwork.isOnAllowedMobileNetwork("192.168.1.25", "8.8.8.8"));
        assertFalse(MobileLanNetwork.isOnAllowedMobileNetwork("127.0.0.1", "127.0.0.1"));
        assertFalse(MobileLanNetwork.isOnAllowedMobileNetwork("not-an-ip", "192.168.1.2"));
    }
}
