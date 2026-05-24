package com.visioncart.api.controller;

import com.visioncart.api.dto.ApiResponse;
import com.visioncart.api.dto.FavoriteRequest;
import com.visioncart.api.dto.HistoryResult;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.service.recognition.RecognitionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class UserDataController {
    private final FavoriteProductRepository favoriteRepository;
    private final RecognitionService recognitionService;

    public UserDataController(FavoriteProductRepository favoriteRepository, RecognitionService recognitionService) {
        this.favoriteRepository = favoriteRepository;
        this.recognitionService = recognitionService;
    }

    @PostMapping("/favorites")
    public ApiResponse<FavoriteProduct> addFavorite(@Valid @RequestBody FavoriteRequest request) {
        FavoriteProduct favorite = favoriteRepository.findByProductId(request.productId()).orElseGet(FavoriteProduct::new);
        favorite.setProductId(request.productId());
        favorite.setSessionId(request.sessionId());
        favorite.setPlatform(request.platform());
        favorite.setTitle(request.title());
        favorite.setImageUrl(request.imageUrl());
        favorite.setPrice(request.price());
        favorite.setDetailUrl(request.detailUrl());
        return ApiResponse.ok(favoriteRepository.save(favorite));
    }

    @GetMapping("/favorites")
    public ApiResponse<List<FavoriteProduct>> favorites() {
        return ApiResponse.ok(favoriteRepository.findTop50ByOrderByCreatedAtDesc());
    }

    @GetMapping("/history")
    public ApiResponse<HistoryResult> history() {
        List<RecognitionHistory> items = recognitionService.latestHistory();
        return ApiResponse.ok(new HistoryResult(items.size(), items));
    }
}
