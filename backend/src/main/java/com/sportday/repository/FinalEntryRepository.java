package com.sportday.repository;

import com.sportday.entity.FinalEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinalEntryRepository extends JpaRepository<FinalEntry, Long> {

    List<FinalEntry> findByGroupIdOrderByLaneAsc(Long groupId);

    List<FinalEntry> findByGroupIdOrderBySeedAsc(Long groupId);

    long countByGroupId(Long groupId);

    void deleteByGroupId(Long groupId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);
}
