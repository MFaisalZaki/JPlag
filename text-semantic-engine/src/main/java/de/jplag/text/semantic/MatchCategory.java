package de.jplag.text.semantic;

/**
 * The type of a matched sentence, derived from how much literal word overlap it has with its source (all matches
 * already share high semantic similarity, which is why they matched). High word overlap means the wording was reused;
 * low overlap means the meaning was reused with different words.
 */
public enum MatchCategory {

    /** Near-identical wording: copied text. */
    COPY_PASTE("Copy-paste", "#ef9a9a"),
    /** Same wording with some words changed: lightly edited. */
    LIGHTLY_EDITED("Lightly edited", "#ffcc80"),
    /** Same meaning, largely different words: paraphrase. */
    PARAPHRASE("Paraphrase", "#90caf9");

    private final String label;
    private final String colour;

    MatchCategory(String label, String colour) {
        this.label = label;
        this.colour = colour;
    }

    /**
     * @return the human-readable label.
     */
    public String label() {
        return label;
    }

    /**
     * @return the highlight colour for this category.
     */
    public String colour() {
        return colour;
    }

    /**
     * Classifies a match by the word-overlap (Jaccard) between the query sentence and its matched source sentence.
     * @param wordOverlap the Jaccard similarity of the two sentences' word sets, in {@code [0, 1]}.
     * @return the category.
     */
    public static MatchCategory fromWordOverlap(double wordOverlap) {
        if (wordOverlap >= 0.8) {
            return COPY_PASTE;
        }
        if (wordOverlap >= 0.4) {
            return LIGHTLY_EDITED;
        }
        return PARAPHRASE;
    }
}
