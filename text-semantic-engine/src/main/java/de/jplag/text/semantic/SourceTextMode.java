package de.jplag.text.semantic;

/**
 * How much of a matched source a report reproduces underneath the document.
 * <p>
 * Showing the source in full is what makes a match checkable at a glance, and against a cohort's own submissions —
 * which the institution holds anyway — there is nothing to weigh against that. Against published material there is: a
 * report that quotes several chapters of a set text to justify a handful of matched sentences reproduces far more of
 * the book than the finding needs, and a licence that allows matching against a text does not necessarily allow
 * redistributing it inside a document that is then circulated. The same setting also decides how large a report gets,
 * which is the practical limit when the sources are chapter-length rather than essay-length.
 */
public enum SourceTextMode {

    /** The whole source document, with its matched passages highlighted. The default for a cohort's own work. */
    FULL,
    /** Only the matched passages and a sentence of context either side, with the omissions marked. */
    EXCERPT,
    /** No source text at all: the report names its sources and says how much each accounts for, and stops there. */
    NONE
}
