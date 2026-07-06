package com.sportday.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventType type;

    @Column(nullable = false)
    private LocalDate eventDate;

    private String location;

    @Column(nullable = false)
    private Integer maxParticipants;

    @Column(nullable = false)
    private Boolean enabled;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enabled == null) enabled = true;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum EventType {
        RUN_100M, RUN_200M, RUN_400M, RUN_800M, RUN_1500M, RUN_5000M,
        SHOT_PUT, DISCUSSION_THROW, JAVELIN_THROW, HAMMER_THROW,
        LONG_JUMP, HIGH_JUMP, TRIPLE_JUMP, POLE_VAULT,
        RELAY_4X100M, RELAY_4X400M, HURDLES_110M, HURDLES_400M,
        OTHER
    }
}
