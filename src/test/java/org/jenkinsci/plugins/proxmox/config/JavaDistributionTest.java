package org.jenkinsci.plugins.proxmox.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDistributionTest {

    @Test
    void noneHasNoPackageOrCommand() {
        assertNull(JavaDistribution.NONE.getPackageName(21));
        assertNull(JavaDistribution.NONE.getInstallCommand(21));
    }

    @Test
    void openjdkPackageNameIsParameterisedByVersion() {
        assertEquals("openjdk-21-jre-headless", JavaDistribution.OPENJDK.getPackageName(21));
        // A version newer than any previously hardcoded constant works without a release.
        assertEquals("openjdk-26-jre-headless", JavaDistribution.OPENJDK.getPackageName(26));
    }

    @Test
    void correttoPackageNameIsParameterisedByVersion() {
        assertEquals("java-21-amazon-corretto-jdk", JavaDistribution.CORRETTO.getPackageName(21));
        assertEquals("java-26-amazon-corretto-jdk", JavaDistribution.CORRETTO.getPackageName(26));
    }

    @Test
    void openjdkInstallCommandInstallsTheVersionedPackage() {
        String cmd = JavaDistribution.OPENJDK.getInstallCommand(26);
        assertTrue(cmd.contains("cloud-init status --wait"));
        assertTrue(cmd.contains("apt-get install -y -qq openjdk-26-jre-headless"));
        // OpenJDK comes from the distro's own repos, not the Corretto repo.
        assertTrue(!cmd.contains("apt.corretto.aws"));
    }

    @Test
    void correttoInstallCommandConfiguresRepoThenInstalls() {
        String cmd = JavaDistribution.CORRETTO.getInstallCommand(26);
        assertTrue(cmd.contains("https://apt.corretto.aws/corretto.key"));
        assertTrue(cmd.contains("https://apt.corretto.aws stable main"));
        assertTrue(cmd.contains("apt-get install -y -qq java-26-amazon-corretto-jdk"));
    }

    @Test
    void recommendedMinMajorVersionIs21() {
        assertEquals(21, JavaDistribution.RECOMMENDED_MIN_MAJOR_VERSION);
    }
}
