package dev.recall.service.learning;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the learning loop reads a command line: which tool it runs, whether it is a known "unblocker" (a command that
 * fixes problems in other tools, like {@code npx kill-port}) and whether it is trivial (like {@code cd} or
 * {@code git status}). Leading {@code sudo} and {@code NAME=value} assignments are ignored.
 */
public final class CommandRules {

    private static final Pattern LEADING_NOISE = Pattern.compile("^(?:(?:sudo|doas)\\s+(?:-\\S+\\s+)*|[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+)+");

    private final List<String> unblockers;
    private final List<String> trivial;

    public CommandRules(List<String> unblockers, List<String> trivial) {
        this.unblockers = unblockers.stream().map(CommandRules::lower).toList();
        this.trivial = trivial.stream().map(CommandRules::lower).toList();
    }

    /** The command without sudo and leading env assignments, trimmed and lower-cased. */
    static String core(String command) {
        String s = command.strip();
        return lower(LEADING_NOISE.matcher(s).replaceFirst(""));
    }

    /** The program being run: {@code git}, {@code npm}, ... ({@code ./gradlew} → gradlew, {@code node.exe} → node). */
    public static String tool(String command) {
        String first = core(command).split("\\s+", 2)[0];
        String base = first.substring(Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\')) + 1);
        return base.endsWith(".exe") || base.endsWith(".cmd") ? base.substring(0, base.length() - 4) : base;
    }

    public boolean isUnblocker(String command) {
        return startsWithAny(core(command), unblockers);
    }

    public boolean isTrivial(String command) {
        return startsWithAny(core(command), trivial);
    }

    /** Prefix match on whole words: "kill" matches "kill -9 12" but not "killall" (which is listed separately). */
    private static boolean startsWithAny(String command, List<String> prefixes) {
        for (String p : prefixes) {
            if (command.equals(p) || command.startsWith(p + " ") || (p.endsWith(":") && command.startsWith(p))) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }
}
