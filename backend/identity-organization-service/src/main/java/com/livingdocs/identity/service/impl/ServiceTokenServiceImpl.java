package com.livingdocs.identity.service.impl;

import com.livingdocs.identity.dto.request.ServiceTokenRequest;
import com.livingdocs.identity.dto.response.ServiceTokenResponse;
import com.livingdocs.identity.security.ServiceJwtIssuer;
import com.livingdocs.identity.security.WorkloadCredentialRegistry;
import com.livingdocs.identity.security.WorkloadCredentialRegistry.InvalidWorkloadException;
import com.livingdocs.identity.service.ServiceTokenService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ServiceTokenServiceImpl implements ServiceTokenService {
    private static final Set<String> DOCUMENT_ACTIONS = Set.of("DOCUMENT_AUTHOR", "DOCUMENT_STAFF_REVIEW",
            "DOCUMENT_FINAL_APPROVE", "DOCUMENT_PUBLISH");
    private final WorkloadCredentialRegistry registry;
    private final ServiceJwtIssuer issuer;

    @Override
    public ServiceTokenResponse issue(String basicAuthorization, ServiceTokenRequest request) {
        String[] pair;
        try {
            if (basicAuthorization == null || !basicAuthorization.startsWith("Basic ")) throw new InvalidWorkloadException();

            // base64 là chuỗi của cặp usernamw:password
            //cắt bỏ 6 kí tự "Basic ", giải mã về chuối TF_8
            String decoded = new String(Base64.getDecoder().decode(basicAuthorization.substring(6)), StandardCharsets.UTF_8);

            // tách thành 2 phần theo :
            pair = decoded.split(":", 2);
            if (pair.length != 2) throw new InvalidWorkloadException();

        } catch (IllegalArgumentException ex) {
            throw new InvalidWorkloadException();
        }

        // xác thực danh tính service
        // xác định đúng service đã đăng ký và đúng secret
        // Tiếp theo: so sánh yêu cầu ServiceTokenRequest với policy
        // trong workload vừa nhận.
        var workload = registry.authenticate(pair[0], pair[1]); // danh sách các quyen duoc cau hinh cho service

        /* allowed = true khi:
        // - audience yêu cầu thuộc danh sách service đích được phép.
        // - operation yêu cầu thuộc danh sách operation được phép.
        // - tenantId thuộc danh sách Tenant được cấu hình.
        // - HTTP method yêu cầu là POST.
         */
        boolean allowed = workload.audiences().contains(request.getAudience())
                && workload.operations().contains(request.getOperation())
                && workload.tenantIds().contains(request.getTenantId().toString())
                && "POST".equals(request.getMethod());

        // TRƯỜNG HỢP 1: Service muốn xin token để gọi S01 API-039.
        // Trong Flow 1: - S02 được xin token kiểm quyền REPOSITORY_CONNECT của Developer An.
        // - S04 được xin token kiểm quyền các thao tác tài liệu.
        // - Gửi đến đúng S01 internal audience/path.
        // - Chỉ rõ Workspace.
        // - Gắn với User JWT qua actorTokenSha256.
        // Với REPOSITORY_CONNECT: repositoryConnectionId phải null
        // vì Connection chưa được tạo.
        // Với DOCUMENT_*: repositoryConnectionId bắt buộc có
        // theo contract PR2 hiện tại.

        // TRƯỜNG HỢP 2: Service muốn xin token để gọi S02 Source APIs.
        // source:validate- Caller: S04.- Mục đích: kiểm tra source SHA của tài liệu qua API-070.
        // source:resolve: - Caller: S03.- Mục đích: lấy authoritative source snapshot qua API-069.
        // Cả hai:
        // - Phải gọi đúng S02 internal audience/path.
        // - Phải có Workspace và Repository Connection.
        // - Không mang action kiểm quyền người dùng.
        // - Không cần actorTokenSha256 vì đây là service-only operation.

        //*1. Đúng workload: S04 cho validate, S03 cho resolve.
        //* 2. Đúng operation và HTTP path.
        //* 3. Audience phải là S02 internal.
        //* 4. Có Workspace ID và Repository Connection ID.
        //* 5. Không mang action hoặc actorTokenSha256.

        if ("authorization:evaluate".equals(request.getOperation())) {
            allowed &= ("s02".equals(workload.id()) && "REPOSITORY_CONNECT".equals(request.getAction())
                    || "s04".equals(workload.id()) && DOCUMENT_ACTIONS.contains(request.getAction()))
                    && "livingdocs-s01-internal".equals(request.getAudience())
                    && "/internal/v1/authorization/evaluate".equals(request.getPath())
                    && request.getWorkspaceId() != null
                    && request.getActorTokenSha256() != null
                    && request.getActorTokenSha256().matches("[0-9a-f]{64}")
                    && ("REPOSITORY_CONNECT".equals(request.getAction())
                            ? request.getRepositoryConnectionId() == null
                            : request.getRepositoryConnectionId() != null);
        } else if ("source:validate".equals(request.getOperation())
                || "source:resolve".equals(request.getOperation())) {
            allowed &= ("source:validate".equals(request.getOperation()) && "s04".equals(workload.id())
                    && "/internal/v1/source/validate".equals(request.getPath())
                    || "source:resolve".equals(request.getOperation()) && "s03".equals(workload.id())
                    && "/internal/v1/source/resolve".equals(request.getPath()))
                    && "livingdocs-s02-internal".equals(request.getAudience())
                    && request.getWorkspaceId() != null && request.getRepositoryConnectionId() != null
                    && request.getAction() == null && request.getActorTokenSha256() == null;
        } else allowed = false;
        if (!allowed) throw new WorkloadScopeException();
        return new ServiceTokenResponse(issuer.issue(workload.id(), request), "Bearer", 60);
    }

    public static class WorkloadScopeException extends RuntimeException {}
}
