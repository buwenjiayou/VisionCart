package com.visioncart.repository;

import com.visioncart.domain.RecognitionHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionHistoryRepository extends JpaRepository<RecognitionHistory, String> {
    List<RecognitionHistory> findTop20ByOrderByCreatedAtDesc();
}
