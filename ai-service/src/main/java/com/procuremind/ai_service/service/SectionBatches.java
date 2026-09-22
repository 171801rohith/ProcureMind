package com.procuremind.ai_service.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Splits an ordered list of contract sections into batches that each fit a character budget.
 *
 * <p>This is what makes full-document coverage a property of the code rather than of the
 * model's judgement. Every item lands in exactly one batch and document order is preserved,
 * so the number of model calls is {@code ceil(total / budget)}: known before the first call
 * and growing linearly with the document instead of being capped at an arbitrary number of
 * lookups.
 *
 * <p>An item larger than the whole budget gets a batch to itself rather than being dropped.
 * Contracts really do contain single sections bigger than a sensible prompt, so losing them
 * would silently punch a hole in the coverage this class exists to guarantee; the caller
 * trims the text it sends, but the section is still screened.
 */
public final class SectionBatches {

    private SectionBatches() {
    }

    /**
     * @param items   the sections, in document order
     * @param weigher characters each item will contribute to a prompt
     * @param budgetChars maximum characters per batch; must be positive
     * @return batches in document order, each non-empty, together containing every item once
     */
    public static <T> List<List<T>> byCharacterBudget(List<T> items, ToIntFunction<T> weigher, int budgetChars) {
        if (budgetChars <= 0) {
            throw new IllegalArgumentException("budgetChars must be positive but was " + budgetChars);
        }
        List<List<T>> batches = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return batches;
        }

        List<T> current = new ArrayList<>();
        int currentChars = 0;

        for (T item : items) {
            int weight = Math.max(0, weigher.applyAsInt(item));
            if (!current.isEmpty() && currentChars + weight > budgetChars) {
                batches.add(List.copyOf(current));
                current = new ArrayList<>();
                currentChars = 0;
            }
            current.add(item);
            currentChars += weight;
        }
        batches.add(List.copyOf(current));
        return batches;
    }
}
