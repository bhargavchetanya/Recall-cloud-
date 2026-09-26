package dev.recall.service.embedding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Adds plain-English phrases to a command before it is embedded, from the curated table in
 * {@code glossary/commands.tsv}. People ask "undo my last commit but keep the changes"; the command says
 * "git reset --soft HEAD~1". A sentence embedding alone does not bridge that gap reliably, the glossary does.
 */
@Component
public class CommandGlossary {

    static final String RESOURCE = "/glossary/commands.tsv";

    record Entry(Pattern pattern, String phrase) {}

    private final List<Entry> entries;

    public CommandGlossary() {
        this(CommandGlossary.class.getResourceAsStream(RESOURCE));
    }

    CommandGlossary(InputStream tsv) {
        if (tsv == null) {
            throw new IllegalStateException("Missing " + RESOURCE);
        }
        this.entries = parse(tsv);
    }

    /** The text that gets embedded for a successful command: the command itself, then every matching phrase. */
    public String intentText(String command) {
        List<String> phrases = glossesFor(command);
        return phrases.isEmpty() ? command : command + " : " + String.join("; ", phrases);
    }

    public List<String> glossesFor(String command) {
        List<String> phrases = new ArrayList<>();
        for (Entry e : entries) {
            if (e.pattern().matcher(command).find() && !phrases.contains(e.phrase())) {
                phrases.add(e.phrase());
            }
        }
        return phrases;
    }

    int size() {
        return entries.size();
    }

    private static List<Entry> parse(InputStream tsv) {
        List<Entry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tsv, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1) {
                    throw new IllegalStateException(RESOURCE + " line " + lineNo + " is not <regex><TAB><phrase>");
                }
                entries.add(new Entry(Pattern.compile(line.substring(0, tab), Pattern.CASE_INSENSITIVE),
                        line.substring(tab + 1).strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }
}
