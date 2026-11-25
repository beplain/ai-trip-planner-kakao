package com.tripplanner.trip;

import jakarta.persistence.*;
import java.time.Instant;

/** Separate from the draft version: heartbeats must never invalidate an itinerary edit. */
@Entity @Table(name="draft_edit_leases")
public class DraftEditLease {
    @Id @Column(length=36) private String draftId;
    @Column(nullable=false) private Long editorId;
    @Column(nullable=false,length=100) private String editorName;
    @Column(nullable=false,length=64) private String token;
    @Column(nullable=false) private Instant expiresAt;
    @Version private Long revision;
    protected DraftEditLease() {}
    DraftEditLease(String draftId,Long editorId,String name,String token,Instant expiresAt) {
        this.draftId=draftId;claim(editorId,name,token,expiresAt);
    }
    void claim(Long user,String name,String token,Instant expiry){editorId=user;editorName=name;this.token=token;expiresAt=expiry;}
    void renew(Instant expiry){expiresAt=expiry;}
    boolean active(Instant now){return expiresAt.isAfter(now);}
    boolean owned(Long user,String supplied){return editorId.equals(user)&&token.equals(supplied);}
    Long editorId(){return editorId;}
    String editorName(){return editorName;}
    String token(){return token;}
    Instant expiresAt(){return expiresAt;}
}
