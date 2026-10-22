package com.tripplanner.user;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 로그인 제공자 + 사용자 id. 카카오: "kakao:<회원번호>", 개발용: "dev:<이름>" */
    @Column(name = "auth_subject", nullable = false, unique = true, length = 100)
    private String authSubject;

    @Column(length = 200)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 8192)
    private String kakaoTokenBundle;
    private boolean kakaoCalendarConnected;

    public boolean isKakaoCalendarConnected() { return kakaoCalendarConnected; }
    public void setKakaoCalendarConnected(boolean connected) { this.kakaoCalendarConnected = connected; }

    public String getKakaoTokenBundle() { return kakaoTokenBundle; }
    public void setKakaoTokenBundle(String encrypted) { this.kakaoTokenBundle = encrypted; }

    protected User() {}

    public User(String authSubject, String email, String name) {
        this.authSubject = authSubject;
        this.email = email;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public void updateProfile(String email, String name) {
        if (email != null) this.email = email;
        if (name != null && !name.isBlank()) this.name = name;
    }

    public Long getId() { return id; }
    public String getAuthSubject() { return authSubject; }
    public String getEmail() { return email; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
