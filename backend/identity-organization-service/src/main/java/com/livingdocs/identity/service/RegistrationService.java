package com.livingdocs.identity.service;

import com.livingdocs.identity.validation.Utf8ByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public interface RegistrationService {

    UUID registerLocal(@NotBlank @Email @Size(max = 320) String email,
                       @NotBlank @Size(min = 12, max = 64) @Utf8ByteLength(max = 72) String rawPassword);
}
