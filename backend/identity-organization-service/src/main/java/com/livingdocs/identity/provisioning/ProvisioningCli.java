package com.livingdocs.identity.provisioning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Activated only with the operator profile and a read-only mounted manifest. */
// khởi chạy, đọc manifest JSON, tính SHA-256
@Component
@Profile("provision") // Bean chỉ tạo khi profile provision đươc kích hoạt
// spring boot tự gọi run() sau khi application context khởi tao xong
public class ProvisioningCli implements ApplicationRunner {
    private final ProvisioningService service;
    private final String manifestPath;

    public ProvisioningCli(ProvisioningService service, @Value("${livingdocs.provisioning.manifest:}") String manifestPath) {
        this.service = service;
        this.manifestPath = manifestPath;
    }

    @Override
    public void run(ApplicationArguments ignored) throws Exception {
        // kiểm tra được dẫn manifest
        if (manifestPath.isBlank()) {
            throw new IllegalArgumentException("livingdocs.provisioning.manifest is required");
        }

        byte[] bytes = Files.readAllBytes(Path.of(manifestPath));
        if (bytes.length == 0 || bytes.length > 16_384) {
            throw new IllegalArgumentException("Provisioning manifest must be 1..16384 bytes");
        }

        // Dùng Jackson ObjectMapper chuyển JSON thành ProvisioningManifest
        ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ProvisioningManifest manifest = json.readValue(bytes, ProvisioningManifest.class);

        // Tính SHA-256 của nội dung bytes gốc
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));

        System.out.println("Provisioning " + service.apply(manifest, digest)
                + " operationId=" + manifest.getOperationId());
    }
}
