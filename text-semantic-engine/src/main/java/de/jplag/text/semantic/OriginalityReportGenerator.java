package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Generates a self-contained, Turnitin-style HTML "originality report" for a query document.
 * <p>
 * Every sentence of the query is aligned (by SBERT embedding cosine) to the most similar sentence across a set of
 * candidate source documents. Sentences whose best match reaches the threshold are highlighted inline and:
 * <ul>
 * <li>attributed to their best-matching source,</li>
 * <li>categorized by literal word overlap ({@link MatchCategory}: copy-paste / lightly edited / paraphrase),</li>
 * <li>checked for acknowledgement ({@link CitationDetector}): quoted/cited passages are attributed, the rest are
 * unattributed,</li>
 * <li>and, when the query's author is known, flagged as <em>self-reuse</em> if the source is by the same author
 * (self-plagiarism) rather than another author.</li>
 * </ul>
 * By default only unattributed matches are highlighted and counted; when {@code excludeAttributed} is false, attributed
 * matches are also shown but de-emphasized.
 * <p>
 * Note that sentence embeddings rate any two sentences on one topic highly whether or not either was copied, so the
 * threshold has to be set high (0.85 rather than 0.70) for similarity to mean reuse rather than shared subject matter.
 */
public class OriginalityReportGenerator {

    /**
     * Light-purple highlight for self-reuse (own prior work), kept distinct from the copy-paste/paraphrase category
     * colours.
     */
    private static final String SELF_REUSE_COLOUR = "#e1bee7";

    private final double matchThreshold;
    private final Function<String, List<EmbeddedSentence>> sentenceEmbedder;
    private final boolean excludeAttributed;
    /** Sentence embeddings per source document id, so a document shared by many queries is embedded once. */
    private final Map<String, List<EmbeddedSentence>> sentenceCache = new HashMap<>();

    /**
     * Creates the generator.
     * @param matchThreshold the minimum sentence cosine similarity to count as a match (e.g. 0.85).
     * @param sentenceEmbedder splits a text into sentences and embeds them (e.g.
     * {@code SbertEmbedder::embedSentencesWithText}).
     * @param excludeAttributed if true, quoted/cited matches are not highlighted or counted (only concerns are shown).
     */
    public OriginalityReportGenerator(double matchThreshold, Function<String, List<EmbeddedSentence>> sentenceEmbedder, boolean excludeAttributed) {
        this.matchThreshold = matchThreshold;
        this.sentenceEmbedder = sentenceEmbedder;
        this.excludeAttributed = excludeAttributed;
    }

    private record SourceDocument(String id, String author, List<EmbeddedSentence> sentences) {
    }

    private record Attribution(String text, String sourceId, String sourceSentence, int sourceSentenceIndex, double score, MatchCategory category,
            AttributionStatus attribution, String attributionEvidence, boolean selfReuse, boolean matched) {

        // Whether this match should be highlighted and counted (a match that is not an excluded attributed one).
        boolean reported(boolean excludeAttributed) {
            return matched && !(excludeAttributed && attribution.isAttributed());
        }
    }

    private record Totals(double overallPercent, double unattributedPercent, double attributedPercent, double excludedPercent,
            double selfReusePercent, Map<MatchCategory, Integer> wordsPerCategory, Map<String, Integer> wordsPerSource, int totalWords) {
    }

    /**
     * Generates the HTML report.
     * @param queryId the query document's name.
     * @param queryText the query document's text.
     * @param sources the candidate source documents to attribute matches to.
     * @param queryAuthor the query document's author; matches to sources by this author are flagged as self-reuse.
     * @return a complete, self-contained HTML document.
     */
    public String generate(String queryId, String queryText, List<ArchivedDocument> sources, String queryAuthor) {
        List<EmbeddedSentence> querySentences = sentenceEmbedder.apply(queryText);
        List<SourceDocument> sourceDocuments = new ArrayList<>();
        for (ArchivedDocument source : sources) {
            sourceDocuments.add(new SourceDocument(source.id(), source.author(), sentencesOf(source.id(), source.text())));
        }

        List<Attribution> attributions = attribute(querySentences, sourceDocuments, queryAuthor);
        Totals totals = totals(attributions);

        List<String> orderedSources = totals.wordsPerSource().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < orderedSources.size(); i++) {
            rankOf.put(orderedSources.get(i), i + 1);
        }

        return renderHtml(queryId, totals, attributions, sourceDocuments, orderedSources, rankOf, !queryAuthor.isBlank());
    }

    /**
     * Embeds a source document's sentences, reusing the result if this generator has seen the document before.
     * <p>
     * Every query document is compared against the same pool of sources, so without this each document's sentences would be
     * re-embedded once per query — quadratic in the corpus size, and the dominant cost of a run, since embedding is far
     * more expensive than the cosine comparisons it feeds. Keyed by document id, which is unique per corpus and stable for
     * the life of a run. Not thread-safe; generate one report at a time.
     */
    private List<EmbeddedSentence> sentencesOf(String documentId, String text) {
        return sentenceCache.computeIfAbsent(documentId, key -> sentenceEmbedder.apply(text));
    }

    private List<Attribution> attribute(List<EmbeddedSentence> querySentences, List<SourceDocument> sources, String queryAuthor) {
        List<Attribution> attributions = new ArrayList<>();
        for (int i = 0; i < querySentences.size(); i++) {
            EmbeddedSentence querySentence = querySentences.get(i);
            double bestScore = -1.0;
            String bestSource = null;
            String bestAuthor = "";
            String bestSentence = null;
            int bestIndex = -1;
            for (SourceDocument source : sources) {
                for (int candidateIndex = 0; candidateIndex < source.sentences().size(); candidateIndex++) {
                    EmbeddedSentence candidate = source.sentences().get(candidateIndex);
                    double similarity = cosine(querySentence.vector(), candidate.vector());
                    if (similarity > bestScore) {
                        bestScore = similarity;
                        bestSource = source.id();
                        bestAuthor = source.author();
                        bestSentence = candidate.text();
                        bestIndex = candidateIndex;
                    }
                }
            }
            boolean matched = bestSentence != null && bestScore >= matchThreshold;
            MatchCategory category = matched ? MatchCategory.fromWordOverlap(wordOverlap(querySentence.text(), bestSentence)) : null;
            String nextSentence = i + 1 < querySentences.size() ? querySentences.get(i + 1).text() : "";
            CitationDetector.AttributionCheck check = matched ? attributionWithLookahead(querySentence.text(), nextSentence) : null;
            boolean selfReuse = matched && !queryAuthor.isBlank() && queryAuthor.equals(bestAuthor);
            attributions.add(new Attribution(querySentence.text(), matched ? bestSource : null, bestSentence, bestIndex, bestScore, category,
                    check == null ? null : check.status(), check == null ? null : check.evidence(), selfReuse, matched));
        }
        return attributions;
    }

    /**
     * Determines a sentence's attribution, checking its own text first and then the following sentence: a citation commonly
     * trails the borrowed material as a separate clause or sentence, e.g. "...borrowed text. (Smith, 2020)."
     */
    private static CitationDetector.AttributionCheck attributionWithLookahead(String sentence, String nextSentence) {
        CitationDetector.AttributionCheck self = CitationDetector.detect(sentence);
        if (self.status() != AttributionStatus.UNATTRIBUTED) {
            return self;
        }
        CitationDetector.AttributionCheck next = CitationDetector.detect(nextSentence);
        return next.status() == AttributionStatus.CITED ? next : self;
    }

    private Totals totals(List<Attribution> attributions) {
        int totalWords = attributions.stream().mapToInt(attribution -> wordCount(attribution.text())).sum();
        Map<String, Integer> wordsPerSource = new LinkedHashMap<>();
        Map<MatchCategory, Integer> wordsPerCategory = new EnumMap<>(MatchCategory.class);
        int matchedWords = 0;
        int unattributedWords = 0;
        int excludedWords = 0;
        int selfReuseWords = 0;
        for (Attribution attribution : attributions) {
            if (!attribution.matched()) {
                continue;
            }
            int words = wordCount(attribution.text());
            if (!attribution.reported(excludeAttributed)) {
                excludedWords += words; // an attributed match hidden by excludeAttributed
                continue;
            }
            matchedWords += words;
            wordsPerSource.merge(attribution.sourceId(), words, Integer::sum);
            if (!attribution.attribution().isAttributed()) {
                unattributedWords += words;
            }
            if (attribution.selfReuse()) {
                // Self-reuse is tracked on its own, not folded into the copy-paste/paraphrase category breakdown.
                selfReuseWords += words;
            } else {
                wordsPerCategory.merge(attribution.category(), words, Integer::sum);
            }
        }
        double overall = percent(matchedWords, totalWords);
        double unattributed = percent(unattributedWords, totalWords);
        return new Totals(overall, unattributed, overall - unattributed, percent(excludedWords, totalWords), percent(selfReuseWords, totalWords),
                wordsPerCategory, wordsPerSource, totalWords);
    }

    /** Anchor of the highlighted query sentence at this attribution index (for jumping back from the source passage). */
    private static String queryAnchor(int attributionIndex) {
        return "q" + attributionIndex;
    }

    /** Anchor of a matched sentence inside a source document's passage block (for jumping there from a highlight). */
    private static String passageAnchor(int sourceRank, int sentenceIndex) {
        return "m-" + sourceRank + "-" + sentenceIndex;
    }

    private String renderHtml(String queryId, Totals totals, List<Attribution> attributions, List<SourceDocument> sources,
            List<String> orderedSources, Map<String, Integer> rankOf, boolean authorKnown) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Originality report - ").append(escape(queryId)).append("</title>");
        html.append("<style>").append(css()).append("</style></head><body>");

        html.append("<header><div class=\"title\">Originality Report</div><div class=\"subtitle\">").append(escape(queryId))
                .append("</div></header>");

        html.append("<div class=\"legend\"><span class=\"grp\">Type:</span>");
        for (MatchCategory category : MatchCategory.values()) {
            html.append(chip(category.colour(), category.label(), percent(totals.wordsPerCategory().getOrDefault(category, 0), totals.totalWords())));
        }
        if (authorKnown) {
            html.append("<span class=\"grp\">Self:</span>");
            html.append(chip(SELF_REUSE_COLOUR, "Self-reuse (own prior work)", totals.selfReusePercent()));
        }
        if (excludeAttributed) {
            html.append("<span class=\"grp\">Excluded:</span>");
            html.append(chip("#a5d6a7", "Quoted / cited (hidden)", totals.excludedPercent()));
        } else {
            html.append("<span class=\"grp\">Attribution:</span>");
            html.append(chip("#a5d6a7", "Quoted / cited", totals.attributedPercent()));
            html.append(chip("#ef5350", "Unattributed (concern)", totals.unattributedPercent()));
        }
        html.append("</div>");

        // For every matched source sentence, remember the first query sentence that hit it, so the passage can link back.
        Map<String, Map<Integer, Integer>> passageBackLinks = new HashMap<>();
        for (int i = 0; i < attributions.size(); i++) {
            Attribution attribution = attributions.get(i);
            if (attribution.reported(excludeAttributed)) {
                passageBackLinks.computeIfAbsent(attribution.sourceId(), key -> new LinkedHashMap<>()).putIfAbsent(attribution.sourceSentenceIndex(),
                        i);
            }
        }

        html.append("<div class=\"layout\"><main>");
        for (int i = 0; i < attributions.size(); i++) {
            Attribution attribution = attributions.get(i);
            if (attribution.reported(excludeAttributed)) {
                boolean attributed = attribution.attribution().isAttributed();
                String background = attribution.selfReuse() ? SELF_REUSE_COLOUR : attribution.category().colour();
                int rank = rankOf.get(attribution.sourceId());
                html.append("<a class=\"match").append(attributed ? " attributed" : "").append(attribution.selfReuse() ? " self" : "")
                        .append("\" style=\"background:").append(background).append("\" title=\"").append(escape(tooltip(attribution, rank)))
                        .append("\" id=\"").append(queryAnchor(i)).append("\" href=\"#")
                        .append(passageAnchor(rank, attribution.sourceSentenceIndex())).append("\">").append(escape(attribution.text()));
                if (attribution.selfReuse()) {
                    html.append("<sup class=\"self-mark\">↺</sup>");
                }
                if (attributed) {
                    html.append("<sup class=\"att\">✓</sup>");
                }
                html.append("<sup>").append(rank).append("</sup></a> ");
            } else {
                html.append("<span>").append(escape(attribution.text())).append("</span> ");
            }
        }
        html.append("</main><aside><h2>Sources</h2>");
        if (orderedSources.isEmpty()) {
            html.append("<p class=\"none\">No matching sources found.</p>");
        }
        for (String source : orderedSources) {
            html.append("<div class=\"source\"><span class=\"swatch\">").append(rankOf.get(source)).append("</span><span class=\"sid\">")
                    .append("<a href=\"#src-").append(rankOf.get(source)).append("\">").append(escape(source))
                    .append("</a></span><span class=\"pct\">").append(format(percent(totals.wordsPerSource().get(source), totals.totalWords())))
                    .append("</span></div>");
        }
        html.append("</aside></div>");
        html.append(renderSourcePassages(sources, orderedSources, rankOf, passageBackLinks));
        html.append("<footer>").append(excludeAttributed
                ? "Only unattributed matches are shown; quoted/cited passages are hidden and excluded from the score. "
                : "Overall similarity is the share of words matched to a source; <b>unattributed</b> excludes passages that are quoted or cited "
                        + "(marked ✓) and is the actual plagiarism concern. ")
                .append("Matches marked ↺ reuse the submitter's own prior work (self-plagiarism). Matches are semantic (SBERT, threshold ")
                .append(String.format(Locale.ROOT, "%.2f", matchThreshold))
                .append("); the type comes from literal word overlap. On a set of documents about one subject some similarity is expected, so "
                        + "read a match as evidence only where the wording, not merely the subject, is shared. Click a highlight to jump to the "
                        + "matched passage in the source below; click the passage to jump back.</footer>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Renders each matching source document's full text below the report, with the matched sentences highlighted and
     * anchored, so a click on an inline highlight lands directly on the passage it was matched to (and back).
     */
    private static String renderSourcePassages(List<SourceDocument> sources, List<String> orderedSources, Map<String, Integer> rankOf,
            Map<String, Map<Integer, Integer>> passageBackLinks) {
        if (orderedSources.isEmpty()) {
            return "";
        }
        Map<String, SourceDocument> sourceById = new HashMap<>();
        sources.forEach(source -> sourceById.put(source.id(), source));
        StringBuilder html = new StringBuilder("<section class=\"passages\"><h2>Matched source passages</h2>");
        for (String sourceId : orderedSources) {
            int rank = rankOf.get(sourceId);
            Map<Integer, Integer> backLinks = passageBackLinks.getOrDefault(sourceId, Map.of());
            html.append("<details class=\"srcdoc\" id=\"src-").append(rank).append("\" open><summary><span class=\"swatch\">").append(rank)
                    .append("</span>").append(escape(sourceId)).append("</summary><div class=\"srctext\">");
            List<EmbeddedSentence> sentences = sourceById.get(sourceId).sentences();
            for (int j = 0; j < sentences.size(); j++) {
                Integer backLink = backLinks.get(j);
                if (backLink != null) {
                    html.append("<a class=\"hit\" id=\"").append(passageAnchor(rank, j)).append("\" href=\"#").append(queryAnchor(backLink))
                            .append("\" title=\"Matched passage - click to jump back to the document\">").append(escape(sentences.get(j).text()))
                            .append("</a> ");
                } else {
                    html.append("<span>").append(escape(sentences.get(j).text())).append("</span> ");
                }
            }
            html.append("</div></details>");
        }
        return html.append("</section>").toString();
    }

    private static String chip(String colour, String label, double percent) {
        return "<span class=\"chip\"><span class=\"box\" style=\"background:" + colour + "\"></span>" + escape(label) + " <b>" + format(percent)
                + "</b></span>";
    }

    private static String tooltip(Attribution attribution, int rank) {
        String excerpt = attribution.sourceSentence() == null ? "" : attribution.sourceSentence();
        if (excerpt.length() > 140) {
            excerpt = excerpt.substring(0, 140) + "...";
        }
        String status = attribution.attribution().label();
        if (attribution.attributionEvidence() != null) {
            status += " (" + attribution.attributionEvidence() + ")";
        }
        String self = attribution.selfReuse() ? "SELF-REUSE - " : "";
        return String.format(Locale.ROOT, "%s%s - %s - source %d %s (%.0f%% similar): %s -- Click to open the matched passage in the source.", self,
                attribution.category().label(), status, rank, attribution.sourceId(), attribution.score() * 100, excerpt);
    }

    /** Cosine similarity of two unit-length embeddings, i.e. their dot product. */
    private static double cosine(float[] first, float[] second) {
        double dot = 0.0;
        for (int k = 0; k < first.length; k++) {
            dot += first[k] * second[k];
        }
        return dot;
    }

    /** Jaccard similarity of the two sentences' word sets, measuring literal word overlap. */
    private static double wordOverlap(String first, String second) {
        Set<String> a = words(first);
        Set<String> b = words(second == null ? "" : second);
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                words.add(token);
            }
        }
        return words;
    }

    private static int wordCount(String text) {
        String trimmed = text.trim();
        return trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
    }

    private static double percent(int part, int total) {
        return total == 0 ? 0.0 : 100.0 * part / total;
    }

    private static String format(double percent) {
        return String.format(Locale.ROOT, "%.0f%%", percent);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String css() {
        return "*{box-sizing:border-box}body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#222;"
                + "background:#f5f5f5}header{display:flex;align-items:center;gap:16px;padding:18px 28px;background:#fff;border-bottom:1px solid #e0e0e0}"
                + ".title{font-size:20px;font-weight:700}.subtitle{color:#666}"
                + ".legend{display:flex;gap:14px;flex-wrap:wrap;align-items:center;padding:12px 28px;background:#fafafa;border-bottom:1px solid #eee;"
                + "font-size:13px}.grp{color:#999;font-weight:700;text-transform:uppercase;font-size:11px}"
                + ".chip{display:flex;align-items:center;gap:6px;color:#555}.chip .box{width:14px;height:14px;border-radius:3px;display:inline-block}"
                + ".layout{display:flex;gap:20px;max-width:1100px;margin:24px auto;padding:0 20px;align-items:flex-start}"
                + "main{flex:1;background:#fff;padding:28px 32px;border-radius:8px;line-height:2;font-size:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + ".match{border-radius:3px;padding:1px 2px;cursor:pointer;color:inherit;text-decoration:none}"
                + ".match sup{font-size:10px;font-weight:700;color:#555;margin-left:1px}"
                + ".match.attributed{opacity:.45;text-decoration:underline dotted}.match .att{color:#2e7d32}" + ".match .self-mark{color:#8e24aa}"
                + "aside{width:270px;background:#fff;padding:20px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);position:sticky;top:20px}"
                + "aside h2{font-size:13px;text-transform:uppercase;color:#888;margin:0 0 14px}"
                + ".source{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #f0f0f0}"
                + ".swatch{width:22px;height:22px;border-radius:4px;background:#eee;display:flex;align-items:center;justify-content:center;"
                + "font-size:12px;font-weight:700;color:#444}.sid{flex:1;font-size:14px;word-break:break-word}.pct{font-weight:700}"
                + ".sid a{color:inherit;text-decoration:none}.sid a:hover{text-decoration:underline}"
                + ".passages{max-width:1100px;margin:0 auto 16px;padding:0 20px}"
                + ".passages h2{font-size:13px;text-transform:uppercase;color:#888;margin:0 0 12px}"
                + ".srcdoc{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:12px}"
                + ".srcdoc summary{cursor:pointer;padding:12px 20px;font-size:14px;font-weight:600;display:flex;align-items:center;gap:10px}"
                + ".srctext{padding:0 20px 20px;line-height:1.9;font-size:14px;color:#444}"
                + ".hit{background:#fff59d;border-radius:3px;padding:1px 2px;color:inherit;text-decoration:none}"
                + ".match,.hit{scroll-margin:120px}.match:target,.hit:target{outline:3px solid #fb8c00;outline-offset:1px}"
                + ".none{color:#888}footer{max-width:1100px;margin:8px auto 40px;padding:0 20px;color:#999;font-size:12px}";
    }
}
