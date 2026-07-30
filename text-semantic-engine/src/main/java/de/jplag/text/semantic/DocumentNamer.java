package de.jplag.text.semantic;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Names a document from where it sits on disk, so that a report is titled with the facts a reader needs — academic
 * year, module, assignment, student — rather than with whatever identifiers the export happened to put in the file
 * name.
 * <p>
 * A submission exported as {@code 2025_6/AH1001/865937/240026012-MTP-4973291.pdf} is named
 * {@code 240026012-MTP-4973291} by default: a student id, an assignment abbreviation and a submission id, of which the
 * middle one is the only part a human reads and the last one means nothing outside the system that issued it. Given a
 * pattern that picks the parts out of the path and a template that puts them back in a sensible order, the same file is
 * named {@code 2025_6-AH1001-MTP-240026012}.
 * <p>
 * The pattern is a regex matched against the document's <em>full path</em> (with {@code /} separators on every
 * platform, so one pattern works everywhere), and the template refers to its groups by name — {@code {module}} — or by
 * number — {@code {1}}. Anchoring the pattern at the end of the path with {@code $} is usually what you want, since the
 * directories above the corpus vary from machine to machine. A group that the pattern declares but the path does not
 * fill contributes nothing, and the separators left stranded around it are cleaned up; that is how an optional part
 * such as a {@code warned/} sub-directory can appear in the name only when it applies.
 * <p>
 * Files the pattern does not match keep the default path-derived name, so a corpus that is only partly organized still
 * ingests in full rather than failing.
 * <p>
 * The name is the document's identity: it is the index key, the title of its report and the name of the report file. So
 * a corpus indexed under one naming scheme has to be queried under the same one, and changing the scheme means
 * re-indexing.
 */
public class DocumentNamer {

    /** A {@code {group}} reference in the template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9]+)}");
    /** Characters that have no business in a document id, which is also used as a file name. */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    /** A run of separators, as left behind by a group the path did not fill. */
    private static final Pattern SEPARATOR_RUN = Pattern.compile("[-_]{2,}");
    /** Separators stranded at either end for the same reason. */
    private static final Pattern EDGE_SEPARATORS = Pattern.compile("^[-_.]+|[-_.]+$");

    private final Pattern pattern;
    private final String template;

    /** A namer that leaves every document with its default path-derived name. */
    public static DocumentNamer pathDerived() {
        return new DocumentNamer(null, null);
    }

    /**
     * Creates the namer.
     * @param pattern the regex matched against each document's full path; null or blank to keep path-derived names.
     * @param template the name to build from the pattern's groups, e.g. {@code "{ayr}-{module}-{assignment}-{student}"};
     * null or blank to keep path-derived names.
     * @throws IllegalArgumentException if only one of the two is given, if the pattern is not a valid regex, or if the
     * template refers to a group the pattern does not define.
     */
    public DocumentNamer(String pattern, String template) {
        boolean hasPattern = pattern != null && !pattern.isBlank();
        boolean hasTemplate = template != null && !template.isBlank();
        if (hasPattern != hasTemplate) {
            throw new IllegalArgumentException("A naming pattern and a naming template have to be given together.");
        }
        this.pattern = hasPattern ? compile(pattern) : null;
        this.template = hasTemplate ? template : null;
        if (this.pattern != null) {
            checkTemplateGroups();
        }
    }

    private static Pattern compile(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Not a valid naming pattern: " + exception.getMessage(), exception);
        }
    }

    /** Fails fast on a template referring to a group that does not exist, rather than silently naming everything alike. */
    private void checkTemplateGroups() {
        List<String> unknown = new ArrayList<>();
        Matcher references = PLACEHOLDER.matcher(template);
        while (references.find()) {
            String reference = references.group(1);
            boolean known = isNumeric(reference) ? Integer.parseInt(reference) <= groupCount() : pattern.namedGroups().containsKey(reference);
            if (!known) {
                unknown.add(reference);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("The naming template refers to " + unknown + ", which the naming pattern does not capture.");
        }
    }

    private int groupCount() {
        return pattern.matcher("").groupCount();
    }

    /**
     * Names a document.
     * @param file the document's path.
     * @param pathDerivedName the default name, used when no naming is configured or the pattern does not match this path.
     * @return the document's name.
     */
    public String nameOf(Path file, String pathDerivedName) {
        if (pattern == null) {
            return pathDerivedName;
        }
        Matcher matcher = pattern.matcher(file.toAbsolutePath().normalize().toString().replace(File.separatorChar, '/'));
        if (!matcher.find()) {
            return pathDerivedName;
        }
        String name = clean(fill(matcher));
        return name.isEmpty() ? pathDerivedName : name;
    }

    /** Substitutes the matched groups into the template. */
    private String fill(Matcher matcher) {
        StringBuilder name = new StringBuilder();
        Matcher references = PLACEHOLDER.matcher(template);
        int cursor = 0;
        while (references.find()) {
            String reference = references.group(1);
            String value = isNumeric(reference) ? matcher.group(Integer.parseInt(reference)) : matcher.group(reference);
            name.append(template, cursor, references.start()).append(value == null ? "" : value);
            cursor = references.end();
        }
        return name.append(template.substring(cursor)).toString();
    }

    /** Makes the name safe to use as a file name, and tidies the separators an unfilled group leaves behind. */
    private static String clean(String name) {
        String cleaned = UNSAFE.matcher(name).replaceAll("_");
        cleaned = SEPARATOR_RUN.matcher(cleaned).replaceAll("-");
        return EDGE_SEPARATORS.matcher(cleaned).replaceAll("");
    }

    private static boolean isNumeric(String reference) {
        return reference.chars().allMatch(Character::isDigit);
    }
}
