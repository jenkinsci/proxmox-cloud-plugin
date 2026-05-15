package org.jenkinsci.plugins.proxmox.api.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public record NetworkInterface(String name,
                               @SerializedName("ip-addresses") List<IpAddress> ipAddresses) {

    public record IpAddress(@SerializedName("ip-address-type") String ipAddressType,
                            @SerializedName("ip-address") String ipAddress) {
    }
}
