package dev.recall.service.ai;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The deny-list every AI suggestion passes before it is shown (spec §4): commands that wipe disks, delete the root
 * or home directory, fork-bomb, power off the machine, or force-push over a protected branch. A model can suggest
 * anything; Recall never passes these on.
 */
@Component
public class CommandSafety {

    private static final Pattern PROTECTED_BRANCH = Pattern.compile("main|master|prod|production|develop|release.*");

    private static final List<Pattern> DENY = List.of(
            // rm -rf /, rm -fr /*, rm -rf ~, rm -rf $HOME (flags in any order, possibly split: -r -f)
            Pattern.compile("\\brm\\s+(?=(?:\\S*\\s+)*-{1,2}\\S*(?:r|recursive))(?=(?:\\S*\\s+)*-{1,2}\\S*(?:f|force))"
                    + "(?:-\\S+\\s+)+(?:/\\*?|~/?|\\$home/?|\\$\\{home}/?)(?:\\s|;|&|\\||$)"),
            Pattern.compile("--no-preserve-root"),
            Pattern.compile("\\bmkfs(?:\\.\\w+)?\\b"),
            Pattern.compile("\\bdd\\s+.*\\bif="),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{"),
            // power commands where a command starts: at the beginning, after ; & | or after sudo
            Pattern.compile("(?:^|[;&|]\\s*|\\bsudo\\s+)(?:shutdown|poweroff|halt|reboot|init\\s+[06])\\b"
                    + "|\\b(?:stop|restart)-computer\\b"),
            Pattern.compile(">\\s*/dev/(?:sd[a-z]|nvme\\d|disk\\d|hd[a-z])"),
            Pattern.compile("\\bformat\\s+[a-z]:"),
            Pattern.compile("\\bchmod\\s+-r\\s+0?777\\s+/(?:\\s|$)"));

    private static final Pattern GIT_PUSH = Pattern.compile("\\bgit\\s+push\\b(.*)");

    /** True when the command must never be suggested. Case-insensitive. */
    public boolean isDestructive(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String c = command.strip().toLowerCase(Locale.ROOT);
        return DENY.stream().anyMatch(p -> p.matcher(c).find()) || forcePushToProtectedBranch(c);
    }

    /**
     * {@code git push --force} or {@code -f} (or a {@code +ref}) with no branch named, or naming a protected branch;
     * {@code --force-with-lease} only when it names a protected branch. Force-pushing a feature branch is allowed.
     */
    static boolean forcePushToProtectedBranch(String c) {
        Matcher m = GIT_PUSH.matcher(c);
        if (!m.find()) {
            return false;
        }
        List<String> args = Arrays.stream(m.group(1).split("[;&|]", 2)[0].trim().split("\\s+"))
                .filter(a -> !a.isEmpty()).toList();
        boolean force = args.contains("--force") || args.contains("-f")
                || args.stream().anyMatch(a -> a.startsWith("+"));
        boolean lease = args.stream().anyMatch(a -> a.startsWith("--force-with-lease"));
        if (!force && !lease) {
            return false;
        }
        List<String> refs = args.stream().filter(a -> !a.startsWith("-")).skip(1).toList();
        if (refs.isEmpty()) {
            return force;
        }
        return refs.stream().map(CommandSafety::targetBranch).anyMatch(b -> PROTECTED_BRANCH.matcher(b).matches());
    }

    /** "+src:dst" → dst, "refs/heads/main" → main. */
    private static String targetBranch(String refspec) {
        String r = refspec.startsWith("+") ? refspec.substring(1) : refspec;
        r = r.contains(":") ? r.substring(r.indexOf(':') + 1) : r;
        return r.startsWith("refs/heads/") ? r.substring("refs/heads/".length()) : r;
    }
}
