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
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 320, message = "Email is too long")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 12, max = 64, message = "Password must contain between 12 and 64 characters")
    @Utf8ByteLength(max = 72)
    @Schema(format = "password", description = "Maximum 72 UTF-8 bytes for BCrypt")
    private String password;

    public void setEmail(String email) {
        this.email = EmailNormalization.trimOuterSpaces(email);
    }
}
