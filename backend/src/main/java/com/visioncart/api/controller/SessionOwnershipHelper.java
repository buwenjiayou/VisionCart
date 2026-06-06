package com.visioncart.api.controller;

import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import org.springframework.stereotype.Component;

/**
 * Unified session ownership check.
 * A session belongs to a user if it exists in either:
 * 1. Redis task manager (active task), or
 * 2. MySQL history table (completed task).
 */
@Component
public class SessionOwnershipHelper {

    private final AsyncRecognitionTaskManager taskManager;
    private final RecognitionHistoryRepository historyRepository;

    public SessionOwnershipHelper(AsyncRecognitionTaskManager taskManager,
                                  RecognitionHistoryRepository historyRepository) {
        this.taskManager = taskManager;
        this.historyRepository = historyRepository;
    }

    /**
     * Check if the given user owns the session (active task or completed history).
     */
    public boolean ownsSession(String sessionId, Long userId) {
        return taskManager.belongsToUser(sessionId, userId)
                || historyRepository.findBySessionIdAndUserId(sessionId, userId).isPresent();
    }
}
