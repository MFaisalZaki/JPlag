package de.jplag.text.semantic;

import java.util.List;

/**
 * The similarity between two submissions, with the terms that contributed most to it.
 * @param firstSubmission the name of the first submission.
 * @param secondSubmission the name of the second submission.
 * @param similarity the cosine similarity in {@code [0, 1]}.
 * @param topSharedTerms the most influential shared terms, most influential first.
 */
public record SubmissionPairSimilarity(String firstSubmission, String secondSubmission, double similarity, List<SharedTerm> topSharedTerms) {
}
