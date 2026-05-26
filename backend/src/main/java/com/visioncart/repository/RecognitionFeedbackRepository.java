package com.visioncart.repository;

import com.visioncart.api.dto.CorrectionStat;
import com.visioncart.domain.RecognitionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RecognitionFeedbackRepository extends JpaRepository<RecognitionFeedback, Long> {

    @Query("SELECT new com.visioncart.api.dto.CorrectionStat(" +
           "f.attributeName, f.vlmOutput, f.userCorrection, COUNT(f)) " +
           "FROM RecognitionFeedback f " +
           "GROUP BY f.attributeName, f.vlmOutput, f.userCorrection " +
           "ORDER BY COUNT(f) DESC")
    List<CorrectionStat> findTopCorrections();
}
