package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.api.model.VmConfig;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.OsType;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.jenkinsci.plugins.proxmox.config.TemplateSelectionMode;
import org.jenkinsci.plugins.proxmox.config.WindowsLoginShell;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ProxmoxTemplateTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void testMatchesExactLabel() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        assertTrue(template.matches(Label.get("linux")));
    }

    @Test
    void testMatchesMultipleLabels() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux docker", 1);
        assertTrue(template.matches(Label.get("linux")));
        assertTrue(template.matches(Label.get("docker")));
    }

    @Test
    void testDoesNotMatchWrongLabel() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        assertFalse(template.matches(Label.get("windows")));
    }

    @Test
    void testMatchesNullLabelNormalMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.NORMAL);
        assertTrue(template.matches(null));
    }

    @Test
    void testDoesNotMatchNullLabelExclusiveMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.EXCLUSIVE);
        assertFalse(template.matches(null));
    }

    @Test
    void testDefaults() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        assertEquals(CloneStrategy.FULL, template.getCloneStrategy());
        assertEquals("/home/ubuntu/agent", template.getRemoteFs());
        assertEquals("java", template.getJavaPath());
        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
        assertEquals(21, template.getJavaMajorVersion());
        assertEquals("jenkins-agent-", template.getNamePrefix());
        assertEquals(30, template.getIdleTerminationMinutes());
        assertEquals(60, template.getStartupWaitSeconds());
        assertEquals(0, template.getInstanceCap());
        assertEquals(0, template.getInstanceMin());
        assertEquals(0, template.getMaxTotalUses());
    }

    @Test
    void instanceMinSetterAccepts() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setInstanceMin(2);
        assertEquals(2, template.getInstanceMin());
    }

    @Test
    void instanceMinSetterRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProxmoxTemplate("test", "pve1", 100, "linux", 1).setInstanceMin(-1));
    }

    @Test
    void doCheckInstanceMinValidatesRangeAndCap() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(0, 0).kind);   // none
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(3, 0).kind);   // cap 0 = unlimited
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(2, 5).kind);   // within cap
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(5, 5).kind);   // at cap
        assertEquals(FormValidation.Kind.ERROR, d.doCheckInstanceMin(-1, 0).kind); // negative
        assertEquals(FormValidation.Kind.ERROR, d.doCheckInstanceMin(6, 5).kind);  // exceeds cap
    }

    @Test
    void testNumExecutorsMinimum() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 0);
        assertEquals(1, template.getNumExecutors());
    }

    @Test
    void getRemoteFsKeepsExplicitValue() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setRemoteFs("/data/jenkins");
        assertEquals("/data/jenkins", template.getRemoteFs());
    }

    @Test
    void getRemoteFsFallsBackToCiUserHomeWhenBlank() {
        // A blank Remote FS Root is stored as null by setRemoteFs; getRemoteFs() must still return a
        // usable path. Issue #18: provision() previously passed the raw null straight to the agent,
        // which then NPE'd in SSHLauncher.getWorkingDirectory() at launch.
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setCiUser("builder");
        template.setRemoteFs("");
        assertEquals("/home/builder/agent", template.getRemoteFs());
    }

    @Test
    void getRemoteFsIsNeverBlank() {
        // Whitespace-only field and no ciUser: still a non-blank default, never null/blank.
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setRemoteFs("   ");
        String fs = template.getRemoteFs();
        assertNotNull(fs);
        assertFalse(fs.isBlank());
        assertEquals("/home/ubuntu/agent", fs);
    }

    @Test
    void ciUserSetterStoresNullForBlankInput() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setCiUser("ubuntu");
        assertEquals("ubuntu", template.getCiUser());
        template.setCiUser("");
        assertNull(template.getCiUser());
        template.setCiUser("  ");
        assertNull(template.getCiUser());
        template.setCiUser(null);
        assertNull(template.getCiUser());
    }

    @Test
    void javaDistributionSetterDefaultsNullToNone() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setJavaDistribution(JavaDistribution.OPENJDK);
        assertEquals(JavaDistribution.OPENJDK, template.getJavaDistribution());
        template.setJavaDistribution(null);
        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
    }

    @Test
    void windowsLoginShellDefaultsToAuto() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "win", 1);
        assertEquals(WindowsLoginShell.AUTO, template.getWindowsLoginShell());
    }

    @Test
    void windowsLoginShellSetterDefaultsNullToAuto() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "win", 1);
        template.setWindowsLoginShell(WindowsLoginShell.POWERSHELL);
        assertEquals(WindowsLoginShell.POWERSHELL, template.getWindowsLoginShell());
        template.setWindowsLoginShell(null);
        assertEquals(WindowsLoginShell.AUTO, template.getWindowsLoginShell());
    }

    @Test
    void windowsLoginShellGetterDefaultsNullFromOldConfig() throws Exception {
        // Configs persisted before this field existed deserialize it as null (XStream skips initializers).
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "win", 1);
        java.lang.reflect.Field f = ProxmoxTemplate.class.getDeclaredField("windowsLoginShell");
        f.setAccessible(true);
        f.set(template, null);
        assertEquals(WindowsLoginShell.AUTO, template.getWindowsLoginShell());
    }

    @Test
    void javaMajorVersionSetterAccepts() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setJavaMajorVersion(25);
        assertEquals(25, template.getJavaMajorVersion());
    }

    @Test
    void javaMajorVersionSetterRejectsNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProxmoxTemplate("test", "pve1", 100, "linux", 1).setJavaMajorVersion(-1));
    }

    @Test
    void doFillJavaMajorVersionItemsListsSuggestions() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        ComboBoxModel items = d.doFillJavaMajorVersionItems();
        assertTrue(items.contains("21"));
        assertTrue(items.contains("25"));
    }

    @Test
    void doCheckJavaMajorVersionIgnoredWhenDistributionNone() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        // The version is unused when no distribution is selected, so any value is accepted.
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("", "NONE").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("nonsense", "NONE").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("17", "").kind);
    }

    @Test
    void doCheckJavaMajorVersionValidatesWhenDistributionSelected() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("21", "OPENJDK").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("26", "CORRETTO").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("", "OPENJDK").kind);    // required
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("0", "OPENJDK").kind);   // not positive
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("abc", "OPENJDK").kind); // not a number
    }

    @Test
    void doCheckJavaMajorVersionWarnsButAllowsBelowRecommendedMinimum() {
        // Java 17 is below the recommended minimum (21) but allowed: the user may have a reason.
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.WARNING, d.doCheckJavaMajorVersion("17", "OPENJDK").kind);
        assertEquals(FormValidation.Kind.WARNING, d.doCheckJavaMajorVersion("11", "CORRETTO").kind);
    }

    @Test
    void doFillItemsRequireAdminPermission() throws Exception {
        // The Proxmox-connecting fill methods enumerate cluster inventory over the network, so they
        // are gated on ADMINISTER (issue #27): a user without it gets an empty model and no API call.
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("reader"));
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(0, d.doFillNodeItems("", "", false).size());
            assertEquals(0, d.doFillTemplateVmIdItems("node", "", "", false).size());
            assertEquals(0, d.doFillTargetStorageItems("node", "", "", false).size());
            assertEquals(0, d.doFillNetworkBridgeItems("node", "", "", false).size());
            assertEquals(0, d.doFillTargetPoolItems("", "", false).size());
        }

        // An admin with no API connection configured still gets the methods' placeholder entries
        // (tryCreateClient returns null), proving the guard is what emptied them for the reader above.
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            ListBoxModel nodes = d.doFillNodeItems("", "", false);
            assertFalse(nodes.isEmpty());
            assertFalse(d.doFillTargetPoolItems("", "", false).isEmpty());
        }
    }

    @Test
    void doCheckItemsSkipValidationWithoutAdminPermission() throws Exception {
        // doCheck methods return OK for a user lacking ADMINISTER rather than running validation (issue #27).
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("reader"));
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, d.doCheckNode("").kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("", "OPENJDK").kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckRemoteFs("", OsType.WINDOWS.name()).kind);
            // The dynamic-selection checks query the Proxmox API; a reader gets a silent OK even
            // for input that would otherwise error.
            assertEquals(FormValidation.Kind.OK, d.doCheckTemplateNameRegex("[unclosed", "pve1", "", "", false).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckTemplateTag("", "pve1", "", "", false).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckTemplateVmId(0, "STATIC_ID").kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(-1, 0).kind);
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, d.doCheckNode("").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("", "OPENJDK").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckRemoteFs("", OsType.WINDOWS.name()).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateNameRegex("[unclosed", "pve1", "", "", false).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateTag("", "pve1", "", "", false).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateVmId(0, "STATIC_ID").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckInstanceMin(-1, 0).kind);
        }
    }

    // --- descriptor fill/check methods backed by the Proxmox API (WireMock) ---
    // An unsecured JenkinsRule treats everyone as ADMINISTER, so the permission guard passes and these
    // exercise the happy/error paths the dedicated permission tests above do not.

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private String apiUrl() {
        return "http://localhost:" + wireMock.getPort();
    }

    private String registerCreds() {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "desc",
                        "user@pve!token", Secret.fromString("secret")));
        return "proxmox-cred";
    }

    private ProxmoxTemplate.DescriptorImpl descriptor() {
        return j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
    }

    @Test
    void doFillNodeItemsListsNodesAndMarksOffline() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes")).willReturn(okJson("{\"data\":["
                + "{\"node\":\"pve1\",\"status\":\"online\"},"
                + "{\"node\":\"pve2\",\"status\":\"offline\"}]}")));
        ListBoxModel m = descriptor().doFillNodeItems(apiUrl(), cred, false);
        assertEquals(3, m.size()); // blank + two nodes
        assertTrue(m.stream().anyMatch(o -> o.value.equals("pve1") && o.name.equals("pve1")));
        assertTrue(m.stream().anyMatch(o -> o.value.equals("pve2") && o.name.equals("pve2 (offline)")));
    }

    @Test
    void doFillNodeItemsReportsApiError() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes")).willReturn(aResponse().withStatus(500).withBody("boom")));
        ListBoxModel m = descriptor().doFillNodeItems(apiUrl(), cred, false);
        assertTrue(m.stream().anyMatch(o -> o.name.startsWith("(API error")));
    }

    @Test
    void doFillNodeItemsPlaceholderWhenNoConnection() {
        ListBoxModel m = descriptor().doFillNodeItems("", "", false);
        assertEquals(1, m.size());
        assertEquals("(configure API connection first)", m.get(0).name);
    }

    @Test
    void doFillTemplateVmIdItemsPromptsForNodeWhenBlank() {
        ListBoxModel m = descriptor().doFillTemplateVmIdItems("", apiUrl(), registerCreds(), false);
        assertEquals(1, m.size());
        assertEquals("(select a node first)", m.get(0).name);
    }

    @Test
    void doFillTemplateVmIdItemsListsTemplates() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu")).willReturn(okJson("{\"data\":["
                + "{\"vmid\":9000,\"name\":\"ubuntu\",\"status\":\"stopped\",\"template\":1}]}")));
        ListBoxModel m = descriptor().doFillTemplateVmIdItems("pve1", apiUrl(), cred, false);
        assertTrue(m.stream().anyMatch(o -> o.value.equals("9000") && o.name.contains("ubuntu")));
    }

    @Test
    void doFillTemplateVmIdItemsReportsNoTemplates() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu")).willReturn(okJson("{\"data\":[]}")));
        ListBoxModel m = descriptor().doFillTemplateVmIdItems("pve1", apiUrl(), cred, false);
        assertTrue(m.stream().anyMatch(o -> o.name.contains("no templates found")));
    }

    @Test
    void doFillTargetStorageItemsListsPools() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage")).willReturn(okJson("{\"data\":["
                + "{\"storage\":\"local-lvm\",\"type\":\"lvmthin\",\"avail\":123}]}")));
        ListBoxModel m = descriptor().doFillTargetStorageItems("pve1", apiUrl(), cred, false);
        assertEquals("(inherit from template)", m.get(0).name);
        assertTrue(m.stream().anyMatch(o -> o.value.equals("local-lvm") && o.name.contains("lvmthin")));
    }

    @Test
    void doFillNetworkBridgeItemsListsOnlyBridges() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/network")).willReturn(okJson("{\"data\":["
                + "{\"iface\":\"vmbr0\",\"type\":\"bridge\",\"active\":1},"
                + "{\"iface\":\"eno1\",\"type\":\"eth\",\"active\":1}]}")));
        ListBoxModel m = descriptor().doFillNetworkBridgeItems("pve1", apiUrl(), cred, false);
        assertTrue(m.stream().anyMatch(o -> o.value.equals("vmbr0")));
        assertFalse(m.stream().anyMatch(o -> o.value.equals("eno1")));
    }

    @Test
    void doFillTargetPoolItemsListsPoolsWithOptionalComment() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/pools")).willReturn(okJson("{\"data\":["
                + "{\"poolid\":\"prod\",\"comment\":\"Production\"},"
                + "{\"poolid\":\"bare\"}]}")));
        ListBoxModel m = descriptor().doFillTargetPoolItems(apiUrl(), cred, false);
        assertEquals("(none)", m.get(0).name);
        assertTrue(m.stream().anyMatch(o -> o.value.equals("prod") && o.name.contains("Production")));
        assertTrue(m.stream().anyMatch(o -> o.value.equals("bare") && o.name.equals("bare")));
    }

    @Test
    void doFillTargetStorageItemsToleratesApiError() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage")).willReturn(aResponse().withStatus(500)));
        ListBoxModel m = descriptor().doFillTargetStorageItems("pve1", apiUrl(), cred, false);
        // The API error is logged and swallowed; the inherit placeholder still renders.
        assertEquals(1, m.size());
        assertEquals("(inherit from template)", m.get(0).name);
    }

    @Test
    void doFillCredentialsIdItemsListsSshCredential() throws Exception {
        // The template's SSH dropdown lists StandardUsernameCredentials (the agent connection), not
        // the Proxmox API token credential the cloud uses.
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "ssh-cred", "desc", "ubuntu", "secret"));
        ListBoxModel m = descriptor().doFillCredentialsIdItems();
        assertTrue(m.stream().anyMatch(o -> o.value.equals("ssh-cred")));
        assertTrue(m.stream().anyMatch(o -> o.name.equals("- none -")));
    }

    @Test
    void doCheckTemplateVmIdRejectsNonPositiveInStaticMode() {
        ProxmoxTemplate.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateVmId(0, "STATIC_ID").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateVmId(-5, "STATIC_ID").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckTemplateVmId(9000, "STATIC_ID").kind);
        // No mode parameter (pre-radioBlock form state) is treated as static.
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateVmId(0, null).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateVmId(0, "").kind);
        // The hidden static select submits an empty value in dynamic modes; not an error then.
        assertEquals(FormValidation.Kind.OK, d.doCheckTemplateVmId(0, "NAME_REGEX").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckTemplateVmId(0, "TAG").kind);
    }

    // --- dynamic template selection: match-count doChecks (WireMock) ---

    private void stubTemplateListWithCtimes() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu")).willReturn(okJson("{\"data\":["
                + "{\"vmid\":9000,\"name\":\"agent-2026-06-01\",\"status\":\"stopped\",\"template\":1,\"tags\":\"jenkins\"},"
                + "{\"vmid\":9001,\"name\":\"agent-2026-07-01\",\"status\":\"stopped\",\"template\":1,\"tags\":\"jenkins;prod\"},"
                + "{\"vmid\":9002,\"name\":\"other\",\"status\":\"stopped\",\"template\":1}]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/config"))
                .willReturn(okJson("{\"data\":{\"meta\":\"creation-qemu=9.0.2,ctime=1000\"}}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/config"))
                .willReturn(okJson("{\"data\":{\"meta\":\"creation-qemu=9.0.2,ctime=2000\"}}")));
    }

    @Test
    void doCheckTemplateNameRegexReportsMatchCountAndWinner() {
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        FormValidation v = descriptor().doCheckTemplateNameRegex("agent-.*", "pve1", apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.OK, v.kind);
        assertTrue(v.getMessage().contains("Matches 2 templates"), v.getMessage());
        assertTrue(v.getMessage().contains("9001"), v.getMessage());
    }

    @Test
    void doCheckTemplateNameRegexWarnsOnZeroMatches() {
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        FormValidation v = descriptor().doCheckTemplateNameRegex("nothing-.*", "pve1", apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.WARNING, v.kind);
        assertTrue(v.getMessage().contains("Matches 0 templates"), v.getMessage());
    }

    @Test
    void doCheckTemplateNameRegexRejectsBlankAndInvalidPattern() {
        ProxmoxTemplate.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateNameRegex("", "pve1", apiUrl(), "", false).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckTemplateNameRegex(null, "pve1", apiUrl(), "", false).kind);
        assertEquals(FormValidation.Kind.ERROR,
                d.doCheckTemplateNameRegex("[unclosed", "pve1", apiUrl(), "", false).kind);
    }

    @Test
    void doCheckTemplateNameRegexSilentWithoutNodeOrConnection() {
        // No node chosen yet, or no API connection: nothing useful to count, stay quiet.
        ProxmoxTemplate.DescriptorImpl d = descriptor();
        assertEquals(FormValidation.Kind.OK, d.doCheckTemplateNameRegex("agent-.*", "", apiUrl(), "", false).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckTemplateNameRegex("agent-.*", "pve1", "", "", false).kind);
    }

    @Test
    void doCheckTemplateNameRegexWarnsOnApiFailure() {
        String cred = registerCreds();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        FormValidation v = descriptor().doCheckTemplateNameRegex("agent-.*", "pve1", apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.WARNING, v.kind);
        assertTrue(v.getMessage().contains("Could not query Proxmox"), v.getMessage());
    }

    @Test
    void doCheckTemplateTagReportsMatchCountAndWinner() {
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        FormValidation v = descriptor().doCheckTemplateTag("jenkins", "pve1", apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.OK, v.kind);
        assertTrue(v.getMessage().contains("Matches 2 templates"), v.getMessage());
        assertTrue(v.getMessage().contains("9001"), v.getMessage());

        FormValidation single = descriptor().doCheckTemplateTag("prod", "pve1", apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.OK, single.kind);
        assertTrue(single.getMessage().contains("Matches 1 template;"), single.getMessage());
    }

    @Test
    void doCheckTemplateTagWarnsOnZeroMatchesAndRejectsBlank() {
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        assertEquals(FormValidation.Kind.WARNING,
                descriptor().doCheckTemplateTag("missing", "pve1", apiUrl(), cred, false).kind);
        assertEquals(FormValidation.Kind.ERROR,
                descriptor().doCheckTemplateTag("", "pve1", apiUrl(), cred, false).kind);
    }

    // --- provision(): clone/configure/start via WireMock, plus the config helpers ---

    private void stubMinimalProvision(int vmId) {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(okJson("{\"data\":\"UPID:clone\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*/status"))
                .willReturn(okJson("{\"data\":{\"upid\":\"x\",\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/" + vmId + "/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:start\"}")));
    }

    private ProxmoxCloud cloudAtWireMock(String cred) {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl(apiUrl());
        cloud.setCredentialsId(cred);
        return cloud;
    }

    @Test
    void provisionClonesStartsAndReturnsAgent() throws Exception {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        stubMinimalProvision(300);

        ProxmoxAgent agent = template.provision(cloud, hudson.model.TaskListener.NULL, 300, null);

        assertEquals("jenkins-agent-300", agent.getNodeName());
        assertEquals(300, agent.getVmId());
        assertEquals("test-cloud", agent.getCloudName());
        assertEquals("pve1", agent.getProxmoxNode());
        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone")));
        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/start")));
    }

    @Test
    void provisionAppliesConfigBridgeAndDiskResize() throws Exception {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        template.setCores(4);
        template.setMemory(8192);
        template.setCiUser("ubuntu");
        template.setIpConfig("ip=10.0.0.5/24,gw=10.0.0.1");
        template.setNetworkBridge("vmbr1");
        template.setDiskSizeGb(20);
        stubMinimalProvision(301);
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/301/config"))
                .willReturn(okJson("{\"data\":{\"net0\":\"virtio=BC:24:11:AA:BB:CC,bridge=vmbr0\"}}")));
        stubFor(put(urlPathMatching("/api2/json/nodes/pve1/qemu/301/(config|resize)"))
                .willReturn(okJson("{\"data\":null}")));

        ProxmoxAgent agent = template.provision(cloud, hudson.model.TaskListener.NULL, 301, null);

        assertEquals(301, agent.getVmId());
        // The bridge override PUTs net0 to /config; the disk grows via /resize.
        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/301/config")).withRequestBody(containing("net0=")));
        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/301/resize")));
    }

    // --- dynamic template selection: provision-time resolution ---

    @Test
    void provisionResolvesNewestTemplateByNameRegex() throws Exception {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        template.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        template.setTemplateNameRegex("agent-.*");
        stubTemplateListWithCtimes();
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/clone"))
                .willReturn(okJson("{\"data\":\"UPID:clone\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*/status"))
                .willReturn(okJson("{\"data\":{\"upid\":\"x\",\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:start\"}")));

        ProxmoxAgent agent = template.provision(cloud, hudson.model.TaskListener.NULL, 300, null);

        assertEquals(300, agent.getVmId());
        // Both 9000 and 9001 match agent-.*; 9001 has the newer ctime and must be the clone source.
        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/clone")));
        verify(0, postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone")));
    }

    @Test
    void provisionResolvesTemplateByTag() throws Exception {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        template.setTemplateSelectionMode(TemplateSelectionMode.TAG);
        template.setTemplateTag("prod");
        stubTemplateListWithCtimes();
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/clone"))
                .willReturn(okJson("{\"data\":\"UPID:clone\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*/status"))
                .willReturn(okJson("{\"data\":{\"upid\":\"x\",\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:start\"}")));

        ProxmoxAgent agent = template.provision(cloud, hudson.model.TaskListener.NULL, 300, null);

        assertEquals(300, agent.getVmId());
        // Only 9001 carries the prod tag.
        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/9001/clone")));
    }

    @Test
    void provisionFailsWhenNoTemplateMatches() {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        template.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        template.setTemplateNameRegex("nothing-.*");
        stubTemplateListWithCtimes();

        org.jenkinsci.plugins.proxmox.api.ProxmoxException e =
                assertThrows(org.jenkinsci.plugins.proxmox.api.ProxmoxException.class,
                        () -> template.provision(cloud, hudson.model.TaskListener.NULL, 300, null));
        assertTrue(e.getMessage().contains("nothing-.*"), e.getMessage());
        assertTrue(e.getMessage().contains("pve1"), e.getMessage());
        verify(0, postRequestedFor(urlPathMatching("/api2/json/nodes/pve1/qemu/.*/clone")));
    }

    @Test
    void provisionStaticModeDoesNotListTemplates() throws Exception {
        ProxmoxCloud cloud = cloudAtWireMock(registerCreds());
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        stubMinimalProvision(300);

        template.provision(cloud, hudson.model.TaskListener.NULL, 300, null);

        // Static selection must stay zero-extra-API-calls: no template listing, no config reads.
        verify(0, getRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu")));
        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone")));
    }

    // --- dynamic template selection: data model ---

    @Test
    void templateSelectionModeDefaultsToStaticForOldConfig() {
        // Pre-dynamic-selection config.xml has no templateSelectionMode element; XStream leaves the
        // field null (field initializers do not run) and the getter must default it.
        String oldXml = "<org.jenkinsci.plugins.proxmox.ProxmoxTemplate>"
                + "<name>t</name><node>pve1</node><templateVmId>9000</templateVmId>"
                + "<labelString>linux</labelString><numExecutors>1</numExecutors>"
                + "</org.jenkinsci.plugins.proxmox.ProxmoxTemplate>";
        ProxmoxTemplate t = (ProxmoxTemplate) Jenkins.XSTREAM2.fromXML(oldXml);
        assertEquals(TemplateSelectionMode.STATIC_ID, t.getTemplateSelectionMode());
        assertNull(t.getTemplateNameRegex());
        assertNull(t.getTemplateTag());
        assertEquals(9000, t.getTemplateVmId());
    }

    @Test
    void templateSelectionSettersNormalizeAndValidate() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        assertEquals(TemplateSelectionMode.STATIC_ID, t.getTemplateSelectionMode());

        t.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        assertEquals(TemplateSelectionMode.NAME_REGEX, t.getTemplateSelectionMode());
        t.setTemplateSelectionMode(null);
        assertEquals(TemplateSelectionMode.STATIC_ID, t.getTemplateSelectionMode());

        t.setTemplateNameRegex("agent-.*");
        assertEquals("agent-.*", t.getTemplateNameRegex());
        t.setTemplateNameRegex("   ");
        assertNull(t.getTemplateNameRegex());
        assertThrows(IllegalArgumentException.class, () -> t.setTemplateNameRegex("[unclosed"));

        t.setTemplateTag(" jenkins ");
        assertEquals("jenkins", t.getTemplateTag());
        t.setTemplateTag("");
        assertNull(t.getTemplateTag());
    }

    @Test
    void templateSelectionValidatedOnFormSubmit() throws Exception {
        // Mirrors the Windows validation tests: newInstance() delegates to this static rule.
        ProxmoxTemplate.validateTemplateSelection(
                new ProxmoxTemplate("t", "pve1", 9000, "linux", 1)); // static + id: ok

        assertThrows(hudson.model.Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateTemplateSelection(
                        new ProxmoxTemplate("t", "pve1", 0, "linux", 1))); // static without id

        ProxmoxTemplate regexOk = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        regexOk.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        regexOk.setTemplateNameRegex("agent-.*");
        ProxmoxTemplate.validateTemplateSelection(regexOk); // must not throw

        ProxmoxTemplate regexMissing = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        regexMissing.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        assertThrows(hudson.model.Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateTemplateSelection(regexMissing));

        ProxmoxTemplate tagOk = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        tagOk.setTemplateSelectionMode(TemplateSelectionMode.TAG);
        tagOk.setTemplateTag("jenkins");
        ProxmoxTemplate.validateTemplateSelection(tagOk); // must not throw

        ProxmoxTemplate tagMissing = new ProxmoxTemplate("t", "pve1", 0, "linux", 1);
        tagMissing.setTemplateSelectionMode(TemplateSelectionMode.TAG);
        assertThrows(hudson.model.Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateTemplateSelection(tagMissing));
    }

    @Test
    void parseStaticIpHandlesDhcpCidrAndMissingIp() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        assertNull(t.parseStaticIp(null));
        assertNull(t.parseStaticIp("   "));
        assertNull(t.parseStaticIp("ip=dhcp"));
        assertNull(t.parseStaticIp("gw=10.0.0.1"));                          // no ip= component
        assertEquals("10.0.0.5", t.parseStaticIp("ip=10.0.0.5/24,gw=10.0.0.1"));
        assertEquals("10.0.0.5", t.parseStaticIp("ip=10.0.0.5/24"));
        assertEquals("10.0.0.5", t.parseStaticIp("ip=10.0.0.5"));            // no CIDR slash
    }

    @Test
    void buildVmConfigReturnsNullWhenNothingSet() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        assertNull(t.buildVmConfig(null));
    }

    @Test
    void buildVmConfigMapsConfiguredFields() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        t.setCores(4);
        t.setMemory(2048);
        t.setCiUser("ubuntu");
        t.setIpConfig("ip=10.0.0.5/24");
        VmConfig cfg = t.buildVmConfig("ssh-ed25519 AAA");
        assertNotNull(cfg);
        assertEquals(4, cfg.cores().intValue());
        assertEquals(2048, cfg.memory().intValue());
        assertEquals("ubuntu", cfg.ciuser());
        assertEquals("ssh-ed25519 AAA", cfg.sshkeys());
        assertEquals("ip=10.0.0.5/24", cfg.ipconfig0());
    }

    // --- OS Type ---

    @Test
    void osTypeDefaultsToLinux() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        assertEquals(OsType.LINUX, t.getOsType());
    }

    @Test
    void osTypeSetterRoundTrips() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        t.setOsType(OsType.WINDOWS);
        assertEquals(OsType.WINDOWS, t.getOsType());
        t.setOsType(null);
        assertEquals(OsType.LINUX, t.getOsType(), "null should coerce to LINUX");
    }

    @Test
    void buildVmConfigOmitsLinuxFieldsForWindows() {
        ProxmoxTemplate t = new ProxmoxTemplate("t", "pve1", 9000, "windows", 1);
        t.setOsType(OsType.WINDOWS);
        t.setCiUser("admin");
        t.setIpConfig("ip=dhcp");
        t.setNameserver("8.8.8.8");
        t.setSearchDomain("example.com");
        VmConfig cfg = t.buildVmConfig("ssh-ed25519 AAA");
        assertNull(cfg, "Windows with no cores/memory and only Linux-only fields should return null");

        t.setCores(4);
        cfg = t.buildVmConfig("ssh-ed25519 AAA");
        assertNotNull(cfg, "Windows with cores set should return a config");
        assertNull(cfg.ciuser(), "ciUser must not be passed to Proxmox for Windows");
        assertNull(cfg.sshkeys(), "sshkeys must not be passed to Proxmox for Windows");
        assertNull(cfg.ipconfig0(), "ipConfig must not be passed to Proxmox for Windows");
        assertNull(cfg.nameserver(), "nameserver must not be passed to Proxmox for Windows");
        assertNull(cfg.searchdomain(), "searchDomain must not be passed to Proxmox for Windows");
        assertEquals(4, cfg.cores().intValue(), "cores should still be set for Windows");
    }

    @Test
    void windowsTemplateRequiresRemoteFsOnFormSubmit() throws Exception {
        ProxmoxTemplate withFs = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        withFs.setOsType(OsType.WINDOWS);
        withFs.setRemoteFs("C:\\Users\\jenkins\\agent");
        ProxmoxTemplate.validateWindowsRemoteFs(withFs); // must not throw

        ProxmoxTemplate withoutFs = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        withoutFs.setOsType(OsType.WINDOWS);
        assertThrows(hudson.model.Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateWindowsRemoteFs(withoutFs));
    }

    @Test
    void windowsTemplateRequiresJavaDistributionNoneOnFormSubmit() throws Exception {
        ProxmoxTemplate windowsNone = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        windowsNone.setOsType(OsType.WINDOWS);
        windowsNone.setRemoteFs("C:\\Users\\jenkins\\agent");
        ProxmoxTemplate.validateWindowsJavaDistribution(windowsNone); // NONE default; must not throw

        ProxmoxTemplate linuxInstall = new ProxmoxTemplate("l", "pve1", 9000, "linux", 1);
        linuxInstall.setJavaDistribution(JavaDistribution.OPENJDK);
        ProxmoxTemplate.validateWindowsJavaDistribution(linuxInstall); // Linux may install; must not throw

        ProxmoxTemplate windowsInstall = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        windowsInstall.setOsType(OsType.WINDOWS);
        windowsInstall.setRemoteFs("C:\\Users\\jenkins\\agent");
        windowsInstall.setJavaDistribution(JavaDistribution.OPENJDK);
        assertThrows(hudson.model.Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateWindowsJavaDistribution(windowsInstall));
    }

    @Test
    void doCheckRemoteFsOkForLinux() {
        ProxmoxTemplate.DescriptorImpl d = new ProxmoxTemplate.DescriptorImpl();
        assertEquals(hudson.util.FormValidation.Kind.OK,
                d.doCheckRemoteFs("", OsType.LINUX.name()).kind);
        assertEquals(hudson.util.FormValidation.Kind.OK,
                d.doCheckRemoteFs(null, OsType.LINUX.name()).kind);
        assertEquals(hudson.util.FormValidation.Kind.OK,
                d.doCheckRemoteFs("/home/ubuntu/agent", OsType.LINUX.name()).kind);
    }

    @Test
    void doCheckRemoteFsErrorForWindowsWhenBlank() {
        ProxmoxTemplate.DescriptorImpl d = new ProxmoxTemplate.DescriptorImpl();
        assertEquals(hudson.util.FormValidation.Kind.ERROR,
                d.doCheckRemoteFs("", OsType.WINDOWS.name()).kind);
        assertEquals(hudson.util.FormValidation.Kind.ERROR,
                d.doCheckRemoteFs(null, OsType.WINDOWS.name()).kind);
        assertEquals(hudson.util.FormValidation.Kind.ERROR,
                d.doCheckRemoteFs("   ", OsType.WINDOWS.name()).kind);
    }

    @Test
    void doCheckRemoteFsOkForWindowsWhenSet() {
        ProxmoxTemplate.DescriptorImpl d = new ProxmoxTemplate.DescriptorImpl();
        assertEquals(hudson.util.FormValidation.Kind.OK,
                d.doCheckRemoteFs("C:\\Users\\jenkins\\agent", OsType.WINDOWS.name()).kind);
    }

    @Test
    void nameRegexTemplateSurvivesConfigFormRoundTrip() throws Exception {
        // End-to-end through the real form: the radioBlock's inline binding must submit the mode,
        // and the hidden static-id select's empty value must bind to 0 rather than fail the save.
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        stubFor(get(urlEqualTo("/api2/json/nodes"))
                .willReturn(okJson("{\"data\":[{\"node\":\"pve1\",\"status\":\"online\"}]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage")).willReturn(okJson("{\"data\":[]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/network")).willReturn(okJson("{\"data\":[]}")));
        stubFor(get(urlEqualTo("/api2/json/pools")).willReturn(okJson("{\"data\":[]}")));

        ProxmoxCloud cloud = cloudAtWireMock(cred);
        ProxmoxTemplate template = new ProxmoxTemplate("dyn", "pve1", 0, "linux", 1);
        template.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        template.setTemplateNameRegex("agent-.*");
        cloud.setTemplates(java.util.List.of(template));
        j.jenkins.clouds.add(cloud);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        org.htmlunit.html.HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
        wc.waitForBackgroundJavaScript(2000);
        j.submit(page.getFormByName("config"));

        ProxmoxCloud saved = (ProxmoxCloud) j.jenkins.getCloud("test-cloud");
        ProxmoxTemplate savedTemplate = saved.getTemplates().get(0);
        assertEquals(TemplateSelectionMode.NAME_REGEX, savedTemplate.getTemplateSelectionMode());
        assertEquals("agent-.*", savedTemplate.getTemplateNameRegex());
        assertEquals(0, savedTemplate.getTemplateVmId());
    }

    @Test
    void savingNameRegexModeWithEmptyRegexIsRejectedServerSide() throws Exception {
        // The red doCheck error does not block submission, and nested template binding on the
        // cloud configure page bypasses ProxmoxTemplate.DescriptorImpl.newInstance, so the rule
        // must be enforced in ProxmoxCloud's newInstance template loop. Submit the real form with
        // the regex cleared and expect the save to fail.
        String cred = registerCreds();
        stubTemplateListWithCtimes();
        stubFor(get(urlEqualTo("/api2/json/nodes"))
                .willReturn(okJson("{\"data\":[{\"node\":\"pve1\",\"status\":\"online\"}]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage")).willReturn(okJson("{\"data\":[]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/network")).willReturn(okJson("{\"data\":[]}")));
        stubFor(get(urlEqualTo("/api2/json/pools")).willReturn(okJson("{\"data\":[]}")));

        ProxmoxCloud cloud = cloudAtWireMock(cred);
        ProxmoxTemplate template = new ProxmoxTemplate("dyn", "pve1", 0, "linux", 1);
        template.setTemplateSelectionMode(TemplateSelectionMode.NAME_REGEX);
        template.setTemplateNameRegex("agent-.*");
        cloud.setTemplates(java.util.List.of(template));
        j.jenkins.clouds.add(cloud);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnScriptError(false);
        org.htmlunit.html.HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
        wc.waitForBackgroundJavaScript(2000);
        org.htmlunit.html.HtmlForm form = page.getFormByName("config");
        form.getInputByName("_.templateNameRegex").setValue("");

        org.htmlunit.FailingHttpStatusCodeException e = assertThrows(
                org.htmlunit.FailingHttpStatusCodeException.class, () -> j.submit(form));
        assertTrue(e.getStatusCode() >= 400, "expected an error status, got " + e.getStatusCode());
        assertEquals("agent-.*",
                ((ProxmoxCloud) j.jenkins.getCloud("test-cloud")).getTemplates().get(0).getTemplateNameRegex(),
                "failed save must not change the stored config");
    }
}
