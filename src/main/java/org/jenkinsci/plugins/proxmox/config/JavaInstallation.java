package org.jenkinsci.plugins.proxmox.config;

public enum JavaInstallation {
    NONE("None - pre-installed", null),
    OPENJDK_21("OpenJDK 21", "openjdk-21-jre-headless"),
    OPENJDK_25("OpenJDK 25", "openjdk-25-jre-headless"),
    CORRETTO_21("Amazon Corretto 21", "java-21-amazon-corretto-jdk"),
    CORRETTO_25("Amazon Corretto 25", "java-25-amazon-corretto-jdk");

    private final String displayName;
    private final String packageName;

    JavaInstallation(String displayName, String packageName) {
        this.displayName = displayName;
        this.packageName = packageName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getInstallCommand() {
        if (this == NONE) return null;
        String preamble = "cloud-init status --wait >/dev/null 2>&1; "
                + "apt-get clean && rm -rf /var/lib/apt/lists/* && ";
        String cleanup = " && apt-get autoremove -y -qq && apt-get clean && rm -rf /var/lib/apt/lists/*";
        if (this == CORRETTO_21 || this == CORRETTO_25) {
            return preamble
                    + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gnupg software-properties-common && "
                    + "curl -fsSL https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && "
                    + "echo 'deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main' > /etc/apt/sources.list.d/corretto.list && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq " + packageName + cleanup;
        }
        return preamble
                + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq " + packageName + cleanup;
    }
}
