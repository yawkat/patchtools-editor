package at.yawk.patchtools.editor;

import dk.brics.automaton.AutomatonMatcher;
import dk.brics.automaton.RegExp;
import dk.brics.automaton.RunAutomaton;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javafx.concurrent.Task;
import org.fxmisc.richtext.*;
import org.reactfx.EventStream;
import uk.co.thinkofdeath.patchtools.instruction.Instruction;

/**
 * @author yawkat
 */
public class KeywordHighlighter {
    public static final KeywordHighlighter JAVA = new KeywordHighlighter(
            keywords(
                    "keyword",

                    "abstract ", "assert ", "boolean ", "break ", "byte ",
                    "case ", "catch ", "char ", "class ", "const ",
                    "continue ", "default ", "do ", "double ", "else ",
                    "enum ", "extends ", "final ", "finally ", "float ",
                    "for ", "goto ", "if ", "implements ", "import ",
                    "instanceof ", "int ", "interface ", "long ", "native ",
                    "new ", "package ", "private ", "protected ", "public ",
                    "return ", "short ", "static ", "strictfp ", "super ",
                    "switch ", "synchronized ", "this ", "throw ", "throws ",
                    "transient ", "try ", "void ", "volatile ", "while"
            ),
            keywords("semicolon", ";"),
            keywords("paren", "[\\(\\)]"),
            keywords("brace", "[\\{\\}]"),
            keywords("bracket", "[\\[\\]]"),
            keywords("string", "\\\"([^\"]*(\\\\\\\")?)*\\\"", "'(\\\\[\"']|.)'")
    );
    public static final KeywordHighlighter PATCH = new KeywordHighlighter(
            keywords(
                    "keyword",

                    "abstract ", "class ", "void ", "double ", "float ", "int ", "interface ", "long ", "native ",
                    "private ", "protected ", "public ", "short ", "static ", "synchronized ", "throws ", "boolean "
            ),
            keywords(
                    "find",

                    Arrays.stream(Instruction.values())
                            .map(i -> "\\." + i.toString().toLowerCase().replace("_", "[-_]"))
                            .toArray(String[]::new)
            ),
            keywords("add", "add"),
            keywords(
                    "add",

                    Arrays.stream(Instruction.values())
                            .map(i -> "\\+" + i.toString().toLowerCase().replace("_", "[-_]"))
                            .toArray(String[]::new)
            ),
            keywords("remove", "remove"),
            keywords(
                    "remove",

                    Arrays.stream(Instruction.values())
                            .map(i -> "-" + i.toString().toLowerCase().replace("_", "[-_]"))
                            .toArray(String[]::new)
            ),
            keywords("semicolon", ";"),
            keywords("paren", "[\\(\\)]"),
            keywords("brace", "[\\{\\}]"),
            keywords("bracket", "[\\[\\]]"),
            keywords("string", "\\\"([^\"]*(\\\\\\\")?)*\\\"", "'(\\\\[\"']|.)'"),
            keywords("comment", "//[^\n]*", "\\#[^\n]*", "/\\*(\\*?[^/*]/?)*\\*/"),
            // patchtools-cli actions
            keywords("action", "\\#(include|exclude) [^\n]+", "//(include|exclude) [^\n]+"),
            keywords("match", "\\~")
    );

    private final List<Keyword> keywords;

    private static List<Keyword> keywords(String clazz, @org.intellij.lang.annotations.RegExp String... keywords) {
        return Arrays.stream(keywords).map(k -> {
            System.out.println("Parsing regex " + k);
            return new Keyword(new RunAutomaton(new RegExp(k).toAutomaton()), clazz);
        }).collect(Collectors.toList());
    }

    @SafeVarargs
    private KeywordHighlighter(List<Keyword>... keywords) {
        this.keywords = Arrays.stream(keywords).flatMap(Collection::stream).collect(Collectors.toList());
    }

    public void decorate(CodeArea codeArea) {
        Executor pool = Executors.newSingleThreadExecutor(new DaemonThreadFactory());

        String extStyle = KeywordHighlighter.class.getResource("style.css").toExternalForm();
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(
                codeArea,
                d -> "%" + d + "d ",
                extStyle
        ));

        EventStream<PlainTextChange> textChanges = codeArea.plainTextChanges();
        textChanges
                .successionEnds(Duration.ofMillis(20))
                .supplyTask(() -> new Task<StyleSpans<Collection<String>>>() {
                    { pool.execute(this); }

                    @Override
                    protected StyleSpans<Collection<String>> call() throws Exception {
                        return findAndSortHighlights(codeArea.getText());
                    }
                })
                .awaitLatest(textChanges)
                .subscribe(h -> codeArea.setStyleSpans(0, h));
    }

    private StyleSpans<Collection<String>> findAndSortHighlights(String text) {
        List<StyleChange> changes = new ArrayList<>(Arrays.asList(new StyleChange(0, "default", true)));
        for (Keyword keyword : keywords) {
            AutomatonMatcher matcher = keyword.matcher.newMatcher(text);
            while (matcher.find()) {
                changes.add(new StyleChange(matcher.start(), keyword.clazz, true));
                changes.add(new StyleChange(matcher.end(), keyword.clazz, false));
            }
        }
        Collections.sort(changes, Comparator.comparingInt(c -> c.index));

        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int i = 0;
        int prevIndex = 0;
        List<String> classes = new ArrayList<>();
        while (i < changes.size()) {
            int index = changes.get(i).index;
            builder.add(new HashSet<>(classes), index - prevIndex);
            for (; i < changes.size(); i++) {
                StyleChange change = changes.get(i);
                if (change.index != index) {
                    break;
                }
                change.apply(classes);
            }
            prevIndex = index;
        }
        return builder.create();
    }

    private static class Keyword {
        private final RunAutomaton matcher;
        private final String clazz;

        public Keyword(RunAutomaton matcher, String clazz) {
            this.matcher = matcher;
            this.clazz = clazz;
        }
    }

    private static class StyleChange {
        private final int index;
        private final String clazz;
        private final boolean add;

        public StyleChange(int index, String clazz, boolean add) {
            this.index = index;
            this.clazz = clazz;
            this.add = add;
        }

        private void apply(Collection<String> classes) {
            if (add) {
                classes.add(clazz);
            } else {
                classes.remove(clazz);
            }
        }
    }
}
