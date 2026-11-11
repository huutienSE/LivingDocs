package com.livingdocs.repository.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** Lần tiếp nhận thông báo từ GitHub, định danh riêng để chống xử lý trùng. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "webhook_deliveries", schema = "repository",
        uniqueConstraints = {
            @UniqueConstraint(name = "uq_webhook_deliveries_tenant_id", columnNames = {"tenant_id", "delivery_id"}),
            @UniqueConstraint(name = "uq_webhook_delivery_deduplication", columnNames = {"tenant_id", "github_connection_id", "provider_delivery_key"})
        })
public class WebhookDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "delivery_id", nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID deliveryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "github_connection_id", nullable = false)
    private UUID githubConnectionId;

    @Column(name = "provider_delivery_key", nullable = false, length = 255)
    private String providerDeliveryKey;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Generated(event = EventType.INSERT)
    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant receivedAt;

    @Column(name = "classification", nullable = false, length = 64)
    private String classification;

}
