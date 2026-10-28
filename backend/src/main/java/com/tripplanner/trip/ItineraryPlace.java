package com.tripplanner.trip;

import jakarta.persistence.*;

@Entity
@Table(name = "itinerary_places")
public class ItineraryPlace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_day_id")
    private TripDay tripDay;

    @Column(length = 100)
    private String fixedEventId;
    public String getFixedEventId() { return fixedEventId; }
    public void setFixedEventId(String id) { fixedEventId = id; }

    /** 카카오 장소 id (검증 실패 시 null) */
    @Column(length = 300)
    private String placeId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 300)
    private String address;

    private Double latitude;
    private Double longitude;

    /** "10:00" 같은 권장 방문 시각 (AI 추정치) */
    @Column(length = 10)
    private String visitTime;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String note;

    @Column(length = 30)
    private String category;

    /** 카카오 장소 검색으로 실제 장소/좌표가 확인되었는지 */
    private boolean verified;

    /** 카카오맵 장소 상세 페이지 (영업시간·메뉴·예약 정보는 여기서 확인) */
    @Column(length = 300)
    private String placeUrl;

    @Column(length = 50)
    private String phone;

    @Column(nullable = false)
    private int orderIndex;

    protected ItineraryPlace() {}

    public ItineraryPlace(String placeId, String name, String address, Double latitude, Double longitude,
                          String visitTime, String reason, String note, String category, boolean verified,
                          String placeUrl, String phone) {
        this.placeId = placeId;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.visitTime = visitTime;
        this.reason = reason;
        this.note = note;
        this.category = category;
        this.verified = verified;
        this.placeUrl = placeUrl;
        this.phone = phone;
    }

    void attachTo(TripDay day, int orderIndex) {
        this.tripDay = day;
        this.orderIndex = orderIndex;
    }

    void setOrderIndex(int orderIndex) { this.orderIndex = orderIndex; }

    public void updateMemo(String visitTime, String note) {
        this.visitTime = visitTime;
        this.note = note;
    }

    public Long getId() { return id; }
    public TripDay getTripDay() { return tripDay; }
    public String getPlaceId() { return placeId; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getVisitTime() { return visitTime; }
    public String getReason() { return reason; }
    public String getNote() { return note; }
    public String getCategory() { return category; }
    public boolean isVerified() { return verified; }
    public String getPlaceUrl() { return placeUrl; }
    public String getPhone() { return phone; }
    public int getOrderIndex() { return orderIndex; }
}
