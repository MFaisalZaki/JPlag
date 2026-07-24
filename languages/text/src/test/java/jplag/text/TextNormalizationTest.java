package jplag.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.jplag.ParsingException;
import de.jplag.SharedTokenType;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.options.LanguageOption;
import de.jplag.text.NaturalLanguage;

/**
 * Tests the optional, WordNet-based normalization that lets the text module detect paraphrased content. Each option
 * collapses surface variants into shared token types, which is what enables the core comparison to match them.
 */
class TextNormalizationTest {

    /**
     * Parses the given text with the named options enabled and returns the descriptions of the resulting content tokens
     * (the trailing {@code FILE_END} token is excluded).
     */
    private static List<String> contentTokenDescriptions(String text, String... enabledOptions) throws IOException, ParsingException {
        NaturalLanguage language = new NaturalLanguage();
        Set<String> toEnable = Set.of(enabledOptions);
        for (LanguageOption<?> option : language.getOptions().getOptionsAsList()) {
            if (toEnable.contains(option.getName())) {
                @SuppressWarnings("unchecked")
                LanguageOption<Boolean> booleanOption = (LanguageOption<Boolean>) option;
                booleanOption.setValue(true);
            }
        }
        File file = File.createTempFile("normalization", ".txt");
        file.deleteOnExit();
        Files.writeString(file.toPath(), text);
        return language.parse(Set.of(file), false).stream().map(Token::getType).filter(type -> type != SharedTokenType.FILE_END)
                .map(TokenType::getDescription).toList();
    }

    @Test
    void testDefaultKeepsDistinctSurfaceForms() throws IOException, ParsingException {
        // Without normalization, inflections and synonyms remain distinct token types.
        List<String> types = contentTokenDescriptions("cats cat big large");
        assertEquals(4, types.size());
        assertEquals(4, types.stream().distinct().count());
    }

    @Test
    void testLemmatizationCollapsesInflections() throws IOException, ParsingException {
        List<String> types = contentTokenDescriptions("cats cat", "lemmatize");
        assertEquals(2, types.size());
        assertEquals(1, types.stream().distinct().count(), "Plural and singular should collapse to one base form");
    }

    @Test
    void testStopwordRemovalDropsFunctionWords() throws IOException, ParsingException {
        List<String> types = contentTokenDescriptions("the cat on the mat", "removeStopwords");
        assertEquals(List.of("cat", "mat"), types, "Only content words should remain");
    }

    @Test
    void testSynonymCanonicalizationCollapsesSynonyms() throws IOException, ParsingException {
        List<String> types = contentTokenDescriptions("big large", "expandSynonyms");
        assertEquals(2, types.size());
        assertEquals(1, types.stream().distinct().count(), "Synonyms should collapse to one canonical token type");
    }

    @Test
    void testUnknownWordsSurviveNormalization() throws IOException, ParsingException {
        // A made-up token that is not in WordNet must pass through unchanged rather than being dropped.
        List<String> types = contentTokenDescriptions("zzzqux", "expandSynonyms", "lemmatize");
        assertTrue(types.contains("zzzqux"));
    }
}
