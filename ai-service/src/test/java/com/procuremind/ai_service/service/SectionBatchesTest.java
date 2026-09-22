package com.procuremind.ai_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Batching is what turns full-document coverage from a hope into a property, so the
 * invariants asserted here are the guarantee itself: nothing is dropped, nothing is
 * duplicated, and document order survives.
 */
class SectionBatchesTest {

    private static final ToIntFunction<String> LENGTH = String::length;

    @Test
    void everyItemAppearsExactlyOnceAndInOrder() {
        List<String> items = IntStream.range(0, 70).mapToObj(i -> "section-" + i).toList();

        List<List<String>> batches = SectionBatches.byCharacterBudget(items, LENGTH, 100);

        List<String> flattened = new ArrayList<>();
        batches.forEach(flattened::addAll);
        assertThat(flattened).isEqualTo(items);
        assertThat(flattened).doesNotHaveDuplicates();
    }

    @ParameterizedTest(name = "{0} items of 10 chars at a {1} char budget gives {2} batch(es)")
    @CsvSource({
            "10,100,1",
            "11,100,2",
            "70,100,7",
            "70,1000,1",
            "1,100,1"
    })
    void theBatchCountIsArithmeticAndKnownBeforeAnyModelCall(int itemCount, int budget, int expectedBatches) {
        // This is the execution bound: the number of model calls is a function of the
        // document and the budget, not of what the model decides to do.
        List<String> items = IntStream.range(0, itemCount).mapToObj(i -> "0123456789").toList();

        assertThat(SectionBatches.byCharacterBudget(items, LENGTH, budget)).hasSize(expectedBatches);
    }

    @Test
    void noBatchExceedsTheBudgetUnlessASingleItemDoes() {
        List<String> items = List.of("aaaaa", "bbbbb", "ccccc", "ddddd");

        List<List<String>> batches = SectionBatches.byCharacterBudget(items, LENGTH, 12);

        assertThat(batches).allSatisfy(batch ->
                assertThat(batch.stream().mapToInt(String::length).sum()).isLessThanOrEqualTo(12));
    }

    @Test
    void anItemLargerThanTheWholeBudgetGetsItsOwnBatchRatherThanBeingDropped() {
        // Contracts contain single sections bigger than a sensible prompt. Dropping one
        // would punch a silent hole in the coverage this class exists to guarantee.
        List<String> items = List.of("small", "x".repeat(5000), "also-small");

        List<List<String>> batches = SectionBatches.byCharacterBudget(items, LENGTH, 100);

        List<String> flattened = new ArrayList<>();
        batches.forEach(flattened::addAll);
        assertThat(flattened).isEqualTo(items);
        assertThat(batches).anySatisfy(batch -> assertThat(batch).containsExactly("x".repeat(5000)));
    }

    @Test
    void anEmptyDocumentProducesNoBatches() {
        assertThat(SectionBatches.byCharacterBudget(List.of(), LENGTH, 100)).isEmpty();
        assertThat(SectionBatches.byCharacterBudget(null, LENGTH, 100)).isEmpty();
    }

    @Test
    void aNonPositiveBudgetIsRejectedRatherThanLoopingForever() {
        assertThatThrownBy(() -> SectionBatches.byCharacterBudget(List.of("a"), LENGTH, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
