package com.tripplanner.trip;

import com.tripplanner.common.StringListConverter;
import com.tripplanner.user.User;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trips")
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 (TripMember OWNER와 동일) */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User owner;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 100)
    private String destination;

    private LocalDate startDate;
    private LocalDate endDate;

    @Column(length = 50)
    private String budget;

    @Convert(converter = StringListConverter.class)
    @Column(length = 500)
    private List<String> styles = new ArrayList<>();

    /** 공동 편집 충돌 감지용. 일정/장소가 바뀔 때마다 touch()로 증가 */
    @Version
    private Long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayNumber ASC")
    private List<TripDay> days = new ArrayList<>();

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<TripMember> members = new ArrayList<>();

    protected Trip() {}

    public Trip(User owner, String title, String destination, LocalDate startDate, LocalDate endDate,
                String budget, List<String> styles) {
        this.owner = owner;
        this.title = title;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.budget = budget;
        this.styles = new ArrayList<>(styles == null ? List.of() : styles);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void updateInfo(String title, String destination, LocalDate startDate, LocalDate endDate,
                           String budget, List<String> styles) {
        this.title = title;
        this.destination = destination;
        this.startDate = startDate;
        this.endDate = endDate;
        this.budget = budget;
        this.styles = new ArrayList<>(styles == null ? List.of() : styles);
        touch();
    }

    /** 하위 엔티티(일차/장소)만 바뀐 경우에도 버전을 올리기 위해 호출 */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public TripDay addDay(int dayNumber, LocalDate date) {
        TripDay d = new TripDay(this, dayNumber, date);
        days.add(d);
        return d;
    }

    public TripMember addMember(User user, MemberRole role) {
        TripMember m = new TripMember(this, user, role);
        members.add(m);
        return m;
    }

    public Long getId() { return id; }
    public User getOwner() { return owner; }
    public String getTitle() { return title; }
    public String getDestination() { return destination; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getBudget() { return budget; }
    public List<String> getStyles() { return styles; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TripDay> getDays() { return days; }
    public List<TripMember> getMembers() { return members; }
}
