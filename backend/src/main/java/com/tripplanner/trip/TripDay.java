package com.tripplanner.trip;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "trip_days")
public class TripDay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @Column(nullable = false)
    private int dayNumber;

    @Column(name = "trip_date") // date는 일부 DB 예약어
    private LocalDate date;

    @OneToMany(mappedBy = "tripDay", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<ItineraryPlace> places = new ArrayList<>();

    protected TripDay() {}

    TripDay(Trip trip, int dayNumber, LocalDate date) {
        this.trip = trip;
        this.dayNumber = dayNumber;
        this.date = date;
    }

    public ItineraryPlace addPlace(ItineraryPlace p) {
        p.attachTo(this, places.size());
        places.add(p);
        return p;
    }

    /** orderIndex를 0..n-1로 다시 매김 */
    public void reindex() {
        List<ItineraryPlace> sorted = new ArrayList<>(places);
        sorted.sort(java.util.Comparator.comparingInt(ItineraryPlace::getOrderIndex));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).setOrderIndex(i);
    }

    void setDate(LocalDate date) { this.date = date; }

    public Long getId() { return id; }
    public Trip getTrip() { return trip; }
    public int getDayNumber() { return dayNumber; }
    public LocalDate getDate() { return date; }
    public List<ItineraryPlace> getPlaces() { return places; }
}
