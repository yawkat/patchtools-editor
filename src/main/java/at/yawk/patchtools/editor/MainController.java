package at.yawk.patchtools.editor;

import com.strobel.assembler.metadata.Buffer;
import com.strobel.assembler.metadata.ClassFileReader;
import com.strobel.assembler.metadata.IMetadataResolver;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.decompiler.DecompilerContext;
import com.strobel.decompiler.DecompilerSettings;
import com.strobel.decompiler.PlainTextOutput;
import com.strobel.decompiler.languages.java.ast.AstBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.PlainTextChange;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.reactfx.EventStream;
import uk.co.thinkofdeath.patchtools.Patcher;
import uk.co.thinkofdeath.patchtools.logging.LoggableException;
import uk.co.thinkofdeath.patchtools.wrappers.ClassPathWrapper;
import uk.co.thinkofdeath.patchtools.wrappers.ClassSet;

/**
 * @author yawkat
 */
public class MainController {
    private static final Pattern NON_WHITESPACE = Pattern.compile("\\S");
    private final Executor executor = Executors.newCachedThreadPool(new DaemonThreadFactory());

    @FXML GridPane rootPane;
    @FXML CodeArea javaCode;
    @FXML CodeArea byteCode;
    @FXML CodeArea patchCode;
    @FXML TextArea log;

    private Stage stage;
    private BooleanProperty saved = new SimpleBooleanProperty(true);
    private ClassNode initClass;
    private Optional<Path> patchFile = Optional.empty();
    private Optional<Path> classFile = Optional.empty();

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void loadClassFile(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            ClassReader reader = new ClassReader(is);
            initClass = new ClassNode(Opcodes.ASM5);
            reader.accept(initClass, 0);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        initClass.accept(writer);

        classFile = Optional.of(path);

        applyComputedCode(asyncComputeCode(writer.toByteArray()));

        updateTitle();
    }

    public synchronized void loadPatchFile(Path path) throws IOException {
        String patch = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);

        patchCode.replaceText(patch);
        patchFile = Optional.of(path);
        saved.set(true);
    }

    @FXML
    private void initialize() throws IOException {
        for (int i = 0; i < 3; i++) {
            ColumnConstraints col = new ColumnConstraints(1, 100, Double.MAX_VALUE);
            col.setHgrow(Priority.ALWAYS);
            rootPane.getColumnConstraints().add(col);

            RowConstraints row = new RowConstraints(1, 100, Double.MAX_VALUE);
            row.setVgrow(Priority.ALWAYS);
            rootPane.getRowConstraints().add(row);
        }

        KeywordHighlighter.JAVA.decorate(javaCode);
        KeywordHighlighter.PATCH.decorate(byteCode);
        KeywordHighlighter.PATCH.decorate(patchCode);

        patchCode.textProperty().addListener((observable, oldValue, newValue) -> saved.set(false));

        patchCode.setOnKeyTyped(new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                if (event.getCharacter().equals(String.valueOf((char) 13))) {
                    int newLineI = patchCode.getCurrentParagraph();
                    String prev = patchCode.getParagraph(newLineI - 1).toString();
                    Matcher matcher = NON_WHITESPACE.matcher(prev);
                    String indent;
                    if (matcher.find()) {
                        indent = prev.substring(0, matcher.start());
                    } else {
                        indent = prev;
                    }
                    System.out.println("Indent: '" + indent + "'");
                    patchCode.insertText(patchCode.getCaretPosition(), indent);
                }
            }
        });

        EventStream<PlainTextChange> patchStream = patchCode.plainTextChanges();
        patchStream
                .successionEnds(Duration.ofSeconds(2))
                .supplyTask(() -> {
                    Task<ComputedCode> task = new Task<ComputedCode>() {
                        @Override
                        protected ComputedCode call() throws Exception {
                            byte[] classBytes = getPatched(patchCode.getText());
                            return asyncComputeCode(classBytes);
                        }
                    };
                    executor.execute(task);
                    return task;
                })
                .awaitLatest(patchStream)
                .handleErrors(exception -> {
                    setPatchFieldColor(true);
                    showException(exception);
                })
                .subscribe(this::applyComputedCode);

        ContextMenu patchContextMenu = new ContextMenu();

        MenuItem open = new MenuItem("Open\u2026");
        open.setOnAction(evt -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Patch File");
            File file = chooser.showOpenDialog(null);
            if (file != null) {
                try {
                    loadPatchFile(file.toPath());
                } catch (IOException e) {
                    showException(e);
                }
            }
        });
        patchContextMenu.getItems().add(open);
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN));

        MenuItem save = new MenuItem("Save");
        save.setOnAction(evt -> save(false));
        patchContextMenu.getItems().add(save);
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN));

        MenuItem saveAs = new MenuItem("Save As\u2026");
        saveAs.setOnAction(evt -> save(true));
        patchContextMenu.getItems().add(saveAs);

        patchCode.setContextMenu(patchContextMenu);

        saved.addListener((observable, oldValue, newValue) -> updateTitle());
    }

    private synchronized void save(boolean forceChoose) {
        if (!patchFile.isPresent() || forceChoose) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Patch File");
            File file = chooser.showSaveDialog(null);
            if (file == null) {
                return;
            }
            patchFile = Optional.of(file.toPath());
        }

        try {
            Files.write(patchFile.get(), patchCode.getText().getBytes(StandardCharsets.UTF_8));
            saved.set(true);
        } catch (IOException e) {
            showException(e);
        }
    }

    private void showException(Throwable exception) {
        String trace;
        if (exception instanceof LoggableException) {
            Path logFile = Paths.get(exception.getMessage());
            try {
                trace = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                try {
                    Files.delete(logFile);
                } catch (IOException ignored) {}
            }
        } else {
            StringWriter w = new StringWriter();
            exception.printStackTrace(new PrintWriter(w));
            trace = w.toString();
        }
        log.setText(trace.replace("\t", "  "));
    }

    private void applyComputedCode(ComputedCode b) {
        setPatchFieldColor(false);
        javaCode.replaceText(b.javaCode);
        byteCode.replaceText(b.byteCode);
        log.setText("");
    }

    private ComputedCode asyncComputeCode(byte[] classBytes) {
        ClassReader classReader = new ClassReader(classBytes);
        PlainTextOutput byteCodeOutput = new PlainTextOutput();
        byteCodeOutput.setIndentToken("  ");
        new BytecodeMarkup().write(v -> classReader.accept(v, 0), byteCodeOutput);

        AstBuilder builder = new AstBuilder(
                new DecompilerContext(new DecompilerSettings())
        );
        TypeDefinition typeDefinition = ClassFileReader.readClass(
                ClassFileReader.OPTION_PROCESS_ANNOTATIONS | ClassFileReader.OPTION_PROCESS_CODE,
                IMetadataResolver.EMPTY,
                new Buffer(classBytes)
        );
        builder.addType(typeDefinition);
        PlainTextOutput output = new PlainTextOutput();
        builder.generateCode(output);

        return new ComputedCode(byteCodeOutput.toString(), output.toString());
    }

    private void setPatchFieldColor(boolean error) {
        if (error) {
            patchCode.setStyle("-fx-border-color: red");
        } else {
            patchCode.setStyle("");
        }
    }

    private byte[] getPatched(String patch) {
        // clone class node
        ClassNode node = new ClassNode(Opcodes.ASM5);
        initClass.accept(node);

        // init class set
        ClassSet classSet = new ClassSet(new ClassPathWrapper());
        classSet.add(node);

        // apply patch
        Patcher patcher = new Patcher(classSet);
        patcher.apply(new StringReader(patch));

        return classSet.getClass(node.name);
    }

    private void updateTitle() {
        StringBuilder title = new StringBuilder();

        if (!saved.get()) { title.append("* "); }

        title.append("PTE");
        patchFile.ifPresent(p -> title.append(" - ").append(p));
        classFile.ifPresent(p -> title.append(" - ").append(p));
        stage.setTitle(title.toString());
    }

    private static class ComputedCode {
        private final String byteCode;
        private final String javaCode;

        public ComputedCode(String byteCode, String javaCode) {
            this.byteCode = byteCode;
            this.javaCode = javaCode;
        }
    }
}
