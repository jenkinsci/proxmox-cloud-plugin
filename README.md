# Jenkins Proxmox Cloud Plugin

A Jenkins cloud provider plugin that dynamically provisions QEMU virtual machines on Proxmox VE as build agents. VMs are cloned from templates, configured via cloud-init, connected over SSH, and destroyed when idle.

## Setup

### 1. Proxmox API Token

Create a dedicated user, role, and API token on your Proxmox host.

Proxmox v8.x
```bash
# Create a role with minimum required privileges
pveum role add JenkinsProvisioner -privs "VM.Allocate VM.Clone VM.Audit VM.Config.Disk VM.Config.CPU VM.Config.Memory VM.Config.Network VM.Config.Options VM.Config.Cloudinit VM.PowerMgmt VM.Monitor Datastore.AllocateSpace Datastore.Audit SDN.Use"

# Create a user
pveum user add jenkins@pve

# Assign the role at the root path (or scope to a specific /pool/... path)
pveum aclmod / -user jenkins@pve -role JenkinsProvisioner

# Create an API token (--privsep 0 = token inherits user permissions)
pveum user token add jenkins@pve jenkins-token --privsep 0
```

Proxmox v9.x
```bash
# Create a role with minimum required privileges
pveum role add JenkinsProvisioner -privs "VM.Allocate VM.Clone VM.Audit VM.Config.Disk VM.Config.CPU VM.Config.Memory VM.Config.Network VM.Config.Options VM.Config.Cloudinit VM.PowerMgmt VM.GuestAgent.Audit Datastore.AllocateSpace Datastore.Audit SDN.Use"

# Create a user
pveum user add jenkins@pve

# Assign the role at the root path (or scope to a specific /pool/... path)
pveum aclmod / -user jenkins@pve -role JenkinsProvisioner

# Create an API token (--privsep 0 = token inherits user permissions)
pveum user token add jenkins@pve jenkins-token --privsep 0
```

Save the output. You will need:
- **Token ID**: `jenkins@pve!jenkins-token`
- **Token Secret**: the UUID printed by the command

#### Privilege Reference

Proxmox v8.x

| Privilege                 | Purpose                                                              |
|---------------------------|----------------------------------------------------------------------|
| `VM.Allocate`             | Create and remove VMs (includes reserving VM IDs and destroying VMs) |
| `VM.Clone`                | Clone templates                                                      |
| `VM.Audit`                | Read VM config and status                                            |
| `VM.Config.Disk`          | Configure/resize disks on cloned VMs                                 |
| `VM.Config.CPU`           | Override CPU cores                                                   |
| `VM.Config.Memory`        | Override memory                                                      |
| `VM.Config.Network`       | Configure network interfaces                                         |
| `VM.Config.Options`       | Set general VM options                                               |
| `VM.Config.Cloudinit`     | Inject cloud-init parameters                                         |
| `VM.PowerMgmt`            | Start, stop, shutdown VMs                                            |
| `VM.Monitor`        | ??                                                                   |
| `Datastore.AllocateSpace` | Allocate disk space for clones                                       |
| `Datastore.Audit`         | List available storage pools                                         |
| `SDN.Use`                 | Use network bridges                                                  |

Proxmox v9.x

| Privilege | Purpose |
|---|---|
| `VM.Allocate` | Create and remove VMs (includes reserving VM IDs and destroying VMs) |
| `VM.Clone` | Clone templates |
| `VM.Audit` | Read VM config and status |
| `VM.Config.Disk` | Configure/resize disks on cloned VMs |
| `VM.Config.CPU` | Override CPU cores |
| `VM.Config.Memory` | Override memory |
| `VM.Config.Network` | Configure network interfaces |
| `VM.Config.Options` | Set general VM options |
| `VM.Config.Cloudinit` | Inject cloud-init parameters |
| `VM.PowerMgmt` | Start, stop, shutdown VMs |
| `VM.GuestAgent.Audit` | Query guest agent for IP address |
| `Datastore.AllocateSpace` | Allocate disk space for clones |
| `Datastore.Audit` | List available storage pools |
| `SDN.Use` | Use network bridges |

### 2. VM Template with Cloud-Init

Create a VM template that has cloud-init and the QEMU guest agent. The example below uses Ubuntu 24.04, but any cloud-init-enabled image works.

```bash
# Download a cloud image
wget https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-amd64.img

# Create a VM (adjust storage and bridge to match your setup)
qm create 9000 --name ubuntu-template --memory 2048 --cores 2 --net0 virtio,bridge=vmbr0

# Import the cloud image as a disk
qm importdisk 9000 noble-server-cloudimg-amd64.img local-lvm

# Attach the disk
qm set 9000 --scsihw virtio-scsi-pci --scsi0 local-lvm:vm-9000-disk-0

# Add a cloud-init drive
qm set 9000 --ide2 local-lvm:cloudinit

# Set boot order to the imported disk
qm set 9000 --boot order=scsi0

# Enable the QEMU guest agent (required for IP discovery)
qm set 9000 --agent enabled=1

# Add serial console (needed for cloud-init on some images)
qm set 9000 --serial0 socket --vga serial0

# Convert to template
qm template 9000
```

**Note:** The default cloud image disk is ~3.5GB. You do not need to resize it here - the plugin can resize the disk at clone time via the "Disk Size GB" template setting. Set it to at least 10GB if you plan to install Java automatically.

### 3. SSH Key Pair

Generate a key pair for Jenkins to connect to provisioned VMs:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/jenkins-proxmox -N "" -C "jenkins-agent"
```

Add the **private key** (`~/.ssh/jenkins-proxmox`) as a Jenkins SSH credential (see step 4). The plugin automatically derives the public key from the credential and injects it into VMs via cloud-init at provision time - you do not need to configure the public key separately.

### 4. Jenkins Credentials

You need two credentials in Jenkins. Add both via **Manage Jenkins → Credentials → System → Global credentials → Add Credentials**.

#### Proxmox API Token credential

| Field | Value |
|---|---|
| Kind | **Proxmox API Token** (added by this plugin) |
| Scope | Global |
| ID | e.g. `proxmox-api-token` |
| Description | e.g. `Proxmox API Token` |
| Token ID | `jenkins@pve!jenkins-token` (from step 1) |
| Token Secret | The UUID secret (from step 1) |

#### SSH credential for agent connection

| Field | Value |
|---|---|
| Kind | **SSH Username with private key** |
| Scope | Global |
| Username | `ubuntu` (the default user in Ubuntu cloud images) |
| Private Key | Enter directly → paste contents of `~/.ssh/jenkins-proxmox` (from step 3) |
| ID | e.g. `proxmox-ssh-key` |
| Description | e.g. `Proxmox Agent SSH Key` |

### 5. Jenkins Cloud Configuration

Go to **Manage Jenkins → Clouds → New cloud → Proxmox VE**.

#### Cloud Settings

| Field | Value |
|---|---|
| Cloud Name | e.g. `proxmox` |
| API URL | `https://<proxmox-host>:8006` |
| Credentials | Select the **Proxmox API Token** credential created above |
| Ignore SSL Errors | Check if using self-signed certs (Proxmox default) |

Click **Test Connection** - it should report the Proxmox VE version.

#### Template Settings

| Field | Value |
|---|---|
| Name | e.g. `ubuntu-agent` |
| Proxmox Node | Your node name (e.g. `pve`, `node1`) |
| Template VM ID | `9000` (or your template ID) |
| Labels | e.g. `linux ubuntu` |
| Number of Executors | `1` |
| Clone Strategy | Full Clone |
| SSH Credentials | Select the **SSH Username with private key** credential |
| Usage | Only build jobs with label expressions matching this node |

Under **Advanced → Proxmox Resources**:

| Field | Value | Description |
|---|---|---|
| Target Storage | *(blank)* | Storage pool for clone disk. Blank = same as template |
| CPU Cores | `0` | 0 = inherit from template |
| Memory MB | `0` | 0 = inherit from template |
| Disk Size GB | `10` | Resize scsi0 after clone. 0 = keep template size |

Under **Advanced → Agent Settings**:

| Field | Value | Description |
|---|---|---|
| Remote FS Root | `/home/ubuntu/agent` | Must be writable by SSH user |
| Java Version | OpenJDK 21 | Auto-installs Java if not present |

Under **Advanced → Cloud-Init**:

| Field | Value |
|---|---|
| User | `ubuntu` (must match the cloud image's default user) |
| IP Configuration | `ip=dhcp` |

The SSH public key is automatically derived from the SSH credential selected above and injected into the VM via cloud-init.

#### Lifecycle Settings (Advanced)

| Field | Default | Description |
|---|---|---|
| Idle Termination (minutes) | 30 | VM destroyed after this idle time |
| Instance Cap | 0 (unlimited) | Max VMs from this template |
| Max Total Uses | 0 (unlimited) | Destroy after N builds |
| Startup Wait (seconds) | 60 | Time to wait for VM boot + SSH |

### 6. Test It

You can manually provision an agent from **Manage Jenkins → Nodes** using the "Provision via" button, or create a freestyle job with a matching label expression:

```bash
hostname
ip addr
java -version
cat /etc/os-release
```

Run it. The plugin will clone the template, resize the disk, boot the VM, install Java, SSH in, execute the build, then terminate after idle timeout.

## How It Works

1. Jenkins detects demand for executors matching a label
2. `ProxmoxCloud.provision()` finds a matching template and submits a provisioning task
3. The task clones the VM template via the Proxmox API (`POST /nodes/{node}/qemu/{id}/clone`)
4. If disk size is configured, the disk is resized (`PUT /nodes/{node}/qemu/{id}/resize`)
5. Cloud-init parameters are applied (SSH keys, user, network config)
6. The VM is started and the plugin waits for the QEMU guest agent to report an IP
7. If Java auto-install is configured, Java is installed via SSH before agent launch
8. `ProxmoxLauncher` connects via SSH using the `ssh-slaves` plugin
9. The Jenkins agent runs builds
10. `ProxmoxRetentionStrategy` monitors idle time and terminates the agent when expired
11. `ProxmoxAgent._terminate()` gracefully shuts down the VM, then destroys it with disk purge

## Java Auto-Installation

The plugin can automatically install a JRE on provisioned agents, eliminating the need to bake Java into your template image. Supported options:

| Option | Package |
|---|---|
| OpenJDK 21 | `openjdk-21-jre-headless` |
| OpenJDK 25 | `openjdk-25-jre-headless` |
| Amazon Corretto 21 | `java-21-amazon-corretto-jdk` |
| Amazon Corretto 25 | `java-25-amazon-corretto-jdk` |

The install process:
1. Waits for cloud-init to finish (avoids apt lock conflicts)
2. Cleans apt cache to free disk space
3. Installs the selected JRE
4. Removes unneeded packages and cleans up

Requirements: the SSH user must have passwordless `sudo` access (default for Ubuntu cloud images).

## Manual Provisioning

The **Nodes** page (`/computer/`) shows a "Provision via [cloud name]" button for each configured template. Clicking it immediately clones and starts a VM without waiting for demand.

## Orphan Cleanup

If enabled (`Cleanup Orphaned Agents` checkbox), a background task runs every 15 minutes to find Proxmox VMs tagged as `jenkins-managed` (via VM description) that have no corresponding Jenkins node, and destroys them. This prevents resource leaks after Jenkins crashes.

## Configuration as Code (Config Sync)

Cloud and agent template configuration can be managed from a YAML file stored in a git repository, with automatic or on-demand sync into Jenkins.

### Setup

Go to **Manage Jenkins → System → Proxmox Cloud Config Sync** and enable the checkbox. Configure:

| Field | Description |
|---|---|
| Git Repository URL | URL of the git repo containing the YAML config |
| Git Credentials | Jenkins credentials for git authentication (optional for public repos) |
| Branch | Git branch to use (default: `main`) |
| YAML File Path | Path to the YAML file within the repo (default: `proxmox-cloud.yaml`) |
| Sync Schedule (cron) | Jenkins cron expression for automatic sync (leave blank to disable) |
| Allow manual changes | When unchecked, config-managed clouds are displayed as read-only in the UI |

Use **Test Git Connection** to verify connectivity and file existence before syncing. Use **Sync Now** to trigger an immediate sync.

### YAML Structure

```yaml
cloudDefaults:
  apiUrl: "https://proxmox.example.com:8006"
  credentialsId: "proxmox-api-token"
  ignoreSslErrors: true
  instanceCap: 10
  operationTimeoutSec: 300
  cleanupOrphanedAgents: true
  orphanCleanupGracePeriodSeconds: 300   # grace before reaping orphaned VMs / dead agent nodes

cloudConfigurations:
  myCluster:
    name: "Proxmox Production"
    # inherits all cloudDefaults, override as needed

agentDefaults:
  cloneStrategy: FULL
  remoteFs: "/home/ubuntu/agent"
  mode: EXCLUSIVE
  credentialsId: "ssh-key"
  javaVersion: OPENJDK_21
  idleTerminationMinutes: 30

agentDefaults-pve1:          # per-node overrides
  templateVmId: 9000
  targetStorage: "local-lvm"

agentConfigurations:
  linux-builder:
    cloudIds: ["myCluster"]  # links to cloudConfigurations key
    node: "pve1"             # selects agentDefaults-pve1
    name: "linux-builder"
    labelString: "linux docker"
    numExecutors: 2
```

Configuration inherits in three levels: `agentDefaults` → `agentDefaults-{node}` → specific agent config. Cloud defaults work similarly. Only clouds defined in the YAML are managed; manually-created clouds are left untouched.

### Behavior

- **All-or-nothing**: if any cloud or template in the YAML is invalid, no changes are applied
- **Config-managed clouds** display a green "Config Managed" badge in the UI with the last sync timestamp
- **Manual edits** to a managed cloud are tracked with an amber warning that clears on the next sync
- **Read-only protection**: when "Allow manual changes" is unchecked, managed cloud forms are greyed out

## Building from Source

```bash
mvn clean verify    # Run tests and build HPI
mvn hpi:run         # Start Jenkins locally with the plugin at http://localhost:8080/jenkins/
```

The built plugin is at `target/proxmox-cloud.hpi`.

## Contributing

Found a bug or have a feature request? Please open an issue on the [GitHub issue tracker](https://github.com/jenkinsci/proxmox-cloud-plugin/issues).

Pull requests are welcome. To build and test locally:

```bash
mvn clean verify
```

## License

MIT License
