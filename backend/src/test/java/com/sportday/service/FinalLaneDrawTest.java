package com.sportday.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The final's lane draw.
 *
 * <p>Requirement, as the school wrote it:</p>
 *
 * <pre>
 *   初賽最佳成績前八名進入決賽，決賽運動員線道分配如下:
 *   線道      1  2  3  4  5  6  7  8
 *   初賽成績  8  7  1  2  3  4  5  6
 * </pre>
 *
 * <p>The fastest qualifier runs in the middle of the track, not in lane 1.</p>
 */
class FinalLaneDrawTest {

    @Test
    @DisplayName("the school's draw: heat rank 1-8 runs in lane 3,4,5,6,7,8,2,1")
    void theSchoolsOwnDraw() {
        int[] laneForRank = new int[9];
        for (int rank = 1; rank <= 8; rank++) {
            laneForRank[rank] = FinalQualificationService.laneForRank(rank);
        }
        assertArrayEquals(new int[]{0, 3, 4, 5, 6, 7, 8, 2, 1},
                laneForRank.clone(),
                "rank 1..8 should run in lanes 3,4,5,6,7,8,2,1");
    }

    @Test
    @DisplayName("the fastest qualifier is in the middle, and the slowest on the outside")
    void theFastestIsNotInLaneOne() {
        assertEquals(3, FinalQualificationService.laneForRank(1),
                "the fastest heat time does not take lane 1");
        assertEquals(4, FinalQualificationService.laneForRank(2));
        assertEquals(1, FinalQualificationService.laneForRank(8),
                "the slowest qualifier is the one who gets lane 1");
        assertEquals(2, FinalQualificationService.laneForRank(7));
    }

    @Test
    @DisplayName("every qualifier gets a lane of their own")
    void nobodySharesALane() {
        Set<Integer> lanes = new HashSet<>();
        for (int rank = 1; rank <= 8; rank++) {
            assertTrue(lanes.add(FinalQualificationService.laneForRank(rank)),
                    "lane " + FinalQualificationService.laneForRank(rank) + " was handed out twice");
        }
        assertEquals(8, lanes.size(), "eight qualifiers fill eight lanes");
        assertTrue(lanes.containsAll(Set.of(1, 2, 3, 4, 5, 6, 7, 8)),
                "and between them they use every lane");
    }

    @Test
    @DisplayName("a final that is not full stops early rather than skipping lanes")
    void aShortFinalStopsEarly() {
        // Five qualifiers: the same rule, simply stopping after lane 7.
        assertArrayEquals(new int[]{3, 4, 5, 6, 7},
                new int[]{FinalQualificationService.laneForRank(1),
                        FinalQualificationService.laneForRank(2),
                        FinalQualificationService.laneForRank(3),
                        FinalQualificationService.laneForRank(4),
                        FinalQualificationService.laneForRank(5)});
        // Six take 3..8, seven add the outside lane 2.
        assertEquals(8, FinalQualificationService.laneForRank(6));
        assertEquals(2, FinalQualificationService.laneForRank(7));
    }

    @Test
    @DisplayName("a rank past eight falls back to itself rather than inventing a lane")
    void aRankPastEightFallsBack() {
        // An eight-lane track has no ninth lane. Nothing should silently wrap around
        // and hand two athletes the same one.
        assertEquals(9, FinalQualificationService.laneForRank(9));
        assertEquals(10, FinalQualificationService.laneForRank(10));
        assertEquals(0, FinalQualificationService.laneForRank(0));
    }
}
