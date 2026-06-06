package com.visioncart.api.controller;

import com.visioncart.api.dto.ActionResult;
import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.AttributeCorrectionRequest;
import com.visioncart.api.dto.UserAction;
import com.visioncart.config.SecurityUtils;
import com.visioncart.service.action.ActionExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attribute correction HTTP endpoint.
 * Routes through ActionExecutionService to produce unified ActionResult,
 * ensuring Android consumes a single response shape with displayMode,
 * fallbackProducts, messageCode, undoToken, etc.
 */
@RestController
@RequestMapping("/api/v1/recognition")
public class AttributeCorrectionController {

    private final ActionExecutionService actionExecutionService;

    public AttributeCorrectionController(ActionExecutionService actionExecutionService) {
        this.actionExecutionService = actionExecutionService;
    }

    @PatchMapping("/{sessionId}/attributes")
    public ApiResponse<ActionResult> correctAttribute(
            @PathVariable String sessionId,
            @Valid @RequestBody AttributeCorrectionRequest request) {
        UserAction action = UserAction.correction(sessionId, request.attribute(), request.newValue());
        ActionResult result = actionExecutionService.execute(action, SecurityUtils.currentUserId());
        return ApiResponse.ok(result);
    }
}
