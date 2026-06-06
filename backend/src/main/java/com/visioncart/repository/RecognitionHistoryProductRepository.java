package com.visioncart.repository;

import com.visioncart.domain.RecognitionHistoryProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecognitionHistoryProductRepository extends JpaRepository<RecognitionHistoryProduct, Long> {

    List<RecognitionHistoryProduct> findByHistoryIdOrderBySortNoAsc(String historyId);

    List<RecognitionHistoryProduct> findByHistoryIdOrderBySortNoAsc(String historyId, org.springframework.data.domain.Pageable pageable);

    void deleteByHistoryId(String historyId);
}
