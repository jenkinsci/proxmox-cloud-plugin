package org.jenkinsci.plugins.proxmox.config;

import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.ProxmoxCloud;
import org.jenkinsci.plugins.proxmox.ProxmoxTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ProxmoxConfigLoaderTest {

    private JenkinsRule j;

    private ProxmoxConfigLoader loader;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        loader = new ProxmoxConfigLoader();
    }

    private Map<String, Object> loadYaml(String classpathPath) {
        Yaml yaml = new Yaml();
        InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath);
        assertNotNull(is, "Test resource not found: " + classpathPath);
        return yaml.load(is);
    }

    // ---- createConfigFromYaml tests ----

    @Test
    void createConfigFromYaml_mergesCloudDefaults() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-basic.yaml");
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("testCluster"));
        Map<String, Object> cloud = result.get("testCluster");
        assertEquals("Test Proxmox Cloud", cloud.get("name"));
        assertEquals("https://proxmox.example.com:8006", cloud.get("apiUrl"));
        assertEquals("proxmox-api-token", cloud.get("credentialsId"));
        assertEquals(true, cloud.get("ignoreSslErrors"));
        assertEquals(10, cloud.get("instanceCap"));
        assertEquals(300, cloud.get("operationTimeoutSec"));
    }

    @Test
    void createConfigFromYaml_agentThreeLevelInheritance() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-basic.yaml");
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentList =
                (List<Map<String, Object>>) result.get("testCluster").get("agentList");
        assertEquals(2, agentList.size());

        Map<String, Object> linuxBuilder = agentList.stream()
                .filter(a -> "linux-builder".equals(a.get("name")))
                .findFirst().orElseThrow();

        // From agentDefaults
        assertEquals("FULL", linuxBuilder.get("cloneStrategy"));
        assertEquals("/home/ubuntu/agent", linuxBuilder.get("remoteFs"));
        assertEquals("ssh-key", linuxBuilder.get("credentialsId"));
        assertEquals("ubuntu", linuxBuilder.get("ciUser"));
        assertEquals("OPENJDK", linuxBuilder.get("javaDistribution"));
        assertEquals(21, linuxBuilder.get("javaMajorVersion"));

        // From agentDefaults-pve1
        assertEquals("local-lvm", linuxBuilder.get("targetStorage"));
        assertEquals("vmbr0", linuxBuilder.get("networkBridge"));

        // From specific config (overrides agentDefaults)
        assertEquals(8, linuxBuilder.get("cores"));
        assertEquals(16384, linuxBuilder.get("memoryMb"));
        assertEquals("linux docker", linuxBuilder.get("labelString"));
        assertEquals(2, linuxBuilder.get("numExecutors"));

        // templateVmId: specific overrides agentDefaults-pve1 (9000)
        // linux-builder doesn't specify templateVmId, so it gets 9000 from node defaults
        assertEquals(9000, linuxBuilder.get("templateVmId"));
    }

    @Test
    void createConfigFromYaml_agentOverridesNodeDefaults() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-basic.yaml");
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agentList =
                (List<Map<String, Object>>) result.get("testCluster").get("agentList");

        Map<String, Object> arm64Builder = agentList.stream()
                .filter(a -> "arm64-builder".equals(a.get("name")))
                .findFirst().orElseThrow();

        // arm64-builder overrides templateVmId from node defaults (9000 → 9001)
        assertEquals(9001, arm64Builder.get("templateVmId"));
        // Still gets targetStorage from node defaults
        assertEquals("local-lvm", arm64Builder.get("targetStorage"));
    }

    @Test
    void createConfigFromYaml_agentLinkedToMultipleClouds() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-multi-cloud.yaml");
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        assertEquals(2, result.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cloud1Agents =
                (List<Map<String, Object>>) result.get("cloud1").get("agentList");
        assertEquals(1, cloud1Agents.size());
        assertEquals("builder-both", cloud1Agents.get(0).get("name"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cloud2Agents =
                (List<Map<String, Object>>) result.get("cloud2").get("agentList");
        assertEquals(2, cloud2Agents.size());
    }

    @Test
    void createConfigFromYaml_missingCloudIdThrows() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-invalid-cloudid.yaml");
        List<String> warnings = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> loader.createConfigFromYaml(yamlData, warnings));
    }

    @Test
    void createConfigFromYaml_missingNodeDefaultsWarns() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-minimal.yaml");
        List<String> warnings = new ArrayList<>();

        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("agentDefaults-pve1"));
        assertEquals(1, result.size());
    }

    @Test
    void createConfigFromYaml_nullDefaultSectionsUsesEmptyMap() {
        Map<String, Object> yamlData = new LinkedHashMap<>();
        yamlData.put("cloudConfigurations", Map.of("c1", Map.of("name", "Cloud 1", "apiUrl", "https://x:8006")));
        yamlData.put("agentConfigurations", Map.of("a1",
                Map.of("cloudIds", List.of("c1"), "name", "agent1", "node", "n1",
                       "templateVmId", 100, "labelString", "x", "numExecutors", 1)));

        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> result = loader.createConfigFromYaml(yamlData, warnings);

        assertEquals(1, result.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents =
                (List<Map<String, Object>>) result.get("c1").get("agentList");
        assertEquals(1, agents.size());
        assertEquals("agent1", agents.get(0).get("name"));
    }

    // ---- createProxmoxCloud tests ----

    @Test
    void createProxmoxCloud_validConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "My Cloud");
        config.put("apiUrl", "https://proxmox.example.com:8006");
        config.put("credentialsId", "my-creds");
        config.put("ignoreSslErrors", false);
        config.put("instanceCap", 20);
        config.put("operationTimeoutSec", 600);
        config.put("startVmId", 1000);
        config.put("cleanupOrphanedAgents", true);
        config.put("orphanCleanupGracePeriodSeconds", 120);

        ProxmoxCloud cloud = loader.createProxmoxCloud(config);

        assertEquals("My Cloud", cloud.name);
        assertEquals("https://proxmox.example.com:8006", cloud.getApiUrl());
        assertEquals("my-creds", cloud.getCredentialsId());
        assertFalse(cloud.isIgnoreSslErrors());
        assertEquals(20, cloud.getInstanceCap());
        assertEquals(600, cloud.getOperationTimeout());
        assertEquals(1000, cloud.getStartVmId());
        assertTrue(cloud.isCleanupOrphanedAgents());
        assertEquals(120, cloud.getOrphanCleanupGracePeriodSeconds());
    }

    @Test
    void createProxmoxCloud_missingNameThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiUrl", "https://proxmox.example.com:8006");
        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxCloud(config));
    }

    @Test
    void createProxmoxCloud_minimalConfig() {
        Map<String, Object> config = Map.of("name", "Minimal Cloud");

        ProxmoxCloud cloud = loader.createProxmoxCloud(config);

        assertEquals("Minimal Cloud", cloud.name);
        assertNull(cloud.getApiUrl());
        assertNull(cloud.getCredentialsId());
        assertFalse(cloud.isIgnoreSslErrors());
        assertEquals(0, cloud.getInstanceCap());
        assertEquals(300, cloud.getOperationTimeout());
        assertEquals(0, cloud.getStartVmId());
        assertFalse(cloud.isCleanupOrphanedAgents());
        assertEquals(300, cloud.getOrphanCleanupGracePeriodSeconds());
    }

    // ---- createProxmoxTemplate tests ----

    @Test
    void createProxmoxTemplate_validConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "linux-builder");
        config.put("node", "pve1");
        config.put("templateVmId", 9000);
        config.put("labelString", "linux docker");
        config.put("numExecutors", 2);
        config.put("cloneStrategy", "LINKED");
        config.put("targetStorage", "local-lvm");
        config.put("targetPool", "dev-pool");
        config.put("cores", 8);
        config.put("memoryMb", 16384);
        config.put("diskSizeGb", 50);
        config.put("networkBridge", "vmbr0");
        config.put("remoteFs", "/opt/agent");
        config.put("mode", "NORMAL");
        config.put("credentialsId", "ssh-key");
        config.put("javaPath", "/usr/bin/java");
        config.put("jvmOptions", "-Xmx512m");
        config.put("javaDistribution", "OPENJDK");
        config.put("javaMajorVersion", 25);
        config.put("idleTerminationMinutes", 60);
        config.put("instanceCap", 5);
        config.put("maxTotalUses", 100);
        config.put("namePrefix", "custom-");
        config.put("startupWaitSeconds", 120);
        config.put("ciUser", "admin");
        config.put("ipConfig", "ip=10.0.0.5/24,gw=10.0.0.1");
        config.put("nameserver", "8.8.8.8");
        config.put("searchDomain", "example.com");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals("linux-builder", template.getName());
        assertEquals("pve1", template.getNode());
        assertEquals(9000, template.getTemplateVmId());
        assertEquals("linux docker", template.getLabelString());
        assertEquals(2, template.getNumExecutors());
        assertEquals(CloneStrategy.LINKED, template.getCloneStrategy());
        assertEquals("local-lvm", template.getTargetStorage());
        assertEquals("dev-pool", template.getTargetPool());
        assertEquals(8, template.getCores());
        assertEquals(16384, template.getMemory());
        assertEquals(50, template.getDiskSizeGb());
        assertEquals("vmbr0", template.getNetworkBridge());
        assertEquals("/opt/agent", template.getRemoteFs());
        assertEquals(Node.Mode.NORMAL, template.getMode());
        assertEquals("ssh-key", template.getCredentialsId());
        assertEquals("/usr/bin/java", template.getJavaPath());
        assertEquals("-Xmx512m", template.getJvmOptions());
        assertEquals(JavaDistribution.OPENJDK, template.getJavaDistribution());
        assertEquals(25, template.getJavaMajorVersion());
        assertEquals(60, template.getIdleTerminationMinutes());
        assertEquals(5, template.getInstanceCap());
        assertEquals(100, template.getMaxTotalUses());
        assertEquals("custom-", template.getNamePrefix());
        assertEquals(120, template.getStartupWaitSeconds());
        assertEquals("admin", template.getCiUser());
        assertEquals("ip=10.0.0.5/24,gw=10.0.0.1", template.getIpConfig());
        assertEquals("8.8.8.8", template.getNameserver());
        assertEquals("example.com", template.getSearchDomain());
    }

    @Test
    void createProxmoxTemplate_missingNameThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("node", "pve1");
        config.put("templateVmId", 9000);
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxTemplate(config));
    }

    @Test
    void createProxmoxTemplate_missingNodeThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "test");
        config.put("templateVmId", 9000);
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxTemplate(config));
    }

    @Test
    void createProxmoxTemplate_missingTemplateVmIdThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "test");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxTemplate(config));
    }

    @Test
    void createProxmoxTemplate_nameRegexModeWithoutTemplateVmId() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "NAME_REGEX");
        config.put("templateNameRegex", "agent-.*");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals(TemplateSelectionMode.NAME_REGEX, template.getTemplateSelectionMode());
        assertEquals("agent-.*", template.getTemplateNameRegex());
        assertEquals(0, template.getTemplateVmId());
    }

    @Test
    void createProxmoxTemplate_tagMode() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "TAG");
        config.put("templateTag", "jenkins");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals(TemplateSelectionMode.TAG, template.getTemplateSelectionMode());
        assertEquals("jenkins", template.getTemplateTag());
    }

    @Test
    void createProxmoxTemplate_nameRegexModeMissingRegexThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "NAME_REGEX");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.createProxmoxTemplate(config));
        assertTrue(e.getMessage().contains("templateNameRegex"), e.getMessage());
    }

    @Test
    void createProxmoxTemplate_tagModeMissingTagThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "TAG");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.createProxmoxTemplate(config));
        assertTrue(e.getMessage().contains("templateTag"), e.getMessage());
    }

    @Test
    void createProxmoxTemplate_invalidRegexThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "NAME_REGEX");
        config.put("templateNameRegex", "[unclosed");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.createProxmoxTemplate(config));
        assertTrue(e.getMessage().contains("regular expression"), e.getMessage());
    }

    @Test
    void createProxmoxTemplate_invalidSelectionModeThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "dyn");
        config.put("node", "pve1");
        config.put("labelString", "linux");
        config.put("numExecutors", 1);
        config.put("templateSelectionMode", "SOMETHING_ELSE");
        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxTemplate(config));
    }

    @Test
    void createProxmoxTemplate_onlyRequiredFields() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "basic");
        config.put("node", "pve1");
        config.put("templateVmId", 9000);
        config.put("labelString", "linux");
        config.put("numExecutors", 1);

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals("basic", template.getName());
        assertEquals("pve1", template.getNode());
        assertEquals(9000, template.getTemplateVmId());
        // Verify class defaults are preserved
        assertEquals(CloneStrategy.FULL, template.getCloneStrategy());
        assertEquals("/home/ubuntu/agent", template.getRemoteFs());
        assertEquals(Node.Mode.EXCLUSIVE, template.getMode());
        assertEquals("java", template.getJavaPath());
        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
        assertEquals(21, template.getJavaMajorVersion());
        assertEquals(30, template.getIdleTerminationMinutes());
        assertEquals(0, template.getInstanceCap());
        assertEquals(0, template.getMaxTotalUses());
        assertEquals("jenkins-agent-", template.getNamePrefix());
        assertEquals(60, template.getStartupWaitSeconds());
    }

    @Test
    void createProxmoxTemplate_enumParsing() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "test");
        config.put("node", "pve1");
        config.put("templateVmId", 9000);
        config.put("labelString", "");
        config.put("numExecutors", 1);
        config.put("cloneStrategy", "LINKED");
        config.put("mode", "NORMAL");
        config.put("javaDistribution", "OPENJDK");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals(CloneStrategy.LINKED, template.getCloneStrategy());
        assertEquals(Node.Mode.NORMAL, template.getMode());
        assertEquals(JavaDistribution.OPENJDK, template.getJavaDistribution());
    }

    @Test
    void createProxmoxTemplate_invalidEnumThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "test");
        config.put("node", "pve1");
        config.put("templateVmId", 9000);
        config.put("labelString", "");
        config.put("numExecutors", 1);
        config.put("cloneStrategy", "INVALID_STRATEGY");

        try {
            loader.createProxmoxTemplate(config);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("INVALID_STRATEGY"));
            assertTrue(e.getMessage().contains("cloneStrategy"));
            assertTrue(e.getMessage().contains("FULL"));
            assertTrue(e.getMessage().contains("LINKED"));
        }
    }

    // ---- convertYamlToObjects tests ----

    @Test
    void convertYamlToObjects_fullIntegration() {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-basic.yaml");
        List<String> warnings = new ArrayList<>();
        Map<String, Map<String, Object>> mergedConfig = loader.createConfigFromYaml(yamlData, warnings);

        List<ProxmoxCloud> clouds = loader.convertYamlToObjects(mergedConfig);

        assertEquals(1, clouds.size());
        ProxmoxCloud cloud = clouds.get(0);
        assertEquals("Test Proxmox Cloud", cloud.name);
        assertEquals("https://proxmox.example.com:8006", cloud.getApiUrl());
        assertEquals("proxmox-api-token", cloud.getCredentialsId());
        assertTrue(cloud.isConfigManaged());
        assertTrue(cloud.getLastSyncTimestamp() > 0);

        List<ProxmoxTemplate> templates = cloud.getTemplates();
        assertEquals(2, templates.size());

        ProxmoxTemplate linuxBuilder = templates.stream()
                .filter(t -> "linux-builder".equals(t.getName()))
                .findFirst().orElseThrow();
        assertEquals("pve1", linuxBuilder.getNode());
        assertEquals(9000, linuxBuilder.getTemplateVmId());
        assertEquals("linux docker", linuxBuilder.getLabelString());
        assertEquals(2, linuxBuilder.getNumExecutors());
        assertEquals(8, linuxBuilder.getCores());
        assertEquals(16384, linuxBuilder.getMemory());
        assertEquals("local-lvm", linuxBuilder.getTargetStorage());
        assertEquals(JavaDistribution.OPENJDK, linuxBuilder.getJavaDistribution());
        assertEquals(21, linuxBuilder.getJavaMajorVersion());
    }

    // ---- persistJenkinsChanges tests ----

    @Test
    void persistJenkinsChanges_addsNewClouds() throws Exception {
        Jenkins jenkins = j.jenkins;

        ProxmoxCloud cloud = new ProxmoxCloud("New Cloud");
        loader.persistJenkinsChanges(List.of(cloud), jenkins);

        assertEquals(1, jenkins.clouds.size());
        assertNotNull(jenkins.getCloud("New Cloud"));
    }

    @Test
    void persistJenkinsChanges_replacesExistingCloud() throws Exception {
        Jenkins jenkins = j.jenkins;
        ProxmoxCloud existing = new ProxmoxCloud("Existing Cloud");
        jenkins.clouds.add(existing);

        ProxmoxCloud replacement = new ProxmoxCloud("Existing Cloud");
        replacement.setApiUrl("https://new-url:8006");
        loader.persistJenkinsChanges(List.of(replacement), jenkins);

        assertEquals(1, jenkins.clouds.size());
        Cloud inList = jenkins.getCloud("Existing Cloud");
        assertTrue(inList instanceof ProxmoxCloud);
        assertEquals("https://new-url:8006", ((ProxmoxCloud) inList).getApiUrl());
    }

    @Test
    void persistJenkinsChanges_leavesUnrelatedClouds() throws Exception {
        Jenkins jenkins = j.jenkins;
        ProxmoxCloud unrelated = new ProxmoxCloud("Unrelated Cloud");
        jenkins.clouds.add(unrelated);

        ProxmoxCloud newCloud = new ProxmoxCloud("New Cloud");
        loader.persistJenkinsChanges(List.of(newCloud), jenkins);

        assertEquals(2, jenkins.clouds.size());
        assertNotNull(jenkins.getCloud("Unrelated Cloud"));
        assertNotNull(jenkins.getCloud("New Cloud"));
    }

    // ---- processYamlAndPersistConfig end-to-end ----

    @Test
    void processYamlAndPersistConfig_endToEnd() throws Exception {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-basic.yaml");
        Jenkins jenkins = j.jenkins;

        ProxmoxSyncResult result = loader.processYamlAndPersistConfig(yamlData, jenkins);

        assertTrue(result.isSuccess());
        assertEquals(1, result.cloudsConfigured());
        assertEquals(2, result.templatesConfigured());
        assertTrue(result.warnings().isEmpty());
        assertTrue(result.errors().isEmpty());

        assertEquals(1, jenkins.clouds.size());
        ProxmoxCloud cloud = (ProxmoxCloud) jenkins.getCloud("Test Proxmox Cloud");
        assertNotNull(cloud);
        assertEquals(2, cloud.getTemplates().size());
    }

    @Test
    void processYamlAndPersistConfig_allOrNothing() throws Exception {
        Map<String, Object> yamlData = loadYaml("proxmox/proxmox-config-no-name.yaml");
        Jenkins jenkins = j.jenkins;

        ProxmoxSyncResult result = loader.processYamlAndPersistConfig(yamlData, jenkins);

        assertFalse(result.isSuccess());
        assertEquals(0, result.cloudsConfigured());
        assertEquals(0, jenkins.clouds.size());
    }

    // ---- combineConfig tests ----

    @Test
    void combineConfig_specificOverridesDefaults() {
        Map<String, Object> defaults = Map.of("a", 1, "b", 2);
        Map<String, Object> specific = Map.of("b", 99, "c", 3);

        Map<String, Object> result = loader.combineConfig(defaults, specific);

        assertEquals(1, result.get("a"));
        assertEquals(99, result.get("b"));
        assertEquals(3, result.get("c"));
    }

    @Test
    void combineConfig_nullDefaultsHandled() {
        Map<String, Object> specific = Map.of("a", 1);
        Map<String, Object> result = loader.combineConfig(null, specific);
        assertEquals(1, result.get("a"));
    }

    @Test
    void combineAgentConfig_threeLevelMerge() {
        Map<String, Object> defaults = Map.of("a", 1, "b", 2, "c", 3);
        Map<String, Object> nodeDefaults = Map.of("b", 20, "d", 4);
        Map<String, Object> specific = new LinkedHashMap<>(Map.of("c", 30, "e", 5, "cloudIds", List.of("x")));

        Map<String, Object> result = loader.combineAgentConfig(defaults, nodeDefaults, specific);

        assertEquals(1, result.get("a"));
        assertEquals(20, result.get("b"));
        assertEquals(30, result.get("c"));
        assertEquals(4, result.get("d"));
        assertEquals(5, result.get("e"));
        assertFalse(result.containsKey("cloudIds"));
    }

    @Test
    void combineAgentConfig_nullNodeDefaultsHandled() {
        Map<String, Object> defaults = Map.of("a", 1);
        Map<String, Object> specific = new LinkedHashMap<>(Map.of("b", 2, "cloudIds", List.of("x")));

        Map<String, Object> result = loader.combineAgentConfig(defaults, null, specific);

        assertEquals(1, result.get("a"));
        assertEquals(2, result.get("b"));
    }

    // ---- osType tests ----

    @Test
    void createProxmoxTemplate_osTypeWindows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "win-builder");
        config.put("node", "pve1");
        config.put("templateVmId", 9001);
        config.put("labelString", "windows");
        config.put("numExecutors", 1);
        config.put("osType", "WINDOWS");
        config.put("remoteFs", "C:\\Users\\jenkins\\agent");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals(OsType.WINDOWS, template.getOsType());
        assertEquals("C:\\Users\\jenkins\\agent", template.getRemoteFs());
    }

    @Test
    void createProxmoxTemplate_windowsWithoutRemoteFsThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "win-builder");
        config.put("node", "pve1");
        config.put("templateVmId", 9001);
        config.put("labelString", "windows");
        config.put("numExecutors", 1);
        config.put("osType", "WINDOWS");

        assertThrows(IllegalArgumentException.class, () -> loader.createProxmoxTemplate(config));
    }

    @Test
    void processYamlAndPersistConfig_windowsWithoutRemoteFsIsAnError() throws Exception {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> yamlData = yaml.load(
                "cloudConfigurations:\n"
                + "  testCluster:\n"
                + "    name: \"Test Cloud\"\n"
                + "agentConfigurations:\n"
                + "  win-builder:\n"
                + "    cloudIds: [\"testCluster\"]\n"
                + "    node: \"pve1\"\n"
                + "    name: \"win-builder\"\n"
                + "    labelString: \"windows\"\n"
                + "    templateVmId: 9001\n"
                + "    osType: WINDOWS\n");

        ProxmoxSyncResult result = loader.processYamlAndPersistConfig(yamlData, j.jenkins);

        assertFalse(result.isSuccess());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Remote FS Root")),
                "Error should mention Remote FS Root, but errors were: " + result.errors());
    }

    @Test
    void createProxmoxTemplate_windowsWithJavaDistributionThrows() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "win-builder");
        config.put("node", "pve1");
        config.put("templateVmId", 9001);
        config.put("labelString", "windows");
        config.put("numExecutors", 1);
        config.put("osType", "WINDOWS");
        config.put("remoteFs", "C:\\Users\\jenkins\\agent");
        config.put("javaDistribution", "OPENJDK");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> loader.createProxmoxTemplate(config));
        assertTrue(e.getMessage().contains("Java Distribution"),
                "Error should mention Java Distribution, but was: " + e.getMessage());
    }

    @Test
    void createProxmoxTemplate_windowsWithExplicitNoneJavaDistributionOk() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", "win-builder");
        config.put("node", "pve1");
        config.put("templateVmId", 9001);
        config.put("labelString", "windows");
        config.put("numExecutors", 1);
        config.put("osType", "WINDOWS");
        config.put("remoteFs", "C:\\Users\\jenkins\\agent");
        config.put("javaDistribution", "NONE");

        ProxmoxTemplate template = loader.createProxmoxTemplate(config);

        assertEquals(JavaDistribution.NONE, template.getJavaDistribution());
    }

    @Test
    void processYamlAndPersistConfig_windowsJavaDistributionInheritedFromDefaultsIsAnError() throws Exception {
        Yaml yaml = new Yaml();
        @SuppressWarnings("unchecked")
        Map<String, Object> yamlData = yaml.load(
                "cloudConfigurations:\n"
                + "  testCluster:\n"
                + "    name: \"Test Cloud\"\n"
                + "agentDefaults:\n"
                + "  javaDistribution: OPENJDK\n"
                + "agentConfigurations:\n"
                + "  win-builder:\n"
                + "    cloudIds: [\"testCluster\"]\n"
                + "    node: \"pve1\"\n"
                + "    name: \"win-builder\"\n"
                + "    labelString: \"windows\"\n"
                + "    templateVmId: 9001\n"
                + "    osType: WINDOWS\n"
                + "    remoteFs: 'C:\\Users\\jenkins\\agent'\n");

        ProxmoxSyncResult result = loader.processYamlAndPersistConfig(yamlData, j.jenkins);

        assertFalse(result.isSuccess());
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Java Distribution")),
                "Error should mention Java Distribution, but errors were: " + result.errors());
    }
}
