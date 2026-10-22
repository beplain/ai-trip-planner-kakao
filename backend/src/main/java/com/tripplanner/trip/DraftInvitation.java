package com.tripplanner.trip;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name = "draft_invites")
public class DraftInvitation {
    @Id @Column(length = 64) private String token;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "draft_id") private DraftDocument document;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private MemberRole role;
    @Column(nullable = false) private Instant expiresAt;
    protected DraftInvitation() {}
    DraftInvitation(String token, DraftDocument document, MemberRole role, Instant expiresAt) {
        this.token = token; this.document = document; this.role = role; this.expiresAt = expiresAt;
    }
    public DraftDocument getDocument() { return document; }
    public MemberRole getRole() { return role; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
