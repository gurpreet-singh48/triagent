package com.incidentintel.ticket;

import com.incidentintel.common.TicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public Page<TicketResponse> list(
            @RequestParam(required = false) String team,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ticketService.list(team, status, pageable);
    }

    @GetMapping("/{id}")
    public TicketDetailResponse detail(@PathVariable UUID id) {
        return ticketService.getDetail(id);
    }

    @PostMapping("/{id}/approve")
    public TicketDetailResponse approve(@PathVariable UUID id, @RequestBody ApproveRejectRequest request) {
        return ticketService.approve(id, request.reviewedBy());
    }

    @PostMapping("/{id}/reject")
    public TicketDetailResponse reject(@PathVariable UUID id, @RequestBody ApproveRejectRequest request) {
        return ticketService.reject(id, request.reviewedBy());
    }
}
