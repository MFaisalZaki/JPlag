package de.jplag.text;

import java.io.File;
import java.util.List;
import java.util.Set;

import de.jplag.Language;
import de.jplag.ParsingException;
import de.jplag.Token;
import de.jplag.options.LanguageOptions;

import com.google.auto.service.AutoService;

/**
 * Language class for parsing (natural language) text. By default it employs a primitive approach where individual words
 * are interpreted as token types, while whitespace and special characters are ignored. This works well for detecting
 * (near-)verbatim reuse but is blind to paraphrasing. To also detect paraphrased text, the module offers optional,
 * WordNet-based normalization (lemmatization, stop-word removal and synonym canonicalization) via
 * {@link TextLanguageOptions}; these collapse surface variants into shared token types so the core comparison can match
 * them. The normalization options are English-specific and disabled by default.
 */
@AutoService(Language.class)
public class NaturalLanguage implements Language {

    private final TextLanguageOptions options = new TextLanguageOptions();

    @Override
    public List<String> fileExtensions() {
        return List.of(".txt", ".asc", ".tex", ".md", ".rtf", ".csv", ".wiki", ".json", ".yaml", ".yml", ".xml");
    }

    @Override
    public String getName() {
        return "Text (naive)";
    }

    @Override
    public String getIdentifier() {
        return "text";
    }

    @Override
    public int minimumTokenMatch() {
        return 5;
    }

    @Override
    public List<Token> parse(Set<File> files, boolean normalize) throws ParsingException {
        return new ParserAdapter(options).parse(files);
    }

    @Override
    public LanguageOptions getOptions() {
        return options;
    }

    @Override
    public boolean supportsMultiLanguage() {
        return false;
    }
}
