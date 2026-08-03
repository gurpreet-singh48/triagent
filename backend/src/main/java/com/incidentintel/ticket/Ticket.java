package com.incidentintel.ticket;

import com.incidentintel.common.Decision;
import com.incidentintel.common.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tickets")
@Getter
@Setter
@NoArgsConstructor
public class Ticket {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Enumerated(EnumType.STRING)
    private TicketStatus status;

    @Column(name = "predicted_team")
    private String predictedTeam;

    @Column(name = "predicted_category")
    private String predictedCategory;

    @Column(name = "predicted_severity")
    private String predictedSeverity;

    @Column(name = "confidence_score", precision = 4, scale = 3)
    private BigDecimal confidenceScore;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "redacted_summary", columnDefinition = "TEXT")
    private String redactedSummary;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
