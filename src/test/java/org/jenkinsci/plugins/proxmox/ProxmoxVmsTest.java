package org.jenkinsci.plugins.proxmox;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.gson.JsonObject;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.ProxmoxResourceNotFoundException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the VM-ownership and already-gone guards that keep a stale or duplicate terminate from
 * destroying a VM whose id has been reused, and that make a double-terminate a no-op (issues #16, #17).
 */
public class ProxmoxVmsTest {

    private WireMockServer wireMock;
    private ProxmoxClient client;

    @Before
    public void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        client = new ProxmoxClient("http://localhost:" + wireMock.port(),
                "user@pve!token", Secret.fromString("secret"), false);
    }

    @After
    public void tearDown() {
        wireMock.stop();
    }

    private static JsonObject configWithDescription(String description) {
        JsonObject config = new JsonObject();
        if (description != null) {
            config.addProperty("description", description);
        }
        return config;
    }

    // --- isManagedByCloud ---

    @Test
    public void isManagedByCloud_trueWhenMarkerPresent() {
        assertTrue(ProxmoxVms.isManagedByCloud(
                configWithDescription("jenkins-managed;cloud:pve01;template:linux"), "pve01"));
    }

    @Test
    public void isManagedByCloud_falseForDifferentCloud() {
        assertFalse(ProxmoxVms.isManagedByCloud(
                configWithDescription("jenkins-managed;cloud:other;template:linux"), "pve01"));
    }

    @Test
    public void isManagedByCloud_falseForForeignOrMissingDescription() {
        assertFalse(ProxmoxVms.isManagedByCloud(configWithDescription("some manual VM"), "pve01"));
        assertFalse(ProxmoxVms.isManagedByCloud(configWithDescription(null), "pve01"));
        assertFalse(ProxmoxVms.isManagedByCloud(new JsonObject(), "pve01"));
    }

    // --- isAlreadyGone ---

    @Test
    public void isAlreadyGone_trueFor404() {
        assertTrue(ProxmoxVms.isAlreadyGone(new ProxmoxResourceNotFoundException("Resource not found")));
    }

    @Test
    public void isAlreadyGone_trueForMissingConfigFile500() {
        // Proxmox returns a 500 (plain ProxmoxException) when destroying a VM whose config is gone.
        assertTrue(ProxmoxVms.isAlreadyGone(new ProxmoxException(
                "Proxmox API error (500): Configuration file 'nodes/clop/qemu-server/305.conf' does not exist")));
    }

    @Test
    public void isAlreadyGone_falseForOtherErrors() {
        assertFalse(ProxmoxVms.isAlreadyGone(new ProxmoxException("Proxmox API error (500): out of disk space")));
    }

    // --- confirmOwnedByCloud (live config read) ---

    @Test
    public void confirmOwnedByCloud_trueWhenMarkerMatches() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud\"}}")));
        assertTrue(ProxmoxVms.confirmOwnedByCloud(client, "pve1", 300, "test-cloud"));
    }

    @Test
    public void confirmOwnedByCloud_falseWhenIdReusedByForeignVm() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"a manual VM\"}}")));
        assertFalse(ProxmoxVms.confirmOwnedByCloud(client, "pve1", 300, "test-cloud"));
    }

    @Test
    public void confirmOwnedByCloud_falseWhenVmGone() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(aResponse().withStatus(404).withBody("{\"data\":null}")));
        assertFalse(ProxmoxVms.confirmOwnedByCloud(client, "pve1", 300, "test-cloud"));
    }
}
