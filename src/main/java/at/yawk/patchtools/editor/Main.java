package at.yawk.patchtools.editor;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.fxml.JavaFXBuilderFactory;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.controlsfx.control.action.Action;
import org.controlsfx.dialog.Dialogs;

/**
 * @author yawkat
 */
public class Main extends Application {
    @Override
    public void start(Stage primaryStage) throws Exception {
        String input = getParameters().getNamed().get("input");

        Path opened;
        if (input == null) {
            opened = requestClass(primaryStage);
        } else {
            opened = expandShell(Paths.get(input));

            String clazz = getParameters().getNamed().get("class");
            if (clazz != null) {
                opened = openClass(opened, clazz);
            }
        }

        openMainWindow(primaryStage, opened);
    }

    private static Path expandShell(Path path) {
        if (path.startsWith("~")) {
            path = Paths.get(System.getProperty("user.home")).resolve(path.subpath(1, path.getNameCount()));
        }
        return path.toAbsolutePath();
    }

    private Path requestClass(Stage primaryStage) throws IOException {
        FileChooser classFileChooser = new FileChooser();
        classFileChooser.setTitle("Class File");
        Path opened = classFileChooser.showOpenDialog(primaryStage).toPath();

        if (opened.toString().toLowerCase().endsWith(".jar")) {
            Optional<String> input = Dialogs.create()
                    .owner(primaryStage)
                    .title("Class")
                    .masthead("Enter class name")
                    .actions(new Action("Ok"))
                    .showTextInput();

            if (!input.isPresent()) {
                System.exit(0);
            }

            String className = input.get();
            opened = openClass(opened, className);
        }
        return opened;
    }

    private Path openClass(Path zip, String className) throws IOException {
        FileSystem zipfs = FileSystems.newFileSystem(zip, null);
        className = className.replace('.', '/');
        if (!className.toLowerCase().endsWith(".class")) { className += ".class"; }
        zip = zipfs.getPath(className);
        return zip;
    }

    private void openMainWindow(Stage primaryStage, Path classFile) throws java.io.IOException {
        Main.<MainController>open(
                primaryStage,
                "main.fxml",
                "PTE",
                c -> {
                    c.setStage(primaryStage);
                    c.loadClassFile(classFile);
                }
        );
    }

    private static <C> void open(Stage stage, String layout, String title,
                                 ThrowingConsumer<C, IOException> controllerHandler)
            throws IOException {
        FXMLLoader loader = new FXMLLoader();
        loader.setLocation(Main.class.getResource(layout));
        loader.setBuilderFactory(new JavaFXBuilderFactory());

        Parent parent = loader.load();
        parent.getStylesheets().add(Main.class.getResource("style.css").toExternalForm());
        parent.getStylesheets().add(Main.class.getResource("scrollbar.css").toExternalForm());

        controllerHandler.accept(loader.getController());

        stage.setTitle(title);
        stage.setScene(new Scene(parent));
        stage.show();
    }

    public static void dump(Node n) { dump(n, 0); }

    private static void dump(Node n, int depth) {
        for (int i = 0; i < depth; i++) { System.out.print("  "); }
        System.out.println(n);
        if (n instanceof Parent) {
            for (Node c : ((Parent) n).getChildrenUnmodifiable()) {
                dump(c, depth + 1);
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static interface ThrowingConsumer<T, E extends Throwable> {
        void accept(T t) throws E;
    }
}
