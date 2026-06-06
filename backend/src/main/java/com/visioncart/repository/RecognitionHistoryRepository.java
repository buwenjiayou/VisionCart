package com.visioncart.repository;

import com.visioncart.domain.RecognitionHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecognitionHistoryRepository extends JpaRepository<RecognitionHistory, String> {

    List<RecognitionHistory> findTop20ByUserIdOrderByCreatedAtDesc(Long userId);

    Page<RecognitionHistory> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<RecognitionHistory> findBySessionIdAndUserId(String sessionId, Long userId);

    void deleteBySessionIdAndUserId(String sessionId, Long userId);
}
