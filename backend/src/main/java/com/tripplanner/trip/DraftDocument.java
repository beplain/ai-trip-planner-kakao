package com.tripplanner.trip;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "draft_rooms")
public class DraftDocument {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long ownerId;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String content;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String participants;
    @Version private Long version;
    private Long savedTripId;
    @Column(nullable = false) private Instant updatedAt;
    protected DraftDocument() {}
    DraftDocument(Long ownerId, String content, String participants) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId;
        this.content = content; this.participants = participants; this.updatedAt = Instant.now();
    }
    void edit(String content) { this.content = content; updatedAt = Instant.now(); }
    void participants(String participants) { this.participants = participants; updatedAt = Instant.now(); }
    void finish(Long tripId) { this.savedTripId = tripId; updatedAt = Instant.now(); }
    public String getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getContent() { return content; }
    public String getParticipants() { return participants; }
    public Long getVersion() { return version; }
    public Long getSavedTripId() { return savedTripId; }
}
