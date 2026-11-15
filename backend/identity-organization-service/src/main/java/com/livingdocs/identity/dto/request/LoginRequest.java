package com.livingdocs.identity.dto.request;

import com.livingdocs.identity.model.EmailNormalization;
import com.livingdocs.identity.validation.Utf8ByteLength;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class LoginRequest {

    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    @NotBlank
    @Size(max = 64)
    @Utf8ByteLength(max = 72)
    @Schema(format = "password")
    private String password;

    public void setEmail(String email) {
        // chuẩn hóa email, cắt bỏ các khoảng trắng bên ngoài
        this.email = EmailNormalization.trimOuterSpaces(email);
    }
}
