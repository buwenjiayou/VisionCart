package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.NlpParseRequest;
import com.visioncart.api.dto.NlpParseResult;
import com.visioncart.service.nlp.NlpModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/nlp")
public class NlpController {
    private final NlpModelService nlpService;

    public NlpController(NlpModelService nlpService) {
        this.nlpService = nlpService;
    }

    @PostMapping("/parse")
    public ApiResponse<NlpParseResult> parse(@Valid @RequestBody NlpParseRequest request) {
        return ApiResponse.ok(nlpService.parse(request));
    }
}
