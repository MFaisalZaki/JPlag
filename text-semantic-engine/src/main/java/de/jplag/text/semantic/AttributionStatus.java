package de.jplag.text.semantic;

/**
 * Whether a matched passage is acknowledged in the query document. Reusing a source is acceptable when it is attributed
 * (quoted and/or cited); an unattributed match is the actual plagiarism concern.
 */
public enum AttributionStatus {

    /** The passage is enclosed in quotation marks. */
    QUOTED("Quoted"),
    /** The passage has a nearby citation (author-year, numeric reference, URL, or DOI). */
    CITED("Cited"),
    /** No quotation or citation was found: an unattributed match. */
    UNATTRIBUTED("Unattributed");

    private final String label;

    AttributionStatus(String label) {
        this.label = label;
    }

    /**
     * @return the human-readable label.
     */
    public String label() {
        return label;
    }

    /**
     * @return whether the passage is attributed (quoted or cited), i.e. not a plagiarism concern on its own.
     */
    public boolean isAttributed() {
        return this != UNATTRIBUTED;
    }
}
