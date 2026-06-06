package org.jenkinsci.plugins.proxmox.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.model.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.*;

public class ProxmoxClientTest {

    private WireMockServer wireMock;
    private ProxmoxClient client;

    @Before
    public void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        client = new ProxmoxClient(
                "http://localhost:" + wireMock.port(),
                "user@pve!token",
                Secret.fromString("test-secret-uuid"),
                false);
    }

    @After
    public void tearDown() {
        wireMock.stop();
    }

    @Test
    public void testAuthHeader() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(okJson("{\"data\":{\"version\":\"8.2.4\"}}")));

        client.getVersion();

        verify(getRequestedFor(urlEqualTo("/api2/json/version"))
                .withHeader("Authorization", equalTo("PVEAPIToken=user@pve!token=test-secret-uuid")));
    }

    @Test
    public void testGetVersion() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(okJson("{\"data\":{\"version\":\"8.2.4\",\"release\":\"8.2\"}}")));

        String version = client.getVersion();
        assertEquals("8.2.4", version);
    }

    @Test
    public void testGetNodes() {
        stubFor(get(urlEqualTo("/api2/json/nodes"))
                .willReturn(okJson("{\"data\":[{\"node\":\"pve1\",\"status\":\"online\",\"cpu\":0.15,\"maxmem\":34359738368}]}")));

        List<ClusterNode> nodes = client.getNodes();
        assertEquals(1, nodes.size());
        assertEquals("pve1", nodes.get(0).node());
        assertEquals("online", nodes.get(0).status());
    }

    @Test
    public void testGetTemplates() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":100,\"name\":\"template-ubuntu\",\"status\":\"stopped\",\"template\":1}," +
                        "{\"vmid\":200,\"name\":\"running-vm\",\"status\":\"running\",\"template\":0}" +
                        "]}")));

        List<VirtualMachine> templates = client.getTemplates("pve1");
        assertEquals(1, templates.size());
        assertEquals(100, templates.get(0).vmid());
        assertTrue(templates.get(0).isTemplate());
    }

    @Test
    public void testGetNextVmId() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"300\"}")));

        int id = client.getNextVmId();
        assertEquals(300, id);
    }

    @Test
    public void testCloneVm() {
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
    public void testStartVm() {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:start\"}")));

        String upid = client.startVm("pve1", 300);
        assertNotNull(upid);
    }

    @Test
    public void testStopVm() {
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:stop\"}")));

        String upid = client.stopVm("pve1", 300);
        assertNotNull(upid);
    }

    @Test
    public void testDestroyVm() {
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/300"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:00001234:destroy\"}")));

        String upid = client.destroyVm("pve1", 300, true);
        assertNotNull(upid);
    }

    @Test
    public void testGetVmStatus() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/status/current"))
                .willReturn(okJson("{\"data\":{\"vmid\":300,\"name\":\"test-vm\",\"status\":\"running\",\"uptime\":3600,\"template\":0}}")));

        VirtualMachine vm = client.getVmStatus("pve1", 300);
        assertEquals(300, vm.vmid());
        assertEquals("running", vm.status());
        assertEquals(3600, vm.uptime());
        assertFalse(vm.isTemplate());
    }

    @Test
    public void testGetStoragePools() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/storage"))
                .willReturn(okJson("{\"data\":[{\"storage\":\"local-lvm\",\"type\":\"lvmthin\",\"avail\":107374182400}]}")));

        List<StoragePool> pools = client.getStoragePools("pve1");
        assertEquals(1, pools.size());
        assertEquals("local-lvm", pools.get(0).storage());
    }

    @Test
    public void testGetNetworkInterfaces() {
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
    public void testWaitForTaskSuccess() {
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

    @Test(expected = ProxmoxTaskFailedException.class)
    public void testWaitForTaskFailure() {
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"upid\":\"UPID:test\",\"status\":\"stopped\",\"exitstatus\":\"ERROR: something went wrong\"}}")));

        client.waitForTask("pve1", "UPID:test", 30);
    }

    @Test(expected = ProxmoxAuthenticationException.class)
    public void testAuthenticationFailure() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(aResponse().withStatus(401).withBody("authentication failure")));

        client.getVersion();
    }

    @Test(expected = ProxmoxResourceNotFoundException.class)
    public void testNotFound() {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/999/status/current"))
                .willReturn(aResponse().withStatus(404).withBody("not found")));

        client.getVmStatus("pve1", 999);
    }

    @Test(expected = ProxmoxException.class)
    public void testServerError() {
        stubFor(get(urlEqualTo("/api2/json/version"))
                .willReturn(aResponse().withStatus(500).withBody("internal error")));

        client.getVersion();
    }

    @Test
    public void testConfigureVm() {
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
    public void testSetNetworkBridge() {
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
    public void testSetNetworkBridgeNoNet0DoesNotWrite() {
        // A VM with no net0 device cannot have a bridge applied; the call is a no-op, not a write.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"cores\":2}}")));

        client.setNetworkBridge("pve1", 300, "vmbr1");

        verify(0, putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config")));
    }

    @Test
    public void testSetNetworkBridgeNoChangeSkipsWrite() {
        // Already on the requested bridge: skip the PUT to avoid a pointless config write.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config"))
                .willReturn(okJson("{\"data\":{\"net0\":\"virtio=BC:24:11:2A:3B:4C,bridge=vmbr1\"}}")));

        client.setNetworkBridge("pve1", 300, "vmbr1");

        verify(0, putRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu/300/config")));
    }

    @Test
    public void testReplaceBridgeSwapsOnlyBridgeComponent() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr1,firewall=1",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,firewall=1", "vmbr1"));
    }

    @Test
    public void testReplaceBridgePreservesOrderAndOptions() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr2,tag=10,mtu=1500",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0,tag=10,mtu=1500", "vmbr2"));
    }

    @Test
    public void testReplaceBridgeAppendsWhenAbsent() {
        assertEquals("virtio=BC:24:11:2A:3B:4C,bridge=vmbr0",
                ProxmoxClient.replaceBridge("virtio=BC:24:11:2A:3B:4C", "vmbr0"));
    }

    @Test
    public void testGetNextVmIdWithMinAboveDefault() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"103\"}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=200"))
                .willReturn(okJson("{\"data\":\"200\"}")));

        int id = client.getNextVmId(200);
        assertEquals(200, id);
    }

    @Test
    public void testGetNextVmIdWithMinBelowDefault() {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid"))
                .willReturn(okJson("{\"data\":\"300\"}")));

        int id = client.getNextVmId(200);
        assertEquals(300, id);
    }

    @Test
    public void testGetNextVmIdWithMinSkipsTaken() {
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
    public void testGetNetworkDevices() {
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
    public void testGetPools() {
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
}
