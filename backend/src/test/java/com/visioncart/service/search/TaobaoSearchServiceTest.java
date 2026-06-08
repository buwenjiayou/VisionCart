package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class TaobaoSearchServiceTest {

    private ExecutorService searchExecutor;
    private ExecutorService detailExecutor;
    private TaobaoSearchService service;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newSingleThreadExecutor();
        detailExecutor = Executors.newSingleThreadExecutor();
        service = new TaobaoSearchService(
                new VisionCartProperties(),
                new ObjectMapper(),
                searchExecutor,
                detailExecutor);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
        detailExecutor.shutdownNow();
    }

    @Test
    void itemScoreMapsToItemRating() throws Exception {
        List<ProductCard> products = mapResponse("""
                {
                  "tbk_dg_material_optional_response": {
                    "result_list": {
                      "map_data": [
                        {
                          "num_iid": "1001",
                          "title": "淘宝商品",
                          "zk_final_price": "99.00",
                          "shop_title": "测试店铺",
                          "item_basic_info": {
                            "item_score": "4.8"
                          }
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(products).hasSize(1);
        assertThat(products.get(0).rating()).isEqualTo(4.8);
        assertThat(products.get(0).ratingSource()).isEqualTo("item_rating");
    }

    @Test
    void shopDsrMapsToShopDsrWhenItemScoreMissing() throws Exception {
        List<ProductCard> products = mapResponse("""
                {
                  "tbk_dg_material_optional_response": {
                    "result_list": {
                      "map_data": [
                        {
                          "num_iid": "1002",
                          "title": "淘宝店铺评分商品",
                          "zk_final_price": "129.00",
                          "shop_title": "测试店铺",
                          "item_basic_info": {
                            "shop_dsr": "4.6"
                          }
                        }
                      ]
                    }
                  }
                }
                """);

        assertThat(products).hasSize(1);
        assertThat(products.get(0).rating()).isEqualTo(4.6);
        assertThat(products.get(0).ratingSource()).isEqualTo("shop_dsr");
    }

    @Test
    void reputationSortsDoNotSendSalesSortToTaobao() throws Exception {
        VisionCartProperties.Taobao tb = taobaoConfig();

        for (String sortBy : List.of("rating", "reviews", "review_quality", "rating_desc", "shop_trust", "seller_trust")) {
            Map<String, String> params = buildParams(sortBy, tb);

            assertThat(params).doesNotContainKey("sort");
        }
    }

    @Test
    void salesSortStillSendsTotalSalesDescendingToTaobao() throws Exception {
        Map<String, String> params = buildParams("sales", taobaoConfig());

        assertThat(params).containsEntry("sort", "total_sales_des");
    }

    @SuppressWarnings("unchecked")
    private List<ProductCard> mapResponse(String body) throws Exception {
        Method method = TaobaoSearchService.class.getDeclaredMethod("mapResponse", String.class);
        method.setAccessible(true);
        return (List<ProductCard>) method.invoke(service, body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> buildParams(String sortBy, VisionCartProperties.Taobao tb) throws Exception {
        Method method = TaobaoSearchService.class.getDeclaredMethod(
                "buildParams", String.class, com.visioncart.api.dto.SearchFilter.class,
                int.class, int.class, VisionCartProperties.Taobao.class);
        method.setAccessible(true);
        com.visioncart.api.dto.SearchFilter filter = new com.visioncart.api.dto.SearchFilter(
                null, List.of(), null, List.of(), List.of(), null,
                sortBy, "desc", null);
        return (Map<String, String>) method.invoke(service, "商品", filter, 1, 50, tb);
    }

    private VisionCartProperties.Taobao taobaoConfig() {
        VisionCartProperties.Taobao tb = new VisionCartProperties.Taobao();
        tb.setAppKey("app-key");
        tb.setAppSecret("app-secret");
        tb.setAdzoneId("123");
        return tb;
    }
}
