package com.visioncart.service.nlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visioncart.service.ai.PromptLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticPlannerModelServiceTest {

    private ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private PromptLoader promptLoader;
    private SemanticPlannerModelService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatClient.Builder> provider = mock(ObjectProvider.class);
        chatClientBuilder = provider;
        promptLoader = mock(PromptLoader.class);
        when(promptLoader.getPrompt("semantic-planner-system")).thenReturn("system");
        when(promptLoader.getPrompt("semantic-planner-user")).thenReturn("Product: %s, Category: %s, History: %s, Input: %s");
        service = new SemanticPlannerModelService(chatClientBuilder, new ObjectMapper(), promptLoader);
    }

    @Test
    void parsesExtendedSemanticPlanFromLlm() {
        mockLlmResponse("""
                {
                  "intent": "filter_current_results",
                  "execution_mode": "LLM_RERANK",
                  "criteria": [
                    {
                      "name": "便携",
                      "type": "evidence_or_score",
                      "required": false,
                      "weight": 0.4,
                      "unknown_policy": "KEEP_AS_SECONDARY",
                      "signals": [
                        {"kind": "keyword", "field": "all_text", "operator": "contains_any", "values": ["轻便"]}
                      ]
                    }
                  ],
                  "negative_criteria": [
                    {
                      "name": "配件",
                      "action": "reject",
                      "signals": [
                        {"kind": "keyword", "field": "product_role", "operator": "contains", "value": "accessory"}
                      ]
                    }
                  ],
                  "judge": {
                    "required": true,
                    "user_meaning": "适合老人用",
                    "positive_signals": ["操作简单", "评价稳定"],
                    "negative_signals": ["太复杂"],
                    "candidate_limit": 80,
                    "return_limit": 50,
                    "min_score": 0.35
                  },
                  "ranking_goal": "优先展示适合老人使用的商品",
                  "result_strategy": {
                    "unknown_policy": "KEEP_AS_SECONDARY",
                    "fallback_policy": "KEEP_PREVIOUS_RESULTS",
                    "preserve_previous_on_empty": true
                  }
                }
                """);

        var plan = service.plan("适合老人用的", "按摩仪", "按摩仪", "");

        assertThat(plan.executionMode()).isEqualTo("LLM_RERANK");
        assertThat(plan.criteria()).hasSize(1);
        assertThat(plan.criteria().get(0).signals()).hasSize(1);
        assertThat(plan.negativeCriteria()).hasSize(1);
        assertThat(plan.judge().userMeaning()).isEqualTo("适合老人用");
        assertThat(plan.judge().positiveSignals()).contains("操作简单");
        assertThat(plan.rankingGoal()).contains("老人");
        assertThat(plan.resultStrategy().preservePreviousOnEmpty()).isTrue();
    }

    @Test
    void oldSemanticPlanStillParsesWithNullExtendedFields() {
        mockLlmResponse("""
                {
                  "intent": "filter_current_results",
                  "execution_mode": "STRICT_FILTER",
                  "hard_filters": [{"field": "price", "operator": "<=", "value": 100}],
                  "zero_result_policy": "KEEP_PREVIOUS_RESULTS"
                }
                """);

        var plan = service.plan("100以内", "耳机", "耳机", "");

        assertThat(plan.hardFilters()).hasSize(1);
        assertThat(plan.criteria()).isNull();
        assertThat(plan.negativeCriteria()).isNull();
        assertThat(plan.judge()).isNull();
    }

    @Test
    void dropsAttributeFilterNotMentionedByUserEvenWhenProductContextContainsIt() {
        mockLlmResponse("""
                {
                  "intent": "filter_current_results",
                  "execution_mode": "COMBINED",
                  "hard_filters": [
                    {"field": "price", "operator": "<=", "value": 50},
                    {"field": "attribute:\u7c7b\u578b", "operator": "equals", "value": "\u6709\u7ebf"}
                  ],
                  "preferences": [
                    {"code": "appearance", "userMeaning": "\u9002\u5408\u5e74\u8f7b\u4eba", "weight": 0.8}
                  ],
                  "zero_result_policy": "KEEP_PREVIOUS_RESULTS"
                }
                """);

        var plan = service.plan(
                "\u6211\u8981\u6bd4\u8f83\u9002\u5408\u5e74\u8f7b\u4eba\u7684\u6b3e\u5f0f",
                "mouse",
                "\u6709\u7ebf\u9f20\u6807",
                "1. \u6211\u8981\u4f4e\u4e8e50\u7684");

        assertThat(plan.hardFilters()).extracting(com.visioncart.api.dto.SemanticActionPlan.HardFilter::field)
                .containsExactly("price");
        assertThat(plan.preferences()).hasSize(1);
    }

    @Test
    void keepsAttributeFilterMentionedInHistoryWhenCurrentAddsPreference() {
        mockLlmResponse("""
                {
                  "intent": "filter_current_results",
                  "execution_mode": "COMBINED",
                  "hard_filters": [
                    {"field": "price", "operator": "<=", "value": 50},
                    {"field": "attribute:\u7c7b\u578b", "operator": "equals", "value": "\u6709\u7ebf"}
                  ],
                  "preferences": [
                    {"code": "appearance", "userMeaning": "\u9002\u5408\u5e74\u8f7b\u4eba", "weight": 0.8}
                  ],
                  "zero_result_policy": "KEEP_PREVIOUS_RESULTS"
                }
                """);

        var plan = service.plan(
                "\u9002\u5408\u5e74\u8f7b\u4eba\u7684\u6b3e\u5f0f",
                "mouse",
                "\u6709\u7ebf\u9f20\u6807",
                "1. \u6211\u8981\u4f4e\u4e8e50\u7684\n2. \u6211\u8981\u6709\u7ebf\u7684");

        assertThat(plan.hardFilters()).extracting(com.visioncart.api.dto.SemanticActionPlan.HardFilter::field)
                .containsExactly("price", "attribute:\u7c7b\u578b");
    }

    @Test
    void keepsAttributeFiltersMentionedByCurrentInput() {
        mockLlmResponse("""
                {
                  "intent": "filter_current_results",
                  "execution_mode": "STRICT_FILTER",
                  "hard_filters": [
                    {"field": "attribute:\u63a5\u53e3", "operator": "equals", "value": "Type-C"},
                    {"field": "attribute:\u6750\u8d28", "operator": "equals", "value": "\u7845\u80f6"},
                    {"field": "attribute:\u6b3e\u5f0f", "operator": "equals", "value": "\u900f\u660e"}
                  ],
                  "zero_result_policy": "KEEP_PREVIOUS_RESULTS"
                }
                """);

        var plan = service.plan(
                "\u6211\u8981Type-C\u63a5\u53e3\u3001\u7845\u80f6\u6750\u8d28\u3001\u900f\u660e\u6b3e",
                "phone_case",
                "\u624b\u673a\u58f3",
                "");

        assertThat(plan.hardFilters()).extracting(com.visioncart.api.dto.SemanticActionPlan.HardFilter::field)
                .containsExactly("attribute:\u63a5\u53e3", "attribute:\u6750\u8d28", "attribute:\u6b3e\u5f0f");
    }

    private void mockLlmResponse(String response) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec promptSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.getIfAvailable()).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(promptSpec);
        when(promptSpec.system(anyString())).thenReturn(promptSpec);
        when(promptSpec.user(anyString())).thenReturn(promptSpec);
        when(promptSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(response);
    }
}
