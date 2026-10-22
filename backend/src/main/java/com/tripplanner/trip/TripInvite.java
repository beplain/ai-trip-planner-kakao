package com.tripplanner.trip;

import com.tripplanner.user.User;
import jakarta.persistence.*;

import java.time.Instant;

/** 초대 링크. 만료 전까지 여러 명이 같은 링크로 참여 가능 */
@Entity
@Table(name = "trip_invites")
public class TripInvite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MemberRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt;

    protected TripInvite() {}

    public TripInvite(Trip trip, String token, MemberRole role, User createdBy, Instant expiresAt) {
        this.trip = trip;
        this.token = token;
        this.role = role;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public String getToken() { return token; }
    public MemberRole getRole() { return role; }
    public User getCreatedBy() { return createdBy; }
    public Instant getExpiresAt() { return expiresAt; }
}
