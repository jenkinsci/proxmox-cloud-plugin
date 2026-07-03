package org.jenkinsci.plugins.proxmox;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;
import org.jenkinsci.plugins.proxmox.config.TemplateSelectionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.regex.PatternSyntaxException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateResolverTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private ProxmoxClient client;

    @BeforeEach
    void setUp() {
        client = new ProxmoxClient(
                "http://localhost:" + wireMock.getPort(),
                "user@pve!token",
                Secret.fromString("test-secret-uuid"),
                false);
    }

    private static void stubTemplateList(String... vmJsonObjects) {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" + String.join(",", vmJsonObjects) + "]}")));
    }

    private static String templateJson(int vmid, String name, String tags) {
        return "{\"vmid\":" + vmid + ",\"name\":\"" + name + "\",\"status\":\"stopped\",\"template\":1"
                + (tags != null ? ",\"tags\":\"" + tags + "\"" : "") + "}";
    }

    private static void stubCtime(int vmid, long ctime) {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/" + vmid + "/config"))
                .willReturn(okJson("{\"data\":{\"meta\":\"creation-qemu=9.0.2,ctime=" + ctime + "\"}}")));
    }

    private static void stubNoCtime(int vmid) {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/" + vmid + "/config"))
                .willReturn(okJson("{\"data\":{\"cores\":2}}")));
    }

    @Test
    void newestCtimeWinsAmongMultipleMatches() {
        stubTemplateList(
                templateJson(9000, "agent-2026-06-01", null),
                templateJson(9001, "agent-2026-07-01", null),
                templateJson(9002, "other", null));
        stubCtime(9000, 1000);
        stubCtime(9001, 2000);

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null);
        assertEquals(9001, winner.vmid());
    }

    @Test
    void ctimeBeatsHigherVmidWithoutCtime() {
        stubTemplateList(
                templateJson(9000, "agent-a", null),
                templateJson(9005, "agent-b", null));
        stubCtime(9000, 1000);
        stubNoCtime(9005); // missing ctime sorts oldest despite the higher vmid

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null);
        assertEquals(9000, winner.vmid());
    }

    @Test
    void highestVmidWinsWhenNoCtimeAnywhere() {
        stubTemplateList(
                templateJson(9000, "agent-a", null),
                templateJson(9005, "agent-b", null));
        stubNoCtime(9000);
        stubNoCtime(9005);

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null);
        assertEquals(9005, winner.vmid());
    }

    @Test
    void highestVmidBreaksCtimeTie() {
        stubTemplateList(
                templateJson(9000, "agent-a", null),
                templateJson(9001, "agent-b", null));
        stubCtime(9000, 1000);
        stubCtime(9001, 1000);

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null);
        assertEquals(9001, winner.vmid());
    }

    @Test
    void ctimeFetchFailureDegradesToOldestInsteadOfFailing() {
        stubTemplateList(
                templateJson(9000, "agent-a", null),
                templateJson(9001, "agent-b", null));
        stubCtime(9000, 1000);
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/config"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null);
        assertEquals(9000, winner.vmid());
    }

    @Test
    void zeroNameMatchesThrowsWithSelfContainedMessage() {
        stubTemplateList(templateJson(9000, "other", null));

        ProxmoxException e = assertThrows(ProxmoxException.class, () -> TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.NAME_REGEX, "agent-.*", null));
        assertTrue(e.getMessage().contains("pve1"), e.getMessage());
        assertTrue(e.getMessage().contains("agent-.*"), e.getMessage());
        assertTrue(e.getMessage().contains("searched 1 templates"), e.getMessage());
    }

    @Test
    void zeroTagMatchesThrowsWithSelfContainedMessage() {
        stubTemplateList(templateJson(9000, "agent-a", "prod"));

        ProxmoxException e = assertThrows(ProxmoxException.class, () -> TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.TAG, null, "jenkins"));
        assertTrue(e.getMessage().contains("tag 'jenkins'"), e.getMessage());
        assertTrue(e.getMessage().contains("pve1"), e.getMessage());
    }

    @Test
    void tagSelectionResolvesEndToEnd() {
        stubTemplateList(
                templateJson(9000, "agent-a", "jenkins;prod"),
                templateJson(9001, "agent-b", "jenkins"),
                templateJson(9002, "agent-c", "prod"));
        stubCtime(9000, 1000);
        stubCtime(9001, 2000);

        VirtualMachine winner = TemplateResolver.resolve(
                client, "pve1", TemplateSelectionMode.TAG, null, "jenkins");
        assertEquals(9001, winner.vmid());
    }

    @Test
    void regexMustMatchTheEntireName() {
        assertFalse(TemplateResolver.nameRegexMatcher("ubuntu").test(vm("ubuntu-2404")));
        assertTrue(TemplateResolver.nameRegexMatcher("ubuntu-.*").test(vm("ubuntu-2404")));
        assertTrue(TemplateResolver.nameRegexMatcher(".*2404.*").test(vm("ubuntu-2404")));
    }

    @Test
    void nullNameIsToleratedByRegexMatcher() {
        assertFalse(TemplateResolver.nameRegexMatcher(".*").test(vm(null)));
    }

    @Test
    void invalidRegexPropagatesPatternSyntaxException() {
        assertThrows(PatternSyntaxException.class, () -> TemplateResolver.nameRegexMatcher("[unclosed"));
    }

    private static VirtualMachine vm(String name) {
        return new VirtualMachine(100, name, "stopped", "pve1", 0, 1, null);
    }
}
