package com.visioncart.api.dto;

import java.util.List;

public record PagedResult<T>(long total, int page, int size, List<T> items) {
}
