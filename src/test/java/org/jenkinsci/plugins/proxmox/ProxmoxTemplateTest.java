package org.jenkinsci.plugins.proxmox;

import hudson.model.Label;
import hudson.model.Node;
import hudson.util.FormValidation;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
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
}
