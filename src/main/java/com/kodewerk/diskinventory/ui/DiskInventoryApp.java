package com.kodewerk.diskinventory.ui;

import com.kodewerk.diskinventory.model.DirectoryNode;
import com.kodewerk.diskinventory.model.DiskFree;
import com.kodewerk.diskinventory.model.DiskUsageModel;
import com.kodewerk.diskinventory.model.FileEntry;
import com.kodewerk.diskinventory.model.FileRef;
import com.kodewerk.diskinventory.model.ScanResult;
import com.kodewerk.diskinventory.model.SizeMode;
import com.kodewerk.diskinventory.model.Sizes;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thin JavaFX wrapper over {@link DiskUsageModel}. The scan runs on a
 * background thread; clicks navigate the already-built tree. Walking up past
 * the original root scans only the newly exposed siblings — the known subtree
 * is grafted in.
 */
public class DiskInventoryApp extends Application {

    private static final double GROUP_BELOW_FRACTION = 0.015;
    private static final int TOOLTIP_MAX_LINES = 25;
    private static final double FILE_ROW_HEIGHT = 22;
    private static final int TOP_FILES = 50;

    private final DiskUsageModel model = new DiskUsageModel();
    private final List<DirectoryNode> trail = new ArrayList<>();
    private SizeMode mode = SizeMode.ALLOCATED;

    private Stage stage;
    private Path root;
    private PieChart chart;
    private HBox breadcrumb;
    private Label crumbSize;
    private Button upButton;
    private Button rescanButton;
    private Button analyzeButton;
    private ProgressIndicator spinner;
    private Label status;
    private GridPane dfGrid;
    private ToggleButton filesToggle;
    private VBox filesPanel;
    private ListView<FileRef> filesList;
    private TreeView<Path> dirTree;
    private boolean syncingTree;

    private Task<ScanResult> currentTask;
    private ScanResult result;
    private volatile Path scanningDir;
    private Path pivotTarget;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        root = resolveRoot(stage);
        if (root == null) {
            Platform.exit();
            return;
        }

        chart = new PieChart();
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setLabelsVisible(true);

        VBox center = new VBox(buildCrumbRow(), chart, buildStatusRow());
        VBox.setVgrow(chart, Priority.ALWAYS);

        BorderPane inner = new BorderPane(center);
        inner.setLeft(buildDirTree());
        inner.setRight(buildFilesPanel());

        BorderPane pane = new BorderPane(inner);
        pane.setTop(buildToolbar());
        pane.setBottom(buildDfPanel());

        Scene scene = new Scene(pane, 1100, 750);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm());
        stage.setScene(scene);
        stage.show();

        scan();
    }

    private HBox buildToolbar() {
        upButton = new Button("↑");
        upButton.setTooltip(new Tooltip("Up one level (keeps working past the launch point, until /)"));
        upButton.setOnAction(e -> up());

        rescanButton = new Button("↻");
        rescanButton.setTooltip(new Tooltip(
                "Rescan the current directory (Shift-click: rescan everything from the scan root)"));
        // A mouse handler, not onAction: ActionEvent does not carry the Shift state.
        rescanButton.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            if (e.isShiftDown() || trail.isEmpty()) {
                scan();
            } else {
                rescan(current().path());
            }
        });

        for (Button b : List.of(upButton, rescanButton)) {
            b.getStyleClass().add("tool-icon");
            b.setFocusTraversable(false);
        }

        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton allocatedMode = new ToggleButton("Allocated");
        allocatedMode.setTooltip(new Tooltip("On-disk size (st_blocks), what du reports"));
        ToggleButton logicalMode = new ToggleButton("Logical");
        logicalMode.setTooltip(new Tooltip("Apparent size (Files.size); sparse files look huge"));
        for (ToggleButton b : List.of(allocatedMode, logicalMode)) {
            b.setToggleGroup(modeGroup);
            b.getStyleClass().add("mode-segment");
            b.setFocusTraversable(false);
        }
        (mode == SizeMode.ALLOCATED ? allocatedMode : logicalMode).setSelected(true);
        modeGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                // Clicking the active segment must not deselect the group.
                modeGroup.selectToggle(old);
                return;
            }
            mode = selected == allocatedMode ? SizeMode.ALLOCATED : SizeMode.LOGICAL;
            if (!trail.isEmpty()) {
                render();
            }
        });

        HBox modeBox = new HBox(2, allocatedMode, logicalMode);
        modeBox.getStyleClass().add("mode-box");
        modeBox.setAlignment(Pos.CENTER_LEFT);

        filesToggle = new ToggleButton("Largest files");
        filesToggle.setTooltip(new Tooltip("Show the largest files under the current directory"));
        filesToggle.getStyleClass().add("bar-toggle");
        filesToggle.setFocusTraversable(false);
        filesToggle.selectedProperty().addListener((obs, old, selected) -> {
            filesPanel.setVisible(selected);
            filesPanel.setManaged(selected);
            if (selected) {
                updateLargestFiles();
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, upButton, rescanButton, modeBox, spacer, filesToggle);
        toolbar.getStyleClass().add("app-bar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return toolbar;
    }

    private HBox buildCrumbRow() {
        breadcrumb = new HBox(4);
        breadcrumb.setAlignment(Pos.CENTER_LEFT);
        crumbSize = new Label();
        crumbSize.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, breadcrumb, spacer, crumbSize);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 12, 0, 8));
        return row;
    }

    private void renderBreadcrumb() {
        breadcrumb.getChildren().clear();

        // Ancestors of the scan root, one crumb per path component.
        List<Path> ancestors = new ArrayList<>();
        for (Path p = trail.getFirst().path().getParent();
                p != null && p.getFileName() != null; p = p.getParent()) {
            ancestors.add(p);
        }
        for (Path ancestor : ancestors.reversed()) {
            Hyperlink link = addCrumb(ancestor.getFileName().toString());
            link.setTooltip(new Tooltip("Walk up to " + ancestor + " (scans its new siblings)"));
            link.setOnAction(e -> walkUpTo(ancestor));
        }

        for (int i = 0; i < trail.size(); i++) {
            Hyperlink link = addCrumb(trail.get(i).name());
            int depth = i;
            link.setOnAction(e -> {
                trail.subList(depth + 1, trail.size()).clear();
                render();
            });
        }
    }

    private Hyperlink addCrumb(String text) {
        if (!breadcrumb.getChildren().isEmpty()) {
            breadcrumb.getChildren().add(new Label("▸"));
        }
        Hyperlink link = new Hyperlink(text);
        breadcrumb.getChildren().add(link);
        return link;
    }

    /** Walks the scan root up to {@code target}, grafting at each level. */
    private void walkUpTo(Path target) {
        ScanResult top = result;
        if (top.root().path().equals(target) || !top.root().path().startsWith(target)) {
            return;
        }
        runTask(new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                ScanResult node = top;
                while (!node.root().path().equals(target)) {
                    node = model.scanParent(node, progressReporter(this::updateMessage));
                }
                return node;
            }
        }, false);
    }

    private HBox buildStatusRow() {
        spinner = new ProgressIndicator();
        spinner.setPrefSize(16, 16);

        analyzeButton = new Button("Analyze this path");
        analyzeButton.setTooltip(new Tooltip(
                "Stop the current scan and analyze the directory it is scanning right now"));
        analyzeButton.setOnAction(e -> pivotToScanningDir());

        status = new Label();
        HBox statusRow = new HBox(8, spinner, status, analyzeButton);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setPadding(new Insets(4, 8, 4, 8));
        showScanActivity(false);
        return statusRow;
    }

    private VBox buildDfPanel() {
        dfGrid = new GridPane();
        dfGrid.setHgap(18);
        dfGrid.setVgap(2);
        dfGrid.setPadding(new Insets(6, 10, 6, 10));
        dfGrid.setStyle("-fx-background-color: -fx-control-inner-background;"
                + " -fx-border-color: -fx-box-border; -fx-border-radius: 3;");

        VBox bottom = new VBox(dfGrid);
        bottom.setPadding(new Insets(4, 8, 8, 8));
        return bottom;
    }

    private VBox buildFilesPanel() {
        Label header = new Label("Largest files (top " + TOP_FILES + ")");
        header.setStyle("-fx-font-weight: bold;");

        filesList = new ListView<>();
        filesList.setFixedCellSize(FILE_ROW_HEIGHT);
        filesList.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        filesList.setCellFactory(lv -> {
            ListCell<FileRef> cell = new ListCell<>() {
                @Override
                protected void updateItem(FileRef ref, boolean empty) {
                    super.updateItem(ref, empty);
                    if (empty || ref == null) {
                        setText(null);
                        setTooltip(null);
                    } else {
                        setText(String.format("%9s  %s",
                                Sizes.human(ref.file().size(mode)), ref.file().name()));
                        Tooltip tip = new Tooltip(ref.path().toString());
                        tip.setShowDelay(Duration.millis(200));
                        setTooltip(tip);
                    }
                }
            };
            cell.setOnMouseClicked(e -> {
                if (cell.getItem() != null) {
                    focusPath(cell.getItem().directory());
                }
            });
            return cell;
        });
        VBox.setVgrow(filesList, Priority.ALWAYS);

        filesPanel = new VBox(6, header, filesList);
        filesPanel.setPadding(new Insets(8));
        filesPanel.setPrefWidth(340);
        filesPanel.setVisible(false);
        filesPanel.setManaged(false);
        return filesPanel;
    }

    private TreeView<Path> buildDirTree() {
        dirTree = new TreeView<>(new LazyDirectoryItem(Path.of("/")));
        dirTree.getRoot().setExpanded(true);
        dirTree.getStyleClass().add("dir-tree");
        dirTree.setPrefWidth(260);
        dirTree.setCellFactory(tv -> {
            TreeCell<Path> cell = new TreeCell<>() {
                @Override
                protected void updateItem(Path path, boolean empty) {
                    super.updateItem(path, empty);
                    if (empty || path == null) {
                        setText(null);
                    } else {
                        Path name = path.getFileName();
                        setText(name != null ? name.toString() : path.toString());
                    }
                }
            };
            // Right-click must not select (selecting focuses, or even scans, the directory).
            cell.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                if (e.getButton() == MouseButton.SECONDARY) {
                    e.consume();
                }
            });
            cell.setOnContextMenuRequested(e -> {
                if (cell.getItem() != null) {
                    showMenu(pathMenu(cell.getItem(), true), cell, e.getScreenX(), e.getScreenY());
                }
                e.consume();
            });
            return cell;
        });
        dirTree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (!syncingTree && item != null) {
                focusPath(item.getValue());
            }
        });
        return dirTree;
    }

    /** Expands and selects the tree branch for {@code target} without re-focusing the pie. */
    private void revealInTree(Path target) {
        TreeItem<Path> selected = dirTree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue().equals(target)) {
            return;
        }
        syncingTree = true;
        try {
            TreeItem<Path> item = findTreeItem(target);
            if (item == null) {
                return;     // branch not listable; leave the tree as it is
            }
            for (TreeItem<Path> i = item; i != null; i = i.getParent()) {
                i.setExpanded(true);
            }
            dirTree.getSelectionModel().select(item);
            int row = dirTree.getRow(item);
            if (row >= 0) {
                dirTree.scrollTo(row);
            }
        } finally {
            syncingTree = false;
        }
    }

    /** The tree item for {@code target}, listing directories on the way; null if not listable. */
    private TreeItem<Path> findTreeItem(Path target) {
        TreeItem<Path> item = dirTree.getRoot();
        if (!target.startsWith(item.getValue())) {
            return null;
        }
        outer:
        for (Path name : item.getValue().relativize(target)) {
            if (name.toString().isEmpty()) {
                break;      // target is the tree root
            }
            for (TreeItem<Path> child : item.getChildren()) {
                Path childName = child.getValue().getFileName();
                if (childName != null && childName.toString().equals(name.toString())) {
                    item = child;
                    continue outer;
                }
            }
            return null;
        }
        return item;
    }

    /** Re-lists {@code dir}'s subdirectories in the tree after the disk changed under it. */
    private void reloadTreeItem(Path dir) {
        if (dir == null) {
            return;
        }
        syncingTree = true;
        try {
            if (findTreeItem(dir) instanceof LazyDirectoryItem item) {
                item.reload();
            }
        } finally {
            syncingTree = false;
        }
        if (!trail.isEmpty()) {
            revealInTree(current().path());
        }
    }

    /** Right-click menu for a directory or file in the scanned tree. */
    private ContextMenu pathMenu(Path p, boolean isDirectory) {
        ContextMenu menu = new ContextMenu();
        if (result != null && p.startsWith(result.root().path())) {
            MenuItem rescan = new MenuItem("Rescan");
            rescan.setOnAction(e -> rescan(p));
            menu.getItems().add(rescan);
        }
        menu.setOnShowing(e -> {
            boolean busy = currentTask != null && currentTask.isRunning();
            menu.getItems().forEach(item -> item.setDisable(busy));
        });
        return menu;
    }

    private static void showMenu(ContextMenu menu, Node anchor, double screenX, double screenY) {
        if (!menu.getItems().isEmpty()) {
            menu.show(anchor, screenX, screenY);
        }
    }

    /**
     * Re-focuses the pie on {@code target}: instantly when it lies inside the
     * scanned tree, otherwise by scanning it. During a scan this pivots.
     */
    private void focusPath(Path target) {
        if (trail.isEmpty()) {
            return;
        }
        if (currentTask != null && currentTask.isRunning()) {
            pivotTarget = target;
            currentTask.cancel(true);
            return;
        }
        if (target.equals(current().path())) {
            return;
        }
        List<DirectoryNode> newTrail = trailTo(trail.getFirst(), target);
        if (newTrail.getLast().path().equals(target)) {
            trail.clear();
            trail.addAll(newTrail);
            render();
            return;
        }
        root = target;
        scan();
    }

    /**
     * The nodes from {@code scanRoot} down towards {@code target}, stopping at
     * the deepest one that exists in the tree: {@code target}'s nearest
     * existing ancestor, or just {@code scanRoot} when target is outside it.
     */
    static List<DirectoryNode> trailTo(DirectoryNode scanRoot, Path target) {
        List<DirectoryNode> nodes = new ArrayList<>();
        nodes.add(scanRoot);
        if (!target.startsWith(scanRoot.path())) {
            return nodes;
        }
        DirectoryNode node = scanRoot;
        for (Path name : scanRoot.path().relativize(target)) {
            if (name.toString().isEmpty()) {
                break;      // target is the scan root
            }
            var child = node.child(name.toString());
            if (child.isEmpty()) {
                break;
            }
            node = child.get();
            nodes.add(node);
        }
        return nodes;
    }

    private void updateLargestFiles() {
        if (!filesToggle.isSelected() || trail.isEmpty()) {
            return;
        }
        filesList.getItems().setAll(result.largestFiles(current(), TOP_FILES, mode));
    }

    private Path resolveRoot(Stage stage) {
        List<String> args = getParameters().getRaw();
        if (!args.isEmpty()) {
            return Path.of(args.getFirst()).toAbsolutePath().normalize();
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose directory to scan");
        File chosen = chooser.showDialog(stage);
        return chosen != null ? chosen.toPath().toAbsolutePath().normalize() : null;
    }

    /** Pops one level, or scans the parent once the trail top is reached. */
    private void up() {
        if (trail.size() > 1) {
            trail.removeLast();
            render();
            return;
        }
        ScanResult top = result;
        if (top.root().path().getParent() == null) {
            return;
        }
        runTask(new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                return model.scanParent(top, progressReporter(this::updateMessage));
            }
        }, false);
    }

    private void scan() {
        Path target = root;
        runTask(new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                return model.scan(target, progressReporter(this::updateMessage));
            }
        }, false);
    }

    /**
     * Re-reads {@code p} (a directory or file under the scan root) and stays
     * on the current directory, or its nearest ancestor if it is gone.
     * Rescanning the scan root is a full scan.
     */
    private void rescan(Path p) {
        ScanResult before = result;
        Task<ScanResult> task = new Task<>() {
            @Override
            protected ScanResult call() throws Exception {
                return model.rescan(before, p, progressReporter(this::updateMessage));
            }
        };
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED, e -> reloadTreeItem(p.getParent()));
        runTask(task, true);
    }

    /** Stops the running scan and re-roots the analysis at its current position. */
    private void pivotToScanningDir() {
        Path target = scanningDir;
        if (target == null || currentTask == null || !currentTask.isRunning()) {
            return;
        }
        pivotTarget = target;
        currentTask.cancel(true);
    }

    private DiskUsageModel.ScanListener progressReporter(Consumer<String> message) {
        return (dir, dirs, bytes) -> {
            scanningDir = dir;
            message.accept(String.format("Scanning %s — %,d dirs, %s so far",
                    dir, dirs, Sizes.human(bytes)));
        };
    }

    /**
     * Runs a tree-producing task, one at a time. {@code keepTrail} keeps the
     * view on the current directory (or its nearest surviving ancestor);
     * otherwise the view starts at the new scan root.
     */
    private void runTask(Task<ScanResult> task, boolean keepTrail) {
        currentTask = task;
        pivotTarget = null;
        scanningDir = null;
        upButton.setDisable(true);
        rescanButton.setDisable(true);
        showScanActivity(true);
        chart.getData().clear();

        status.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            scanFinished();
            Path at = keepTrail && !trail.isEmpty() ? current().path() : null;
            result = task.getValue();
            root = result.root().path();
            stage.setTitle("Disk Inventory — " + root);
            trail.clear();
            trail.addAll(at != null ? trailTo(result.root(), at) : List.of(result.root()));
            render();
            refreshDf();
        });
        task.setOnFailed(e -> {
            scanFinished();
            upButton.setDisable(false);
            status.setText("Scan failed: " + task.getException().getMessage());
        });
        task.setOnCancelled(e -> {
            scanFinished();
            if (pivotTarget != null) {
                root = pivotTarget;
                pivotTarget = null;
                scan();
            } else {
                upButton.setDisable(false);
                status.setText("Scan cancelled");
            }
        });
        Thread thread = new Thread(task, "disk-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void scanFinished() {
        status.textProperty().unbind();
        rescanButton.setDisable(false);
        showScanActivity(false);
        currentTask = null;
    }

    private void showScanActivity(boolean scanning) {
        spinner.setVisible(scanning);
        spinner.setManaged(scanning);
        analyzeButton.setVisible(scanning);
        analyzeButton.setManaged(scanning);
    }

    private void refreshDf() {
        Path path = root;
        Thread thread = new Thread(() -> {
            String raw = DiskFree.df(path);
            DiskFree.Table table = DiskFree.parse(raw);
            Platform.runLater(() -> populateDfGrid(table, raw));
        }, "df");
        thread.setDaemon(true);
        thread.start();
    }

    private void populateDfGrid(DiskFree.Table table, String raw) {
        dfGrid.getChildren().clear();
        if (table.isEmpty()) {
            dfGrid.add(monospaceLabel(raw), 0, 0);
            return;
        }
        for (int col = 0; col < table.headers().size(); col++) {
            Label header = new Label(table.headers().get(col));
            header.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            dfGrid.add(header, col, 0);
        }
        for (int row = 0; row < table.rows().size(); row++) {
            List<String> cells = table.rows().get(row);
            for (int col = 0; col < cells.size(); col++) {
                dfGrid.add(monospaceLabel(cells.get(col)), col, row + 1);
            }
        }
    }

    private static Label monospaceLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-family: monospace; -fx-font-size: 11px;");
        return label;
    }

    private DirectoryNode current() {
        return trail.getLast();
    }

    private void render() {
        DirectoryNode node = current();
        chart.getData().clear();
        renderBreadcrumb();
        crumbSize.setText(Sizes.human(node.totalSize(mode))
                + (mode == SizeMode.ALLOCATED ? " on disk" : " logical"));
        upButton.setDisable(trail.size() == 1 && trail.getFirst().path().getParent() == null);
        updateLargestFiles();
        revealInTree(node.path());

        long total = node.totalSize(mode);
        if (total == 0) {
            status.setText("Empty directory");
            return;
        }

        List<DirectoryNode> children = node.children().stream()
                .sorted(Comparator.comparingLong((DirectoryNode c) -> c.totalSize(mode)).reversed())
                .toList();

        long grouped = 0;
        int groupedCount = 0;
        StringBuilder groupedNames = new StringBuilder();
        for (DirectoryNode child : children) {
            long size = child.totalSize(mode);
            if (size == 0) {
                continue;
            }
            if ((double) size / total < GROUP_BELOW_FRACTION) {
                grouped += size;
                groupedCount++;
                if (groupedCount <= TOOLTIP_MAX_LINES) {
                    groupedNames.append(groupedCount > 1 ? "\n" : "")
                            .append(child.name()).append("  ").append(Sizes.human(size));
                }
            } else {
                String label = sliceLabel(child.name(), size, total);
                long shared = result.sharedBytes(child.path(), mode);
                String tip = shared > 0
                        ? label + "\n" + Sizes.human(shared) + " shared with other directories"
                        : null;
                addSlice(label, size, child, tip);
            }
        }
        if (node.directFileSize(mode) > 0) {
            addSlice(sliceLabel("[files]", node.directFileSize(mode), total),
                    node.directFileSize(mode), null, filesTooltip(node));
        }
        if (grouped > 0) {
            if (groupedCount > TOOLTIP_MAX_LINES) {
                groupedNames.append("\n… and ").append(groupedCount - TOOLTIP_MAX_LINES).append(" more");
            }
            addSlice(sliceLabel("[" + groupedCount + " small dirs]", grouped, total),
                    grouped, null, groupedNames.toString());
        }

        int subdirs = node.children().size();
        String summary = subdirs + (subdirs == 1 ? " subdirectory" : " subdirectories");
        if (node.errorCount() > 0) {
            summary += " — " + node.errorCount()
                    + (node.errorCount() == 1 ? " unreadable entry skipped" : " unreadable entries skipped");
        }
        status.setText(summary);
    }

    /** Direct files of this directory, largest first in the current mode, one per line. */
    private String filesTooltip(DirectoryNode node) {
        List<FileEntry> files = node.files().stream()
                .sorted(Comparator.comparingLong((FileEntry f) -> f.size(mode)).reversed())
                .toList();
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(files.size(), TOOLTIP_MAX_LINES);
        for (int i = 0; i < shown; i++) {
            FileEntry file = files.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(String.format("%9s  %s", Sizes.human(file.size(mode)), file.name()));
        }
        if (files.size() > shown) {
            sb.append("\n… and ").append(files.size() - shown).append(" more files");
        }
        return sb.toString();
    }

    private String sliceLabel(String name, long size, long total) {
        return String.format("%s  %s (%.1f%%)", name, Sizes.human(size), 100.0 * size / total);
    }

    private void addSlice(String label, long size, DirectoryNode target, String tooltipText) {
        PieChart.Data data = new PieChart.Data(label, size);
        chart.getData().add(data);
        String tip = tooltipText != null ? tooltipText : label;
        if (data.getNode() != null) {
            wireSlice(data.getNode(), target, tip);
        } else {
            // The chart creates slice nodes lazily; attach once it exists.
            data.nodeProperty().addListener((obs, old, sliceNode) -> {
                if (sliceNode != null) {
                    wireSlice(sliceNode, target, tip);
                }
            });
        }
    }

    private void wireSlice(Node sliceNode, DirectoryNode target, String tooltipText) {
        Tooltip tooltip = new Tooltip(tooltipText);
        tooltip.setShowDelay(Duration.millis(200));
        tooltip.setShowDuration(Duration.minutes(2));
        tooltip.setStyle("-fx-font-family: monospace;");
        Tooltip.install(sliceNode, tooltip);
        if (target != null) {
            sliceNode.setStyle("-fx-cursor: hand;");
            sliceNode.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    trail.add(target);
                    render();
                }
            });
            sliceNode.setOnContextMenuRequested(e -> {
                showMenu(pathMenu(target.path(), true), sliceNode, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }
    }
}
