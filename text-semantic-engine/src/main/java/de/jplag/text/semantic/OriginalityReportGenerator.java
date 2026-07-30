package de.jplag.text.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * <li>and, when the query's authors are known, flagged as <em>self-reuse</em> if the source shares an author
 * (self-plagiarism) rather than being someone else's work.</li>
 * </ul>
 * By default only unattributed matches are highlighted and counted; when {@code excludeAttributed} is false, attributed
 * matches are also shown but de-emphasized.
 * <p>
 * Every sentence of the document is checked and every sentence counts towards the score: a cohort's shared cover
 * sheets, reference lists and assignment boilerplate are therefore reported like any other reuse, and a reader has to
 * discount them.
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

    /**
     * How far below the report's threshold a match is still written into it, ready to be shown if the reader lowers the
     * threshold. The reader can only re-threshold within what the report contains, and going the other way — raising it —
     * needs nothing extra, so the window is deliberately narrow: at the default 0.85 it reaches 0.75, and well below that
     * sentence similarity is reporting a shared subject rather than reuse anyway.
     */
    private static final double CANDIDATE_WINDOW = 0.10;

    private final double matchThreshold;
    private final double minimumWordOverlap;
    private final Function<String, List<EmbeddedSentence>> sentenceEmbedder;
    private final boolean excludeAttributed;
    /** Sentence embeddings per source document id, so a document shared by many queries is embedded once. */
    private final Map<String, List<EmbeddedSentence>> sentenceCache = new HashMap<>();

    /**
     * Creates the generator.
     * @param matchThreshold the minimum sentence cosine similarity to count as a match (e.g. 0.85).
     * @param minimumWordOverlap the minimum literal word overlap (Jaccard) a match must also reach, in {@code [0, 1]}; 0
     * accepts any wording, so semantic similarity alone decides. Raise it when the sources are not the cohort's own work:
     * two students writing about one subject share wording only by copying, but a student and a textbook share the
     * subject's standard sentences ("Km is the substrate concentration at which the velocity is half of Vmax") without
     * either having copied anything. Against such a corpus the embedding finds the candidate and the wording has to decide.
     * @param sentenceEmbedder splits a text into sentences and embeds them (e.g.
     * {@code SbertEmbedder::embedSentencesWithText}).
     * @param excludeAttributed if true, quoted/cited matches are not highlighted or counted (only concerns are shown).
     */
    public OriginalityReportGenerator(double matchThreshold, double minimumWordOverlap, Function<String, List<EmbeddedSentence>> sentenceEmbedder,
            boolean excludeAttributed) {
        this.matchThreshold = matchThreshold;
        this.minimumWordOverlap = minimumWordOverlap;
        this.sentenceEmbedder = sentenceEmbedder;
        this.excludeAttributed = excludeAttributed;
    }

    private record SourceDocument(String id, Set<String> authors, String text, List<EmbeddedSentence> sentences) {
    }

    /**
     * A query sentence and the best source sentence found for it, kept whenever that best match is close enough to be worth
     * showing at <em>some</em> threshold the reader might choose — see {@link #candidateFloor()}. Whether it counts as a
     * match is then a question of where the threshold sits, which the report lets the reader move.
     */
    private record Attribution(String text, int begin, int end, String sourceId, String sourceSentence, int sourceSentenceIndex, double score,
            MatchCategory category, AttributionStatus attribution, String attributionEvidence, boolean selfReuse, boolean candidate) {

        /** Whether this is a match at the given threshold. */
        boolean matched(double threshold) {
            return candidate && score >= threshold;
        }

        // Whether this match should be highlighted and counted (a match that is not an excluded attributed one).
        boolean reported(double threshold, boolean excludeAttributed) {
            return matched(threshold) && !(excludeAttributed && attribution.isAttributed());
        }
    }

    private record Totals(double unattributedPercent, double attributedPercent, double excludedPercent, double selfReusePercent,
            Map<MatchCategory, Integer> wordsPerCategory, Map<String, Integer> wordsPerSource, int totalWords) {
    }

    /**
     * Generates the HTML report.
     * @param queryId the query document's name.
     * @param queryText the query document's text.
     * @param sources the candidate source documents to attribute matches to.
     * @param queryAuthors the query document's authors; matches to sources sharing an author are flagged as self-reuse.
     * @return a complete, self-contained HTML document.
     */
    public String generate(String queryId, String queryText, List<ArchivedDocument> sources, Set<String> queryAuthors) {
        List<EmbeddedSentence> querySentences = sentenceEmbedder.apply(queryText);
        List<SourceDocument> sourceDocuments = new ArrayList<>();
        for (ArchivedDocument source : sources) {
            sourceDocuments.add(new SourceDocument(source.id(), source.authors(), source.text(), sentencesOf(source.id(), source.text())));
        }

        List<Attribution> attributions = attribute(querySentences, sourceDocuments, queryAuthors);
        Totals totals = totals(attributions, queryText);

        List<String> orderedSources = rankSources(attributions, totals);
        Map<String, Integer> rankOf = new HashMap<>();
        for (int i = 0; i < orderedSources.size(); i++) {
            rankOf.put(orderedSources.get(i), i + 1);
        }

        return renderHtml(queryId, queryText, totals, attributions, sourceDocuments, orderedSources, rankOf, !queryAuthors.isEmpty());
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

    /**
     * Orders the sources: the ones credited at the report's own threshold first, by how much they account for, then the
     * ones only a lower threshold would reach. A source's rank is its label throughout the report and the anchor of its
     * passages, so every source the reader can turn on has to be ranked and rendered up front, even when it contributes
     * nothing at the setting the report was generated with.
     */
    private static List<String> rankSources(List<Attribution> attributions, Totals totals) {
        Map<String, Integer> candidateWords = new LinkedHashMap<>();
        for (Attribution attribution : attributions) {
            if (attribution.candidate()) {
                candidateWords.merge(attribution.sourceId(), wordCount(attribution.text()), Integer::sum);
            }
        }
        Comparator<String> byContribution = Comparator.comparingInt((String source) -> totals.wordsPerSource().getOrDefault(source, 0))
                .thenComparingInt(source -> candidateWords.getOrDefault(source, 0)).reversed();
        return candidateWords.keySet().stream().sorted(byContribution.thenComparing(Comparator.naturalOrder())).toList();
    }

    /**
     * The lowest similarity written into the report, and so the lowest threshold its reader can turn the report down to.
     */
    private double candidateFloor() {
        return Math.max(0.0, matchThreshold - CANDIDATE_WINDOW);
    }

    private List<Attribution> attribute(List<EmbeddedSentence> querySentences, List<SourceDocument> sources, Set<String> queryAuthors) {
        List<Attribution> attributions = new ArrayList<>();
        for (int i = 0; i < querySentences.size(); i++) {
            EmbeddedSentence querySentence = querySentences.get(i);
            double bestScore = -1.0;
            String bestSource = null;
            Set<String> bestAuthors = Set.of();
            String bestSentence = null;
            int bestIndex = -1;
            for (SourceDocument source : sources) {
                for (int candidateIndex = 0; candidateIndex < source.sentences().size(); candidateIndex++) {
                    EmbeddedSentence candidate = source.sentences().get(candidateIndex);
                    double similarity = cosine(querySentence.vector(), candidate.vector());
                    if (similarity > bestScore) {
                        bestScore = similarity;
                        bestSource = source.id();
                        bestAuthors = source.authors();
                        bestSentence = candidate.text();
                        bestIndex = candidateIndex;
                    }
                }
            }
            double overlap = bestSentence == null ? 0.0 : wordOverlap(querySentence.text(), bestSentence);
            boolean candidate = bestSentence != null && bestScore >= candidateFloor() && overlap >= minimumWordOverlap;
            MatchCategory category = candidate ? MatchCategory.fromWordOverlap(overlap) : null;
            String nextSentence = i + 1 < querySentences.size() ? querySentences.get(i + 1).text() : "";
            CitationDetector.AttributionCheck check = candidate ? attributionWithLookahead(querySentence.text(), nextSentence) : null;
            boolean selfReuse = candidate && !Collections.disjoint(queryAuthors, bestAuthors);
            attributions.add(new Attribution(querySentence.text(), querySentence.begin(), querySentence.end(), candidate ? bestSource : null,
                    bestSentence, bestIndex, bestScore, category, check == null ? null : check.status(), check == null ? null : check.evidence(),
                    selfReuse, candidate));
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

    /**
     * Adds up the report's percentages, all of them shares of the <em>whole</em> document's word count — including the
     * headings, table cells and short lines that are never long enough to be checked as sentences. The report renders the
     * document in full, so its percentages have to be shares of what a reader can see; counting only the checked sentences
     * would quietly divide by about 97% of the document and read a little high.
     */
    private Totals totals(List<Attribution> attributions, String queryText) {
        int totalWords = wordCount(queryText);
        Map<String, Integer> wordsPerSource = new LinkedHashMap<>();
        Map<MatchCategory, Integer> wordsPerCategory = new EnumMap<>(MatchCategory.class);
        int matchedWords = 0;
        int unattributedWords = 0;
        int excludedWords = 0;
        int selfReuseWords = 0;
        for (Attribution attribution : attributions) {
            if (!attribution.matched(matchThreshold)) {
                continue;
            }
            int words = wordCount(attribution.text());
            if (!attribution.reported(matchThreshold, excludeAttributed)) {
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
        return new Totals(unattributed, overall - unattributed, percent(excludedWords, totalWords), percent(selfReuseWords, totalWords),
                wordsPerCategory, wordsPerSource, totalWords);
    }

    /**
     * Appends the document's own text between two positions — the whitespace, headings and short unchecked lines that sit
     * around the sentences — and returns the position it advanced to.
     */
    private static int appendGap(StringBuilder html, String text, int from, int to) {
        if (from >= 0 && from < to && to <= text.length()) {
            html.append(escape(text.substring(from, to)));
            return to;
        }
        return from;
    }

    /**
     * A sentence as it stands in the document, falling back to the splitter's own copy if the offsets do not fit the text.
     */
    private static String textOf(String documentText, int begin, int end, String fallback) {
        return begin >= 0 && begin < end && end <= documentText.length() ? documentText.substring(begin, end) : fallback;
    }

    /** Anchor of the highlighted query sentence at this attribution index (for jumping back from the source passage). */
    private static String queryAnchor(int attributionIndex) {
        return "q" + attributionIndex;
    }

    /** Anchor of a matched sentence inside a source document's passage block (for jumping there from a highlight). */
    private static String passageAnchor(int sourceRank, int sentenceIndex) {
        return "m-" + sourceRank + "-" + sentenceIndex;
    }

    private String renderHtml(String queryId, String queryText, Totals totals, List<Attribution> attributions, List<SourceDocument> sources,
            List<String> orderedSources, Map<String, Integer> rankOf, boolean authorKnown) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<title>Originality report - ").append(escape(queryId)).append("</title>");
        html.append("<style>").append(css()).append("</style></head><body>");

        html.append("<header><div class=\"title\">Originality Report</div><div class=\"subtitle\">").append(escape(queryId))
                .append("</div></header>");

        html.append(renderControls(totals, authorKnown));

        // For every candidate source sentence, remember the first query sentence that reached it, so the passage can link
        // back. Candidates rather than matches: a passage the reader can turn on has to be anchored before they do.
        Map<String, Map<Integer, Integer>> passageBackLinks = new HashMap<>();
        for (int i = 0; i < attributions.size(); i++) {
            Attribution attribution = attributions.get(i);
            if (attribution.candidate()) {
                passageBackLinks.computeIfAbsent(attribution.sourceId(), key -> new LinkedHashMap<>()).putIfAbsent(attribution.sourceSentenceIndex(),
                        i);
            }
        }

        // The document is rendered from its own text, with the matched sentences wrapped where they sit, so everything
        // between them - blank lines, headings, table rows, the short lines that are never checked - survives intact.
        html.append("<div class=\"layout\"><main data-words=\"").append(totals.totalWords()).append("\">");
        int cursor = 0;
        for (int i = 0; i < attributions.size(); i++) {
            Attribution attribution = attributions.get(i);
            cursor = appendGap(html, queryText, cursor, attribution.begin());
            String sentence = escape(textOf(queryText, attribution.begin(), attribution.end(), attribution.text()));
            if (attribution.candidate()) {
                html.append(renderCandidate(attribution, i, rankOf.get(attribution.sourceId()), sentence));
            } else {
                html.append("<span>").append(sentence).append("</span>");
            }
            cursor = Math.max(cursor, attribution.end());
        }
        appendGap(html, queryText, cursor, queryText.length());
        html.append("</main><aside><h2>Sources</h2>");
        html.append("<p class=\"none\"").append(orderedSources.isEmpty() ? "" : " hidden").append(">No matching sources found.</p>");
        for (String source : orderedSources) {
            int words = totals.wordsPerSource().getOrDefault(source, 0);
            html.append("<div class=\"source\" data-rank=\"").append(rankOf.get(source)).append("\"").append(words == 0 ? " hidden" : "")
                    .append("><span class=\"swatch\">").append(rankOf.get(source)).append("</span><span class=\"sid\">").append("<a href=\"#src-")
                    .append(rankOf.get(source)).append("\">").append(escape(source)).append("</a></span><span class=\"pct\">")
                    .append(format(percent(words, totals.totalWords()))).append("</span></div>");
        }
        html.append("</aside></div>");
        Set<String> highlightedPassages = new HashSet<>();
        for (Attribution attribution : attributions) {
            if (attribution.reported(matchThreshold, excludeAttributed)) {
                highlightedPassages.add(passageAnchor(rankOf.get(attribution.sourceId()), attribution.sourceSentenceIndex()));
            }
        }
        html.append(renderSourcePassages(sources, orderedSources, rankOf, passageBackLinks, highlightedPassages, totals.wordsPerSource()));
        html.append("<footer>").append(excludeAttributed
                ? "Only unattributed matches are shown; quoted/cited passages are hidden and excluded from the score. "
                : "Overall similarity is the share of words matched to a source; <b>unattributed</b> excludes passages that are quoted or cited "
                        + "(marked ✓) and is the actual plagiarism concern. ")
                .append("Matches marked ↺ reuse the submitter's own prior work (self-plagiarism). Matches are semantic (SBERT, threshold ")
                .append(String.format(Locale.ROOT, "%.2f", matchThreshold))
                .append(minimumWordOverlap > 0
                        ? String.format(Locale.ROOT, ", and must also share at least %.0f%% of their wording)", minimumWordOverlap * 100)
                        : ")")
                .append("; the type comes from literal word overlap. Percentages are shares of the whole document's words. Every sentence is "
                        + "checked and counted, including the cover sheet and reference list, which a cohort shares by design. On a set of documents "
                        + "about one subject some similarity is expected, so read a match as evidence only where the wording, not merely the "
                        + "subject, is shared. Click a highlight to jump to the matched passage in the source below; click the passage to jump "
                        + "back. The text shown is the document's text as extracted, with its paragraphs and line breaks; formatting, images and "
                        + "page furniture are not reproduced. Use the controls above to take a match type out of the report or to move the "
                        + "threshold, and the percentages follow; the numbers saved in this file are the ones it was generated with.</footer>");
        html.append("<script>").append(script()).append("</script>");
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * The report's controls: re-decide every candidate against the reader's threshold and type filters, repaint it, and add
     * the percentages up again.
     * <p>
     * All of it works on what is already in the page — each candidate carries its own similarity, type, source and word
     * count — so nothing is fetched and no setting has to be plumbed back through a re-run. The arithmetic mirrors
     * {@code totals()}: words matched over the document's words, with self-reuse counted apart from the type breakdown.
     */
    private static String script() {
        return """
                (function () {
                  var main = document.querySelector('main');
                  var total = +main.dataset.words || 0;
                  var candidates = [].slice.call(main.querySelectorAll('a[data-score]'));
                  var passages = [].slice.call(document.querySelectorAll('.srctext a[id^="m-"]'));
                  var rows = [].slice.call(document.querySelectorAll('.source'));
                  var blocks = [].slice.call(document.querySelectorAll('.srcdoc'));
                  var chips = [].slice.call(document.querySelectorAll('.chip'));
                  var slider = document.getElementById('thr');
                  var output = document.getElementById('thrOut');
                  var none = document.querySelector('.none');
                  var aside = document.querySelector('aside');
                  var defaults = {threshold: +slider.value, off: {}};
                  chips.forEach(function (chip) {
                    if (chip.dataset.key && chip.getAttribute('aria-pressed') === 'false') {
                      defaults.off[chip.dataset.key] = true;
                    }
                  });
                  var off = Object.assign({}, defaults.off);

                  function chipOf(key) {
                    return chips.filter(function (chip) { return chip.dataset.key === key; })[0];
                  }

                  // Threshold and type decide whether a candidate is in scope at all; the quoted filter then decides
                  // whether an acknowledged one is counted, which is a separate question from whether it is a match.
                  function inScope(el) {
                    if (+el.dataset.score < +slider.value) {
                      return false;
                    }
                    return el.dataset.self === '1' ? !off.self : !off['cat:' + el.dataset.cat];
                  }

                  function included(el) {
                    return inScope(el) && (el.dataset.att === '0' || !off.attributed);
                  }

                  function percent(words) {
                    return total ? Math.round(100 * words / total) + '%' : '0%';
                  }

                  function setPercent(key, words) {
                    var chip = chipOf(key);
                    if (chip) {
                      chip.querySelector('b').textContent = percent(words);
                    }
                  }

                  function paint(el, on) {
                    var att = el.dataset.att === '1';
                    var self = el.dataset.self === '1';
                    el.className = on ? 'match' + (att ? ' attributed' : '') + (self ? ' self' : '') : 'cand';
                    el.style.background = on ? el.dataset.colour : '';
                    el.querySelector('.marks').innerHTML = on
                      ? (self ? '<span class="self-mark">↺</span>' : '') + (att ? '<span class="att">✓</span>' : '') + el.dataset.src
                      : '';
                  }

                  function apply() {
                    var perSource = {}, perCategory = {}, quoted = 0, unattributed = 0, self = 0, targets = {};
                    candidates.forEach(function (el) {
                      var words = +el.dataset.words;
                      var on = included(el);
                      paint(el, on);
                      if (inScope(el) && el.dataset.att === '1') {
                        quoted += words;   // reported as the share the quoted filter is keeping out, or letting in
                      }
                      if (!on) {
                        return;
                      }
                      targets[el.getAttribute('href').slice(1)] = el.id;
                      perSource[el.dataset.src] = (perSource[el.dataset.src] || 0) + words;
                      if (el.dataset.att === '0') {
                        unattributed += words;
                      }
                      if (el.dataset.self === '1') {
                        self += words;
                      } else {
                        perCategory[el.dataset.cat] = (perCategory[el.dataset.cat] || 0) + words;
                      }
                    });

                    ['COPY_PASTE', 'LIGHTLY_EDITED', 'PARAPHRASE'].forEach(function (category) {
                      setPercent('cat:' + category, perCategory[category] || 0);
                    });
                    setPercent('self', self);
                    setPercent('attributed', quoted);
                    setPercent('unattributed', unattributed);
                    setPercent('overall', unattributed + (off.attributed ? 0 : quoted));
                    var state = chipOf('attributed').querySelector('.state');
                    state.textContent = off.attributed ? ' (hidden)' : '';

                    passages.forEach(function (passage) {
                      var back = targets[passage.id];
                      passage.className = back ? 'hit' : 'nohit';
                      if (back) {
                        passage.setAttribute('href', '#' + back);
                      }
                    });

                    var credited = 0;
                    rows.forEach(function (row) {
                      var words = perSource[row.dataset.rank] || 0;
                      row.querySelector('.pct').textContent = percent(words);
                      row.hidden = !words;
                      credited += words ? 1 : 0;
                    });
                    blocks.forEach(function (block) { block.hidden = !perSource[block.dataset.rank]; });
                    none.hidden = credited > 0;
                    rows.slice().sort(function (a, b) {
                      return (perSource[b.dataset.rank] || 0) - (perSource[a.dataset.rank] || 0);
                    }).forEach(function (row) { aside.appendChild(row); });
                  }

                  chips.forEach(function (chip) {
                    if (!chip.classList.contains('toggle')) {
                      return;
                    }
                    chip.addEventListener('click', function () {
                      off[chip.dataset.key] = !off[chip.dataset.key];
                      chip.setAttribute('aria-pressed', off[chip.dataset.key] ? 'false' : 'true');
                      apply();
                    });
                  });
                  slider.addEventListener('input', function () {
                    output.textContent = (+slider.value).toFixed(2);
                    apply();
                  });
                  document.getElementById('reset').addEventListener('click', function () {
                    slider.value = defaults.threshold;
                    output.textContent = defaults.threshold.toFixed(2);
                    off = Object.assign({}, defaults.off);
                    chips.forEach(function (chip) {
                      if (chip.classList.contains('toggle')) {
                        chip.setAttribute('aria-pressed', off[chip.dataset.key] ? 'false' : 'true');
                      }
                    });
                    apply();
                  });
                  apply();
                })();
                """;
    }

    /**
     * Renders each matching source document's full text below the report, with the matched sentences highlighted and
     * anchored, so a click on an inline highlight lands directly on the passage it was matched to (and back).
     */
    private static String renderSourcePassages(List<SourceDocument> sources, List<String> orderedSources, Map<String, Integer> rankOf,
            Map<String, Map<Integer, Integer>> passageBackLinks, Set<String> highlightedPassages, Map<String, Integer> wordsPerSource) {
        if (orderedSources.isEmpty()) {
            return "";
        }
        Map<String, SourceDocument> sourceById = new HashMap<>();
        sources.forEach(source -> sourceById.put(source.id(), source));
        StringBuilder html = new StringBuilder("<section class=\"passages\"><h2>Matched source passages</h2>");
        for (String sourceId : orderedSources) {
            int rank = rankOf.get(sourceId);
            Map<Integer, Integer> backLinks = passageBackLinks.getOrDefault(sourceId, Map.of());
            html.append("<details class=\"srcdoc\" id=\"src-").append(rank).append("\" data-rank=\"").append(rank).append("\"")
                    .append(wordsPerSource.getOrDefault(sourceId, 0) == 0 ? " hidden" : "").append(" open><summary><span class=\"swatch\">")
                    .append(rank).append("</span>").append(escape(sourceId)).append("</summary><div class=\"srctext\">");
            SourceDocument document = sourceById.get(sourceId);
            List<EmbeddedSentence> sentences = document.sentences();
            int cursor = 0;
            for (int j = 0; j < sentences.size(); j++) {
                EmbeddedSentence sentence = sentences.get(j);
                cursor = appendGap(html, document.text(), cursor, sentence.begin());
                String rendered = escape(textOf(document.text(), sentence.begin(), sentence.end(), sentence.text()));
                Integer backLink = backLinks.get(j);
                if (backLink != null) {
                    // Anchored for every candidate, but highlighted only where the match is currently reported: a passage
                    // the reader can turn on by lowering the threshold has to be there to jump to before they do.
                    String anchor = passageAnchor(rank, j);
                    html.append("<a class=\"").append(highlightedPassages.contains(anchor) ? "hit" : "nohit").append("\" id=\"").append(anchor)
                            .append("\" href=\"#").append(queryAnchor(backLink))
                            .append("\" title=\"Matched passage - click to jump back to the document\">").append(rendered).append("</a>");
                } else {
                    html.append("<span>").append(rendered).append("</span>");
                }
                cursor = Math.max(cursor, sentence.end());
            }
            appendGap(html, document.text(), cursor, document.text().length());
            html.append("</div></details>");
        }
        return html.append("</section>").toString();
    }

    /**
     * The controls above the document: one toggle per match type, one for quoted/cited matches, a threshold slider, and the
     * scores they add up to.
     * <p>
     * A category that fires on everything is worse than useless — on an assignment where the whole cohort is answering one
     * prompt in much the same words, "paraphrase" can be true of every submission and still mean nothing. The reader can
     * take such a category out of the report and see what is left, rather than having to discount it in their head or ask
     * for the run to be repeated with different settings.
     */
    private String renderControls(Totals totals, boolean authorKnown) {
        StringBuilder html = new StringBuilder("<div class=\"legend\"><span class=\"grp\">Type:</span>");
        for (MatchCategory category : MatchCategory.values()) {
            html.append(toggle("cat:" + category.name(), category.colour(), category.label(),
                    percent(totals.wordsPerCategory().getOrDefault(category, 0), totals.totalWords()), true, ""));
        }
        if (authorKnown) {
            html.append(toggle("self", SELF_REUSE_COLOUR, "Self-reuse (own prior work)", totals.selfReusePercent(), true, ""));
        }
        html.append("<span class=\"grp\">Quoted:</span>");
        html.append(toggle("attributed", "#a5d6a7", "Quoted / cited", excludeAttributed ? totals.excludedPercent() : totals.attributedPercent(),
                !excludeAttributed, excludeAttributed ? " (hidden)" : ""));
        html.append("<span class=\"grp\">Score:</span>");
        html.append(readout("overall", "Overall", totals.unattributedPercent() + totals.attributedPercent()));
        html.append(readout("unattributed", "Unattributed (concern)", totals.unattributedPercent()));
        html.append("</div>");

        html.append("<div class=\"tuning\"><label for=\"thr\">Match threshold</label>");
        html.append("<input type=\"range\" id=\"thr\" min=\"").append(String.format(Locale.ROOT, "%.2f", candidateFloor()))
                .append("\" max=\"1\" step=\"0.01\" value=\"").append(String.format(Locale.ROOT, "%.2f", matchThreshold)).append("\">");
        html.append("<output id=\"thrOut\">").append(String.format(Locale.ROOT, "%.2f", matchThreshold)).append("</output>");
        html.append("<button type=\"button\" id=\"reset\">Reset</button>");
        html.append("<span class=\"hint\">Raise it to keep only closer matches. Click a type above to take it out of the report. "
                + "Percentages update as you go; this report holds matches down to ").append(String.format(Locale.ROOT, "%.2f", candidateFloor()))
                .append(".</span></div>");
        return html.toString();
    }

    /** A legend chip that filters the report when clicked. */
    private static String toggle(String key, String colour, String label, double percent, boolean on, String state) {
        return "<button type=\"button\" class=\"chip toggle\" data-key=\"" + key + "\" aria-pressed=\"" + on
                + "\"><span class=\"box\" style=\"background:" + colour + "\"></span>" + escape(label) + " <b>" + format(percent)
                + "</b><span class=\"state\">" + escape(state) + "</span></button>";
    }

    /** A legend chip that only reports a number. */
    private static String readout(String key, String label, double percent) {
        return "<span class=\"chip\" data-key=\"" + key + "\">" + escape(label) + " <b>" + format(percent) + "</b></span>";
    }

    /** The superscripts on a highlighted match: self-reuse, acknowledged, and the rank of the source it came from. */
    private static String marks(boolean attributed, boolean selfReuse, int rank) {
        return (selfReuse ? "<span class=\"self-mark\">↺</span>" : "") + (attributed ? "<span class=\"att\">✓</span>" : "") + rank;
    }

    /**
     * Renders one candidate sentence, carrying everything the reader's controls need to re-decide it: its similarity, its
     * type, its source, its word count, and whether it is acknowledged or the submitter's own prior work. It is rendered
     * highlighted or not according to the settings the report was generated with, so the report reads correctly with no
     * scripting at all; the controls only move it between those two states.
     */
    private String renderCandidate(Attribution attribution, int index, int rank, String sentence) {
        boolean reported = attribution.reported(matchThreshold, excludeAttributed);
        boolean attributed = attribution.attribution().isAttributed();
        String colour = attribution.selfReuse() ? SELF_REUSE_COLOUR : attribution.category().colour();
        StringBuilder html = new StringBuilder("<a class=\"");
        html.append(reported ? "match" + (attributed ? " attributed" : "") + (attribution.selfReuse() ? " self" : "") : "cand").append('"');
        if (reported) {
            html.append(" style=\"background:").append(colour).append('"');
        }
        html.append(" title=\"").append(escape(tooltip(attribution, rank))).append('"');
        html.append(" id=\"").append(queryAnchor(index)).append('"');
        html.append(" href=\"#").append(passageAnchor(rank, attribution.sourceSentenceIndex())).append('"');
        html.append(" data-score=\"").append(String.format(Locale.ROOT, "%.3f", attribution.score())).append('"');
        html.append(" data-words=\"").append(wordCount(attribution.text())).append('"');
        html.append(" data-cat=\"").append(attribution.category().name()).append('"');
        html.append(" data-src=\"").append(rank).append('"');
        html.append(" data-att=\"").append(attributed ? 1 : 0).append('"');
        html.append(" data-self=\"").append(attribution.selfReuse() ? 1 : 0).append('"');
        html.append(" data-colour=\"").append(colour).append("\">").append(sentence);
        return html.append("<sup class=\"marks\">").append(reported ? marks(attributed, attribution.selfReuse(), rank) : "").append("</sup></a>")
                .toString();
    }

    private static String tooltip(Attribution attribution, int rank) {
        // The matched sentence keeps the source document's line breaks; a tooltip has to read as one line.
        String excerpt = attribution.sourceSentence() == null ? "" : attribution.sourceSentence().replaceAll("\\s+", " ").strip();
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

    /**
     * Words in a text: whitespace-separated tokens that carry at least one letter or digit, so stray punctuation is not
     * one.
     */
    private static int wordCount(String text) {
        int words = 0;
        for (String token : text.split("\\s+")) {
            if (token.codePoints().anyMatch(Character::isLetterOrDigit)) {
                words++;
            }
        }
        return words;
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
                + ".chip.toggle{font:inherit;border:1px solid transparent;background:none;padding:3px 7px;border-radius:14px;cursor:pointer}"
                + ".chip.toggle:hover{border-color:#ccc;background:#fff}"
                + ".chip.toggle[aria-pressed=false]{opacity:.45}.chip.toggle[aria-pressed=false] b{text-decoration:line-through}"
                + ".chip .state{font-style:italic;color:#999}"
                + ".tuning{display:flex;gap:10px;align-items:center;padding:10px 28px;background:#fafafa;border-bottom:1px solid #eee;font-size:13px;"
                + "color:#666;flex-wrap:wrap}.tuning input{width:200px}.tuning output{font-weight:700;min-width:34px}"
                + ".tuning button{font:inherit;padding:3px 10px;border:1px solid #ccc;border-radius:14px;background:#fff;cursor:pointer}"
                + ".tuning .hint{color:#999;flex:1;min-width:240px}" + "[hidden]{display:none !important}"
                + ".layout{display:flex;gap:20px;max-width:1280px;margin:24px auto;padding:0 20px;align-items:flex-start}"
                // pre-wrap is what makes the document read as the document: its paragraphs, indentation and line breaks are
                // in the text, and without it the browser collapses every one of them into a single running block.
                + "main{flex:1;background:#fff;padding:28px 32px;border-radius:8px;line-height:1.7;font-size:15px;white-space:pre-wrap;"
                + "overflow-wrap:break-word;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + ".match{border-radius:3px;padding:1px 2px;cursor:pointer;color:inherit;text-decoration:none}"
                + ".match sup{font-size:10px;font-weight:700;color:#555;margin-left:1px}"
                + ".match.attributed{opacity:.45;text-decoration:underline dotted}.match .att{color:#2e7d32}" + ".match .self-mark{color:#8e24aa}"
                // A candidate the reader's settings currently exclude: still in the document, just not a match any more.
                + ".cand{color:inherit;text-decoration:none;background:none;cursor:default}.cand .marks{display:none}"
                + ".nohit{color:inherit;text-decoration:none;background:none}"
                + "aside{width:270px;background:#fff;padding:20px;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);position:sticky;top:20px}"
                + "aside h2{font-size:13px;text-transform:uppercase;color:#888;margin:0 0 14px}"
                + ".source{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #f0f0f0}"
                + ".swatch{width:22px;height:22px;border-radius:4px;background:#eee;display:flex;align-items:center;justify-content:center;"
                + "font-size:12px;font-weight:700;color:#444}.sid{flex:1;font-size:14px;word-break:break-word}.pct{font-weight:700}"
                + ".sid a{color:inherit;text-decoration:none}.sid a:hover{text-decoration:underline}"
                + ".passages{max-width:1280px;margin:0 auto 16px;padding:0 20px}"
                + ".passages h2{font-size:13px;text-transform:uppercase;color:#888;margin:0 0 12px}"
                + ".srcdoc{background:#fff;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,.08);margin-bottom:12px}"
                + ".srcdoc summary{cursor:pointer;padding:12px 20px;font-size:14px;font-weight:600;display:flex;align-items:center;gap:10px}"
                + ".srctext{padding:0 20px 20px;line-height:1.6;font-size:14px;color:#444;white-space:pre-wrap;overflow-wrap:break-word}"
                + ".hit{background:#fff59d;border-radius:3px;padding:1px 2px;color:inherit;text-decoration:none}"
                + ".match,.hit{scroll-margin:120px}.match:target,.hit:target{outline:3px solid #fb8c00;outline-offset:1px}"
                + ".none{color:#888}footer{max-width:1280px;margin:8px auto 40px;padding:0 20px;color:#999;font-size:12px}";
    }
}
