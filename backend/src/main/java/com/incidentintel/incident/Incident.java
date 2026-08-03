package com.incidentintel.incident;

import com.incidentintel.common.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Implements {@link Persistable} because {@code id} is assigned by the
 * application (not DB-generated) before the first save — without this,
 * Spring Data JPA's default isNew() check sees a non-null id and treats
 * even the first save() as a merge/update instead of a persist/insert,
 * which means @PrePersist runs on an internal Hibernate copy rather than
 * the instance the caller holds, silently leaving fields like receivedAt
 * unset on any subsequent save() of that same reference.
 */
@Entity
@Table(name = "incidents")
@Getter
@Setter
@NoArgsConstructor
public class Incident implements Persistable<UUID> {

    @Id
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "routing_key")
    private String routingKey;

    private String source;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private String severity;

    private String component;

    @Column(name = "group_name")
    private String groupName;

    @Column(name = "class")
    private String className;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_details", columnDefinition = "jsonb")
    private Map<String, Object> customDetails;

    @Enumerated(EnumType.STRING)
    private IncidentStatus status = IncidentStatus.RECEIVED;

    @Column(name = "received_at")
    private OffsetDateTime receivedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    protected void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
