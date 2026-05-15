package org.jenkinsci.plugins.proxmox.config;

import com.cloudbees.plugins.credentials.CredentialsNameProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

public class ProxmoxTokenCredentialsImpl extends BaseStandardCredentials implements ProxmoxTokenCredentials {

    private final String tokenId;
    private final Secret tokenSecret;

    @DataBoundConstructor
    public ProxmoxTokenCredentialsImpl(CredentialsScope scope, String id, String description,
                                        String tokenId, Secret tokenSecret) {
        super(scope, id, description);
        this.tokenId = tokenId;
        this.tokenSecret = tokenSecret;
    }

    @Override
    public String getTokenId() {
        return tokenId;
    }

    @Override
    public Secret getTokenSecret() {
        return tokenSecret;
    }

    @Extension
    @Symbol("proxmoxToken")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox API Token";
        }
    }

    @Extension
    public static class NameProvider extends CredentialsNameProvider<ProxmoxTokenCredentialsImpl> {

        @Override
        public String getName(ProxmoxTokenCredentialsImpl credentials) {
            String description = credentials.getDescription();
            if (description != null && !description.isBlank()) {
                return description + " (" + credentials.getTokenId() + ")";
            }
            return credentials.getTokenId();
        }
    }
}
