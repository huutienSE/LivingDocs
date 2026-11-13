package com.livingdocs.identity.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 320, message = "Email is too long")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 12, max = 64, message = "Password must contain between 12 and 64 characters")
    private String password;
}
