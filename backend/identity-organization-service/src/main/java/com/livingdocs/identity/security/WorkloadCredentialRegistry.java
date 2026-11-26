package com.livingdocs.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/* Operator-managed secret file, never part of the repository or API response.
 * 1. Đọc danh sách workload được cấu hình trong file JSON ngoài Git.
 *  2. Tìm workload tương ứng với ID mà service gửi lên.
 *  3. So sánh secret được gửi với BCrypt hash đã lưu.
 *  4. Nếu hợp lệ, trả về đối tượng Workload chứa danh tính service
 *  và các chính sách được cấu hình cho service đó.
 *  Trả Workload về ServiceTokenServiceImpl để tiếp tục kiểm tra
 *  * operation, audience và resource scope trước khi phát JWT.
 *
 * */
@Component
public class WorkloadCredentialRegistry {
    private final String path;
    private final ObjectMapper json = new ObjectMapper();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public WorkloadCredentialRegistry(@Value("${livingdocs.security.workloads.registry-path:}") String path) {
        this.path = path;
    }

    public Workload authenticate(String id, String secret) {
        // kiem tra dinh dang
        if (id == null || secret == null || !id.matches("s0[234]") || secret.isBlank()) {
            throw new InvalidWorkloadException();
        }

        // doc workload trong danh sách đã đang ký
        Workload match = load().stream().filter(w -> id.equals(w.id())).findFirst().orElse(null);

        // A fixed dummy hash limits timing differences for unknown IDs.
        String hash = match == null ? "$2a$10$K7L1OJ1fF9l.f9tCmbm4s.b5cQoA6Wk5Xr5qF0BZX5lpOMnBM4NYu" : match.secretHash();
        if (!encoder.matches(secret, hash) || match == null) throw new InvalidWorkloadException();
        return match;
    }


    /*
     Lấy đường dẫn registry đã cấu hình từ application.yaml/environment.

     */
    private List<Workload> load() {
        if (path.isBlank()) throw new RegistryUnavailableException();
        try {
            // Đọc nội dung file JSON ngoài Git.
            //Chuyển JSON thành danh sách đối tượng Workload.
            Workload[] entries = json.readValue(Files.readAllBytes(Path.of(path)), Workload[].class);

            // Kiểm tra các thuộc tính cấu hình bắt buộc.
            if (entries.length == 0 || Arrays.stream(entries).anyMatch(w -> w.id() == null
                    || w.secretHash() == null || w.audiences() == null || w.operations() == null
                    || w.tenantIds() == null)) throw new RegistryUnavailableException();
            return List.of(entries); //chứa các service đã được đăng ký  và chính sách cấp token của từng service.
        } catch (Exception ex) {
            throw new RegistryUnavailableException();
        }
    }

    /*
     * Đại diện cho một service đã được đăng ký với S01.
     *
     * id:         Danh tính service, ví dụ s02, s03, s04.
     * secretHash: BCrypt hash dùng xác thực secret của service.
     * audiences:  Danh sách service đích được phép xin JWT.
     * operations: Danh sách internal operations được phép yêu cầu.
     * tenantIds:  Danh sách Tenant mà workload được cấu hình hoạt động.
     *
     * Đây là CHÍNH SÁCH CẤP TOKEN CHO SERVICE,
     * không phải quyền Developer/Staff/Manager của người dùng.
     */
    public record Workload(String id, String secretHash, List<String> audiences,
                           List<String> operations, List<String> tenantIds) {}
    public static class InvalidWorkloadException extends RuntimeException {}
    public static class RegistryUnavailableException extends RuntimeException {}
}
