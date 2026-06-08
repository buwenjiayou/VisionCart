package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class PddSearchServiceTest {

    private ExecutorService searchExecutor;
    private ExecutorService detailExecutor;
    private PddSearchService service;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newSingleThreadExecutor();
        detailExecutor = Executors.newSingleThreadExecutor();
        VisionCartProperties properties = new VisionCartProperties();
        properties.getPdd().setClientId("client-id");
        properties.getPdd().setClientSecret("client-secret");
        properties.getPdd().setPid("pid");
        service = new PddSearchService(properties, new ObjectMapper(), searchExecutor, detailExecutor);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
        detailExecutor.shutdownNow();
    }

    @Test
    void parsesGoodsIdFromProductIdAndDetailUrl() throws Exception {
        assertThat(invokeString("parseGoodsId", "pdd_123456", null)).isEqualTo("123456");
        assertThat(invokeString("parseGoodsId", "123456", null)).isEqualTo("123456");
        assertThat(invokeString("parseGoodsId", null, "https://mobile.yangkeduo.com/goods.html?goods_id=987654"))
                .isEqualTo("987654");
        assertThat(invokeString("parseGoodsId", "https://x.test/item?goods_id=2468", null))
                .isEqualTo("2468");
    }

    @Test
    void parsesGoodsSignFromProductIdAndDetailUrl() throws Exception {
        assertThat(invokeString("parseGoodsSign", "goods_sign=abc%2Fdef", null)).isEqualTo("abc/def");
        assertThat(invokeString("parseGoodsSign", null, "https://mobile.yangkeduo.com/goods.html?goods_sign=sign-123"))
                .isEqualTo("sign-123");
    }

    @Test
    void mapsDetailResponseToExactProductCard() throws Exception {
        String body = """
                {
                  "goods_detail_response": {
                    "goods_details": [
                      {
                        "goods_id": "123456",
                        "goods_sign": "sign-123",
                        "goods_name": "PDD exact item",
                        "goods_image_url": "https://img.example/item.jpg",
                        "min_group_price": 1299,
                        "min_normal_price": 1999,
                        "mall_name": "Exact Shop",
                        "brand_name": "BrandX",
                        "goods_eval_score": 4.7,
                        "sales_tip": "sold 2000"
                      }
                    ]
                  }
                }
                """;
        Method map = PddSearchService.class.getDeclaredMethod("mapDetailResponse", String.class);
        map.setAccessible(true);
        Object detail = map.invoke(service, body);

        Method convert = PddSearchService.class.getDeclaredMethod(
                "detailToProductCard", detail.getClass(), String.class, String.class, String.class);
        convert.setAccessible(true);
        ProductCard card = (ProductCard) convert.invoke(service, detail, "fallback-sign", "fallback-id", "");

        assertThat(card.id()).isEqualTo("pdd_123456");
        assertThat(card.title()).isEqualTo("PDD exact item");
        assertThat(card.price()).isEqualByComparingTo(BigDecimal.valueOf(12.99));
        assertThat(card.originalPrice()).isEqualByComparingTo(BigDecimal.valueOf(19.99));
        assertThat(card.platform()).isEqualTo("PDD");
        assertThat(card.shopName()).isEqualTo("Exact Shop");
        assertThat(card.brand()).isEqualTo("BrandX");
        assertThat(card.rating()).isEqualTo(4.7);
        assertThat(card.ratingSource()).isEqualTo("item_rating");
        assertThat(card.detailUrl()).contains("goods_id=123456");
    }

    private String invokeString(String methodName, String productId, String detailUrl) throws Exception {
        Method method = PddSearchService.class.getDeclaredMethod(methodName, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, productId, detailUrl);
    }
}
