package com.livingdocs.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "identity", name = "identities")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Identity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "identity_id", nullable = false, updatable = false)
    @Getter
    private UUID identityId;

    @Column(name = "email", nullable = false, length = 320)
    @Getter
    private String email;

    @Column(name = "password_hash", columnDefinition = "text")
    private String passwordHash;

    @Column(name = "status", nullable = false, length = 32)
    @Getter
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Getter
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    @Getter
    private Instant updatedAt;

    public Identity(String email, String passwordHash) {
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    }

    public String passwordHashForVerification() {
        return passwordHash;
    }
}
