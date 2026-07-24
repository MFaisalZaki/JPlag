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
 * <li>and checked for acknowledgement ({@link CitationDetector}): quoted/cited passages are attributed, the rest are
 * unattributed.</li>
 * </ul>
 * The report shows the overall similarity and, separately, the <em>unattributed</em> similarity (the actual plagiarism
 * concern), a category breakdown, a ranked source list, and the query text with matched sentences colour-coded by
 * category (attributed matches are de-emphasized).
 */
public class OriginalityReportGenerator {

    private final double matchThreshold;
    private final Function<String, List<EmbeddedSentence>> sentenceEmbedder;

    /**
     * @param matchThreshold the minimum sentence cosine similarity to count as a match (e.g. 0.7).
     * @param sentenceEmbedder splits a text into sentences and embeds them (e.g.
     * {@code SbertEmbedder::embedSentencesWithText}).
     */
    public OriginalityReportGenerator(double matchThreshold, Function<String, List<EmbeddedSentence>> sentenceEmbedder) {
        this.matchThreshold = matchThreshold;
        this.sentenceEmbedder = sentenceEmbedder;
    }

    private record SourceDocument(String id, List<EmbeddedSentence> sentences) {
    }

    private record Attribution(String text, String sourceId, String sourceSentence, double score, MatchCategory category,
            AttributionStatus attribution, String attributionEvidence, boolean matched) {
    }

    private record Totals(double overallPercent, double unattributedPercent, double attributedPercent, Map<MatchCategory, Integer> wordsPerCategory,
            Map<String, Integer> wordsPerSource, int totalWords) {
    }

    /**
     * Generates the HTML report.
     * @param queryId the query document's name.
     * @param queryText the query document's text.
     * @param sources the candidate source documents to attribute matches to.
     * @return a complete, self-contained HTML document.
     */
    public String generate(String queryId, String queryText, List<ArchivedDocument> sources) {
        List<EmbeddedSentence> querySentences = sentenceEmbedder.apply(queryText);
        List<SourceDocument> sourceDocuments = new ArrayList<>();
        for (ArchivedDocument source : sources) {
            sourceDocuments.add(new SourceDocument(source.id(), sentenceEmbedder.apply(source.text())));
        }

        List<Attribution> attributions = attribute(querySentences, sourceDocuments);
        Totals totals = totals(querySentences, attributions);

        List<String> orderedSources = totals.wordsPerSource().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < orderedSources.size(); i++) {
            rankOf.put(orderedSources.get(i), i + 1);
        }

        return renderHtml(queryId, totals, attributions, orderedSources, rankOf);
    }

    private List<Attribution> attribute(List<EmbeddedSentence> querySentences, List<SourceDocument> sources) {
        List<Attribution> attributions = new ArrayList<>();
        for (int i = 0; i < querySentences.size(); i++) {
            EmbeddedSentence querySentence = querySentences.get(i);
            double bestScore = -1.0;
            String bestSource = null;
            String bestSentence = null;
            for (SourceDocument source : sources) {
                for (EmbeddedSentence candidate : source.sentences()) {
                    double similarity = SbertBackend.cosine(querySentence.vector(), candidate.vector());
                    if (similarity > bestScore) {
                        bestScore = similarity;
                        bestSource = source.id();
                        bestSentence = candidate.text();
                    }
                }
            }
            boolean matched = bestSource != null && bestScore >= matchThreshold;
            MatchCategory category = matched ? MatchCategory.fromWordOverlap(wordOverlap(querySentence.text(), bestSentence)) : null;
            String nextSentence = i + 1 < querySentences.size() ? querySentences.get(i + 1).text() : "";
            CitationDetector.AttributionCheck check = matched ? attributionWithLookahead(querySentence.text(), nextSentence) : null;
            attributions.add(new Attribution(querySentence.text(), matched ? bestSource : null, bestSentence, bestScore, category,
                    check == null ? null : check.status(), check == null ? null : check.evidence(), matched));
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

    private Totals totals(List<EmbeddedSentence> querySentences, List<Attribution> attributions) {
        int totalWords = querySentences.stream().mapToInt(sentence -> wordCount(sentence.text())).sum();
        Map<String, Integer> wordsPerSource = new LinkedHashMap<>();
        Map<MatchCategory, Integer> wordsPerCategory = new EnumMap<>(MatchCategory.class);
        int matchedWords = 0;
        int unattributedWords = 0;
        for (Attribution attribution : attributions) {
            if (attribution.matched()) {
                int words = wordCount(attribution.text());
                matchedWords += words;
                wordsPerSource.merge(attribution.sourceId(), words, Integer::sum);
                wordsPerCategory.merge(attribution.category(), words, Integer::sum);
                if (!attribution.attribution().isAttributed()) {
                    unattributedWords += words;
                }
            }
        }
        double overall = totalWords == 0 ? 0.0 : 100.0 * matchedWords / totalWords;
        double unattributed = totalWords == 0 ? 0.0 : 100.0 * unattributedWords / totalWords;
        double attributed = overall - unattributed;
        return new Totals(overall, unattributed, attributed, wordsPerCategory, wordsPerSource, totalWords);
    }

    private String renderHtml(String queryId, Totals totals, List<Attribution> attributions, List<String> orderedSources,
            Map<String, Integer> rankOf) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Originality report - ").append(escape(queryId)).append("</title>");
        html.append("<style>").append(css()).append("</style></head><body>");

        html.append("<header><div><div class=\"title\">Originality Report</div><div class=\"subtitle\">").append(escape(queryId))
                .append("</div></div><div class=\"badges\">");
        html.append(badge(totals.overallPercent(), scoreColour(totals.overallPercent()), "similarity"));
        html.append(badge(totals.unattributedPercent(), "#d32f2f", "unattributed"));
        html.append("</div></header>");

        html.append("<div class=\"legend\"><span class=\"grp\">Type:</span>");
        for (MatchCategory category : MatchCategory.values()) {
            double percent = percent(totals.wordsPerCategory().getOrDefault(category, 0), totals.totalWords());
            html.append(chip(category.colour(), category.label(), percent));
        }
        html.append("<span class=\"grp\">Attribution:</span>");
        html.append(chip("#a5d6a7", "Quoted / cited", totals.attributedPercent()));
        html.append(chip("#ef5350", "Unattributed (concern)", totals.unattributedPercent()));
        html.append("</div>");

        html.append("<div class=\"layout\"><main>");
        for (Attribution attribution : attributions) {
            if (attribution.matched()) {
                boolean attributed = attribution.attribution().isAttributed();
                html.append("<span class=\"match").append(attributed ? " attributed" : "").append("\" style=\"background:")
                        .append(attribution.category().colour()).append("\" title=\"")
                        .append(escape(tooltip(attribution, rankOf.get(attribution.sourceId())))).append("\">").append(escape(attribution.text()));
                if (attributed) {
                    html.append("<sup class=\"att\">✓</sup>");
                }
                html.append("<sup>").append(rankOf.get(attribution.sourceId())).append("</sup></span> ");
            } else {
                html.append("<span>").append(escape(attribution.text())).append("</span> ");
            }
        }
        html.append("</main><aside><h2>Sources</h2>");
        if (orderedSources.isEmpty()) {
            html.append("<p class=\"none\">No matching sources found.</p>");
        }
        for (String source : orderedSources) {
            double percent = percent(totals.wordsPerSource().get(source), totals.totalWords());
            html.append("<div class=\"source\"><span class=\"swatch\">").append(rankOf.get(source)).append("</span><span class=\"sid\">")
                    .append(escape(source)).append("</span><span class=\"pct\">").append(format(percent)).append("</span></div>");
        }
        html.append("</aside></div>");
        html.append("<footer>Overall similarity is the share of words matched to a source; <b>unattributed</b> excludes passages that are "
                + "quoted or cited (marked ✓) and is the actual plagiarism concern. Matches are semantic (SBERT, threshold ")
                .append(String.format(Locale.ROOT, "%.2f", matchThreshold)).append("); the type comes from literal word overlap.</footer>");
        html.append("</body></html>");
        return html.toString();
    }

    private static String badge(double percent, String colour, String label) {
        return "<div class=\"score\" style=\"--c:" + colour + "\">" + format(percent) + "<span>" + label + "</span></div>";
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
        String attribution1 = attribution.attribution().label();
        if (attribution.attributionEvidence() != null) {
            attribution1 += " (" + attribution.attributionEvidence() + ")";
        }
        return String.format(Locale.ROOT, "%s - %s - source %d %s (%.0f%% similar): %s", attribution.category().label(), attribution1, rank,
                attribution.sourceId(), attribution.score() * 100, excerpt);
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

    private static String scoreColour(double percent) {
        if (percent >= 40) {
            return "#d32f2f";
        }
        if (percent >= 20) {
            return "#f57c00";
        }
        if (percent >= 5) {
            return "#fbc02d";
        }
        return "#388e3c";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String css() {
        return "*{box-sizing:border-box}body{margin:0;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#222;"
                + "background:#f5f5f5}header{display:flex;align-items:center;gap:16px;padding:18px 28px;background:#fff;border-bottom:1px solid #e0e0e0}"
                + ".title{font-size:20px;font-weight:700}.subtitle{color:#666}.badges{margin-left:auto;display:flex;gap:14px}"
                + ".score{width:82px;height:82px;border-radius:50%;border:6px solid var(--c);color:var(--c);display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;font-size:22px;font-weight:700}"
                + ".score span{font-size:10px;color:#888;font-weight:600;text-transform:uppercase}"
                + ".legend{display:flex;gap:14px;flex-wrap:wrap;align-items:center;padding:12px 28px;background:#fafafa;border-bottom:1px solid #eee;"
                + "font-size:13px}.grp{color:#999;font-weight:700;text-transform:uppercase;font-size:11px}"
                + ".chip{display:flex;align-items:center;gap:6px;color:#555}.chip .box{width:14px;height:14px;border-radius:3px;display:inline-block}"
                + ".layout{display:flex;gap:20px;max-width:1100px;margin:24px auto;padding:0 20px;align-items:flex-start}"
                + "main{flex:1;background:#fff;padding:28px 32px;border-radius:8px;line-height:2;font-size:16px;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + ".match{border-radius:3px;padding:1px 2px;cursor:help}.match sup{font-size:10px;font-weight:700;color:#555;margin-left:1px}"
                + ".match.attributed{opacity:.45;text-decoration:underline dotted}.match .att{color:#2e7d32}"
                + "aside{width:270px;background:#fff;padding:20px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);position:sticky;top:20px}"
                + "aside h2{font-size:13px;text-transform:uppercase;color:#888;margin:0 0 14px}"
                + ".source{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #f0f0f0}"
                + ".swatch{width:22px;height:22px;border-radius:4px;background:#eee;display:flex;align-items:center;justify-content:center;"
                + "font-size:12px;font-weight:700;color:#444}.sid{flex:1;font-size:14px;word-break:break-word}.pct{font-weight:700}"
                + ".none{color:#888}footer{max-width:1100px;margin:8px auto 40px;padding:0 20px;color:#999;font-size:12px}";
    }
}
