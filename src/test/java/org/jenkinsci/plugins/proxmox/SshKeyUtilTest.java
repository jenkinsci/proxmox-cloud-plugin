package org.jenkinsci.plugins.proxmox;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class SshKeyUtilTest {

    private static final String RSA_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABFwAAAAdzc2gtcn
            NhAAAAAwEAAQAAAQEArJ3d7XA5y2I8MXvWoXZA2CqIWct1innY+990K0h2KpJIY9qI7p/w
            N4BXi/sN6F6HfoemPqZR1/nAcQe3dwvKH1DyYeFCtWGumCsueXTY1d3hqzhEpkI7gI82Zo
            73Zt1TZtk0cEGC3jdn8alsYp46kImpnZ3ZL/SkT8OKjuF3q1zIsjGsIsiA0RCiyKmzXgRP
            Bgn9niXAIO7unlvVbLoU0XrTmpVVETGp5OSdQ6TpiT6iSq2/4AMDS/Rf6zOyVAcRJ0sA0X
            t8d6YOpOUcuWqy/2BlUmH/oywGnBKiZ0F6w1l1s7CwNWaBM7eTleDEoapW8nFcjZJVDWKI
            XOzOFvdPZwAAA9C5JkU6uSZFOgAAAAdzc2gtcnNhAAABAQCsnd3tcDnLYjwxe9ahdkDYKo
            hZy3WKedj733QrSHYqkkhj2ojun/A3gFeL+w3oXod+h6Y+plHX+cBxB7d3C8ofUPJh4UK1
            Ya6YKy55dNjV3eGrOESmQjuAjzZmjvdm3VNm2TRwQYLeN2fxqWxinjqQiamdndkv9KRPw4
            qO4XerXMiyMawiyIDREKLIqbNeBE8GCf2eJcAg7u6eW9VsuhTRetOalVURMank5J1DpOmJ
            PqJKrb/gAwNL9F/rM7JUBxEnSwDRe3x3pg6k5Ry5arL/YGVSYf+jLAacEqJnQXrDWXWzsL
            A1ZoEzt5OV4MShqlbycVyNklUNYohc7M4W909nAAAAAwEAAQAAAQAH6LssmsSd0TRL2iOV
            RgCmRvCfbfyb6dsA02tpuPS9HPMXLCyzrlKzNadilmVmkmo9PcQ8Fk0lYF//wyhAr/gaDj
            tE3oLld60oc2tk4kSLLBL1JZYI/iY8Ij5jsu+atXpm+dMhb5xU8o9J2dxJpSauzQ1KAluw
            tadlBjh439N8ClLdjUjN7uf9fQ/rNsdxVYCtQsS0bKvZ1IsFrtgykUZXDMkobj9MlkWyBZ
            t80EiH2m3ckUUTQqH+5EghvDQBnZQ6wS5Ji6ADygYyCkRSdVqFqtxnj0Cn102PWQmZ5pwu
            Xe2acEL1QhP4XjvLSEQTshfFp2veqoiIhsTzgSI5k78NAAAAgEJFa5bLCHzgJXkQO/6IuY
            FzeWHj6AHPxRYE5RzwnCEhyEeXO8PofVaCq1oyCt81E8rvlYV24xJlTKlCx9umZwZn+wuJ
            B5aZNjYfbIqTBF/d9GxVnL19ecawr/sbilX53LYabQenck5N5KqqcPs1j8LHlgnjCxzsOB
            i5+kx4jBazAAAAgQDV0SgYqjqMh5rRHMGTEUgzDq6mw5LxGzyRNAnSZRMa1FHuje+HrxxE
            tEmeA3xnqLhTFT8WRfgE2BCytQToFOnk+MPKU65zqlqhxTJaGwmnss2j37n0RbSup7i8nh
            H73SBbpLC5TMIatkygopOGG8YIbTkXsCxSArt1n+e6WOz2SwAAAIEAzqviOtxIR+LliVqE
            HHtt+V+uuC6O3RMazEgTBSsw2jgAeztyAXya9l1SuvSkzhTYSkOsKQYRpng99RQNl4W5dE
            b18+PBrSmXeNxOOwE+P5mUqu8G4k7SCFgLyC4UFHaPm4a/vD0q0xemsopg+mGAuE/y3wXb
            9NPk/z+7zAbZSdUAAAAVYWlkYW5AZGV2dm0tbWludC16ZW5hAQIDBAUG
            -----END OPENSSH PRIVATE KEY-----
            """;

    private static final String RSA_EXPECTED_PREFIX = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCsnd3tcDnLYjwxe9ahdkDYKo";

    private static final String ED25519_PRIVATE_KEY = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACCYkH+cncLgXimu2JaqlPPMiwNlYmUIndhEY2I6O8hJPwAAAJiebpLRnm6S
            0QAAAAtzc2gtZWQyNTUxOQAAACCYkH+cncLgXimu2JaqlPPMiwNlYmUIndhEY2I6O8hJPw
            AAAEBiXpGHptX4sO8tB40acURrf752p4SN6CNwh26d36hKl5iQf5ydwuBeKa7YlqqU88yL
            A2ViZQid2ERjYjo7yEk/AAAAFWFpZGFuQGRldnZtLW1pbnQtemVuYQ==
            -----END OPENSSH PRIVATE KEY-----
            """;

    private static final String ED25519_EXPECTED = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJiQf5ydwuBeKa7YlqqU88yLA2ViZQid2ERjYjo7yEk/";

    @Test
    public void testDeriveRsaPublicKey() throws IOException {
        String publicKey = SshKeyUtil.deriveOpenSshPublicKey(RSA_PRIVATE_KEY, null);
        assertNotNull(publicKey);
        assertTrue("Should start with ssh-rsa", publicKey.startsWith("ssh-rsa "));
        assertTrue("Public key should match expected", publicKey.startsWith(RSA_EXPECTED_PREFIX));
    }

    @Test
    public void testDeriveEd25519PublicKey() throws IOException {
        String publicKey = SshKeyUtil.deriveOpenSshPublicKey(ED25519_PRIVATE_KEY, null);
        assertEquals(ED25519_EXPECTED, publicKey);
    }

    @Test(expected = IOException.class)
    public void testInvalidPemThrows() throws IOException {
        SshKeyUtil.deriveOpenSshPublicKey("not a valid PEM key", null);
    }
}
