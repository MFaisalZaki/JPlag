package de.jplag.text.semantic;

/**
 * A term shared by a pair of submissions and how much it contributed to their similarity (the product of the term's
 * TF-IDF weight in both submissions). This makes a match explainable: it shows which content drives the score.
 * @param term the normalized shared term.
 * @param contribution the contribution to the cosine similarity (higher means more influential).
 */
public record SharedTerm(String term, double contribution) {
}
