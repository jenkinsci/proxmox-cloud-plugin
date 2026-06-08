package org.jenkinsci.plugins.proxmox;

import hudson.model.Label;
import hudson.model.Node;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class ProxmoxTemplateTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testMatchesExactLabel() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        assertTrue(template.matches(Label.get("linux")));
    }

    @Test
    public void testMatchesMultipleLabels() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux docker", 1);
        assertTrue(template.matches(Label.get("linux")));
        assertTrue(template.matches(Label.get("docker")));
    }

    @Test
    public void testDoesNotMatchWrongLabel() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        assertFalse(template.matches(Label.get("windows")));
    }

    @Test
    public void testMatchesNullLabelNormalMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.NORMAL);
        assertTrue(template.matches(null));
    }

    @Test
    public void testDoesNotMatchNullLabelExclusiveMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.EXCLUSIVE);
        assertFalse(template.matches(null));
    }

    @Test
    public void testDefaults() {
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
    public void instanceMinSetterAccepts() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setInstanceMin(2);
        assertEquals(2, template.getInstanceMin());
    }

    @Test(expected = IllegalArgumentException.class)
    public void instanceMinSetterRejectsNegative() {
        new ProxmoxTemplate("test", "pve1", 100, "linux", 1).setInstanceMin(-1);
    }

    @Test
    public void doCheckInstanceMinValidatesRangeAndCap() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(0, 0).kind);   // none
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(3, 0).kind);   // cap 0 = unlimited
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(2, 5).kind);   // within cap
        assertEquals(FormValidation.Kind.OK, d.doCheckInstanceMin(5, 5).kind);   // at cap
        assertEquals(FormValidation.Kind.ERROR, d.doCheckInstanceMin(-1, 0).kind); // negative
        assertEquals(FormValidation.Kind.ERROR, d.doCheckInstanceMin(6, 5).kind);  // exceeds cap
    }

    @Test
    public void testNumExecutorsMinimum() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 0);
        assertEquals(1, template.getNumExecutors());
    }

    @Test
    public void getRemoteFsKeepsExplicitValue() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setRemoteFs("/data/jenkins");
        assertEquals("/data/jenkins", template.getRemoteFs());
    }

    @Test
    public void getRemoteFsFallsBackToCiUserHomeWhenBlank() {
        // A blank Remote FS Root is stored as null by setRemoteFs; getRemoteFs() must still return a
        // usable path. Issue #18: provision() previously passed the raw null straight to the agent,
        // which then NPE'd in SSHLauncher.getWorkingDirectory() at launch.
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setCiUser("builder");
        template.setRemoteFs("");
        assertEquals("/home/builder/agent", template.getRemoteFs());
    }

    @Test
    public void getRemoteFsIsNeverBlank() {
        // Whitespace-only field and no ciUser: still a non-blank default, never null/blank.
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setRemoteFs("   ");
        String fs = template.getRemoteFs();
        assertNotNull(fs);
        assertFalse(fs.isBlank());
        assertEquals("/home/ubuntu/agent", fs);
    }

    @Test
    public void javaDistributionSetterDefaultsNullToNone() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setJavaDistribution(JavaDistribution.OPENJDK);
        assertEquals(JavaDistribution.OPENJDK, template.getJavaDistribution());
        template.setJavaDistribution(null);
        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
    }

    @Test
    public void javaMajorVersionSetterAccepts() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setJavaMajorVersion(25);
        assertEquals(25, template.getJavaMajorVersion());
    }

    @Test(expected = IllegalArgumentException.class)
    public void javaMajorVersionSetterRejectsNegative() {
        new ProxmoxTemplate("test", "pve1", 100, "linux", 1).setJavaMajorVersion(-1);
    }

    @Test
    public void doFillJavaMajorVersionItemsListsSuggestions() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        ComboBoxModel items = d.doFillJavaMajorVersionItems();
        assertTrue(items.contains("21"));
        assertTrue(items.contains("25"));
    }

    @Test
    public void doCheckJavaMajorVersionIgnoredWhenDistributionNone() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        // The version is unused when no distribution is selected, so any value is accepted.
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("", "NONE").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("nonsense", "NONE").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("17", "").kind);
    }

    @Test
    public void doCheckJavaMajorVersionValidatesWhenDistributionSelected() {
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("21", "OPENJDK").kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckJavaMajorVersion("26", "CORRETTO").kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("", "OPENJDK").kind);    // required
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("0", "OPENJDK").kind);   // not positive
        assertEquals(FormValidation.Kind.ERROR, d.doCheckJavaMajorVersion("abc", "OPENJDK").kind); // not a number
    }

    @Test
    public void doCheckJavaMajorVersionWarnsButAllowsBelowRecommendedMinimum() {
        // Java 17 is below the recommended minimum (21) but allowed: the user may have a reason.
        ProxmoxTemplate.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxTemplate.DescriptorImpl.class);
        assertEquals(FormValidation.Kind.WARNING, d.doCheckJavaMajorVersion("17", "OPENJDK").kind);
        assertEquals(FormValidation.Kind.WARNING, d.doCheckJavaMajorVersion("11", "CORRETTO").kind);
    }
}
