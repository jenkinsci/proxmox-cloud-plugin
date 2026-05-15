package org.jenkinsci.plugins.proxmox;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.*;

public class ProxmoxCloudTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testCanProvisionMatchingLabel() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);

        assertTrue(cloud.canProvision(state));
    }

    @Test
    public void testCanProvisionNonMatchingLabel() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(Label.get("windows"), 0);

        assertFalse(cloud.canProvision(state));
    }

    @Test
    public void testCanProvisionNullLabelNormalMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.NORMAL);

        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setTemplates(List.of(template));

        Cloud.CloudState state = new Cloud.CloudState(null, 0);
        assertTrue(cloud.canProvision(state));
    }

    @Test
    public void testCanProvisionNullLabelExclusiveMode() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(null, 0);

        assertFalse(cloud.canProvision(state));
    }

    @Test
    public void testInstanceCapPreventsProvisioning() {
        ProxmoxCloud cloud = createTestCloud();
        cloud.setInstanceCap(0);

        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);
        assertTrue(cloud.canProvision(state));

        cloud.setInstanceCap(1);
        assertTrue(cloud.canProvision(state));
    }

    @Test
    public void testEmptyTemplatesCantProvision() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setTemplates(List.of());

        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);
        assertFalse(cloud.canProvision(state));
    }

    private ProxmoxCloud createTestCloud() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setCloneStrategy(CloneStrategy.FULL);
        template.setMode(Node.Mode.EXCLUSIVE);

        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("https://proxmox:8006");
        cloud.setTemplates(List.of(template));
        return cloud;
    }
}
