package org.jenkinsci.plugins.proxmox.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.model.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ProxmoxClient {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxClient.class.getName());
    private static final Gson GSON = new Gson();

    private final String apiUrl;
    private final String authHeader;
    private final HttpClient httpClient;

    public ProxmoxClient(String apiUrl, String tokenId, Secret tokenSecret, boolean ignoreSslErrors) {
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        this.authHeader = "PVEAPIToken=" + tokenId + "=" + tokenSecret.getPlainText();

        HttpClient.Builder builder = HttpClient.newBuilder();
        if (ignoreSslErrors) {
            builder.sslContext(createTrustAllSslContext());
        }
        this.httpClient = builder.build();
    }

    public String getVersion() {
        JsonObject data = get("/api2/json/version", JsonObject.class);
        return data.get("version").getAsString();
    }

    public List<ClusterNode> getNodes() {
        return get("/api2/json/nodes", new TypeToken<List<ClusterNode>>() {}.getType());
    }

    public List<VirtualMachine> getVms(String node) {
        return get("/api2/json/nodes/" + node + "/qemu",
                new TypeToken<List<VirtualMachine>>() {}.getType());
    }

    public List<VirtualMachine> getTemplates(String node) {
        return getVms(node).stream().filter(VirtualMachine::isTemplate).collect(Collectors.toList());
    }

    public int getNextVmId() {
        return getNextVmId(0);
    }

    public int getNextVmId(int minId) {
        String defaultId = get("/api2/json/cluster/nextid", String.class);
        int id = Integer.parseInt(defaultId);
        if (minId <= 0 || id >= minId) {
            return id;
        }
        // Proxmox's ?vmid=N validates that exact ID, not "next >= N".
        // Search upward from minId for the first available ID.
        for (int candidate = minId; candidate < minId + 1000; candidate++) {
            try {
                String result = get("/api2/json/cluster/nextid?vmid=" + candidate, String.class);
                return Integer.parseInt(result);
            } catch (ProxmoxException e) {
                // ID is taken, try next
            }
        }
        throw new ProxmoxException("No available VM ID found in range " + minId + "-" + (minId + 999));
    }

    public String cloneVm(String node, int templateId, CloneOptions opts) {
        Map<String, String> params = new HashMap<>();
        params.put("newid", String.valueOf(opts.newVmId()));
        if (opts.name() != null) params.put("name", opts.name());
        if (opts.description() != null) params.put("description", opts.description());
        params.put("full", opts.full() ? "1" : "0");
        if (opts.storage() != null && !opts.storage().isBlank()) params.put("storage", opts.storage());
        if (opts.pool() != null && !opts.pool().isBlank()) params.put("pool", opts.pool());

        return post("/api2/json/nodes/" + node + "/qemu/" + templateId + "/clone", params);
    }

    public void resizeVmDisk(String node, int vmId, String disk, int sizeGb) {
        Map<String, String> params = new HashMap<>();
        params.put("disk", disk);
        params.put("size", sizeGb + "G");
        put("/api2/json/nodes/" + node + "/qemu/" + vmId + "/resize", params);
    }

    public String configureVm(String node, int vmId, VmConfig config) {
        Map<String, String> params = new HashMap<>();
        if (config.cores() != null && config.cores() > 0) params.put("cores", String.valueOf(config.cores()));
        if (config.memory() != null && config.memory() > 0) params.put("memory", String.valueOf(config.memory()));
        if (config.ciuser() != null) params.put("ciuser", config.ciuser());
        if (config.sshkeys() != null) params.put("sshkeys",
                URLEncoder.encode(config.sshkeys(), StandardCharsets.UTF_8).replace("+", "%20"));
        if (config.ipconfig0() != null) params.put("ipconfig0", config.ipconfig0());
        if (config.nameserver() != null) params.put("nameserver", config.nameserver());
        if (config.searchdomain() != null) params.put("searchdomain", config.searchdomain());

        return put("/api2/json/nodes/" + node + "/qemu/" + vmId + "/config", params);
    }

    public String startVm(String node, int vmId) {
        return post("/api2/json/nodes/" + node + "/qemu/" + vmId + "/status/start", Map.of());
    }

    public String stopVm(String node, int vmId) {
        return post("/api2/json/nodes/" + node + "/qemu/" + vmId + "/status/stop", Map.of());
    }

    public String shutdownVm(String node, int vmId, int timeout) {
        return post("/api2/json/nodes/" + node + "/qemu/" + vmId + "/status/shutdown",
                Map.of("timeout", String.valueOf(timeout)));
    }

    public String destroyVm(String node, int vmId, boolean purge) {
        String path = "/api2/json/nodes/" + node + "/qemu/" + vmId;
        if (purge) {
            path += "?purge=1&destroy-unreferenced-disks=1";
        }
        return delete(path);
    }

    public VirtualMachine getVmStatus(String node, int vmId) {
        return get("/api2/json/nodes/" + node + "/qemu/" + vmId + "/status/current", VirtualMachine.class);
    }

    public JsonObject getVmConfig(String node, int vmId) {
        return get("/api2/json/nodes/" + node + "/qemu/" + vmId + "/config", JsonObject.class);
    }

    public List<NetworkInterface> getVmNetworkInterfaces(String node, int vmId) {
        JsonObject data = get(
                "/api2/json/nodes/" + node + "/qemu/" + vmId + "/agent/network-get-interfaces",
                JsonObject.class);
        return GSON.fromJson(data.get("result"), new TypeToken<List<NetworkInterface>>() {}.getType());
    }

    public List<StoragePool> getStoragePools(String node) {
        return get("/api2/json/nodes/" + node + "/storage", new TypeToken<List<StoragePool>>() {}.getType());
    }

    public List<NetworkDevice> getNetworkDevices(String node) {
        return get("/api2/json/nodes/" + node + "/network", new TypeToken<List<NetworkDevice>>() {}.getType());
    }

    public List<ResourcePool> getPools() {
        return get("/api2/json/pools", new TypeToken<List<ResourcePool>>() {}.getType());
    }

    public TaskStatus getTaskStatus(String node, String upid) {
        return get("/api2/json/nodes/" + node + "/tasks/" + URLEncoder.encode(upid, StandardCharsets.UTF_8) + "/status",
                TaskStatus.class);
    }

    public void waitForTask(String node, String upid, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        long sleepMs = 1000;

        while (System.currentTimeMillis() < deadline) {
            TaskStatus status = getTaskStatus(node, upid);
            if (!status.isRunning()) {
                if (status.isSuccessful()) {
                    return;
                }
                throw new ProxmoxTaskFailedException(upid, status.exitstatus());
            }

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxmoxException("Interrupted while waiting for task " + upid, e);
            }
            sleepMs = Math.min(sleepMs * 2, 5000);
        }

        throw new ProxmoxTimeoutException("Task " + upid + " did not complete within " + timeoutSeconds + " seconds");
    }

    private <T> T get(String path, Type type) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Authorization", authHeader)
                .GET()
                .build();
        return execute(request, type);
    }

    private String post(String path, Map<String, String> params) {
        String body = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request, String.class);
    }

    private String put(String path, Map<String, String> params) {
        String body = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return execute(request, String.class);
    }

    private String delete(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        return execute(request, String.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T execute(HttpRequest request, Type type) {
        try {
            LOGGER.fine(() -> request.method() + " " + request.uri());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw new ProxmoxAuthenticationException(
                        "Authentication failed (" + status + "): " + response.body());
            }
            if (status == 404) {
                throw new ProxmoxResourceNotFoundException(
                        "Resource not found: " + request.uri());
            }
            if (status < 200 || status >= 300) {
                throw new ProxmoxException(
                        "Proxmox API error (" + status + "): " + response.body());
            }

            JsonObject envelope = GSON.fromJson(response.body(), JsonObject.class);
            if (envelope == null || !envelope.has("data")) {
                throw new ProxmoxException("Unexpected response format: " + response.body());
            }

            JsonElement data = envelope.get("data");

            if (type == String.class) {
                return data.isJsonPrimitive() ? (T) data.getAsString() : (T) data.toString();
            }
            return GSON.fromJson(data, type);

        } catch (IOException e) {
            throw new ProxmoxException("Failed to communicate with Proxmox API at " + apiUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProxmoxException("Request interrupted", e);
        }
    }

    private static SSLContext createTrustAllSslContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            return sslContext;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new ProxmoxException("Failed to create trust-all SSL context", e);
        }
    }
}
