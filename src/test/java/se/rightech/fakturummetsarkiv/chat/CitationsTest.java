package se.rightech.fakturummetsarkiv.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationsTest {

    @Test
    void removesCitationShapes() {
        assertThat(Citations.strip("Claim [1].")).isEqualTo("Claim.");
        assertThat(Citations.strip("Claim [1][3].")).isEqualTo("Claim.");
        assertThat(Citations.strip("Claim [3, 4].")).isEqualTo("Claim.");
        assertThat(Citations.strip("Claim [1,2].")).isEqualTo("Claim.");
        assertThat(Citations.strip("Claim [1-3].")).isEqualTo("Claim.");
        assertThat(Citations.strip("Claim [ 2 ].")).isEqualTo("Claim.");
    }

    @Test
    void leavesNonCitationBracketsAndCodeAlone() {
        assertThat(Citations.strip("See [note] and [appendix]."))
                .isEqualTo("See [note] and [appendix].");
        assertThat(Citations.strip("Use `arr[0]` and [1]."))
                .isEqualTo("Use `arr[0]` and.");
        assertThat(Citations.strip("```\nint x = arr[0]; // [1]\n```\nClaim [2]."))
                .isEqualTo("```\nint x = arr[0]; // [1]\n```\nClaim.");
    }

    @Test
    void stripsOnlyAssistantHistory() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Question [1]"),
                Map.of("role", "assistant", "content", "Answer [1]."));

        assertThat(Citations.stripAssistantHistory(history)).containsExactly(
                Map.of("role", "user", "content", "Question [1]"),
                Map.of("role", "assistant", "content", "Answer."));
    }

    @Test
    void findsCitedNumbersOutsideCode() {
        assertThat(Citations.citedNumbers("A [1] B [3, 4] C `x[9]` D ```\n[7]\n``` [2]"))
                .containsExactly(1, 3, 4, 2);
    }

    @Test
    void handlesNullAndEmptyText() {
        assertThat(Citations.strip(null)).isNull();
        assertThat(Citations.strip("")).isEmpty();
    }
}
