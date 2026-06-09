package org.jenkinsci.plugins.proxmox;

import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

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
    void javaDistributionSetterDefaultsNullToNone() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setJavaDistribution(JavaDistribution.OPENJDK);
        assertEquals(JavaDistribution.OPENJDK, template.getJavaDistribution());
        template.setJavaDistribution(null);
        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
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
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, d.doCheckNode("").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("", "OPENJDK").kind);
        }
    }
}
