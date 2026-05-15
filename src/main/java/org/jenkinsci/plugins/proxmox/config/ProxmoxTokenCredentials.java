package org.jenkinsci.plugins.proxmox.config;

import com.cloudbees.plugins.credentials.common.StandardCredentials;
import hudson.util.Secret;

public interface ProxmoxTokenCredentials extends StandardCredentials {

    String getTokenId();

    Secret getTokenSecret();
}
