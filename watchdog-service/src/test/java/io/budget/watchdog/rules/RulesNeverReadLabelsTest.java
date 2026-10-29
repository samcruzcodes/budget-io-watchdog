package io.budget.watchdog.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Detection must never see the answer key: no code on the path into the rules (the rules themselves, the ingest code
 * that feeds them and warms them up, and the alerts they produce) may mention the labels, their topic or table, or the
 * evaluation package that reads them. A plain text check of the source files, so it needs no extra library. IssueType
 * is allowed, because each rule declares which type it detects.
 */
class RulesNeverReadLabelsTest {

    private static final Path MAIN_SOURCE = Path.of("src/main/java/io/budget/watchdog");

    private static final List<String> FORBIDDEN = List.of(
            "GroundTruthLabel", "EdgeCase", "TRANSACTION_LABELS", "transaction-labels", "ground_truth_labels",
            "io.budget.watchdog.evaluation");

    @Test
    void theRulesPackageIsChecked() throws IOException {
        // So the check can't pass by reading an empty or misspelled folder.
        assertThat(sources("rules")).hasSizeGreaterThanOrEqualTo(6);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules", "ingest", "alerts"})
    void noDetectionCodeMentionsTheLabels(String packageName) throws IOException {
        List<Path> sources = sources(packageName);
        assertThat(sources).isNotEmpty();

        for (Path source : sources) {
            String code = Files.readString(source);
            assertThat(FORBIDDEN).as(packageName + "/" + source.getFileName()).noneMatch(code::contains);
        }
    }

    private static List<Path> sources(String packageName) throws IOException {
        try (Stream<Path> files = Files.list(MAIN_SOURCE.resolve(packageName))) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }
}
