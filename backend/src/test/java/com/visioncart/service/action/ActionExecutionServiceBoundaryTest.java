package com.visioncart.service.action;

import com.visioncart.service.recognition.AttributeCorrectionService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ActionExecutionServiceBoundaryTest {

    @Test
    void correctionExecutionDependsOnServiceNotController() {
        assertThat(Arrays.stream(ActionExecutionService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .contains(AttributeCorrectionService.class.getName())
                .noneMatch(name -> name.endsWith(".AttributeCorrectionController"));

        assertThat(Arrays.stream(ActionExecutionService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getName))
                .contains(AttributeCorrectionService.class.getName())
                .noneMatch(name -> name.endsWith(".AttributeCorrectionController"));
    }
}
