package com.visioncart.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    private PromptLoader promptLoader;

    @BeforeEach
    void setUp() {
        promptLoader = new PromptLoader();
    }

    @Test
    void loadsVisionRecognitionPrompt() {
        String prompt = promptLoader.getPrompt("vision-recognition");
        assertThat(prompt).contains("电商商品识别模型");
        assertThat(prompt).contains("严格 JSON");
        assertThat(prompt).contains("%s");
    }

    @Test
    void loadsNlpSystemPrompt() {
        String prompt = promptLoader.getPrompt("nlp-system");
        assertThat(prompt).contains("电商筛选意图解析器");
        assertThat(prompt).contains("sort_by");
    }

    @Test
    void loadsNlpUserPrompt() {
        String prompt = promptLoader.getPrompt("nlp-user");
        assertThat(prompt).contains("当前商品");
        assertThat(prompt).contains("%s");
    }

    @Test
    void semanticPlannerUserPromptPreservesMultiTurnStateRules() {
        String prompt = promptLoader.getPrompt("semantic-planner-user");

        assertThat(prompt)
                .contains("Output the complete target state for this turn")
                .contains("最终状态可以同时包含多个筛选条件")
                .contains("历史中的成功筛选默认属于当前状态")
                .contains("必须保留历史中不冲突的条件")
                .contains("不同类型条件默认共存")
                .contains("预算100以内")
                .contains("再找适合学生党的")
                .contains("price <= 100")
                .contains("\"hard_filters\": []")
                .contains("\"judge\": null");
    }

    @Test
    void returnsVersionMetadata() {
        String version = promptLoader.getVersion("vision-recognition");
        assertThat(version).isEqualTo("1.1");
    }

    @Test
    void cachesAfterFirstLoad() {
        String first = promptLoader.getPrompt("nlp-system");
        String second = promptLoader.getPrompt("nlp-system");
        assertThat(first).isSameAs(second);
    }

    @Test
    void throwsOnMissingPrompt() {
        assertThatThrownBy(() -> promptLoader.getPrompt("nonexistent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
