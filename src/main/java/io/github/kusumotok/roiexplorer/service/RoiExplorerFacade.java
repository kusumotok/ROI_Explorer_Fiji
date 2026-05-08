package io.github.kusumotok.roiexplorer.service;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementColumn;
import io.github.kusumotok.roiexplorer.service.measure.MeasurementProfile;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;

import java.util.EnumSet;
import java.util.Set;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerWindow;

import javax.swing.*;
import java.nio.file.Path;

public class RoiExplorerFacade {

    private static final RoiExplorerFacade INSTANCE = new RoiExplorerFacade();

    private RoiExplorerWindow window;

    public static RoiExplorerFacade getInstance() {
        return INSTANCE;
    }

    public synchronized RoiExplorerWindow showWindow() {
        if (window == null || !window.isDisplayable()) {
            window = new RoiExplorerWindow();
        }
        window.setVisible(true);
        window.toFront();
        return window;
    }

    public synchronized RoiExplorerWindow getWindow() {
        return window != null && window.isDisplayable() ? window : null;
    }

    public synchronized boolean hasWindow() {
        return getWindow() != null;
    }

    /** Returns the embedded panel, or null if no window is open. */
    public synchronized RoiExplorerPanel getPanel() {
        RoiExplorerWindow w = getWindow();
        return w != null ? w.getPanel() : null;
    }

    public synchronized boolean hasLoadedRoot() {
        RoiExplorerWindow w = getWindow();
        return w != null && w.hasLoadedRoot();
    }

    public synchronized Path getCurrentRoot() {
        RoiExplorerWindow w = getWindow();
        return w != null ? w.getCurrentRoot() : null;
    }

    public synchronized ImagePlus getCurrentBindImage() {
        RoiExplorerWindow w = getWindow();
        return w != null ? w.getBoundImage() : null;
    }

    public synchronized boolean hasActivePreview() {
        RoiExplorerWindow w = getWindow();
        return w != null && w.hasActivePreview();
    }

    public synchronized RoiExplorerWindow openRoot(Path root, ImagePlus bindImage) {
        RoiExplorerWindow w = showWindow();
        runOnEdt(() -> {
            if (bindImage != null) {
                w.setBindImage(bindImage);
            }
            w.openFolder(root);
        });
        return w;
    }

    public synchronized RoiExplorerWindow replaceRoot(Path root, ImagePlus bindImage) {
        RoiExplorerWindow w = showWindow();
        runOnEdt(() -> {
            w.cleanupPreview();
            if (bindImage != null) {
                w.setBindImage(bindImage);
            }
            w.openFolder(root);
        });
        return w;
    }

    public synchronized void setBindImage(ImagePlus bindImage) {
        RoiExplorerWindow w = showWindow();
        runOnEdt(() -> w.setBindImage(bindImage));
    }

    public synchronized void reload() {
        RoiExplorerWindow w = getWindow();
        if (w == null) return;
        runOnEdt(w::reloadFromDisk);
    }

    public synchronized void cleanupPreview() {
        RoiExplorerWindow w = getWindow();
        if (w == null) return;
        runOnEdt(w::cleanupPreview);
    }

    public synchronized void measureCurrentRoot() {
        RoiExplorerWindow w = getWindow();
        if (w == null) return;
        runOnEdt(w::measureCurrentRoot);
    }

    public synchronized MeasurementResult measureCurrentRoot(MeasurementRequest request) {
        RoiExplorerWindow w = getWindow();
        if (w == null) {
            return MeasurementResult.notPerformed("ROI Explorer window is not open.");
        }
        final MeasurementResult[] result = new MeasurementResult[1];
        runOnEdt(() -> result[0] = w.measureCurrentRoot(request));
        return result[0];
    }

    public synchronized void launch3DWatershed() {
        RoiExplorerWindow w = getWindow();
        if (w == null) return;
        runOnEdt(w::launch3DWatershed);
    }

    public static final class MeasurementRequest {
        private final GroupMeasurementService.Options options;
        private final boolean promptForOptions;
        private final boolean showResultsTable;
        private final Path csvOutputPath;
        private final MeasurementProfile profile;
        private final Set<MeasurementColumn> enabledColumns;
        private final boolean measureAll;

        private MeasurementRequest(GroupMeasurementService.Options options, boolean promptForOptions,
                                   boolean showResultsTable, Path csvOutputPath,
                                   MeasurementProfile profile, Set<MeasurementColumn> enabledColumns,
                                   boolean measureAll) {
            this.options = options != null ? options.copy() : null;
            this.promptForOptions = promptForOptions;
            this.showResultsTable = showResultsTable;
            this.csvOutputPath = csvOutputPath;
            this.profile = profile;
            this.enabledColumns = enabledColumns != null
                ? EnumSet.copyOf(enabledColumns) : null;
            this.measureAll = measureAll;
        }

        /** Legacy: prompt user for GroupMeasurement options. */
        public static MeasurementRequest promptWithCurrentOptions() {
            return new MeasurementRequest(null, true, true, null, null, null, false);
        }

        /** Legacy: run GroupMeasurement with the given options. */
        public static MeasurementRequest useOptions(GroupMeasurementService.Options options) {
            return new MeasurementRequest(options, false, true, null, null, null, false);
        }

        /** New: run with a MeasurementProfile (e.g. XyzObjectProfile). */
        public static MeasurementRequest useProfile(MeasurementProfile profile) {
            return new MeasurementRequest(null, false, false, null, profile, null, false);
        }

        public MeasurementRequest withShowResultsTable(boolean v) {
            return new MeasurementRequest(options, promptForOptions, v, csvOutputPath, profile, enabledColumns, measureAll);
        }

        public MeasurementRequest withCsvOutput(Path p) {
            return new MeasurementRequest(options, promptForOptions, showResultsTable, p, profile, enabledColumns, measureAll);
        }

        public MeasurementRequest withEnabledColumns(Set<MeasurementColumn> columns) {
            return new MeasurementRequest(options, promptForOptions, showResultsTable, csvOutputPath, profile, columns, measureAll);
        }

        /** When true, ignore ROI Explorer selection and measure all direct children of the current root. */
        public MeasurementRequest withMeasureAll(boolean all) {
            return new MeasurementRequest(options, promptForOptions, showResultsTable, csvOutputPath, profile, enabledColumns, all);
        }

        public GroupMeasurementService.Options getOptions() {
            return options != null ? options.copy() : null;
        }

        public boolean isPromptForOptions() { return promptForOptions; }
        public boolean isShowResultsTable() { return showResultsTable; }
        public Path getCsvOutputPath()      { return csvOutputPath; }
        public MeasurementProfile getProfile() { return profile; }
        /** Returns enabled columns, or all columns if not specified. */
        public Set<MeasurementColumn> getEnabledColumns() {
            return enabledColumns != null ? EnumSet.copyOf(enabledColumns) : MeasurementColumn.allEnabled();
        }
        public boolean isMeasureAll() { return measureAll; }
    }

    public static final class MeasurementResult {
        private final boolean performed;
        private final String message;

        private MeasurementResult(boolean performed, String message) {
            this.performed = performed;
            this.message = message;
        }

        public static MeasurementResult performed(String message) {
            return new MeasurementResult(true, message);
        }

        public static MeasurementResult notPerformed(String message) {
            return new MeasurementResult(false, message);
        }

        public boolean isPerformed() {
            return performed;
        }

        public String getMessage() {
            return message;
        }
    }

    private static void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            throw new RuntimeException("Failed to run on EDT", e);
        }
    }
}
