package com.visioncart.service.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class QueryBudgetAllocator {

    public List<PlannedQuery> allocate(QueryPlan plan, RetrievalLevel level) {
        if (plan == null) {
            return List.of();
        }
        List<PlannedQuery> result = new ArrayList<>();
        if (level.ordinal() >= RetrievalLevel.PRIMARY.ordinal()) {
            plan.primaryQueries().forEach(query -> result.add(new PlannedQuery(query, RetrievalLevel.PRIMARY)));
        }
        if (level.ordinal() >= RetrievalLevel.SECONDARY.ordinal()) {
            plan.secondaryQueries().forEach(query -> result.add(new PlannedQuery(query, RetrievalLevel.SECONDARY)));
        }
        if (level.ordinal() >= RetrievalLevel.FALLBACK.ordinal()) {
            plan.fallbackQueries().forEach(query -> result.add(new PlannedQuery(query, RetrievalLevel.FALLBACK)));
        }
        return dedupe(result);
    }

    public List<String> queries(QueryPlan plan, RetrievalLevel level) {
        return allocate(plan, level).stream()
                .map(PlannedQuery::query)
                .filter(query -> !query.isBlank())
                .toList();
    }

    private List<PlannedQuery> dedupe(List<PlannedQuery> queries) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<PlannedQuery> result = new ArrayList<>();
        for (PlannedQuery planned : queries) {
            if (seen.add(planned.query())) {
                result.add(planned);
            }
        }
        return result;
    }
}
