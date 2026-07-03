package org.jenkinsci.plugins.proxmox.api;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class ProxmoxClientTest {

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

    @Test
    void testAuthHeader() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(okJson("{\"data\":{\"version\":\"8.2.4\"}}")));

        client.getVersion();

        verify(getRequestedFor(urlEqualTo("/api2/json/version"))
                .withHeader("Authorization", equalTo("PVEAPIToken=user@pve!token=test-secret-uuid")));
    }

    @Test
    void testGetVersion() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(okJson("{\"data\":{\"version\":\"8.2.4\",\"release\":\"8.2\"}}")));

        String version = client.getVersion();
        assertEquals("8.2.4", version);
    }

    @Test
    void testGetNodes() {
        stubFor(get(urlEqualTo("/api2/json/nodes"))
                .willReturn(okJson("{\"data\":[{\"node\":\"pve1\",\"status\":\"online\",\"cpu\":0.15,\"maxmem\":34359738368}]}")));

        List<ClusterNode> nodes = client.getNodes();
        assertEquals(1, nodes.size());
        assertEquals("pve1", nodes.get(0).node());
        assertEquals("online", nodes.get(0).status());
    }

    @Test
    void testGetTemplates() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":100,\"name\":\"template-ubuntu\",\"status\":\"stopped\",\"template\":1,\"tags\":\"jenkins;prod\"}," +
                        "{\"vmid\":200,\"name\":\"running-vm\",\"status\":\"running\",\"template\":0}" +
                        "]}")));

        List<VirtualMachine> templates = client.getTemplates("pve1");
        assertEquals(1, templates.size());
        assertEquals(100, templates.get(0).vmid());
        assertTrue(templates.get(0).isTemplate());
        assertEquals(List.of("jenkins", "prod"), templates.get(0).tagList());
    }

    @Test
    void testGetNextVmId() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"300\"}")));

        int id = client.getNextVmId();
        assertEquals(300, id);
    }

    @Test
    void testCloneVm() {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/100/clone"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:0000ABCD:12345678:qmclone:100:user@pve:\"}")));

        CloneOptions opts = new CloneOptions(300, "test-vm", "description", true, "local-lvm", null);
        String upid = client.cloneVm("pve1", 100, opts);
        assertNotNull(upid);

        verify(postRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/100/clone"))
                .withRequestBody(containing("newid=300"))
                .withRequestBody(containing("name=test-vm"))
                .withRequestBody(containing("full=1")));
    }

    @Test
    void testStartVm() {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:start\"}")));

        String upid = client.startVm("pve1", 300);
        assertNotNull(upid);
    }

    @Test
    void testStopVm() {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:stop\"}")));

        String upid = client.stopVm("pve1", 300);
        assertNotNull(upid);
    }

    @Test
    void testDestroyVm() {
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/300"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:destroy\"}")));

        String upid = client.destroyVm("pve1", 300, true);
        assertNotNull(upid);
    }

    @Test
    void testGetVmStatus() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/current"))
                .willReturn(okJson("{\"data\":{\"vmid\":300,\"name\":\"test-vm\",\"status\":\"running\",\"uptime\":3600,\"template\":0}}")));

        VirtualMachine vm = client.getVmStatus("pve1", 300);
        assertEquals(300, vm.vmid());
        assertEquals("running", vm.status());
        assertEquals(3600, vm.uptime());
        assertFalse(vm.isTemplate());
    }

    @Test
    void testGetStoragePools() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage"))
                .willReturn(okJson("{\"data\":[{\"storage\":\"local-lvm\",\"type\":\"lvmthin\",\"avail\":107374182400}]}")));

        List<StoragePool> pools = client.getStoragePools("pve1");
        assertEquals(1, pools.size());
        assertEquals("local-lvm", pools.get(0).storage());
    }

    @Test
    void testGetNetworkInterfaces() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/agent/network-get-interfaces"))
                .willReturn(okJson("{\"data\":{\"result\":[" +
                        "{\"name\":\"eth0\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"10.0.0.50\"}]}," +
                        "{\"name\":\"lo\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}]}" +
                        "]}}")));

        List<NetworkInterface> ifaces = client.getVmNetworkInterfaces("pve1", 300);
        assertEquals(2, ifaces.size());
        assertEquals("eth0", ifaces.get(0).name());
        assertEquals("10.0.0.50", ifaces.get(0).ipAddresses().get(0).ipAddress());
    }

    @Test
    void testWaitForTaskSuccess() {
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .inScenario("task-completion")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("{\"data\":{\"upid\":\"UPID:test\",\"status\":\"running\",\"exitstatus\":null}}"))
                .willSetStateTo("completed"));

        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .inScenario("task-completion")
                .whenScenarioStateIs("completed")
                .willReturn(okJson("{\"data\":{\"upid\":\"UPID:test\",\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));

        client.waitForTask("pve1", "UPID:test", 30);
    }

    @Test
    void testWaitForTaskFailure() {
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"upid\":\"UPID:test\",\"status\":\"stopped\",\"exitstatus\":\"ERROR: something went wrong\"}}")));

        assertThrows(ProxmoxTaskFailedException.class, () -> client.waitForTask("pve1", "UPID:test", 30));
    }

    @Test
    void testAuthenticationFailure() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(aResponse().withStatus(401).withBody("authentication failure")));

        assertThrows(ProxmoxAuthenticationException.class, () -> client.getVersion());
    }

    @Test
    void testNotFound() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/999/status/current"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        assertThrows(ProxmoxResourceNotFoundException.class, () -> client.getVmStatus("pve1", 999));
    }

    @Test
    void testServerError() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        assertThrows(ProxmoxException.class, () -> client.getVersion());
    }

    @Test
    void testConfigureVm() {
        stubFor(put(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":null}")));

        VmConfig config = new VmConfig(4, 8192, "jenkins", null, "ip=dhcp", null, null);
        client.configureVm("pve1", 300, config);

        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .withRequestBody(containing("cores=4"))
                .withRequestBody(containing("memory=8192"))
                .withRequestBody(containing("ciuser=jenkins")));
    }

    @Test
    void testSetNetworkBridge() {
        // A clone inherits the template's net0; setNetworkBridge reads it, swaps only the bridge=
        // component, and writes the result back, preserving the NIC model, MAC, and other options.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"net0\":\"virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1\"}}")));
        stubFor(put(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":null}")));

        client.setNetworkBridge("pve1", 300, "vmbr1");

        // The PUT body is form-url-encoded, so '=' -> %3D, ':' -> %3A, ',' -> %2C.
        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .withRequestBody(containing("net0="))
                .withRequestBody(containing("bridge%3Dvmbr1"))     // new bridge applied
                .withRequestBody(containing("virtio%3DBC%3A24"))   // NIC model + MAC preserved
                .withRequestBody(containing("firewall%3D1")));     // other net0 options preserved
    }

    @Test
    void testSetNetworkBridgeNoNet0DoesNotWrite() {
        // A VM with no net0 device cannot have a bridge applied; the call is a no-op, not a write.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"cores\":2}}")));

        client.setNetworkBridge("pve1", 300, "vmbr1");

        verify(0, putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config")));
    }

    @Test
    void testSetNetworkBridgeNoChangeSkipsWrite() {
        // Already on the requested bridge: skip the PUT to avoid a pointless config write.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"net0\":\"virtio=BC:24:11:2A:3B:4C,bridge=vmbr1\"}}")));

        client.setNetworkBridge("pve1", 300, "vmbr1");

        verify(0, putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config")));
    }

    @Test
    void testReplaceBridgeSwapsOnlyBridgeComponent() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr1,firewall=1",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1", "vmbr1"));
    }

    @Test
    void testReplaceBridgePreservesOrderAndOptions() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr2,tag=10,mtu=1500",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,tag=10,mtu=1500", "vmbr2"));
    }

    @Test
    void testReplaceBridgeAppendsWhenAbsent() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C", "vmbr0"));
    }

    @Test
    void testGetNextVmIdWithMinAboveDefault() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"103\"}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=200"))
                .willReturn(okJson("{\"data\":\"200\"}")));

        int id = client.getNextVmId(200);
        assertEquals(200, id);
    }

    @Test
    void testGetNextVmIdWithMinBelowDefault() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"300\"}")));

        int id = client.getNextVmId(200);
        assertEquals(300, id);
    }

    @Test
    void testGetNextVmIdWithMinSkipsTaken() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"103\"}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=200"))
                .willReturn(aResponse().withStatus(400)
                        .withBody("{\"errors\":{\"vmid\":\"VM 200 already exists\"}}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=201"))
                .willReturn(okJson("{\"data\":\"201\"}")));

        int id = client.getNextVmId(200);
        assertEquals(201, id);
    }

    @Test
    void testGetNetworkDevices() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/network"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"iface\":\"vmbr0\",\"type\":\"bridge\",\"active\":1}," +
                        "{\"iface\":\"eth0\",\"type\":\"eth\",\"active\":1}," +
                        "{\"iface\":\"vmbr1\",\"type\":\"bridge\",\"active\":0}" +
                        "]}")));

        List<NetworkDevice> devices = client.getNetworkDevices("pve1");
        assertEquals(3, devices.size());

        List<NetworkDevice> bridges = devices.stream()
                .filter(NetworkDevice::isBridge).toList();
        assertEquals(2, bridges.size());
        assertEquals("vmbr0", bridges.get(0).iface());
        assertEquals("vmbr1", bridges.get(1).iface());
    }

    @Test
    void testGetPools() {
        stubFor(get(urlEqualTo("/api2/json/pools"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"poolid\":\"dev\",\"comment\":\"Development pool\"}," +
                        "{\"poolid\":\"prod\",\"comment\":null}" +
                        "]}")));

        List<ResourcePool> pools = client.getPools();
        assertEquals(2, pools.size());
        assertEquals("dev", pools.get(0).poolid());
        assertEquals("Development pool", pools.get(0).comment());
        assertEquals("prod", pools.get(1).poolid());
    }

    @Test
    void ignoreSslErrorsBuildsTrustAllClient() {
        // Constructing with ignoreSslErrors=true runs createTrustAllSslContext() to install the
        // trust-all SSLContext on the client. (The no-op X509TrustManager method bodies only execute
        // during a real TLS handshake against a hostname-matching self-signed cert, which needs a
        // keystore fixture; that is left uncovered.)
        ProxmoxClient sslClient = new ProxmoxClient(
                "https://localhost:1", "user@pve!token", Secret.fromString("test-secret-uuid"), true);
        assertNotNull(sslClient);
    }

    @Test
    void getNextVmIdSearchesUpwardWhenDefaultBelowFloor() {
        // The cluster default (100) is below the requested floor (300), so the client probes ?vmid=N.
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid")).willReturn(okJson("{\"data\":\"100\"}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=300")).willReturn(okJson("{\"data\":\"300\"}")));
        assertEquals(300, client.getNextVmId(300));
    }

    @Test
    void replaceBridgeSwapsAppendsAndPreservesOrder() {
        assertEquals("virtio=AA:BB,bridge=vmbr1,firewall=1",
                ProxmoxClient.replaceBridge("virtio=AA:BB,bridge=vmbr0,firewall=1", "vmbr1"));
        assertEquals("virtio=AA:BB,bridge=vmbr1",
                ProxmoxClient.replaceBridge("virtio=AA:BB", "vmbr1")); // no bridge= present -> appended
    }

    @Test
    void resizeVmDiskSendsSizeWithGigabyteSuffix() {
        stubFor(put(urlEqualTo("/api2/json/nodes/pve1/qemu/300/resize")).willReturn(okJson("{\"data\":null}")));
        client.resizeVmDisk("pve1", 300, "scsi0", 20);
        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/resize"))
                .withRequestBody(containing("disk=scsi0"))
                .withRequestBody(containing("size=20G")));
    }

    @Test
    void configureVmSendsOnlyTheSetFields() {
        stubFor(put(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config")).willReturn(okJson("{\"data\":null}")));
        // memory/sshkeys/ipconfig0/nameserver/searchdomain are null -> their branches are skipped.
        client.configureVm("pve1", 300, new VmConfig(2, null, "ubuntu", null, null, null, null));
        verify(putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .withRequestBody(containing("cores=2"))
                .withRequestBody(containing("ciuser=ubuntu")));
    }

    @Test
    void executeWrapsConnectionFailureAsProxmoxException() {
        ProxmoxClient deadClient = new ProxmoxClient("http://localhost:1", "u@pve!t", Secret.fromString("s"), false);
        assertThrows(ProxmoxException.class, deadClient::getVersion);
    }

    @Test
    void getVmCreationTimeReadsMetaCtime() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/config"))
                .willReturn(okJson("{\"data\":{\"meta\":\"creation-qemu=9.0.2,ctime=1700000000\"}}")));

        assertEquals(1700000000L, client.getVmCreationTime("pve1", 9000));
    }

    @Test
    void getVmCreationTimeMissingMetaReturnsMinusOne() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/config"))
                .willReturn(okJson("{\"data\":{\"cores\":2}}")));

        assertEquals(-1, client.getVmCreationTime("pve1", 9000));
    }

    @Test
    void parseCtimeFromMetaHandlesPositionsAndMalformedValues() {
        assertEquals(1700000000L, ProxmoxClient.parseCtimeFromMeta("ctime=1700000000"));
        assertEquals(1700000000L, ProxmoxClient.parseCtimeFromMeta("creation-qemu=9.0.2,ctime=1700000000"));
        assertEquals(1700000000L, ProxmoxClient.parseCtimeFromMeta("ctime=1700000000,creation-qemu=9.0.2"));
        assertEquals(-1, ProxmoxClient.parseCtimeFromMeta("creation-qemu=9.0.2"));
        assertEquals(-1, ProxmoxClient.parseCtimeFromMeta("ctime=notanumber"));
        assertEquals(-1, ProxmoxClient.parseCtimeFromMeta(""));
    }

    @Test
    void tagListSplitsTrimsLowercasesAndMatchesExactly() {
        assertEquals(List.of(), vmWithTags(null).tagList());
        assertEquals(List.of(), vmWithTags("  ").tagList());
        assertEquals(List.of("a", "b"), vmWithTags("a;b").tagList());
        assertEquals(List.of("a", "b"), vmWithTags(" a ; B ;").tagList());

        assertTrue(vmWithTags("Jenkins;prod").hasTag("jenkins"));
        assertTrue(vmWithTags("jenkins").hasTag(" JENKINS "));
        assertFalse(vmWithTags("production").hasTag("prod")); // exact tag, not a prefix
        assertFalse(vmWithTags("prod").hasTag(null));
        assertFalse(vmWithTags("prod").hasTag(" "));
        assertFalse(vmWithTags(null).hasTag("prod"));
    }

    private static VirtualMachine vmWithTags(String tags) {
        return new VirtualMachine(100, "vm", "stopped", "pve1", 0, 1, tags);
    }
}
