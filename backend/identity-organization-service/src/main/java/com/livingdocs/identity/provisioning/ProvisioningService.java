package com.livingdocs.identity.provisioning;

public interface ProvisioningService {
    /** Applies exactly one manifest atomically, or reports a matching prior operation. */
    String apply(ProvisioningManifest manifest, String requestSha256);
}
