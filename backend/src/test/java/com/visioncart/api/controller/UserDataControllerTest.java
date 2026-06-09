package com.visioncart.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.FavoriteRequest;
import com.visioncart.config.JwtAuthenticationFilter;
import com.visioncart.domain.FavoriteProduct;
import com.visioncart.domain.RecognitionHistory;
import com.visioncart.repository.FavoriteProductRepository;
import com.visioncart.repository.PriceAlertRepository;
import com.visioncart.repository.PriceHistoryRepository;
import com.visioncart.repository.RecognitionHistoryProductRepository;
import com.visioncart.repository.RecognitionHistoryRepository;
import com.visioncart.service.price.PriceMonitorService;
import com.visioncart.service.recognition.AsyncRecognitionTaskManager;
import com.visioncart.service.recognition.RecognitionImageStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDataControllerTest {
    private FavoriteProductRepository favoriteRepository;
    private RecognitionHistoryRepository historyRepository;
    private RecognitionImageStorage imageStorage;
    private AsyncRecognitionTaskManager taskManager;
    private UserDataController controller;

    @BeforeEach
    void setUp() {
        favoriteRepository = mock(FavoriteProductRepository.class);
        historyRepository = mock(RecognitionHistoryRepository.class);
        imageStorage = mock(RecognitionImageStorage.class);
        taskManager = mock(AsyncRecognitionTaskManager.class);
        controller = new UserDataController(
                favoriteRepository,
                historyRepository,
                mock(PriceHistoryRepository.class),
                mock(PriceAlertRepository.class),
                mock(RecognitionHistoryProductRepository.class),
                mock(PriceMonitorService.class),
                Executors.newSingleThreadExecutor(),
                new ObjectMapper(),
                imageStorage,
                taskManager
        );
        when(favoriteRepository.save(any(FavoriteProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new JwtAuthenticationFilter.AuthPrincipal(7L, "a@example.com"),
                        null
                )
        );
    }

    @Test
    void addFavoriteAcceptsLongUrls() {
        String longDetailUrl = "https://mobile.example.com/item?id=1&trace="
                + "x".repeat(320);
        String longImageUrl = "https://img.example.com/image.jpg?token="
                + "y".repeat(320);
        FavoriteRequest request = new FavoriteRequest(
                "product-1",
                "pdd",
                "Long URL product",
                longImageUrl,
                BigDecimal.valueOf(9.90),
                longDetailUrl,
                null);

        var response = controller.addFavorite(request);

        assertThat(response.code()).isEqualTo(200);
        assertThat(response.data().detailUrl()).isEqualTo(longDetailUrl);
        assertThat(response.data().imageUrl()).isEqualTo(longImageUrl);
        verify(favoriteRepository).save(any(FavoriteProduct.class));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void historyImageRequiresCurrentUserOwnership() {
        RecognitionHistory history = new RecognitionHistory();
        history.setSessionId("session-1");
        history.setUserId(7L);
        when(historyRepository.findBySessionIdAndUserId("session-1", 7L)).thenReturn(Optional.of(history));
        when(imageStorage.loadHistoryImage("session-1")).thenReturn(Optional.of(new ByteArrayResource(new byte[]{1, 2, 3})));

        var response = controller.historyImage("session-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void historyImageRejectsOtherUsersSession() {
        when(historyRepository.findBySessionIdAndUserId("session-2", 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.historyImage("session-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void historyImageAllowsOwnedActiveTaskBeforeHistoryExists() {
        when(taskManager.belongsToUser("pending-session", 7L)).thenReturn(true);
        when(historyRepository.findBySessionIdAndUserId("pending-session", 7L)).thenReturn(Optional.empty());
        when(imageStorage.loadHistoryImage("pending-session")).thenReturn(Optional.of(new ByteArrayResource(new byte[]{4, 5, 6})));

        var response = controller.historyImage("pending-session");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
    }
}
