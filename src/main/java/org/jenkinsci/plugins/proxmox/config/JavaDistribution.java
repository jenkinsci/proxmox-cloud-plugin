package org.jenkinsci.plugins.proxmox.config;

public enum JavaDistribution {
    NONE("None - pre-installed"),
    OPENJDK("OpenJDK"),
    CORRETTO("Amazon Corretto");

    /**
     * Recommended minimum Java major version. Older versions are allowed (the field warns rather
     * than blocks), but this is the floor the plugin is tested against and the default for new
     * templates.
     */
    public static final int RECOMMENDED_MIN_MAJOR_VERSION = 21;

    private static final String PREAMBLE = "cloud-init status --wait >/dev/null 2>&1; "
            + "apt-get clean && rm -rf /var/lib/apt/lists/* && ";
    private static final String CLEANUP =
            " && apt-get autoremove -y -qq && apt-get clean && rm -rf /var/lib/apt/lists/*";

    private final String displayName;

    JavaDistribution(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Apt package name for the given major version, or {@code null} for {@link #NONE}. */
    public String getPackageName(int majorVersion) {
        return switch (this) {
            case NONE -> null;
            case OPENJDK -> "openjdk-" + majorVersion + "-jre-headless";
            case CORRETTO -> "java-" + majorVersion + "-amazon-corretto-jdk";
        };
    }

    /**
     * Shell command that installs the JRE for the given major version, or {@code null} for
     * {@link #NONE}. Corretto packages live in Amazon's apt repository, so its key and source list
     * are configured first; OpenJDK is installed from the agent distro's own repositories.
     */
    public String getInstallCommand(int majorVersion) {
        if (this == NONE) {
            return null;
        }
        String packageName = getPackageName(majorVersion);
        if (this == CORRETTO) {
            return PREAMBLE
                    + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq gnupg software-properties-common && "
                    + "curl -fsSL https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto-keyring.gpg && "
                    + "echo 'deb [signed-by=/usr/share/keyrings/corretto-keyring.gpg] https://apt.corretto.aws stable main' > /etc/apt/sources.list.d/corretto.list && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq " + packageName + CLEANUP;
        }
        return PREAMBLE
                + "DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
                + "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq " + packageName + CLEANUP;
    }
}
