package com.incidentintel.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ticket_retrieved_docs")
@Getter
@Setter
@NoArgsConstructor
public class TicketRetrievedDoc {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "doc_id")
    private String docId;

    @Column(name = "doc_title")
    private String docTitle;

    @Column(name = "doc_source_type")
    private String docSourceType;

    private BigDecimal score;

    @Column(columnDefinition = "TEXT")
    private String snippet;

    private Integer rank;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
