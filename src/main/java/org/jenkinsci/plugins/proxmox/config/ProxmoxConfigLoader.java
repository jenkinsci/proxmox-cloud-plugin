package org.jenkinsci.plugins.proxmox.config;

import hudson.model.Node;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.ProxmoxCloud;
import org.jenkinsci.plugins.proxmox.ProxmoxTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxConfigLoader {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxConfigLoader.class.getName());

    static final String KEY_CLOUD_DEFAULTS = "cloudDefaults";
    static final String KEY_CLOUD_CONFIGURATIONS = "cloudConfigurations";
    static final String KEY_AGENT_DEFAULTS = "agentDefaults";
    static final String KEY_AGENT_CONFIGURATIONS = "agentConfigurations";
    static final String KEY_AGENT_DEFAULTS_PREFIX = "agentDefaults-";
    static final String KEY_AGENT_LIST = "agentList";
    static final String KEY_CLOUD_IDS = "cloudIds";

    public ProxmoxSyncResult processYamlAndPersistConfig(Map<String, Object> yamlData) {
        return processYamlAndPersistConfig(yamlData, Jenkins.get());
    }

    public ProxmoxSyncResult processYamlAndPersistConfig(Map<String, Object> yamlData, Jenkins jenkins) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Map<String, Map<String, Object>> mergedConfigMap;
        try {
            mergedConfigMap = createConfigFromYaml(yamlData, warnings);
        } catch (Exception e) {
            errors.add("YAML processing failed: " + e.getMessage());
            return new ProxmoxSyncResult(0, 0, warnings, errors);
        }

        List<ProxmoxCloud> clouds;
        try {
            clouds = convertYamlToObjects(mergedConfigMap);
        } catch (Exception e) {
            errors.add("Object construction failed: " + e.getMessage());
            return new ProxmoxSyncResult(0, 0, warnings, errors);
        }

        int totalTemplates = clouds.stream().mapToInt(c -> c.getTemplates().size()).sum();

        try {
            persistJenkinsChanges(clouds, jenkins);
        } catch (Exception e) {
            errors.add("Persistence failed: " + e.getMessage());
            return new ProxmoxSyncResult(0, 0, warnings, errors);
        }

        return new ProxmoxSyncResult(clouds.size(), totalTemplates, warnings, errors);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> createConfigFromYaml(Map<String, Object> yamlData,
                                                                  List<String> warnings) {
        Map<String, Object> cloudDefaults = getMapOrEmpty(yamlData, KEY_CLOUD_DEFAULTS);
        Map<String, Object> cloudConfigs = getMapOrEmpty(yamlData, KEY_CLOUD_CONFIGURATIONS);
        Map<String, Object> agentDefaults = getMapOrEmpty(yamlData, KEY_AGENT_DEFAULTS);
        Map<String, Object> agentConfigs = getMapOrEmpty(yamlData, KEY_AGENT_CONFIGURATIONS);

        Map<String, Map<String, Object>> mergedCloudMap = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : cloudConfigs.entrySet()) {
            Map<String, Object> specificConfig = (Map<String, Object>) entry.getValue();
            Map<String, Object> combined = combineConfig(cloudDefaults, specificConfig);
            mergedCloudMap.put(entry.getKey(), combined);
        }

        for (Map.Entry<String, Object> entry : agentConfigs.entrySet()) {
            String agentId = entry.getKey();
            Map<String, Object> agentSpecificConfig = (Map<String, Object>) entry.getValue();

            List<String> cloudIds = (List<String>) agentSpecificConfig.get(KEY_CLOUD_IDS);
            if (cloudIds == null || cloudIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "Agent '" + agentId + "' has no cloudIds defined");
            }

            for (String cloudId : cloudIds) {
                Map<String, Object> cloudConfig = mergedCloudMap.get(cloudId);
                if (cloudConfig == null) {
                    throw new IllegalArgumentException(String.format(
                            "Agent '%s' references cloud '%s' which does not exist in cloudConfigurations",
                            agentId, cloudId));
                }

                String node = (String) agentSpecificConfig.get("node");
                Map<String, Object> nodeDefaults = null;
                if (node != null) {
                    nodeDefaults = (Map<String, Object>) yamlData.get(KEY_AGENT_DEFAULTS_PREFIX + node);
                    if (nodeDefaults == null) {
                        String msg = String.format(
                                "No agentDefaults-%s found for agent '%s'. Node-specific defaults will be skipped.",
                                node, agentId);
                        LOGGER.warning(msg);
                        warnings.add(msg);
                    }
                }

                Map<String, Object> combinedAgent = combineAgentConfig(
                        agentDefaults, nodeDefaults, agentSpecificConfig);

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> agentList =
                        (List<Map<String, Object>>) cloudConfig.get(KEY_AGENT_LIST);
                if (agentList == null) {
                    agentList = new ArrayList<>();
                }
                agentList.add(combinedAgent);
                cloudConfig.put(KEY_AGENT_LIST, agentList);
            }
        }

        return mergedCloudMap;
    }

    public List<ProxmoxCloud> convertYamlToObjects(Map<String, Map<String, Object>> configMap) {
        List<ProxmoxCloud> cloudList = new ArrayList<>();
        long syncTimestamp = System.currentTimeMillis();

        for (Map.Entry<String, Map<String, Object>> entry : configMap.entrySet()) {
            Map<String, Object> cloudData = entry.getValue();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> agentList =
                    (List<Map<String, Object>>) cloudData.get(KEY_AGENT_LIST);

            List<ProxmoxTemplate> templates = new ArrayList<>();
            if (agentList != null) {
                for (Map<String, Object> agentConfig : agentList) {
                    templates.add(createProxmoxTemplate(agentConfig));
                }
            }

            ProxmoxCloud cloud = createProxmoxCloud(cloudData);
            cloud.setTemplates(templates);
            cloud.setConfigManaged(true);
            cloud.setLastSyncTimestamp(syncTimestamp);
            cloudList.add(cloud);
        }

        return cloudList;
    }

    public ProxmoxCloud createProxmoxCloud(Map<String, Object> configMap) {
        String name = getStr(configMap, "name", null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "Cloud configuration missing required 'name' property");
        }

        ProxmoxCloud cloud = new ProxmoxCloud(name);

        if (configMap.containsKey("apiUrl")) {
            cloud.setApiUrl(getStr(configMap, "apiUrl", null));
        }
        if (configMap.containsKey("credentialsId")) {
            cloud.setCredentialsId(getStr(configMap, "credentialsId", null));
        }
        if (configMap.containsKey("ignoreSslErrors")) {
            cloud.setIgnoreSslErrors(getBool(configMap, "ignoreSslErrors", true));
        }
        if (configMap.containsKey("instanceCap")) {
            cloud.setInstanceCap(getInt(configMap, "instanceCap", 0));
        }
        if (configMap.containsKey("operationTimeoutSec")) {
            cloud.setOperationTimeout(getInt(configMap, "operationTimeoutSec", 300));
        }
        if (configMap.containsKey("startVmId")) {
            cloud.setStartVmId(getInt(configMap, "startVmId", 0));
        }
        if (configMap.containsKey("cleanupOrphanedAgents")) {
            cloud.setCleanupOrphanedAgents(getBool(configMap, "cleanupOrphanedAgents", false));
        }
        if (configMap.containsKey("orphanCleanupGracePeriodSeconds")) {
            cloud.setOrphanCleanupGracePeriodSeconds(getInt(configMap, "orphanCleanupGracePeriodSeconds", 300));
        }
        if (configMap.containsKey("orphanCleanupPeriodSeconds")) {
            cloud.setOrphanCleanupPeriodSeconds(getInt(configMap, "orphanCleanupPeriodSeconds", 600));
        }

        return cloud;
    }

    public ProxmoxTemplate createProxmoxTemplate(Map<String, Object> configMap) {
        String name = getRequiredStr(configMap, "name", "Agent template");
        String node = getRequiredStr(configMap, "node", "Agent template '" + name + "'");
        int templateVmId = getRequiredInt(configMap, "templateVmId", "Agent template '" + name + "'");
        String labelString = getStr(configMap, "labelString", "");
        int numExecutors = getInt(configMap, "numExecutors", 1);

        ProxmoxTemplate template = new ProxmoxTemplate(name, node, templateVmId, labelString, numExecutors);

        if (configMap.containsKey("cloneStrategy")) {
            template.setCloneStrategy(parseEnum(CloneStrategy.class,
                    getStr(configMap, "cloneStrategy", null), "cloneStrategy"));
        }
        if (configMap.containsKey("targetStorage")) {
            template.setTargetStorage(getStr(configMap, "targetStorage", null));
        }
        if (configMap.containsKey("targetPool")) {
            template.setTargetPool(getStr(configMap, "targetPool", null));
        }
        if (configMap.containsKey("cores")) {
            template.setCores(getInt(configMap, "cores", 0));
        }
        if (configMap.containsKey("memoryMb")) {
            template.setMemory(getInt(configMap, "memoryMb", 0));
        }
        if (configMap.containsKey("diskSizeGb")) {
            template.setDiskSizeGb(getInt(configMap, "diskSizeGb", 0));
        }
        if (configMap.containsKey("networkBridge")) {
            template.setNetworkBridge(getStr(configMap, "networkBridge", null));
        }
        if (configMap.containsKey("remoteFs")) {
            template.setRemoteFs(getStr(configMap, "remoteFs", "/home/ubuntu/agent"));
        }
        if (configMap.containsKey("mode")) {
            template.setMode(parseEnum(Node.Mode.class,
                    getStr(configMap, "mode", null), "mode"));
        }
        if (configMap.containsKey("credentialsId")) {
            template.setCredentialsId(getStr(configMap, "credentialsId", null));
        }
        if (configMap.containsKey("javaPath")) {
            template.setJavaPath(getStr(configMap, "javaPath", "java"));
        }
        if (configMap.containsKey("jvmOptions")) {
            template.setJvmOptions(getStr(configMap, "jvmOptions", null));
        }
        if (configMap.containsKey("javaVersion")) {
            template.setJavaVersion(parseEnum(JavaInstallation.class,
                    getStr(configMap, "javaVersion", null), "javaVersion"));
        }
        if (configMap.containsKey("idleTerminationMinutes")) {
            template.setIdleTerminationMinutes(getInt(configMap, "idleTerminationMinutes", 30));
        }
        if (configMap.containsKey("instanceCap")) {
            template.setInstanceCap(getInt(configMap, "instanceCap", 0));
        }
        if (configMap.containsKey("maxTotalUses")) {
            template.setMaxTotalUses(getInt(configMap, "maxTotalUses", 0));
        }
        if (configMap.containsKey("namePrefix")) {
            template.setNamePrefix(getStr(configMap, "namePrefix", "jenkins-agent-"));
        }
        if (configMap.containsKey("startupWaitSeconds")) {
            template.setStartupWaitSeconds(getInt(configMap, "startupWaitSeconds", 60));
        }
        if (configMap.containsKey("ciUser")) {
            template.setCiUser(getStr(configMap, "ciUser", null));
        }
        if (configMap.containsKey("ipConfig")) {
            template.setIpConfig(getStr(configMap, "ipConfig", null));
        }
        if (configMap.containsKey("nameserver")) {
            template.setNameserver(getStr(configMap, "nameserver", null));
        }
        if (configMap.containsKey("searchDomain")) {
            template.setSearchDomain(getStr(configMap, "searchDomain", null));
        }
        if (configMap.containsKey("userDataScript")) {
            template.setUserDataScript(getStr(configMap, "userDataScript", null));
        }

        return template;
    }

    public void persistJenkinsChanges(List<ProxmoxCloud> clouds, Jenkins jenkins) {
        for (ProxmoxCloud cloud : clouds) {
            String name = cloud.name;
            Cloud existing = jenkins.getCloud(name);
            if (existing != null) {
                LOGGER.fine("Removing existing cloud config: " + name);
                jenkins.clouds.remove(existing);
            } else {
                LOGGER.fine("No existing cloud config found for: " + name);
            }
            LOGGER.fine("Adding cloud config: " + name);
            jenkins.clouds.add(cloud);
        }

        try {
            jenkins.save();
            LOGGER.fine("Jenkins configuration saved successfully");
        } catch (IOException e) {
            throw new RuntimeException("Failed to save Jenkins configuration", e);
        }
    }

    Map<String, Object> combineConfig(Map<String, Object> defaults, Map<String, Object> specific) {
        Map<String, Object> combined = new LinkedHashMap<>();
        if (defaults != null) {
            combined.putAll(defaults);
        }
        if (specific != null) {
            combined.putAll(specific);
        }
        return combined;
    }

    Map<String, Object> combineAgentConfig(Map<String, Object> agentDefaults,
                                            Map<String, Object> nodeDefaults,
                                            Map<String, Object> specific) {
        Map<String, Object> combined = new LinkedHashMap<>();
        if (agentDefaults != null) {
            combined.putAll(agentDefaults);
        }
        if (nodeDefaults != null) {
            combined.putAll(nodeDefaults);
        }
        if (specific != null) {
            combined.putAll(specific);
        }
        combined.remove(KEY_CLOUD_IDS);
        return combined;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getMapOrEmpty(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val == null) {
            return Collections.emptyMap();
        }
        return (Map<String, Object>) val;
    }

    private String getStr(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        return val.toString();
    }

    private String getRequiredStr(Map<String, Object> map, String key, String context) {
        String val = getStr(map, key, null);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException(context + " missing required field: " + key);
        }
        return val;
    }

    private int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private int getRequiredInt(Map<String, Object> map, String key, String context) {
        Object val = map.get(key);
        if (val == null) {
            throw new IllegalArgumentException(context + " missing required field: " + key);
        }
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private boolean getBool(Map<String, Object> map, String key, boolean defaultVal) {
        Object val = map.get(key);
        if (val == null) return defaultVal;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Empty value for enum field '" + fieldName + "'. Valid values: "
                    + java.util.Arrays.toString(enumClass.getEnumConstants()));
        }
        try {
            return Enum.valueOf(enumClass, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value '" + value + "' for field '" + fieldName
                    + "'. Valid values: " + java.util.Arrays.toString(enumClass.getEnumConstants()));
        }
    }
}
