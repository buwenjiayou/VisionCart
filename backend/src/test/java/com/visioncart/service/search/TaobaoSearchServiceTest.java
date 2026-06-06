package com.visioncart.service.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.api.dto.ProductCard;
import com.visioncart.config.VisionCartProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    @SuppressWarnings("unchecked")
    private List<ProductCard> mapResponse(String body) throws Exception {
        Method method = TaobaoSearchService.class.getDeclaredMethod("mapResponse", String.class);
        method.setAccessible(true);
        return (List<ProductCard>) method.invoke(service, body);
    }
}
