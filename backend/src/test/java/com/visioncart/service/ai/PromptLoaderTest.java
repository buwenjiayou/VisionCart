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
    void returnsVersionMetadata() {
        String version = promptLoader.getVersion("vision-recognition");
        assertThat(version).isEqualTo("1.0");
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
