package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Detection must never see the answer key: no rule may mention the labels, their topic or table, or the evaluation
 * package that will read them. A plain text check of the source files, so it needs no extra library. IssueType is
 * allowed, because each rule declares which type it detects.
 */
class RulesNeverReadLabelsTest {

    private static final Path RULES_SOURCE = Path.of("src/main/java/io/budget/watchdog/rules");

    private static final List<String> FORBIDDEN = List.of(
            "GroundTruthLabel", "EdgeCase", "TRANSACTION_LABELS", "transaction-labels", "ground_truth_labels",
            "io.budget.watchdog.evaluation");

    @Test
    void noRuleMentionsTheLabels() throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.list(RULES_SOURCE)) {
            sources = files.filter(file -> file.toString().endsWith(".java")).toList();
        }
        assertThat(sources).hasSizeGreaterThanOrEqualTo(6); // so it can't pass by reading an empty folder

        for (Path source : sources) {
            String code = Files.readString(source);
            assertThat(FORBIDDEN).as(source.getFileName().toString()).noneMatch(code::contains);
        }
    }
}
