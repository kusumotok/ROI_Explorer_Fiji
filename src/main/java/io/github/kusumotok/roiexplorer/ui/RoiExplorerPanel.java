package io.github.kusumotok.roiexplorer.ui;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.io.RoiEncoder;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.PathKey;
import io.github.kusumotok.roiexplorer.controller.RoiEditController;
import io.github.kusumotok.roiexplorer.controller.SplitWorkflowSession;
import io.github.kusumotok.roiexplorer.model.*;
import io.github.kusumotok.roiexplorer.service.*;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;
import io.github.kusumotok.roiexplorer.service.measure.ObjectMeasurementCsvExporter;
import io.github.kusumotok.roiexplorer.service.measure.ObjectMeasurementResult;
import io.github.kusumotok.roiexplorer.service.measure.ObjectMeasurementService;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.Set;

public class RoiExplorerPanel extends JPanel implements RoiEditController.EditHost {

    // ── Services ─────────────────────────────────────────────────────────────
    private final DiskSyncService diskSync = new DiskSyncService();
    private final RoiZipService roiZipService = new RoiZipService();
    private final SelectionResolver selResolver = new SelectionResolver();
    private final RoiMeasurementService measureSvc = new RoiMeasurementService();
    private final GroupMeasurementService groupMeasureSvc = new GroupMeasurementService();
    private final ObjectMeasurementService objectMeasureSvc = new ObjectMeasurementService();
    private final ObjectMeasurementCsvExporter objectMeasureCsvExporter = new ObjectMeasurementCsvExporter();
    private final Roi3DWatershedService watershed3dSvc = new Roi3DWatershedService();
    private GroupMeasurementService.Options groupMeasureOptions = new GroupMeasurementService.Options();
    private boolean groupMeasureConfigured;
    private final RoiManagerInteropService roiManagerSvc = new RoiManagerInteropService();
    private final SessionHistoryService historySvc = new SessionHistoryService();
    private final NodeLoader nodeLoader = new NodeLoader();
    private final RoiEditController editCtrl = new RoiEditController(this);

    // ── Model / rendering ────────────────────────────────────────────────────
    private final ExplorerTreeTableModel tableModel = new ExplorerTreeTableModel();
    private final TreeColumnRenderer nameRenderer = new TreeColumnRenderer();
    private final JTable table;

    // ── Buttons ───────────────────────────────────────────────────────────────
    private final JButton btnOpen         = new JButton("Open Folder...");
    private final JButton btnReload       = new JButton("Reload");
    private final JButton btnAdd          = new JButton("Add");
    private final JButton btnEdit         = new JButton("Edit");
    private final JButton btnNewFolder    = new JButton("New Folder");
    private final JButton btnDelete       = new JButton("Delete");
    private final JButton btnRename       = new JButton("Rename...");
    private final JButton btnMeasure      = new JButton("Measure ROI");
    private final JButton btnGroupMeasure = new JButton("Measure Folder...");
    private final JButton btnPickRoi      = new JButton("Pick ROI");
    private final JButton btnToggleVis    = new JButton("Toggle Visibility");
    private final JButton btnDeselect     = new JButton("Deselect");
    private final JButton btnMore         = new JButton("More >>");
    private final JButton btnBindImage    = new JButton("Bind");
    private final JButton btnBindSubImage = new JButton("Bind Sub");
    private final JButton btnClearSubImage = new JButton("Clear Sub");
    private final JCheckBox chkContainerOr = new JCheckBox("Container OR");

    // Edit-mode buttons
    private final JButton btnSave         = new JButton("Save");
    private final JButton btnSelectionEdit = new JButton("Cleanup...");
    private final JButton btnEditUndo     = new JButton("Undo");
    private final JButton btnEditRedo     = new JButton("Redo");
    private final JButton btnCancelEdit   = new JButton("Cancel");
    private final JButton btnSplitToolPicker = new JButton("Split Tools...");
    private final JCheckBox chkReplaceOriginal = new JCheckBox("Replace original");

    // ── Header labels ─────────────────────────────────────────────────────────
    private final JLabel rootPathLabel   = new JLabel("No folder open");
    private final JLabel boundImageLabel = new JLabel("No image");
    private final JLabel subImageLabel   = new JLabel("No sub image");
    private JPanel editModePanel;
    private JPanel centerWrapper;
    private JPopupMenu moreMenu;

    // ── Status ────────────────────────────────────────────────────────────────
    private final JLabel statusLabel = new JLabel("0 ROI");
    private ImagePlus boundImage;
    private ImagePlus subImage;
    private boolean overlayEnabled = true;
    private Path viewRootPath;
    private final SplitWorkflowSession splitWorkflow = new SplitWorkflowSession();
    private JDialog watershed3dDialog;
    private Watershed3DSelection watershed3dSelection;
    private Roi3DWatershedService.Result watershed3dPreview;
    private ImagePlus watershed3dThresholdPreviewImage;
    private boolean watershed3dSeedOnlyPreview;
    private JSpinner watershed3dThresholdSpinner;
    private JComboBox<Integer> watershed3dConnectivityBox;
    private JSpinner watershed3dMinSeedSizeSpinner;
    private JSpinner watershed3dChannelSpinner;
    private JSpinner watershed3dTimeSpinner;
    private JCheckBox watershed3dReplaceOriginalBox;
    private JLabel watershed3dStatusLabel;
    private boolean projectChannel;
    private boolean projectZ;
    private boolean projectTime;
    private List<Path> pendingSelectionRestorePaths = Collections.emptyList();
    private boolean pickMode;
    private ExplorerNode hoveredPickNode;
    private java.util.List<ExplorerNode> pickCandidates = Collections.emptyList();
    private int pickCandidateIndex;
    private Point pickPoint;
    private final java.util.List<ImageCanvas> pickCanvases = new ArrayList<ImageCanvas>();
    private MouseAdapter pickListener;
    private KeyAdapter imageShortcutListener;
    private ImagePlus shortcutBoundImage;
    private static boolean imageListenerInstalled;
    private static boolean overlayRefreshing;
    private static final Map<ImagePlus, int[]> LAST_IMAGE_POSITIONS = Collections.synchronizedMap(new WeakHashMap<ImagePlus, int[]>());
    private static final int WINDOW_STAGGER = 24;
    private static final Color SELECTED_OVERLAY_COLOR = new Color(0, 220, 220, 120);
    private static final Color PICK_OVERLAY_COLOR = new Color(255, 170, 0, 180);
    private static final Color EDIT_REFERENCE_COLOR = new Color(255, 255, 255, 90);
    private static final Color PICK_BUTTON_ACTIVE_BG = new Color(255, 236, 179);
    private static final Color PICK_BUTTON_ACTIVE_FG = new Color(102, 60, 0);
    private Color watershed3dSeedPreviewColor = new Color(255, 200, 0, 220);

    // ── Sort state ────────────────────────────────────────────────────────────
    private int lastSortCol = -1;
    private boolean lastSortAsc = true;

    public RoiExplorerPanel() {
        super(new BorderLayout(0, 0));
        ensureImageListenerInstalled();

        table = buildTable();
        JScrollPane scroll = new JScrollPane(table,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.getViewport().setBackground(table.getBackground());

        final JPanel content = new JPanel(new BorderLayout(4, 2));
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        content.add(buildHeaderPanel(), BorderLayout.NORTH);

        editModePanel = buildEditModePanel();

        final JPanel innerCenter = new JPanel(new BorderLayout(2, 0));
        innerCenter.add(scroll, BorderLayout.CENTER);
        innerCenter.add(buildActionsPanel(), BorderLayout.EAST);

        centerWrapper = new JPanel(new BorderLayout(0, 2));
        centerWrapper.add(editModePanel, BorderLayout.NORTH);
        centerWrapper.add(innerCenter, BorderLayout.CENTER);

        content.add(centerWrapper, BorderLayout.CENTER);

        add(content, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        registerKeyBindings();
        OpenViewRegistry.getInstance().register(this);
        bindActiveImageIfPresent();
        updateStatus();
        updateEditControls();
    }

    // ── Window-context helpers ────────────────────────────────────────────────

    /** Returns the enclosing Window for use as dialog parent. */
    private Window parentWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    /** Updates the title of the enclosing JFrame (no-op when embedded without a JFrame ancestor). */
    private void updateTitle(String title) {
        Window w = parentWindow();
        if (w instanceof JFrame) ((JFrame) w).setTitle(title);
    }

    /** Called by the enclosing JFrame's window-closing handler to release resources. */
    public void onWindowClosing() {
        uninstallPickMode();
        uninstallImageShortcuts();
        cancelSplitMode();
        clearSubOverlay();
        OpenViewRegistry.getInstance().unregister(this);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void openFolder(Path path) {
        clearPickHover();
        viewRootPath = path;
        FolderNode root = nodeLoader.loadFolder(path);
        tableModel.setRoot(root);
        nameRenderer.setViewRoot(root);
        rootPathLabel.setText(path.toString());
        rootPathLabel.setToolTipText(path.toString());
        updateTitle("ROI Explorer – " + path.getFileName());
        updateStatus();
        refreshOverlay();
    }

    public Path getCurrentRoot() {
        return viewRootPath;
    }

    public boolean hasLoadedRoot() {
        return viewRootPath != null && tableModel.getViewRoot() != null;
    }

    public void closeFolder() {
        uninstallPickMode();
        cleanupPreview();
        table.clearSelection();
        tableModel.setRoot(null);
        nameRenderer.setViewRoot(null);
        viewRootPath = null;
        rootPathLabel.setText("No folder open");
        rootPathLabel.setToolTipText(null);
        updateTitle("ROI Explorer");
        updateStatus();
        refreshOverlay();
    }

    public boolean hasActivePreview() {
        return pickMode || editCtrl.isEditing() || isSplitModeActive() || watershed3dPreview != null;
    }

    public void cleanupPreview() {
        uninstallPickMode();
        if (editCtrl.isEditing()) {
            editCtrl.cancel(OpenViewRegistry.getInstance());
        } else if (isSplitModeActive()) {
            cancelSplitMode();
        } else if (watershed3dDialog != null || watershed3dPreview != null) {
            close3DWatershedDialog();
        } else if (boundImage != null) {
            boundImage.killRoi();
            boundImage.setOverlay((Overlay) null);
            clearSubOverlay();
            boundImage.updateAndDraw();
        }
        refreshOverlay();
    }

    public void setBindImage(ImagePlus imp) {
        if (imp == null) return;
        bindImage(imp);
    }

    public void setSubBindImage(ImagePlus imp) {
        if (imp == null) {
            clearSubBindImage();
            return;
        }
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "Bind a main image before binding a sub image.",
                    "Bind Sub Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (imp.getWidth() != boundImage.getWidth() || imp.getHeight() != boundImage.getHeight()) {
            JOptionPane.showMessageDialog(this,
                    "Sub image XY pixel size must match the main image.",
                    "Bind Sub Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (subImage == imp) {
            refreshOverlay();
            return;
        }
        if (!sameXyCalibration(boundImage, imp)) {
            JOptionPane.showMessageDialog(this,
                    "Sub image XY calibration differs from the main image. Binding is allowed because pixel size matches.",
                    "Bind Sub Image", JOptionPane.WARNING_MESSAGE);
        }
        if (subImage != imp) clearSubOverlay();
        subImage = imp;
        subImageLabel.setText("Sub: " + imp.getTitle());
        subImageLabel.setToolTipText(imp.getTitle());
        refreshOverlay();
    }

    public void clearSubBindImage() {
        clearSubOverlay();
        subImage = null;
        subImageLabel.setText("No sub image");
        subImageLabel.setToolTipText(null);
        refreshOverlay();
    }

    public void setContainerOrMode(boolean enabled) {
        chkContainerOr.setSelected(enabled);
        refreshOverlay();
    }

    public void setProjectionMode(boolean projectC, boolean projectZ, boolean projectT) {
        projectChannel = projectC;
        this.projectZ = projectZ;
        projectTime = projectT;
        refreshOverlay();
    }

    public void setOverlayEnabled(boolean enabled) {
        overlayEnabled = enabled;
        if (enabled) refreshOverlay();
    }

    public boolean ownsImage(ImagePlus image) {
        return overlayEnabled && image != null && (image == boundImage || image == subImage);
    }

    public void measureCurrentRoot() {
        cmdGroupMeasure();
    }

    public MeasurementResult measureCurrentRoot(MeasurementRequest request) {
        if (request == null) {
            measureCurrentRoot();
            return MeasurementResult.performed("Measurement completed.");
        }

        if (request.getProfile() != null) {
            // measureAll=true ignores current selection → uses all direct children of root
            List<ExplorerNode> sel = request.isMeasureAll()
                ? Collections.<ExplorerNode>emptyList() : getSelectedNodes();
            List<ObjectMeasurementResult> results;
            try {
                results = objectMeasureSvc.measure(sel, tableModel.getViewRoot(), request.getProfile(), boundImage);
            } catch (IllegalArgumentException e) {
                return MeasurementResult.notPerformed(e.getMessage());
            }
            if (results.isEmpty()) {
                return MeasurementResult.notPerformed("No measurement units found.");
            }
            if (request.getCsvOutputPath() != null) {
                try {
                    objectMeasureCsvExporter.write(results, request.getCsvOutputPath(),
                            request.getEnabledColumns());
                } catch (IOException e) {
                    return MeasurementResult.notPerformed("Failed to save measurement CSV: " + e.getMessage());
                }
            }
            if (request.isShowResultsTable()) {
                showObjectMeasurementTable(results);
            }
            return MeasurementResult.performed("Measurement completed. " + results.size() + " object(s).");
        }

        // Legacy path: GroupMeasurementService
        GroupMeasurementService.Options explicitOptions = request.getOptions();
        if (explicitOptions != null) {
            groupMeasureOptions = explicitOptions.copy();
            groupMeasureConfigured = true;
        } else if (request.isPromptForOptions()) {
            if (!cmdSetGroupMeasureOptions()) {
                return MeasurementResult.notPerformed("Measurement options dialog was cancelled.");
            }
        }
        List<ExplorerNode> units = selResolver.resolveGroupUnits(Collections.<ExplorerNode>emptyList(), tableModel.getViewRoot());
        if (units.isEmpty()) {
            return MeasurementResult.notPerformed("No folders or grouped ROI units to measure.");
        }
        try {
            ij.measure.ResultsTable rt = groupMeasureSvc.measure(
                    units,
                    groupMeasureOptions.copy(),
                    boundImage,
                    request.isShowResultsTable());
            if (request.getCsvOutputPath() != null) {
                groupMeasureSvc.saveCsv(rt, request.getCsvOutputPath());
            }
        } catch (IOException e) {
            return MeasurementResult.notPerformed("Failed to save measurement CSV: " + e.getMessage());
        }
        return MeasurementResult.performed("Measurement completed.");
    }

    private void showObjectMeasurementTable(List<ObjectMeasurementResult> results) {
        ij.measure.ResultsTable rt = new ij.measure.ResultsTable();
        boolean hasTimeComparison = false;
        for (ObjectMeasurementResult r : results) {
            if (r.tFrom > 0 && r.tTo > 0) {
                hasTimeComparison = true;
                break;
            }
        }
        for (ObjectMeasurementResult r : results) {
            rt.incrementCounter();
            rt.addValue("spot_id", r.spotId);
            rt.addLabel(r.unitName);
            rt.addValue("c", r.c);
            rt.addValue("t", r.t);
            if (hasTimeComparison) {
                rt.addValue("t_from", r.tFrom);
                rt.addValue("t_to", r.tTo);
            }
            rt.addValue("volume_um3", r.volumeUm3);
            rt.addValue("volume_vox", r.volumeVox);
            if (hasTimeComparison) {
                rt.addValue("volume_from_um3", r.volumeFromUm3);
                rt.addValue("volume_to_um3", r.volumeToUm3);
                rt.addValue("delta_volume_um3", r.deltaVolumeUm3);
                rt.addValue("volume_from_vox", r.volumeFromVox);
                rt.addValue("volume_to_vox", r.volumeToVox);
            }
            rt.addValue("surface_area_um2", r.surfaceAreaUm2);
            rt.addValue("sphericity", r.sphericity);
            rt.addValue("integrated_intensity", r.integratedIntensity);
            rt.addValue("mean_intensity", r.meanIntensity);
            rt.addValue("max_intensity", r.maxIntensity);
            rt.addValue("centroid_x_um", r.centroidXUm);
            rt.addValue("centroid_y_um", r.centroidYUm);
            rt.addValue("centroid_z_um", r.centroidZUm);
            if (hasTimeComparison) {
                rt.addValue("centroid_from_x_um", r.centroidFromXUm);
                rt.addValue("centroid_from_y_um", r.centroidFromYUm);
                rt.addValue("centroid_from_z_um", r.centroidFromZUm);
                rt.addValue("centroid_to_x_um", r.centroidToXUm);
                rt.addValue("centroid_to_y_um", r.centroidToYUm);
                rt.addValue("centroid_to_z_um", r.centroidToZUm);
                rt.addValue("displacement_um", r.displacementUm);
                rt.addValue("interval", r.interval);
                rt.addValue("velocity_um_per_frame", r.velocityUmPerFrame);
            }
            rt.addValue("max_feret3d_um", r.maxFeret3dUm);
        }
        rt.show("Object Measurements");
    }

    public void launch3DWatershed() {
        cmd3DWatershed();
    }

    @Override
    public ImagePlus getBoundImage() {
        return boundImage;
    }

    @Override
    public void clearActiveImageSelection() {
        if (boundImage == null || editCtrl.isEditing() || isSplitModeActive()) return;
        boundImage.killRoi();
        boundImage.updateAndDraw();
    }

    @Override
    public void updateEditControls() {
        boolean editing = editCtrl.isEditing();
        boolean splitting = isSplitModeActive();
        boolean modalToolMode = editing || splitting;
        if (editModePanel != null) {
            editModePanel.setVisible(modalToolMode);
            if (editing) {
                RoiNode en = editCtrl.getEditingNode();
                editModePanel.setBorder(BorderFactory.createTitledBorder(
                        en != null ? "Editing: " + en.getName() : "Edit mode"));
            } else if (splitting) {
                editModePanel.setBorder(BorderFactory.createTitledBorder(
                        splitWorkflow.getNode() != null ? "Splitting: " + splitWorkflow.getNode().getName() : "Split mode"));
            }
            if (centerWrapper != null) centerWrapper.revalidate();
        }
        btnAdd.setEnabled(!modalToolMode);
        btnEdit.setEnabled(!modalToolMode);
        btnMeasure.setEnabled(!modalToolMode);
        btnGroupMeasure.setEnabled(!modalToolMode);
        btnMore.setEnabled(!modalToolMode);
        btnOpen.setEnabled(!modalToolMode);
        btnReload.setEnabled(!modalToolMode);
        btnBindImage.setEnabled(!modalToolMode);
        btnNewFolder.setEnabled(!modalToolMode);
        btnDelete.setEnabled(!modalToolMode);
        btnRename.setEnabled(!modalToolMode);
        btnEditUndo.setEnabled(editing && editCtrl.canUndoSelection());
        btnEditRedo.setEnabled(editing && editCtrl.canRedoSelection());
        btnSelectionEdit.setVisible(editing);
        btnEditUndo.setVisible(editing);
        btnEditRedo.setVisible(editing);
        btnSplitToolPicker.setVisible(splitting);
        chkReplaceOriginal.setVisible(splitting);
        btnSave.setText(splitting ? "Save Split Results" : "Save");
        if (editing) {
            RoiNode en = editCtrl.getEditingNode();
            updateTitle("ROI Explorer – EDITING: " + (en != null ? en.getName() : ""));
        } else if (splitting) {
            updateTitle("ROI Explorer – SPLITTING: " + (splitWorkflow.getNode() != null ? splitWorkflow.getNode().getName() : ""));
        } else if (viewRootPath != null) {
            updateTitle("ROI Explorer – " + viewRootPath.getFileName());
        }
        tableModel.fullRefresh();
    }

    @Override
    public Window getWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    @Override
    public boolean isProjectChannel() {
        return projectChannel;
    }

    @Override
    public boolean isProjectZ() {
        return projectZ;
    }

    @Override
    public boolean isProjectTime() {
        return projectTime;
    }

    // ── External change notifications ─────────────────────────────────────────

    public void onExternalChange(Path folderPath) {
        if (viewRootPath == null) return;
        if (folderPath.startsWith(viewRootPath) || viewRootPath.startsWith(folderPath)) {
            SwingUtilities.invokeLater(this::reloadFromDisk);
        }
    }

    public void onPathRenamed(Path oldPath, Path newPath) {
        if (viewRootPath == null) return;
        if (viewRootPath.startsWith(oldPath)) {
            viewRootPath = newPath.resolve(oldPath.relativize(viewRootPath));
        }
        if (tableModel.getViewRoot() != null) {
            tableModel.getViewRoot().updatePathPrefix(oldPath, newPath);
            nameRenderer.setViewRoot(tableModel.getViewRoot());
            tableModel.fullRefresh();
        }
        updateStatus();
        refreshOverlay();
    }

    public void onPathDeleted(Path path) {
        if (viewRootPath != null && viewRootPath.startsWith(path)) {
            SwingUtilities.invokeLater(this::handleRootDeleted);
        } else {
            SwingUtilities.invokeLater(() -> {
                tableModel.fullRefresh();
                refreshOverlay();
            });
        }
    }

    private void handleRootDeleted() {
        uninstallPickMode();
        JOptionPane.showMessageDialog(this,
                "The root folder was deleted or moved.", "Root Folder Gone",
                JOptionPane.WARNING_MESSAGE);
        tableModel.setRoot(null);
        viewRootPath = null;
        rootPathLabel.setText("No folder open");
        updateTitle("ROI Explorer");
        updateStatus();
        refreshOverlay();
    }

    // ── Build UI ──────────────────────────────────────────────────────────────

    private JTable buildTable() {
        JTable t = new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) return null;
                ExplorerNode node = tableModel.getNodeAt(row);
                return node != null ? node.getPath().toString() : null;
            }
        };
        t.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        t.setRowHeight(22);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.getTableHeader().setReorderingAllowed(true);
        // columns stay fixed width; horizontal scroll appears when viewport is narrower
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        installNameColumnLock(t);

        // Name column renderer
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_NAME).setCellRenderer(nameRenderer);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_NAME).setPreferredWidth(220);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_Z).setPreferredWidth(36);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_C).setPreferredWidth(36);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_T).setPreferredWidth(36);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_DATE).setPreferredWidth(90);
        t.getColumnModel().getColumn(ExplorerTreeTableModel.COL_ROI_COUNT).setPreferredWidth(60);

        // Center-align numeric columns
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        for (int c = 1; c <= 5; c++) {
            t.getColumnModel().getColumn(c).setCellRenderer(centerRenderer);
        }

        // Mouse: triangle toggle + double-click
        t.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = t.rowAtPoint(e.getPoint());
                int col = t.columnAtPoint(e.getPoint());
                if (row < 0) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        t.clearSelection();
                    }
                    return;
                }

                if (col == ExplorerTreeTableModel.COL_NAME) {
                    ExplorerNode node = tableModel.getNodeAt(row);
                    if (isExpansionHandleHit(t, e, row, col, node)) {
                        List<Path> sel = tableModel.snapshotSelection(t.getSelectedRows());
                        tableModel.toggleExpansion(row);
                        restoreSelection(sel);
                        e.consume();
                        return;
                    }
                }

                if (e.getButton() == MouseEvent.BUTTON3) {
                    if (!t.isRowSelected(row)) {
                        t.setRowSelectionInterval(row, row);
                    }
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() < 2) return;
                int row = t.rowAtPoint(e.getPoint());
                if (row < 0) return;
                int col = t.columnAtPoint(e.getPoint());
                ExplorerNode node = tableModel.getNodeAt(row);
                if (isExpansionHandleHit(t, e, row, col, node)) {
                    e.consume();
                    return;
                }
                if (node instanceof RoiNode) {
                    cmdEdit();
                } else if (node instanceof FolderNode || node instanceof ZipNode) {
                    if (!t.isRowSelected(row)) {
                        t.setRowSelectionInterval(row, row);
                    }
                    List<Path> sel = tableModel.snapshotSelection(t.getSelectedRows());
                    tableModel.toggleExpansion(row);
                    restoreSelection(sel);
                }
            }
        });

        // Column header click -> sort
        t.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int col = t.columnAtPoint(e.getPoint());
                if (col == ExplorerTreeTableModel.COL_NAME) return;
                if (col == lastSortCol) {
                    lastSortAsc = !lastSortAsc;
                } else {
                    lastSortCol = col;
                    lastSortAsc = true;
                }
                List<Path> sel = tableModel.snapshotSelection(t.getSelectedRows());
                tableModel.sortBy(col, lastSortAsc);
                restoreSelection(sel);
            }
        });

        // Update status on selection change
        t.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                syncImagePositionToSelection();
                updateStatus();
                refreshOverlay();
            }
        });

        // Drag & drop
        t.setDragEnabled(true);
        t.setDropMode(DropMode.ON_OR_INSERT_ROWS);
        t.setTransferHandler(buildTransferHandler());

        t.setPreferredScrollableViewportSize(new Dimension(280, 200));
        return t;
    }

    private boolean isExpansionHandleHit(JTable t, MouseEvent e, int row, int col, ExplorerNode node) {
        if (col != ExplorerTreeTableModel.COL_NAME || node == null || node.isLeaf()) return false;
        Rectangle cell = t.getCellRect(row, 0, false);
        int depth = tableModel.getViewRoot() != null
                ? node.getDepthRelativeTo(tableModel.getViewRoot()) : 0;
        int tx = cell.x + depth * TreeColumnRenderer.INDENT + 2;
        int ex = tx + TreeColumnRenderer.TRIANGLE_W;
        return e.getX() >= tx && e.getX() <= ex;
    }

    private JPanel buildHeaderPanel() {
        final JPanel header = new JPanel(new GridLayout(0, 1, 0, 0));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));

        // Row 1: root folder path + Open + Reload
        final JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folderRow.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        rootPathLabel.setFont(rootPathLabel.getFont().deriveFont(Font.PLAIN, 11f));
        folderRow.add(rootPathLabel, BorderLayout.CENTER);
        final JPanel folderBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        makeCompact(btnOpen, btnReload);
        folderBtns.add(btnOpen);
        folderBtns.add(btnReload);
        folderRow.add(folderBtns, BorderLayout.EAST);
        header.add(folderRow);

        // Row 2: bound image + Bind button
        final JPanel imageRow = new JPanel(new BorderLayout(4, 0));
        imageRow.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        boundImageLabel.setFont(boundImageLabel.getFont().deriveFont(Font.PLAIN, 11f));
        imageRow.add(boundImageLabel, BorderLayout.CENTER);
        makeCompact(btnBindImage, btnBindSubImage, btnClearSubImage);
        final JPanel imageBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        imageBtns.add(btnBindImage);
        imageBtns.add(btnBindSubImage);
        imageBtns.add(btnClearSubImage);
        imageRow.add(imageBtns, BorderLayout.EAST);
        header.add(imageRow);

        // Row 3: sub image + Container OR
        final JPanel subRow = new JPanel(new BorderLayout(4, 0));
        subRow.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        subImageLabel.setFont(subImageLabel.getFont().deriveFont(Font.PLAIN, 11f));
        subRow.add(subImageLabel, BorderLayout.CENTER);
        chkContainerOr.setFont(chkContainerOr.getFont().deriveFont(Font.PLAIN, 11f));
        chkContainerOr.setFocusable(false);
        subRow.add(chkContainerOr, BorderLayout.EAST);
        header.add(subRow);

        wireButtons();
        return header;
    }

    private JPanel buildEditModePanel() {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        panel.setBackground(new Color(255, 243, 205));
        panel.setBorder(BorderFactory.createTitledBorder("Edit mode"));
        makeCompact(btnSave, btnSelectionEdit, btnEditUndo, btnEditRedo, btnSplitToolPicker, btnCancelEdit);
        chkReplaceOriginal.setOpaque(false);
        panel.add(btnSave);
        panel.add(btnSelectionEdit);
        panel.add(btnEditUndo);
        panel.add(btnEditRedo);
        panel.add(btnSplitToolPicker);
        panel.add(chkReplaceOriginal);
        panel.add(btnCancelEdit);
        btnSplitToolPicker.setVisible(false);
        chkReplaceOriginal.setVisible(false);
        panel.setVisible(false);
        return panel;
    }

    private JPanel buildActionsPanel() {
        final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 0));
        makeCompact(btnAdd, btnEdit, btnNewFolder, btnDelete, btnRename, btnPickRoi, btnToggleVis,
                    btnDeselect, btnMeasure, btnGroupMeasure, btnMore);
        actions.add(btnAdd);
        actions.add(btnEdit);
        actions.add(btnNewFolder);
        actions.add(btnDelete);
        actions.add(btnRename);
        actions.add(btnPickRoi);
        actions.add(btnToggleVis);
        actions.add(btnDeselect);
        actions.add(btnMeasure);
        actions.add(btnGroupMeasure);
        actions.add(btnMore);
        return actions;
    }

    private JPanel buildStatusBar() {
        final JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        p.add(statusLabel, BorderLayout.CENTER);
        return p;
    }

    private static void makeCompact(JButton... buttons) {
        for (JButton b : buttons) {
            b.setMargin(new Insets(0, 3, 0, 3));
            b.setFocusable(false);
        }
    }

    private void wireButtons() {
        btnOpen.addActionListener(e -> cmdOpenFolder());
        btnReload.addActionListener(e -> reloadFromDisk());
        btnAdd.addActionListener(e -> cmdAdd());
        btnEdit.addActionListener(e -> cmdEdit());
        btnNewFolder.addActionListener(e -> cmdNewFolder());
        btnDelete.addActionListener(e -> cmdDelete());
        btnRename.addActionListener(e -> cmdRename());
        btnPickRoi.addActionListener(e -> cmdPickRoiOnImage());
        btnToggleVis.addActionListener(e -> cmdToggleHiddenOnSelection());
        btnMeasure.addActionListener(e -> cmdMeasure());
        btnGroupMeasure.addActionListener(e -> cmdGroupMeasure());
        btnDeselect.addActionListener(e -> table.clearSelection());
        btnMore.addActionListener(e -> showMoreMenu());
        btnBindImage.addActionListener(e -> cmdBindImage());
        btnBindSubImage.addActionListener(e -> cmdBindSubImage());
        btnClearSubImage.addActionListener(e -> clearSubBindImage());
        chkContainerOr.addActionListener(e -> refreshOverlay());
        btnSave.addActionListener(e -> {
            if (isSplitModeActive()) cmdSaveSplitMode();
            else editCtrl.save(diskSync, historySvc, OpenViewRegistry.getInstance());
        });
        btnSelectionEdit.addActionListener(e -> cmdSelectionEditTools());
        btnEditUndo.addActionListener(e -> cmdUndo());
        btnEditRedo.addActionListener(e -> cmdRedo());
        btnSplitToolPicker.addActionListener(e -> cmdSplitTool(SplitToolsDialog.Tool.KNIFE));
        btnCancelEdit.addActionListener(e -> {
            if (isSplitModeActive()) cancelSplitMode();
            else editCtrl.cancel(OpenViewRegistry.getInstance());
        });
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private void cmdOpenFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (viewRootPath != null) fc.setCurrentDirectory(viewRootPath.toFile());
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openFolder(fc.getSelectedFile().toPath());
    }

    public void reloadFromDisk() {
        if (viewRootPath == null) return;
        List<Path> sel = tableModel.snapshotSelection(table.getSelectedRows());
        Set<Path> expanded = tableModel.snapshotExpandedPaths();
        openFolder(viewRootPath);
        tableModel.restoreExpanded(expanded);
        List<Path> targetSelection = pendingSelectionRestorePaths.isEmpty() ? sel : pendingSelectionRestorePaths;
        attemptPendingSelectionRestore(targetSelection);
    }

    private void cmdNewFolder() {
        ExplorerNode target = getNewFolderTarget();
        if (!(target instanceof FolderNode)) {
            JOptionPane.showMessageDialog(this, "Select a folder first.", "New Folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            Path newPath = target.getPath().resolve(DiskSyncService.uniqueName(target.getPath(), "New Folder", false));
            executeUndoable("New Folder",
                    Collections.singletonList(newPath),
                    Collections.<Path>emptyList(),
                    () -> diskSync.newFolder((FolderNode) target, OpenViewRegistry.getInstance()));
            tableModel.expand(target);
            tableModel.fullRefresh();
            restoreSelection(Collections.singletonList(newPath));
            int row = table.getSelectedRow();
            if (row >= 0) {
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
            }
            updateStatus();
            ExplorerNode created = row >= 0 ? tableModel.getNodeAt(row) : null;
            if (created != null) {
                startInlineRename(created);
            }
        } catch (IOException e) {
            showError("New Folder failed", e);
        }
    }

    private ExplorerNode getNewFolderTarget() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.isEmpty()) return tableModel.getViewRoot();
        ExplorerNode first = sel.get(0);
        if (first instanceof FolderNode) return first;
        if (first instanceof RoiNode && first.getParent() instanceof FolderNode) return first.getParent();
        return tableModel.getViewRoot();
    }

    private void cmdDelete() {
        List<ExplorerNode> sel = pruneDescendantSelections(getSelectedNodes());
        if (sel.isEmpty()) return;
        for (ExplorerNode node : sel) {
            if (node instanceof RoiNode && OpenViewRegistry.getInstance().isBeingEdited(PathKey.forRoiNode((RoiNode) node))) {
                JOptionPane.showMessageDialog(this,
                        "This ROI is being edited. Stop editing before deleting.",
                        "Delete", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        String msg = sel.size() == 1
                ? "Delete \"" + sel.get(0).getName() + "\"?"
                : "Delete " + sel.size() + " items?";
        if (JOptionPane.showConfirmDialog(this, msg, "Delete",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            executeUndoable("Delete",
                    historyFileTargetsForNodes(sel),
                    historyHiddenTargetsForNodes(sel),
                    () -> {
                        for (ExplorerNode node : sel) {
                            diskSync.delete(node, OpenViewRegistry.getInstance());
                        }
                    });
            tableModel.fullRefresh();
            updateStatus();
        } catch (IOException e) {
            showError("Delete failed", e);
        }
    }

    private void cmdRename() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.size() != 1) return;
        startInlineRename(sel.get(0));
    }

    private void startInlineRename(ExplorerNode node) {
        int row = tableModel.getRowOf(node);
        if (row < 0) return;
        String current = node.getName();
        String input = (String) JOptionPane.showInputDialog(this,
                "New name:", "Rename", JOptionPane.PLAIN_MESSAGE, null, null, current);
        if (input == null || input.trim().isEmpty() || input.trim().equals(current)) return;
        try {
            executeUndoable("Rename",
                    historyRenameTargets(node, input.trim()),
                    historyRenameHiddenTargets(node, input.trim()),
                    () -> diskSync.rename(node, input.trim(), OpenViewRegistry.getInstance()));
            tableModel.fullRefresh();
            updateStatus();
        } catch (IOException e) {
            showError("Rename failed", e);
        }
    }

    private void cmdDuplicate() {
        List<ExplorerNode> sel = pruneDescendantSelections(getSelectedNodes());
        if (sel.isEmpty()) return;
        try {
            executeUndoable("Duplicate",
                    historyDuplicateTargets(sel),
                    Collections.<Path>emptyList(),
                    () -> {
                        for (ExplorerNode node : sel) {
                            diskSync.duplicate(node, OpenViewRegistry.getInstance());
                        }
                    });
            reloadFromDisk();
        } catch (IOException e) {
            showError("Duplicate failed", e);
        }
    }

    private void cmdMoveToFolder() {
        List<ExplorerNode> sel = pruneDescendantSelections(getSelectedNodes());
        if (sel.isEmpty()) return;
        ExplorerNode vr = tableModel.getViewRoot();
        if (vr == null) return;
        MoveToFolderDialog dlg = new MoveToFolderDialog(parentWindow(), vr, sel);
        dlg.setVisible(true);
        ExplorerNode dest = dlg.getSelected();
        if (dest == null) return;
        try {
            executeUndoable("Move",
                    historyMoveTargets(sel, dest),
                    historyMoveHiddenTargets(sel, dest),
                    () -> diskSync.moveNodes(sel, dest, OpenViewRegistry.getInstance()));
            reloadFromDisk();
        } catch (IOException e) {
            showError("Move failed", e);
        }
    }

    private void cmdSelectionEditTools() {
        if (isSplitModeActive()) return;
        cmdCleanupTool(SelectionEditToolsDialog.Tool.KEEP_LARGEST);
    }

    private void cmdSplitTool(SplitToolsDialog.Tool tool) {
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "No image is bound. Use Bind Image first.",
                    "Split Tools", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!ensureSplitMode()) return;
        SplitToolsDialog.open(parentWindow(), boundImage, tool);
        refreshOverlay();
    }

    private void cmdCleanupTool(SelectionEditToolsDialog.Tool tool) {
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "No image is bound. Use Bind Image first.",
                    "Selection Edit Tools", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!editCtrl.isEditing()) {
            Roi source = getRepresentativeSelectionRoi();
            if (source != null) {
                syncImagePositionToSelection();
                boundImage.setRoi((Roi) source.clone());
            }
        }
        SelectionEditToolsDialog.open(parentWindow(), boundImage, tool);
        refreshOverlay();
    }

    private void cmdPickRoiOnImage() {
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "No image is bound. Use Bind Image first.",
                    "Pick ROI on Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (tableModel.getViewRoot() == null) {
            JOptionPane.showMessageDialog(this, "Open a folder first.",
                    "Pick ROI on Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        togglePickMode();
    }

    private void cmdMeasure() {
        List<ExplorerNode> sel = getSelectedNodes();
        List<RoiNode> rois = selResolver.resolveRoiNodes(sel, tableModel.getViewRoot());
        if (rois.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No ROIs to measure.", "Measure ROI", JOptionPane.WARNING_MESSAGE);
            return;
        }
        measureSvc.measureRois(rois, boundImage);
    }

    private void cmdGroupMeasure() {
        List<ExplorerNode> sel = getSelectedNodes();
        List<ExplorerNode> units = selResolver.resolveGroupUnits(sel, tableModel.getViewRoot());
        if (units.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No folders or grouped ROI units to measure.", "Measure Folder", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!groupMeasureConfigured && !cmdSetGroupMeasureOptions()) return;
        groupMeasureSvc.measure(units, groupMeasureOptions.copy(), boundImage);
    }

    private boolean cmdSetGroupMeasureOptions() {
        GroupMeasurementDialog dlg = new GroupMeasurementDialog(parentWindow(), boundImage, groupMeasureOptions);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return false;
        groupMeasureOptions = dlg.getOptions();
        groupMeasureConfigured = true;
        return true;
    }

    private void cmdEdit() {
        if (isSplitModeActive()) {
            JOptionPane.showMessageDialog(this, "Finish the current split first.", "Edit", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.size() != 1 || !(sel.get(0) instanceof RoiNode)) {
            JOptionPane.showMessageDialog(this, "Select exactly one ROI to edit.",
                    "Edit", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (editCtrl.isEditing()) {
            JOptionPane.showMessageDialog(this, "Finish the current edit first.", "Edit", JOptionPane.WARNING_MESSAGE);
            return;
        }
        editCtrl.startEdit((RoiNode) sel.get(0), OpenViewRegistry.getInstance());
    }

    private void cmdAdd() {
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "No image is bound. Use Bind Image first.",
                    "Add", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Roi roi = boundImage.getRoi();
        if (roi == null) {
            JOptionPane.showMessageDialog(this, "No active selection on the bound image.", "Add", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ExplorerNode target = getAddTarget();
        if (!(target instanceof FolderNode || target instanceof ZipNode)) {
            JOptionPane.showMessageDialog(this, "Select a folder or ZIP first.", "Add", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String name = roi.getName();
        if (name == null || name.isEmpty()) name = "roi";
        if (!name.toLowerCase().endsWith(".roi")) name += ".roi";
        final String preferredName = name;
        try {
            Roi toSave = (Roi) roi.clone();
            if (toSave.getStrokeColor() == null && toSave.getFillColor() == null) {
                toSave.setStrokeColor(Color.YELLOW);
            }
            if (toSave.getStrokeWidth() <= 0f && (toSave.getStrokeColor() != null || toSave.getFillColor() != null)) {
                toSave.setStrokeWidth(1f);
            }
            applyCurrentImagePosition(toSave, boundImage);
            Collection<Path> fileTargets = target instanceof ZipNode
                    ? Collections.singletonList(target.getPath())
                    : Collections.singletonList(DiskSyncService.uniquePath(target.getPath().resolve(preferredName)));
            executeUndoable("Add ROI",
                    fileTargets,
                    Collections.<Path>emptyList(),
                    () -> diskSync.saveNewRoi(target, toSave, preferredName, OpenViewRegistry.getInstance()));
        } catch (Exception e) {
            showError("Add failed", e);
        }
    }

    private ExplorerNode getAddTarget() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.isEmpty()) return tableModel.getViewRoot();
        if (sel.size() == 1) {
            ExplorerNode n = sel.get(0);
            if (n instanceof FolderNode || n instanceof ZipNode) return n;
            if (n instanceof RoiNode && (n.getParent() instanceof FolderNode || n.getParent() instanceof ZipNode)) return n.getParent();
            return tableModel.getViewRoot();
        }
        ExplorerNode commonFolder = null;
        for (ExplorerNode n : sel) {
            ExplorerNode folder = n instanceof FolderNode ? n : n.getParent();
            if (!(folder instanceof FolderNode || folder instanceof ZipNode)) return tableModel.getViewRoot();
            if (commonFolder == null) {
                commonFolder = folder;
            } else if (commonFolder != folder) {
                return tableModel.getViewRoot();
            }
        }
        return commonFolder != null ? commonFolder : tableModel.getViewRoot();
    }

    private void cmdRevealOnImage() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.size() != 1 || !(sel.get(0) instanceof RoiNode)) return;
        RoiNode node = (RoiNode) sel.get(0);
        Roi roi = node.getRoi();
        if (roi == null) return;
        ImagePlus imp = boundImage;
        if (imp == null) return;
        if (hasStructuredAxes(imp)) {
            int c = roi.getCPosition(), z = roi.getZPosition(), t = roi.getTPosition();
            int nextC = projectChannel ? imp.getC() : (c > 0 ? c : imp.getC());
            int nextZ = projectZ ? imp.getZ() : (z > 0 ? z : imp.getZ());
            int nextT = projectTime ? imp.getT() : (t > 0 ? t : imp.getT());
            imp.setPosition(nextC, nextZ, nextT);
        }
        imp.setRoi(roi);
        if (imp.getWindow() != null) imp.getWindow().toFront();
    }

    private void cmdCombine() {
        List<ExplorerNode> sel = getSelectedNodes();
        List<RoiNode> rois = selResolver.resolveRoiNodes(sel, tableModel.getViewRoot());
        if (rois.size() < 2) {
            JOptionPane.showMessageDialog(this, "Select at least two ROIs to combine.", "Combine", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ExplorerNode parent = rois.get(0).getParent();
        if (!(parent instanceof FolderNode)) {
            JOptionPane.showMessageDialog(this, "Combine is only available for ROI files in a folder, not inside a ZIP.", "Combine", JOptionPane.WARNING_MESSAGE);
            return;
        }
        for (RoiNode node : rois) {
            if (node.getParent() != parent) {
                JOptionPane.showMessageDialog(this, "Combine requires all selected ROIs to be in the same folder.", "Combine", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        ShapeRoi combined = null;
        for (RoiNode node : rois) {
            Roi r = node.getRoi();
            if (r == null) continue;
            ShapeRoi sr = new ShapeRoi(r);
            combined = combined == null ? sr : combined.or(sr);
        }
        if (combined == null) return;
        combined.setName("combined");
        try {
            Path dest = DiskSyncService.uniquePath(parent.getPath().resolve("combined.roi"));
            combined.setName(dest.getFileName().toString().replace(".roi", ""));
            final ShapeRoi combinedRoi = combined;
            executeUndoable("Combine",
                    Collections.singletonList(dest),
                    Collections.singletonList(dest),
                    () -> {
                        new RoiEncoder(dest.toString()).write(combinedRoi);
                        OpenViewRegistry.getInstance().notifyChildrenChanged(parent.getPath());
                    });
        } catch (Exception e) {
            showError("Combine failed", e);
        }
    }

    private void cmdSplit() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.size() != 1 || !(sel.get(0) instanceof RoiNode)) {
            JOptionPane.showMessageDialog(this, "Select exactly one ROI to split.", "Split", JOptionPane.WARNING_MESSAGE);
            return;
        }
        RoiNode node = (RoiNode) sel.get(0);
        Roi roi = node.getRoi();
        if (roi == null) return;
        List<Roi> parts = RoiSplitService.decomposeConnectedParts(roi);
        if (parts.size() < 2) {
            JOptionPane.showMessageDialog(this, "Could not split this ROI into multiple parts.", "Split", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ExplorerNode parent = node.getParent();
        if (!(parent instanceof FolderNode || parent instanceof ZipNode)) return;
        String base = node.getName().replace(".roi", "");
        try {
            Collection<Path> fileTargets = node.isZipEntry() && node.getContainingZip() != null
                    ? Collections.singletonList(node.getContainingZip().getPath())
                    : Collections.singletonList(parent.getPath());
            executeUndoable("Split",
                    fileTargets,
                    Collections.<Path>emptyList(),
                    () -> diskSync.saveRoiSplit(node, parts, base, false, OpenViewRegistry.getInstance()));
        } catch (Exception e) {
            showError("Split failed", e);
        }
    }

    private void cmd3DWatershed() {
        if (boundImage == null) {
            JOptionPane.showMessageDialog(this, "No image is bound. Use Bind Image first.",
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Watershed3DSelection selection;
        try {
            selection = resolve3DWatershedSelection(getSelectedNodes());
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "3D Watershed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        open3DWatershedDialog(selection);
    }

    private void open3DWatershedDialog(Watershed3DSelection selection) {
        watershed3dSelection = selection;
        if (watershed3dDialog == null) {
            watershed3dDialog = new JDialog(parentWindow(), "3D Watershed", Dialog.ModalityType.MODELESS);
            watershed3dDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            watershed3dDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    close3DWatershedDialog();
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    close3DWatershedDialog();
                }
            });

            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
              JPanel form = new JPanel(new GridLayout(0, 2, 8, 6));
              watershed3dThresholdSpinner = new JSpinner(new SpinnerNumberModel(500, 0, Integer.MAX_VALUE, 1));
              watershed3dConnectivityBox = new JComboBox<Integer>(new Integer[]{6, 18, 26});
              watershed3dConnectivityBox.setSelectedItem(6);
              watershed3dMinSeedSizeSpinner = new JSpinner(new SpinnerNumberModel(0.1, 0.0, 1_000_000.0, 0.1));
              watershed3dChannelSpinner = new JSpinner(new SpinnerNumberModel(selection.defaultCPosition, 1, Math.max(1, boundImage.getNChannels()), 1));
              watershed3dTimeSpinner = new JSpinner(new SpinnerNumberModel(selection.defaultTPosition, 1, Math.max(1, boundImage.getNFrames()), 1));
              watershed3dChannelSpinner.setEnabled(boundImage.getNChannels() > 1);
              watershed3dTimeSpinner.setEnabled(boundImage.getNFrames() > 1);
              watershed3dReplaceOriginalBox = new JCheckBox("Replace original input", true);
              JButton seedColorButton = new JButton("Choose...");
              seedColorButton.setBackground(watershed3dSeedPreviewColor);
              seedColorButton.addActionListener(e -> {
                  Color chosen = JColorChooser.showDialog(watershed3dDialog, "Seed Preview Color", watershed3dSeedPreviewColor);
                  if (chosen == null) return;
                  watershed3dSeedPreviewColor = new Color(chosen.getRed(), chosen.getGreen(), chosen.getBlue(), watershed3dSeedPreviewColor.getAlpha());
                  seedColorButton.setBackground(watershed3dSeedPreviewColor);
                  if (watershed3dPreview != null) refreshOverlay();
              });
              form.add(new JLabel("Seed threshold"));
              form.add(watershed3dThresholdSpinner);
              form.add(new JLabel("Connectivity"));
              form.add(watershed3dConnectivityBox);
              form.add(new JLabel("Min seed volume (um^3)"));
              form.add(watershed3dMinSeedSizeSpinner);
              form.add(new JLabel("Seed color"));
              form.add(seedColorButton);
              form.add(new JLabel("Channel"));
              form.add(watershed3dChannelSpinner);
              form.add(new JLabel("Time"));
              form.add(watershed3dTimeSpinner);
            form.add(new JLabel(""));
            form.add(watershed3dReplaceOriginalBox);
            panel.add(form, BorderLayout.NORTH);

              JTextArea note = new JTextArea("Preview Seeds shows threshold-connected seed components only. Min seed volume follows spot quantifier semantics and uses calibrated volume (um^3). Set 0 to disable the lower filter. Apply adds watershed result overlays, then Save writes split folders next to the source.");
            note.setEditable(false);
            note.setOpaque(false);
            note.setWrapStyleWord(true);
            note.setLineWrap(true);
            note.setBorder(null);
            panel.add(note, BorderLayout.CENTER);

              watershed3dStatusLabel = new JLabel("Adjust threshold, then Apply.");
              panel.add(watershed3dStatusLabel, BorderLayout.WEST);
  
              JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
              JButton previewSeeds = new JButton("Preview Seeds");
              JButton apply = new JButton("Apply");
              JButton save = new JButton("Save");
              JButton close = new JButton("Close");
              previewSeeds.addActionListener(e -> preview3DWatershedSeeds());
              apply.addActionListener(e -> apply3DWatershedPreview());
              save.addActionListener(e -> save3DWatershedPreview());
              close.addActionListener(e -> close3DWatershedDialog());
              buttons.add(previewSeeds);
              buttons.add(apply);
              buttons.add(save);
              buttons.add(close);
            panel.add(buttons, BorderLayout.SOUTH);

            watershed3dDialog.setContentPane(panel);
            watershed3dDialog.pack();
        }
        if (watershed3dChannelSpinner != null) {
            watershed3dChannelSpinner.setValue(selection.defaultCPosition);
            watershed3dChannelSpinner.setEnabled(boundImage.getNChannels() > 1);
        }
        if (watershed3dTimeSpinner != null) {
            watershed3dTimeSpinner.setValue(selection.defaultTPosition);
            watershed3dTimeSpinner.setEnabled(boundImage.getNFrames() > 1);
        }
        watershed3dDialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        watershed3dDialog.setVisible(true);
        watershed3dDialog.toFront();
    }

    private void apply3DWatershedPreview() {
        if (watershed3dSelection == null) return;
        try {
            commit3DWatershedEditors();
            watershed3dSeedOnlyPreview = false;
            watershed3dPreview = watershed3dSvc.runThresholdSeeds(
                    build3DWatershedRequest());
            update3DWatershedThresholdPreviewImage();
            refreshOverlay();
            if (watershed3dStatusLabel != null) {
                if (watershed3dPreview.canRunWatershed()) {
                    watershed3dStatusLabel.setText("Preview on image: " + watershed3dPreview.getObjectCount() + " result objects from " + watershed3dPreview.getSeedCount() + " seeds.");
                } else {
                    watershed3dStatusLabel.setText("Preview on image: " + watershed3dPreview.getSeedCount() + " seed(s).");
                }
            }
        } catch (IllegalStateException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            showError("3D Watershed preview failed", e);
        }
    }

    private void preview3DWatershedSeeds() {
        if (watershed3dSelection == null) return;
        try {
            commit3DWatershedEditors();
            watershed3dSeedOnlyPreview = true;
            watershed3dPreview = watershed3dSvc.previewThresholdSeeds(build3DWatershedRequest());
            update3DWatershedThresholdPreviewImage();
            refreshOverlay();
            if (watershed3dStatusLabel != null) {
                watershed3dStatusLabel.setText("Seed preview on image: " + watershed3dPreview.getSeedCount() + " seed(s).");
            }
        } catch (IllegalStateException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            showError("3D Watershed seed preview failed", e);
        }
    }

    private Roi3DWatershedService.Request build3DWatershedRequest() {
        return new Roi3DWatershedService.Request(
                  boundImage,
                  watershed3dSelection.roiNodes,
                  ((Number) watershed3dThresholdSpinner.getValue()).intValue(),
                  ((Integer) watershed3dConnectivityBox.getSelectedItem()).intValue(),
                  ((Number) watershed3dMinSeedSizeSpinner.getValue()).doubleValue(),
                  ((Number) watershed3dChannelSpinner.getValue()).intValue(),
                  ((Number) watershed3dTimeSpinner.getValue()).intValue());
    }

    private void commit3DWatershedEditors() throws ParseException {
        if (watershed3dThresholdSpinner != null) watershed3dThresholdSpinner.commitEdit();
        if (watershed3dMinSeedSizeSpinner != null) watershed3dMinSeedSizeSpinner.commitEdit();
        if (watershed3dChannelSpinner != null) watershed3dChannelSpinner.commitEdit();
        if (watershed3dTimeSpinner != null) watershed3dTimeSpinner.commitEdit();
    }

    private void save3DWatershedPreview() {
        if (watershed3dSelection == null) {
            JOptionPane.showMessageDialog(this, "No 3D Watershed selection is active.",
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            commit3DWatershedEditors();
            watershed3dSeedOnlyPreview = false;
            watershed3dPreview = watershed3dSvc.runThresholdSeeds(build3DWatershedRequest());
            update3DWatershedThresholdPreviewImage();
            refreshOverlay();
        } catch (IllegalStateException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(),
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
            return;
        } catch (Exception e) {
            showError("3D Watershed save preparation failed", e);
            return;
        }
        if (!watershed3dPreview.canRunWatershed()) {
            JOptionPane.showMessageDialog(this, "Current threshold preview has fewer than two seeds, so there is nothing to save yet.",
                    "3D Watershed", JOptionPane.WARNING_MESSAGE);
            return;
        }
        final boolean replaceOriginal = watershed3dReplaceOriginalBox != null && watershed3dReplaceOriginalBox.isSelected();
        try {
            executeUndoable("3D Watershed",
                    Collections.singletonList(watershed3dSelection.outputParent),
                    watershed3dSelection.originalRoiPaths,
                    () -> diskSync.saveRoiObjectFolders(
                            watershed3dSelection.outputParent,
                            watershed3dSelection.baseName,
                            watershed3dPreview.getRoisByLabel(),
                            replaceOriginal,
                            watershed3dSelection.originalRoiPaths,
                            watershed3dSelection.originalFolderPath,
                            watershed3dSelection.originalZipPath,
                            OpenViewRegistry.getInstance()));
            int objectCount = watershed3dPreview.getObjectCount();
            int seedCount = watershed3dPreview.getSeedCount();
            close3DWatershedDialog();
            reloadFromDisk();
            JOptionPane.showMessageDialog(this,
                    "3D watershed created " + objectCount + " object folders from " + seedCount + " seeds.",
                    "3D Watershed", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            showError("3D Watershed save failed", e);
        }
    }

    private void close3DWatershedDialog() {
        watershed3dPreview = null;
        watershed3dSelection = null;
        watershed3dSeedOnlyPreview = false;
        close3DWatershedThresholdPreviewImage();
        if (watershed3dDialog != null) {
            JDialog dialog = watershed3dDialog;
            watershed3dDialog = null;
            dialog.dispose();
        }
        watershed3dChannelSpinner = null;
        watershed3dTimeSpinner = null;
        watershed3dStatusLabel = null;
        refreshOverlay();
    }

    private void update3DWatershedThresholdPreviewImage() {
        close3DWatershedThresholdPreviewImage();
        // Threshold/seed previews are shown directly on the bound image overlay.
        // Keep this hook so older call sites stay simple, but do not open a second window.
    }

    private void close3DWatershedThresholdPreviewImage() {
        if (watershed3dThresholdPreviewImage == null) return;
        watershed3dThresholdPreviewImage.changes = false;
        watershed3dThresholdPreviewImage.close();
        watershed3dThresholdPreviewImage = null;
    }

    private void cmdProperties() {
        List<ExplorerNode> sel = getSelectedNodes();
        List<RoiNode> rois = selResolver.resolveRoiNodes(sel, tableModel.getViewRoot());
        if (rois.isEmpty()) return;
        List<Path> selectedPaths = tableModel.snapshotSelection(table.getSelectedRows());
        RoiPropertiesDialog dlg = new RoiPropertiesDialog(parentWindow(), rois);
        dlg.setVisible(true);
        if (!dlg.isConfirmed()) return;
        try {
            executeUndoable("Properties",
                    historySaveTargets(rois),
                    historyRoiPaths(rois),
                    () -> {
                        for (RoiNode node : rois) {
                            dlg.applyTo(node);
                            Roi r = node.getRoi();
                            if (r == null) continue;
                            diskSync.saveRoiToDisk(node, r, OpenViewRegistry.getInstance());
                        }
                    });
            tableModel.fullRefresh();
            restoreSelection(selectedPaths);
            refreshOverlay();
        } catch (IOException e) {
            showError("Properties save failed", e);
        }
    }

    private Watershed3DSelection resolve3DWatershedSelection(List<ExplorerNode> selected) {
        if (selected == null || selected.isEmpty()) {
            throw new IllegalArgumentException("Select a folder or sibling ROI slices for 3D Watershed.");
        }
        if (selected.size() == 1 && selected.get(0) instanceof FolderNode) {
            FolderNode folder = (FolderNode) selected.get(0);
            List<RoiNode> rois = new ArrayList<RoiNode>();
            for (ExplorerNode child : folder.getChildren()) {
                if (!(child instanceof RoiNode)) {
                    throw new IllegalArgumentException("3D Watershed currently supports folders that contain ROI files only.");
                }
                rois.add((RoiNode) child);
            }
            if (rois.size() < 2) {
                throw new IllegalArgumentException("3D Watershed requires at least two ROI slices in the selected folder.");
            }
            if (folder.getParent() == null) {
                throw new IllegalArgumentException("The selected folder has no parent output location.");
            }
            return new Watershed3DSelection(rois, folder.getPath().getParent(), folder.getName(),
                    historyRoiPaths(rois), folder.getPath(), null,
                    inferDefaultChannel(rois), inferDefaultTime(rois));
        }
        if (selected.size() == 1 && selected.get(0) instanceof ZipNode) {
            ZipNode zip = (ZipNode) selected.get(0);
            List<RoiNode> rois = new ArrayList<RoiNode>();
            for (ExplorerNode child : zip.getChildren()) {
                if (!(child instanceof RoiNode)) {
                    throw new IllegalArgumentException("3D Watershed currently supports ROI ZIP entries only.");
                }
                rois.add((RoiNode) child);
            }
            if (rois.size() < 2) {
                throw new IllegalArgumentException("3D Watershed requires at least two ROI slices in the selected ZIP.");
            }
            if (zip.getParent() == null) {
                throw new IllegalArgumentException("The selected ZIP has no parent output location.");
            }
            return new Watershed3DSelection(rois, zip.getPath().getParent(), stripZipExt(zip.getName()),
                    historyRoiPaths(rois), null, zip.getPath(),
                    inferDefaultChannel(rois), inferDefaultTime(rois));
        }

        List<RoiNode> rois = selResolver.resolveRoiNodes(selected, tableModel.getViewRoot());
        if (rois.size() < 2) {
            throw new IllegalArgumentException("Select at least two sibling ROI slices for 3D Watershed.");
        }
        ExplorerNode parent = rois.get(0).getParent();
        if (!(parent instanceof FolderNode) && !(parent instanceof ZipNode)) {
            throw new IllegalArgumentException("All ROI slices for 3D Watershed must share the same folder or ZIP parent.");
        }
        for (RoiNode roi : rois) {
            if (roi.getParent() != parent) {
                throw new IllegalArgumentException("All ROI slices for 3D Watershed must share the same parent folder or ZIP.");
            }
        }
        String baseName = stripRoiExt(rois.get(0).getName());
        Path outputParent = parent instanceof ZipNode ? parent.getPath().getParent() : parent.getPath();
        Path originalZipPath = parent instanceof ZipNode ? parent.getPath() : null;
        return new Watershed3DSelection(rois, outputParent, baseName, historyRoiPaths(rois), null, originalZipPath,
                inferDefaultChannel(rois), inferDefaultTime(rois));
    }

    private int inferDefaultChannel(List<RoiNode> rois) {
        Integer common = null;
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi == null) continue;
            int c = roi.getCPosition();
            if (c <= 0) continue;
            if (common == null) {
                common = c;
            } else if (common.intValue() != c) {
                break;
            }
        }
        return common != null ? common.intValue() : Math.max(1, boundImage != null ? boundImage.getC() : 1);
    }

    private int inferDefaultTime(List<RoiNode> rois) {
        Integer common = null;
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi == null) continue;
            int t = roi.getTPosition();
            if (t <= 0) continue;
            if (common == null) {
                common = t;
            } else if (common.intValue() != t) {
                break;
            }
        }
        return common != null ? common.intValue() : Math.max(1, boundImage != null ? boundImage.getT() : 1);
    }

    private static String stripZipExt(String name) {
        return name != null && name.toLowerCase().endsWith(".zip")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    private void cmdSetHiddenOnSelection(boolean hidden) {
        List<RoiNode> rois = selResolver.resolveRoiNodes(getSelectedNodes(), tableModel.getViewRoot());
        applyHiddenState(rois, hidden, false);
    }

    private void cmdToggleHiddenOnSelection() {
        List<RoiNode> rois = selResolver.resolveRoiNodes(getSelectedNodes(), tableModel.getViewRoot());
        applyHiddenState(rois, false, true);
    }

    private void cmdSetHiddenOnView(boolean hidden) {
        ExplorerNode root = tableModel.getViewRoot();
        if (root == null) return;
        List<RoiNode> rois = selResolver.resolveRoiNodes(Collections.<ExplorerNode>emptyList(), root);
        applyHiddenState(rois, hidden, false);
    }

    private void cmdToggleHiddenOnView() {
        ExplorerNode root = tableModel.getViewRoot();
        if (root == null) return;
        List<RoiNode> rois = selResolver.resolveRoiNodes(Collections.<ExplorerNode>emptyList(), root);
        applyHiddenState(rois, false, true);
    }

    private void applyHiddenState(List<RoiNode> rois, boolean hidden, boolean toggle) {
        if (rois.isEmpty()) return;
        List<Path> selectedPaths = tableModel.snapshotSelection(table.getSelectedRows());
        try {
            executeUndoable(toggle ? "Toggle Visibility" : (hidden ? "Hide" : "Show"),
                    Collections.<Path>emptyList(),
                    historyRoiPaths(rois),
                    () -> {
                        for (RoiNode node : rois) {
                            boolean nextHidden = toggle ? !node.isHidden() : hidden;
                            node.setHidden(nextHidden);
                        }
                        tableModel.fullRefresh();
                        restoreSelection(selectedPaths);
                        refreshOverlay();
                    });
        } catch (IOException e) {
            showError("Visibility change failed", e);
        }
    }

    private void cmdImportFromRoiManager() {
        ExplorerNode target = getAddTarget();
        if (!(target instanceof FolderNode)) {
            JOptionPane.showMessageDialog(this, "Import from ROI Manager requires a folder target, not a ZIP.", "Import", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            roiManagerSvc.importFromRoiManager(target, boundImage, OpenViewRegistry.getInstance());
        } catch (IOException e) {
            showError("Import failed", e);
        }
    }

    private void cmdExportToRoiManager() {
        List<ExplorerNode> sel = getSelectedNodes();
        List<RoiNode> rois = selResolver.resolveRoiNodes(sel, tableModel.getViewRoot());
        if (rois.isEmpty()) { JOptionPane.showMessageDialog(this, "No ROIs selected.", "Export", JOptionPane.WARNING_MESSAGE); return; }
        roiManagerSvc.exportToRoiManager(rois, false);
    }

    private void cmdZip() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.isEmpty()) return;
        if (!sel.stream().allMatch(n -> n instanceof FolderNode)) {
            JOptionPane.showMessageDialog(this, "Zip is only available for ROI-only folders.", "Zip", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Path> nextSelection = new ArrayList<Path>();
        try {
            executeUndoable("Zip",
                    historyZipTargets(sel),
                    historyHiddenTargetsForNodes(sel),
                    () -> {
                        for (ExplorerNode node : sel) {
                            if (!(node instanceof FolderNode)) continue;
                            boolean roiOnly = node.getChildren().stream().allMatch(c -> c instanceof RoiNode);
                            if (!roiOnly) {
                                JOptionPane.showMessageDialog(RoiExplorerPanel.this, "\"" + node.getName() + "\" contains non-ROI items and cannot be zipped.",
                                        "Zip", JOptionPane.WARNING_MESSAGE);
                                continue;
                              }
                              nextSelection.add(diskSync.zipFolder((FolderNode) node, OpenViewRegistry.getInstance()));
                          }
                      });
            queueSelectionRestore(nextSelection);
            reloadFromDisk();
        } catch (IOException e) {
            showError("Zip failed", e);
        }
    }

    private void cmdUnzip() {
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.isEmpty()) return;
        if (!sel.stream().allMatch(n -> n instanceof ZipNode)) {
            JOptionPane.showMessageDialog(this, "Unzip is only available for ZIP items.", "Unzip", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<Path> nextSelection = new ArrayList<Path>();
        try {
            executeUndoable("Unzip",
                    historyUnzipTargets(sel),
                    historyHiddenTargetsForNodes(sel),
                    () -> {
                        for (ExplorerNode node : sel) {
                            if (node instanceof ZipNode) {
                                  nextSelection.add(diskSync.unzipToFolder((ZipNode) node, OpenViewRegistry.getInstance()));
                              }
                          }
                      });
            queueSelectionRestore(nextSelection);
            reloadFromDisk();
        } catch (IOException e) {
            showError("Unzip failed", e);
        }
    }

    private void cmdOpenInNewWindow(ExplorerNode node) {
        if (!(node instanceof FolderNode)) return;
        RoiExplorerWindow w = new RoiExplorerWindow();
        w.openFolder(node.getPath());
        w.setVisible(true);
    }

    private void cmdBindImage() {
        ImagePlus imp = IJ.getImage();
        if (imp == null) {
            JOptionPane.showMessageDialog(this, "No image is open in Fiji.", "Bind Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        bindImage(imp);
    }

    private void cmdBindSubImage() {
        ImagePlus imp = ij.WindowManager.getCurrentImage();
        if (imp == null) {
            JOptionPane.showMessageDialog(this, "No image is open in Fiji.", "Bind Sub Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        setSubBindImage(imp);
    }

    private boolean isSplitModeActive() {
        return splitWorkflow.isActive();
    }

    private boolean ensureSplitMode() {
        if (editCtrl.isEditing()) {
            JOptionPane.showMessageDialog(this, "Finish the current edit first.", "Split Tools", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        if (isSplitModeActive()) {
            return true;
        }
        List<ExplorerNode> sel = getSelectedNodes();
        if (sel.size() != 1 || !(sel.get(0) instanceof RoiNode)) {
            JOptionPane.showMessageDialog(this, "Select exactly one ROI to split.", "Split Tools", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        RoiNode node = (RoiNode) sel.get(0);
        Roi roi = node.getRoi();
        if (roi == null) {
            JOptionPane.showMessageDialog(this, "Cannot load ROI from disk.", "Split Tools", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        chkReplaceOriginal.setSelected(false);
        syncImagePositionToSelection();
        if (!splitWorkflow.start(this, node, roi, boundImage, OpenViewRegistry.getInstance())) {
            return false;
        }
        updateEditControls();
        return true;
    }

    private void cancelSplitMode() {
        if (!isSplitModeActive()) return;
        splitWorkflow.cancel(boundImage, OpenViewRegistry.getInstance());
        chkReplaceOriginal.setSelected(false);
        clearActiveImageSelection();
        updateEditControls();
        refreshOverlay();
    }

    private void cmdSaveSplitMode() {
        if (!isSplitModeActive() || boundImage == null || splitWorkflow.getNode() == null) return;
        Roi current = boundImage.getRoi();
        List<Roi> parts = SplitToolsDialog.consumePendingSplitParts(boundImage, current);
        if (parts == null || parts.size() < 2) {
            JOptionPane.showMessageDialog(this, "No split result is ready. Run Knife or Seed Split first.",
                    "Save Split Results", JOptionPane.WARNING_MESSAGE);
            return;
        }
        splitWorkflow.setReplaceOriginal(chkReplaceOriginal.isSelected());
        OpenViewRegistry.EditSession session = splitWorkflow.getRegistrySession(OpenViewRegistry.getInstance());
        if (session != null && session.getState() == OpenViewRegistry.EditState.DELETED && splitWorkflow.isReplaceOriginal()) {
            splitWorkflow.setReplaceOriginal(false);
            chkReplaceOriginal.setSelected(false);
        }
        splitWorkflow.syncNodePath(OpenViewRegistry.getInstance());
        RoiNode splitNode = splitWorkflow.getNode();
        String baseName = splitNode.getPath().getFileName().toString();
        if (baseName.toLowerCase().endsWith(".roi")) baseName = baseName.substring(0, baseName.length() - 4);
        final String splitBaseName = baseName;
        final List<Roi> splitParts = new ArrayList<>(parts);
        final boolean replaceOriginal = splitWorkflow.isReplaceOriginal();
        try {
            executeUndoable("Save Split Results",
                    Collections.singletonList(splitNode.isZipEntry() && splitNode.getContainingZip() != null
                            ? splitNode.getContainingZip().getPath()
                            : (splitNode.getParent() != null ? splitNode.getParent().getPath() : splitNode.getPath().getParent())),
                    Collections.singletonList(splitNode.getPath()),
                    () -> diskSync.saveRoiSplit(splitNode, splitParts, splitBaseName, replaceOriginal, OpenViewRegistry.getInstance()));
            splitWorkflow.finish(boundImage, OpenViewRegistry.getInstance());
            chkReplaceOriginal.setSelected(false);
            clearActiveImageSelection();
            updateEditControls();
            reloadFromDisk();
        } catch (IOException e) {
            showError("Save Split Results failed", e);
        }
    }

    private void cmdUndo() {
        try {
            if (editCtrl.isEditing() && editCtrl.canUndoSelection()) {
                editCtrl.undoSelection();
                updateEditControls();
            } else {
                historySvc.undo(OpenViewRegistry.getInstance());
            }
        } catch (IOException e) {
            showError("Undo failed", e);
        }
    }

    private void cmdRedo() {
        try {
            if (editCtrl.isEditing() && editCtrl.canRedoSelection()) {
                editCtrl.redoSelection();
                updateEditControls();
            } else {
                historySvc.redo(OpenViewRegistry.getInstance());
            }
        } catch (IOException e) {
            showError("Redo failed", e);
        }
    }

    // ── More >> menu ──────────────────────────────────────────────────────────

    private void showMoreMenu() {
        if (moreMenu != null && moreMenu.isVisible()) {
            moreMenu.setVisible(false);
            moreMenu = null;
            return;
        }
        List<ExplorerNode> sel = getSelectedNodes();
        moreMenu = buildMoreMenu(sel);
        moreMenu.show(btnMore, 0, btnMore.getHeight());
    }

    private void showContextMenu(MouseEvent e) {
        List<ExplorerNode> sel = getSelectedNodes();
        JPopupMenu menu = buildMoreMenu(sel);
        menu.show(table, e.getX(), e.getY());
    }

    private JPopupMenu buildMoreMenu(List<ExplorerNode> sel) {
        JPopupMenu menu = new JPopupMenu();
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                if (moreMenu == menu) moreMenu = null;
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                if (moreMenu == menu) moreMenu = null;
            }
        });
        boolean singleRoi = sel.size() == 1 && sel.get(0) instanceof RoiNode;
        boolean multiRoi = sel.stream().allMatch(n -> n instanceof RoiNode) && !sel.isEmpty();
        boolean hasFolder = sel.stream().anyMatch(n -> n instanceof FolderNode);
        boolean hasZip = sel.stream().anyMatch(n -> n instanceof ZipNode);
        boolean splitting = isSplitModeActive();
        boolean selectionToolsEnabled = editCtrl.isEditing()
                ? boundImage != null && boundImage.getRoi() != null
                : boundImage != null && (singleRoi || splitting);
        boolean roiOnlyFolder = hasFolder && sel.size() == 1
                && ((FolderNode) sel.get(0)).getChildren().stream().allMatch(c -> c instanceof RoiNode)
                && !sel.get(0).getChildren().isEmpty();

        addMenuItem(menu, historyMenuLabel("Undo", activeUndoLabel()), e -> cmdUndo())
                .setEnabled(canActiveUndo());
        addMenuItem(menu, historyMenuLabel("Redo", activeRedoLabel()), e -> cmdRedo())
                .setEnabled(canActiveRedo());
        menu.addSeparator();
        addMenuItem(menu, "Delete", e -> cmdDelete()).setEnabled(!sel.isEmpty() && !splitting);
        addMenuItem(menu, "Rename...", e -> cmdRename()).setEnabled(sel.size() == 1 && !splitting);
        addMenuItem(menu, "Toggle Visibility", e -> cmdToggleHiddenOnSelection()).setEnabled(!sel.isEmpty());
        addMenuItem(menu, "Deselect All", e -> table.clearSelection()).setEnabled(!sel.isEmpty());
        menu.addSeparator();
        addMenuItem(menu, "Reveal on Image", e -> cmdRevealOnImage()).setEnabled(singleRoi);
        addMenuItem(menu, pickMode ? "Stop Picking on Image" : "Pick ROI on Image", e -> cmdPickRoiOnImage())
                .setEnabled(boundImage != null && tableModel.getViewRoot() != null);
        menu.addSeparator();
        addMenuItem(menu, "Duplicate", e -> cmdDuplicate()).setEnabled(!sel.isEmpty() && !splitting);
        addMenuItem(menu, "Move to Folder...", e -> cmdMoveToFolder()).setEnabled(!sel.isEmpty() && !splitting);
        menu.addSeparator();
        addMenuItem(menu, "Combine", e -> cmdCombine()).setEnabled(multiRoi && sel.size() >= 2);
        addMenuItem(menu, "Split", e -> cmdSplit()).setEnabled(singleRoi);
        addMenuItem(menu, "3D Watershed...", e -> cmd3DWatershed())
                .setEnabled(boundImage != null && !splitting && (hasFolder || multiRoi));
        JMenu splitToolsMenu = new JMenu("Split Tools");
        addMenuItem(splitToolsMenu, "Knife", e -> cmdSplitTool(SplitToolsDialog.Tool.KNIFE)).setEnabled(selectionToolsEnabled);
        addMenuItem(splitToolsMenu, "Seed Split", e -> cmdSplitTool(SplitToolsDialog.Tool.SEED_SPLIT)).setEnabled(selectionToolsEnabled);
        menu.add(splitToolsMenu);
        addMenuItem(menu, "Cleanup...", e -> cmdCleanupTool(SelectionEditToolsDialog.Tool.KEEP_LARGEST)).setEnabled(editCtrl.isEditing());
        menu.addSeparator();
        addMenuItem(menu, "Properties...", e -> cmdProperties()).setEnabled(!sel.isEmpty() && !splitting);
        JMenu visibilityMenu = new JMenu("Visibility");
        addMenuItem(visibilityMenu, "Hide", e -> cmdSetHiddenOnSelection(true)).setEnabled(!sel.isEmpty());
        addMenuItem(visibilityMenu, "Show", e -> cmdSetHiddenOnSelection(false)).setEnabled(!sel.isEmpty());
        addMenuItem(visibilityMenu, "Toggle Visibility", e -> cmdToggleHiddenOnSelection()).setEnabled(!sel.isEmpty());
        visibilityMenu.addSeparator();
        addMenuItem(visibilityMenu, "Hide All in View", e -> cmdSetHiddenOnView(true)).setEnabled(tableModel.getViewRoot() != null);
        addMenuItem(visibilityMenu, "Show All in View", e -> cmdSetHiddenOnView(false)).setEnabled(tableModel.getViewRoot() != null);
        addMenuItem(visibilityMenu, "Toggle Visibility in View", e -> cmdToggleHiddenOnView()).setEnabled(tableModel.getViewRoot() != null);
        menu.add(visibilityMenu);
        JMenu displayMenu = new JMenu("Display");
        JCheckBoxMenuItem projectZItem = new JCheckBoxMenuItem("Z proj", projectZ);
        projectZItem.addActionListener(e -> {
            projectZ = projectZItem.isSelected();
            syncImagePositionToSelection();
            refreshOverlay();
        });
        displayMenu.add(projectZItem);
        JCheckBoxMenuItem projectCItem = new JCheckBoxMenuItem("C proj", projectChannel);
        projectCItem.addActionListener(e -> {
            projectChannel = projectCItem.isSelected();
            syncImagePositionToSelection();
            refreshOverlay();
        });
        displayMenu.add(projectCItem);
        JCheckBoxMenuItem projectTItem = new JCheckBoxMenuItem("T proj", projectTime);
        projectTItem.addActionListener(e -> {
            projectTime = projectTItem.isSelected();
            syncImagePositionToSelection();
            refreshOverlay();
        });
        displayMenu.add(projectTItem);
        menu.add(displayMenu);
        menu.addSeparator();
        addMenuItem(menu, "Import from ROI Manager", e -> cmdImportFromRoiManager()).setEnabled(!splitting);
        addMenuItem(menu, "Export to ROI Manager", e -> cmdExportToRoiManager()).setEnabled(!sel.isEmpty() && !splitting);
        addMenuItem(menu, "Set Folder Measurements...", e -> cmdSetGroupMeasureOptions()).setEnabled(!splitting);
        menu.addSeparator();
        addMenuItem(menu, "Zip", e -> cmdZip()).setEnabled(roiOnlyFolder && !splitting);
        addMenuItem(menu, "Unzip to Folder", e -> cmdUnzip()).setEnabled(hasZip && !splitting);
        menu.addSeparator();
        JMenu selectMenu = new JMenu("Select");
        addMenuItem(selectMenu, "Select All", e -> table.selectAll());
        addMenuItem(selectMenu, "Deselect All", e -> table.clearSelection());
        addMenuItem(selectMenu, "ROIs Only", e -> selectByType(RoiNode.class));
        addMenuItem(selectMenu, "Folders Only", e -> selectByType(FolderNode.class));
        menu.add(selectMenu);
        return menu;
    }

    private JMenuItem addMenuItem(JComponent menu, String label, ActionListener al) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(al);
        menu.add(item);
        return item;
    }

    private void selectByType(Class<? extends ExplorerNode> type) {
        table.clearSelection();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            ExplorerNode n = tableModel.getNodeAt(i);
            if (type.isInstance(n)) {
                table.addRowSelectionInterval(i, i);
            }
        }
    }

    // ── Keyboard bindings ─────────────────────────────────────────────────────

    private void registerKeyBindings() {
        ActionMap am = table.getActionMap();
        InputMap im = table.getInputMap(JComponent.WHEN_FOCUSED);

        @SuppressWarnings("deprecation")
        int primary = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();

        bindKey(im, am, "openFolder", KeyStroke.getKeyStroke(KeyEvent.VK_O, primary), e -> cmdOpenFolder());
        bindKey(im, am, "newFolder", KeyStroke.getKeyStroke(KeyEvent.VK_N, primary), e -> cmdNewFolder());
        bindKey(im, am, "duplicate", KeyStroke.getKeyStroke(KeyEvent.VK_D, primary), e -> cmdDuplicate());
        bindKey(im, am, "reload", KeyStroke.getKeyStroke(KeyEvent.VK_R, primary), e -> reloadFromDisk());
        bindKey(im, am, "undo", KeyStroke.getKeyStroke(KeyEvent.VK_Z, primary), e -> cmdUndo());
        bindKey(im, am, "redo", KeyStroke.getKeyStroke(KeyEvent.VK_Y, primary), e -> cmdRedo());
        bindKey(im, am, "redoShift", KeyStroke.getKeyStroke(KeyEvent.VK_Z, primary | InputEvent.SHIFT_MASK), e -> cmdRedo());
        bindKey(im, am, "selectAll", KeyStroke.getKeyStroke(KeyEvent.VK_A, primary), e -> table.selectAll());
        bindKey(im, am, "groupMeasure", KeyStroke.getKeyStroke(KeyEvent.VK_J, primary), e -> cmdGroupMeasure());
        bindKey(im, am, "editRoi", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, primary), e -> cmdEdit());
        bindKey(im, am, "rename", KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), e -> cmdRename());
        bindKey(im, am, "deselect", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), e -> {
            if (editCtrl.isEditing()) editCtrl.cancel(OpenViewRegistry.getInstance());
            else if (isSplitModeActive()) cancelSplitMode();
            else table.clearSelection();
        });
        bindKey(im, am, "delete", KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), e -> cmdDelete());
        bindKey(im, am, "backspace", KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), e -> cmdDelete());
        bindKey(im, am, "reveal", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), e -> {
            List<ExplorerNode> sel = getSelectedNodes();
            if (sel.size() == 1) {
                if (sel.get(0) instanceof RoiNode) cmdRevealOnImage();
                else cmdOpenInNewWindow(sel.get(0));
            }
        });
        bindKey(im, am, "add", KeyStroke.getKeyStroke('a'), e -> cmdAdd());
        bindKey(im, am, "measure", KeyStroke.getKeyStroke('m'), e -> cmdMeasure());
        bindKey(im, am, "pickOnImage", KeyStroke.getKeyStroke('i'), e -> cmdPickRoiOnImage());
        bindKey(im, am, "selectionEditTools", KeyStroke.getKeyStroke('e'), e -> {
            if (editCtrl.isEditing()) cmdSelectionEditTools();
        });

        // Arrow navigation for expand/collapse
        bindKey(im, am, "expandRight", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            List<Path> sel = tableModel.snapshotSelection(rows);
            boolean expandedAny = false;
            for (int row : rows) {
                ExplorerNode node = tableModel.getNodeAt(row);
                if (node == null || node.isLeaf() || node.isExpanded()) continue;
                tableModel.expand(node);
                expandedAny = true;
            }
            if (expandedAny) {
                restoreSelection(sel);
                return;
            }
            if (rows.length != 1) return;
            ExplorerNode node = tableModel.getNodeAt(rows[0]);
            if (node == null || node.isLeaf()) return;
            if (!node.getChildren().isEmpty()) {
                int childRow = tableModel.getRowOf(node.getChildren().get(0));
                if (childRow >= 0) table.setRowSelectionInterval(childRow, childRow);
            }
        });
        bindKey(im, am, "collapseLeft", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) return;
            List<Path> sel = tableModel.snapshotSelection(rows);
            boolean collapsedAny = false;
            for (int row : rows) {
                ExplorerNode node = tableModel.getNodeAt(row);
                if (node == null || node.isLeaf() || !node.isExpanded()) continue;
                tableModel.toggleExpansion(row);
                collapsedAny = true;
            }
            if (collapsedAny) {
                restoreSelection(sel);
                return;
            }
            if (rows.length != 1) return;
            ExplorerNode node = tableModel.getNodeAt(rows[0]);
            if (node == null) return;
            if (node.getParent() != null && node.getParent() != tableModel.getViewRoot()) {
                int parentRow = tableModel.getRowOf(node.getParent());
                if (parentRow >= 0) table.setRowSelectionInterval(parentRow, parentRow);
            }
        });
    }

    private void bindKey(InputMap im, ActionMap am, String name, KeyStroke ks, ActionListener al) {
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { al.actionPerformed(e); }
        });
    }

    private void installNameColumnLock(JTable table) {
        table.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            private boolean adjusting;

            @Override
            public void columnAdded(TableColumnModelEvent e) {}

            @Override
            public void columnRemoved(TableColumnModelEvent e) {}

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {}

            @Override
            public void columnMarginChanged(javax.swing.event.ChangeEvent e) {}

            @Override
            public void columnMoved(TableColumnModelEvent e) {
                if (adjusting) return;
                if (e.getFromIndex() == e.getToIndex()) return;
                TableColumnModel model = table.getColumnModel();
                String nameHeader = table.getModel().getColumnName(ExplorerTreeTableModel.COL_NAME);
                int nameViewIndex = model.getColumnIndex(nameHeader);
                if (nameViewIndex == 0) return;
                adjusting = true;
                try {
                    model.moveColumn(nameViewIndex, 0);
                } finally {
                    adjusting = false;
                }
            }
        });
    }

    // ── Drag & drop ───────────────────────────────────────────────────────────

    private static final DataFlavor NODE_FLAVOR;
    static {
        DataFlavor f;
        try {
            f = new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.util.List");
        } catch (ClassNotFoundException ex) {
            f = DataFlavor.stringFlavor;
        }
        NODE_FLAVOR = f;
    }

    private TransferHandler buildTransferHandler() {
        return new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return MOVE | COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                final List<ExplorerNode> nodes = getSelectedNodes();
                return new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[]{NODE_FLAVOR};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return flavor.equals(NODE_FLAVOR);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor) {
                        return nodes;
                    }
                };
            }

            @Override
            public boolean canImport(TransferSupport support) {
                if (!support.isDataFlavorSupported(NODE_FLAVOR)) return false;
                ExplorerNode target = resolveDropTarget((JTable.DropLocation) support.getDropLocation());
                if (!(target instanceof FolderNode || target instanceof ZipNode)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    List<ExplorerNode> nodes = (List<ExplorerNode>) support.getTransferable().getTransferData(NODE_FLAVOR);
                    return canDrop(pruneDescendantSelections(nodes), target);
                } catch (Exception ex) {
                    return false;
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                ExplorerNode target = resolveDropTarget((JTable.DropLocation) support.getDropLocation());
                if (target == null) return false;
                try {
                    List<ExplorerNode> nodes = (List<ExplorerNode>) support.getTransferable().getTransferData(NODE_FLAVOR);
                    nodes = pruneDescendantSelections(nodes);
                    final List<ExplorerNode> draggedNodes = nodes;
                    final ExplorerNode dropTarget = target;
                    boolean copy = (support.getDropAction() & COPY) != 0;
                    if (copy) {
                        executeUndoable("Duplicate",
                                historyDuplicateTargets(draggedNodes),
                                Collections.<Path>emptyList(),
                                () -> {
                                    for (ExplorerNode n : draggedNodes) {
                                        diskSync.duplicate(n, OpenViewRegistry.getInstance());
                                    }
                                });
                    } else {
                        executeUndoable("Move",
                                historyMoveTargets(draggedNodes, dropTarget),
                                historyMoveHiddenTargets(draggedNodes, dropTarget),
                                () -> diskSync.moveNodes(draggedNodes, dropTarget, OpenViewRegistry.getInstance()));
                    }
                    reloadFromDisk();
                    return true;
                } catch (Exception ex) {
                    showError("Drop failed", ex);
                    return false;
                }
            }

            private ExplorerNode resolveDropTarget(JTable.DropLocation dl) {
                if (dl == null) return tableModel.getViewRoot();
                int row = dl.getRow();
                if (row < 0) return tableModel.getViewRoot();
                if (dl.isInsertRow()) {
                    ExplorerNode prev = row > 0 && row - 1 < tableModel.getRowCount()
                            ? tableModel.getNodeAt(row - 1) : null;
                    ExplorerNode next = row < tableModel.getRowCount()
                            ? tableModel.getNodeAt(row) : null;

                    ExplorerNode nextParent = next != null ? next.getParent() : null;
                    ExplorerNode prevParent = prev != null ? prev.getParent() : null;

                    if (nextParent != null && nextParent == prevParent) return nextParent;
                    if (nextParent != null) return nextParent;
                    if (prev != null && !prev.isLeaf() && prev.isExpanded()) return prev;
                    if (prevParent != null) return prevParent;
                    return tableModel.getViewRoot();
                }
                if (row >= tableModel.getRowCount()) return tableModel.getViewRoot();
                return tableModel.getNodeAt(row);
            }
        };
    }

    private boolean canDrop(List<ExplorerNode> sourceNodes, ExplorerNode target) {
        if (target == null || sourceNodes == null || sourceNodes.isEmpty()) return false;
        if (!(target instanceof FolderNode || target instanceof ZipNode)) return false;

        boolean hasRoi = false;
        boolean hasFolder = false;
        boolean hasZip = false;
        for (ExplorerNode node : sourceNodes) {
            if (node == null || node == target || isAncestorOf(node, target)) return false;
            hasRoi |= node instanceof RoiNode;
            hasFolder |= node instanceof FolderNode;
            hasZip |= node instanceof ZipNode;
        }

        int typeCount = (hasRoi ? 1 : 0) + (hasFolder ? 1 : 0) + (hasZip ? 1 : 0);
        if (typeCount > 1) return false;

        if (target instanceof ZipNode) {
            return hasRoi && !hasFolder && !hasZip;
        }
        return true;
    }

    private boolean isAncestorOf(ExplorerNode ancestor, ExplorerNode node) {
        ExplorerNode current = node;
        while (current != null) {
            if (current == ancestor) return true;
            current = current.getParent();
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void executeUndoable(String label,
                                 Collection<Path> fileTargets,
                                 Collection<Path> hiddenTargets,
                                 SessionHistoryService.IoRunnable action) throws IOException {
        historySvc.runUndoable(label, fileTargets, hiddenTargets, OpenViewRegistry.getInstance(), action);
    }

    private String historyMenuLabel(String prefix, String actionLabel) {
        return actionLabel == null ? prefix : prefix + " " + actionLabel;
    }

    private boolean canActiveUndo() {
        return editCtrl.isEditing() ? editCtrl.canUndoSelection() : historySvc.canUndo();
    }

    private boolean canActiveRedo() {
        return editCtrl.isEditing() ? editCtrl.canRedoSelection() : historySvc.canRedo();
    }

    private String activeUndoLabel() {
        return editCtrl.isEditing() ? editCtrl.getUndoSelectionLabel() : historySvc.getUndoLabel();
    }

    private String activeRedoLabel() {
        return editCtrl.isEditing() ? editCtrl.getRedoSelectionLabel() : historySvc.getRedoLabel();
    }

    private List<Path> historyFileTargetsForNodes(Collection<ExplorerNode> nodes) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            Path fsPath = historyFsPath(node);
            if (fsPath != null) targets.add(fsPath);
        }
        return new ArrayList<>(targets);
    }

    private List<Path> historyHiddenTargetsForNodes(Collection<ExplorerNode> nodes) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            collectRoiPaths(node, paths);
        }
        return new ArrayList<>(paths);
    }

    private List<Path> historyRoiPaths(Collection<RoiNode> nodes) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (RoiNode node : nodes) {
            if (node != null && node.getPath() != null) paths.add(node.getPath());
        }
        return new ArrayList<>(paths);
    }

    private List<Path> historySaveTargets(Collection<RoiNode> nodes) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (RoiNode node : nodes) {
            Path target = historyFsPath(node);
            if (target != null) targets.add(target);
        }
        return new ArrayList<>(targets);
    }

    private List<Path> historyRenameTargets(ExplorerNode node, String newName) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        targets.add(historyFsPath(node));
        Path renamed = predictRenamedPath(node, newName);
        if (renamed != null) targets.add(historyFsPath(renamed, node));
        return new ArrayList<>(targets);
    }

    private List<Path> historyRenameHiddenTargets(ExplorerNode node, String newName) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        if (node instanceof RoiNode) {
            paths.add(node.getPath());
            Path renamed = predictRenamedPath(node, newName);
            if (renamed != null) paths.add(renamed);
            return new ArrayList<>(paths);
        }
        collectRoiPaths(node, paths);
        Path renamedRoot = predictRenamedPath(node, newName);
        if (renamedRoot != null) {
            collectMovedRoiPaths(node, node.getPath(), renamedRoot, paths);
        }
        return new ArrayList<>(paths);
    }

    private List<Path> historyDuplicateTargets(Collection<ExplorerNode> nodes) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
                ZipNode zip = ((RoiNode) node).getContainingZip();
                if (zip != null) targets.add(zip.getPath());
            } else if (node != null) {
                String copyName = buildCopyName(node.getPath().getFileName().toString());
                targets.add(DiskSyncService.uniquePath(node.getPath().getParent().resolve(copyName)));
            }
        }
        return new ArrayList<>(targets);
    }

    private List<Path> historyMoveTargets(Collection<ExplorerNode> nodes, ExplorerNode targetParent) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            targets.add(historyFsPath(node));
            Path moved = predictMovedPath(node, targetParent);
            Path movedFs = historyFsPath(moved, targetParent);
            if (movedFs != null) targets.add(movedFs);
        }
        return new ArrayList<>(targets);
    }

    private List<Path> historyMoveHiddenTargets(Collection<ExplorerNode> nodes, ExplorerNode targetParent) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            collectRoiPaths(node, paths);
            Path moved = predictMovedPath(node, targetParent);
            if (moved != null) {
                if (node instanceof RoiNode) {
                    paths.add(moved);
                } else {
                    collectMovedRoiPaths(node, node.getPath(), moved, paths);
                }
            }
        }
        return new ArrayList<>(paths);
    }

    private List<Path> historyZipTargets(Collection<ExplorerNode> nodes) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            if (!(node instanceof FolderNode)) continue;
            targets.add(node.getPath());
            targets.add(predictZipPath((FolderNode) node));
        }
        return new ArrayList<>(targets);
    }

    private List<Path> historyUnzipTargets(Collection<ExplorerNode> nodes) {
        LinkedHashSet<Path> targets = new LinkedHashSet<>();
        for (ExplorerNode node : nodes) {
            if (!(node instanceof ZipNode)) continue;
            targets.add(node.getPath());
            targets.add(predictUnzipPath((ZipNode) node));
        }
        return new ArrayList<>(targets);
    }

    private Path historyFsPath(ExplorerNode node) {
        if (node == null) return null;
        if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
            ZipNode zip = ((RoiNode) node).getContainingZip();
            return zip != null ? zip.getPath() : null;
        }
        return node.getPath();
    }

    private Path historyFsPath(Path logicalPath, ExplorerNode context) {
        if (logicalPath == null) return null;
        if (context instanceof RoiNode && ((RoiNode) context).isZipEntry()) {
            ZipNode zip = ((RoiNode) context).getContainingZip();
            return zip != null ? zip.getPath() : null;
        }
        if (context instanceof ZipNode) return context.getPath();
        return logicalPath;
    }

    private void collectRoiPaths(ExplorerNode node, Set<Path> out) {
        if (node == null) return;
        if (node instanceof RoiNode) {
            out.add(node.getPath());
        }
        for (ExplorerNode child : node.getChildren()) {
            collectRoiPaths(child, out);
        }
    }

    private void collectMovedRoiPaths(ExplorerNode node, Path oldRoot, Path newRoot, Set<Path> out) {
        if (node == null || oldRoot == null || newRoot == null) return;
        if (node instanceof RoiNode) {
            out.add(newRoot.resolve(oldRoot.relativize(node.getPath())));
        }
        for (ExplorerNode child : node.getChildren()) {
            collectMovedRoiPaths(child, oldRoot, newRoot, out);
        }
    }

    private Path predictRenamedPath(ExplorerNode node, String newName) {
        if (node == null || newName == null) return null;
        String trimmed = newName.trim();
        if (trimmed.isEmpty()) return null;
        Path oldPath = node.getPath();
        if (node instanceof ZipNode) {
            return oldPath.getParent().resolve(trimmed.toLowerCase().endsWith(".zip") ? trimmed : trimmed + ".zip");
        }
        if (node instanceof RoiNode && !((RoiNode) node).isZipEntry()) {
            return oldPath.getParent().resolve(trimmed.toLowerCase().endsWith(".roi") ? trimmed : trimmed + ".roi");
        }
        if (node instanceof RoiNode) {
            return oldPath.getParent().resolve(trimmed.toLowerCase().endsWith(".roi") ? trimmed : trimmed + ".roi");
        }
        return oldPath.getParent().resolve(trimmed);
    }

    private Path predictMovedPath(ExplorerNode node, ExplorerNode targetParent) {
        if (node == null || targetParent == null) return null;
        if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
            String entryName = node.getPath().getFileName().toString();
            if (targetParent instanceof ZipNode) {
                return uniqueZipEntryPath((ZipNode) targetParent, entryName);
            }
            return DiskSyncService.uniquePath(targetParent.getPath().resolve(entryName));
        }
        if (targetParent instanceof ZipNode && node instanceof RoiNode) {
            return uniqueZipEntryPath((ZipNode) targetParent, node.getPath().getFileName().toString());
        }
        return DiskSyncService.uniquePath(targetParent.getPath().resolve(node.getPath().getFileName().toString()));
    }

    private Path uniqueZipEntryPath(ZipNode zip, String preferredName) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ExplorerNode child : zip.getChildren()) {
            names.add(child.getPath().getFileName().toString());
        }
        String name = preferredName;
        if (!names.contains(name)) {
            return zip.getPath().resolve(name);
        }
        String base = preferredName.toLowerCase().endsWith(".roi")
                ? preferredName.substring(0, preferredName.length() - 4)
                : preferredName;
        for (int i = 2; i < 9999; i++) {
            String candidate = base + " " + i + ".roi";
            if (!names.contains(candidate)) {
                return zip.getPath().resolve(candidate);
            }
        }
        return zip.getPath().resolve(preferredName + "_" + System.currentTimeMillis() + ".roi");
    }

    private Path predictZipPath(FolderNode folder) {
        Path folderPath = folder.getPath();
        return DiskSyncService.uniquePath(folderPath.getParent().resolve(folderPath.getFileName().toString() + ".zip"));
    }

    private Path predictUnzipPath(ZipNode zip) {
        Path zipPath = zip.getPath();
        String name = zipPath.getFileName().toString();
        String folderName = name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
        return DiskSyncService.uniquePath(zipPath.getParent().resolve(folderName));
    }

    private static String stripRoiExt(String name) {
        return name != null && name.toLowerCase().endsWith(".roi")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    private String buildCopyName(String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        return base + " copy" + ext;
    }

    private List<ExplorerNode> getSelectedNodes() {
        int[] rows = table.getSelectedRows();
        List<ExplorerNode> nodes = new ArrayList<>();
        for (int row : rows) {
            ExplorerNode n = tableModel.getNodeAt(row);
            if (n != null) nodes.add(n);
        }
        return nodes;
    }

    private List<ExplorerNode> pruneDescendantSelections(List<ExplorerNode> nodes) {
        List<ExplorerNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingInt(n -> n.getPath().getNameCount()));
        List<ExplorerNode> pruned = new ArrayList<>();
        for (ExplorerNode candidate : ordered) {
            boolean covered = false;
            for (ExplorerNode kept : pruned) {
                if (candidate.getPath().startsWith(kept.getPath())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                pruned.add(candidate);
            }
        }
        return pruned;
    }

    private int restoreSelection(List<Path> paths) {
        int[] rows = tableModel.restoreSelection(paths);
        table.clearSelection();
        for (int row : rows) table.addRowSelectionInterval(row, row);
        syncImagePositionToSelection();
        return rows.length;
    }

    private void queueSelectionRestore(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            pendingSelectionRestorePaths = Collections.emptyList();
            return;
        }
        pendingSelectionRestorePaths = new ArrayList<Path>(paths);
    }

    private void attemptPendingSelectionRestore(List<Path> targetSelection) {
        int restored = restoreSelection(targetSelection);
        if (restored > 0) {
            pendingSelectionRestorePaths = Collections.emptyList();
            return;
        }
        if (targetSelection == null || targetSelection.isEmpty()) return;
        // zip/unzip triggers multiple path change notifications; retry once after the
        // current UI update settles so the transformed node can be reloaded first.
        pendingSelectionRestorePaths = new ArrayList<Path>(targetSelection);
        SwingUtilities.invokeLater(() -> {
            if (pendingSelectionRestorePaths.isEmpty()) return;
            int delayedRestored = restoreSelection(pendingSelectionRestorePaths);
            if (delayedRestored > 0) {
                pendingSelectionRestorePaths = Collections.emptyList();
            }
        });
    }

    private void updateStatus() {
        ExplorerNode root = tableModel.getViewRoot();
        if (root == null) {
            statusLabel.setText("No folder open");
            return;
        }
        int total = root.getRoiCount();
        int selected = table.getSelectedRowCount();
        statusLabel.setText(total + " ROI" + (total != 1 ? "s" : "")
                + (selected > 0 ? ",  " + selected + " selected" : ""));
    }

    private void syncImagePositionToSelection() {
        if (boundImage == null) return;
        if (editCtrl.isEditing() || isSplitModeActive()) return;
        Roi roi = getRepresentativeSelectionRoi();
        if (roi == null) return;
        if (hasStructuredAxes(boundImage)) {
            int c = roi.getCPosition();
            int z = roi.getZPosition();
            int t = roi.getTPosition();
            int nextC = projectChannel ? boundImage.getC() : (c > 0 ? c : boundImage.getC());
            int nextZ = projectZ ? boundImage.getZ() : (z > 0 ? z : boundImage.getZ());
            int nextT = projectTime ? boundImage.getT() : (t > 0 ? t : boundImage.getT());
            if (nextC != boundImage.getC() || nextZ != boundImage.getZ() || nextT != boundImage.getT()) {
                boundImage.setPosition(nextC, nextZ, nextT);
            }
        } else if (roi.getPosition() > 0 && roi.getPosition() != boundImage.getCurrentSlice()) {
            boundImage.setSlice(roi.getPosition());
        }
    }

    private Roi getRepresentativeSelectionRoi() {
        List<ExplorerNode> selected = getSelectedNodes();
        if (selected.isEmpty()) return null;
        List<RoiNode> rois = selResolver.resolveRoiNodes(selected, tableModel.getViewRoot());
        if (rois.isEmpty()) return null;
        Roi direct = firstDirectSelectedRoi(selected);
        if (direct != null) return direct;
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi != null) return roi;
        }
        return null;
    }

    private Roi firstDirectSelectedRoi(List<ExplorerNode> selected) {
        for (ExplorerNode node : selected) {
            if (node instanceof RoiNode) {
                return ((RoiNode) node).getRoi();
            }
        }
        return null;
    }

    private List<RoiNode> getSelectedRoiNodesOnly() {
        List<RoiNode> rois = new ArrayList<>();
        for (ExplorerNode node : getSelectedNodes()) {
            if (node instanceof RoiNode) {
                rois.add((RoiNode) node);
            }
        }
        return rois;
    }

    public void refreshOverlay() {
        if (!overlayEnabled) return;
        if (boundImage == null && subImage == null) return;
        if (overlayRefreshing) return;
        overlayRefreshing = true;
        try {
            refreshOverlayForView(mainView());
            refreshOverlayForView(subView());
        } finally {
            overlayRefreshing = false;
        }
    }

    private void refreshOverlayForView(ImageViewContext view) {
        if (view == null || view.image == null) return;
        ExplorerNode root = tableModel.getViewRoot();
        if (root == null) {
            view.image.setOverlay((Overlay) null);
            LAST_IMAGE_POSITIONS.put(view.image, currentImagePosition(view.image));
            return;
        }
        Overlay overlay = new Overlay();
        RoiNode editingNode = editCtrl.getEditingNode();
        for (OverlayEntry entry : buildOverlayEntries(root)) {
            if (entry.target instanceof RoiNode && editCtrl.isEditing() && entry.target == editingNode) continue;
            for (Roi roi : displayRoisForEntry(entry, view)) {
                if (roi == null || !matchesProjection(roi, view)) continue;
                Roi copy = (Roi) roi.clone();
                applyProjectionPosition(copy, view);
                if (copy.getName() == null || copy.getName().isEmpty()) copy.setName(entry.target.getName());
                overlay.add(copy);
            }
        }
        for (ExplorerNode node : getSelectedNodes()) {
            Roi highlight = createHighlightRoi(node, view, SELECTED_OVERLAY_COLOR, 1.25f);
            if (highlight != null) overlay.add(highlight);
        }
        if (editCtrl.isEditing()) {
            Roi reference = createEditReferenceRoi(view);
            if (reference != null) overlay.add(reference);
        }
        Roi pickHighlight = createHighlightRoi(hoveredPickNode, view, PICK_OVERLAY_COLOR, 1.5f);
        if (pickHighlight != null) overlay.add(pickHighlight);
        if (view.main && watershed3dPreview != null) add3DWatershedPreviewOverlay(overlay, view);
        view.image.setOverlay(overlay.size() > 0 ? overlay : null);
        LAST_IMAGE_POSITIONS.put(view.image, currentImagePosition(view.image));
        view.image.updateAndDraw();
    }

    private boolean matchesProjection(Roi roi, ImageViewContext view) {
        if (view == null || !hasStructuredAxes(view.image)) return true;
        return matchesProjected(view.projectT, roi.getTPosition(), view.image.getT(), view.image.getNFrames())
                && matchesProjected(view.projectZ, roi.getZPosition(), view.image.getZ(), view.image.getNSlices())
                && matchesProjected(view.projectC, roi.getCPosition(), view.image.getC(), view.image.getNChannels());
    }

    private void add3DWatershedPreviewOverlay(Overlay overlay, ImageViewContext view) {
        if (watershed3dPreview == null) return;
        for (Roi roi : watershed3dPreview.getThresholdMaskRois()) {
            // Original ROI boundary already shows the domain. Keep threshold mask
            // out of the overlay so only seeds/results add extra signal.
        }
        for (List<Roi> rois : watershed3dPreview.getSeedRoisByLabel().values()) {
            for (Roi roi : rois) {
                if (roi == null || !matchesProjection(roi, view)) continue;
                Roi copy = (Roi) roi.clone();
                applyProjectionPosition(copy, view);
                copy.setFillColor(null);
                copy.setStrokeColor(watershed3dSeedPreviewColor);
                overlay.add(copy);
            }
        }
        if (!watershed3dPreview.canRunWatershed()) {
            return;
        }
        Color[] colors = new Color[]{
                new Color(255, 140, 0, 220),
                new Color(0, 170, 220, 220),
                new Color(80, 200, 120, 220),
                new Color(220, 80, 120, 220),
                new Color(180, 120, 255, 220)
        };
        int labelIndex = 0;
        for (List<Roi> rois : watershed3dPreview.getRoisByLabel().values()) {
            Color color = colors[labelIndex % colors.length];
            labelIndex++;
            for (Roi roi : rois) {
                if (roi == null || !matchesProjection(roi, view)) continue;
                Roi copy = (Roi) roi.clone();
                applyProjectionPosition(copy, view);
                copy.setFillColor(null);
                copy.setStrokeColor(color);
                overlay.add(copy);
            }
        }
    }

    private boolean isHiddenByWatershedPreview(RoiNode node) {
        return false;
    }

    private void applyProjectionPosition(Roi roi, ImageViewContext view) {
        if (view == null || !hasStructuredAxes(view.image)) return;
        int projectedC = view.projectC ? 0 : roi.getCPosition();
        int projectedZ = view.projectZ ? 0 : roi.getZPosition();
        int projectedT = view.projectT ? 0 : roi.getTPosition();
        roi.setPosition(projectedC, projectedZ, projectedT);
    }

    private boolean matchesProjected(boolean projected, int roiPos, int currentPos, int axisSize) {
        if (projected || axisSize <= 1) return true;
        return roiPos <= 0 || roiPos == currentPos;
    }

    private void applyCurrentImagePosition(Roi roi, ImagePlus imp) {
        if (roi == null || imp == null) return;
        if (hasStructuredAxes(imp)) {
            int c = roi.getCPosition();
            int z = roi.getZPosition();
            int t = roi.getTPosition();
            roi.setPosition(
                    c > 0 ? c : (imp.getNChannels() > 1 ? imp.getC() : 0),
                    z > 0 ? z : (imp.getNSlices() > 1 ? imp.getZ() : 0),
                    t > 0 ? t : (imp.getNFrames() > 1 ? imp.getT() : 0));
        } else if (roi.getPosition() <= 0) {
            roi.setPosition(imp.getCurrentSlice());
        }
    }

    private static boolean hasStructuredAxes(ImagePlus imp) {
        return imp != null && (imp.getNChannels() > 1 || imp.getNSlices() > 1 || imp.getNFrames() > 1);
    }

    private Roi createHighlightRoi(ExplorerNode node, ImageViewContext view, Color color, float strokeWidth) {
        if (node == null || view == null || nodeHidden(node)) return null;
        Roi roi = representativeRoiForNode(node, view);
        if (roi == null || !matchesProjection(roi, view)) return null;
        Roi copy = (Roi) roi.clone();
        applyProjectionPosition(copy, view);
        copy.setFillColor(null);
        copy.setStrokeColor(color);
        copy.setStrokeWidth(Math.max(strokeWidth, (float) copy.getStrokeWidth()));
        return copy;
    }

    private Roi createEditReferenceRoi(ImageViewContext view) {
        Roi reference = editCtrl.getOriginalRoiReference();
        if (reference == null || view == null || !view.main) return null;
        if (!matchesProjection(reference, view)) return null;
        applyProjectionPosition(reference, view);
        reference.setFillColor(null);
        reference.setStrokeColor(EDIT_REFERENCE_COLOR);
        reference.setStrokeWidth(1f);
        return reference;
    }

    private ImageViewContext mainView() {
        if (boundImage == null) return null;
        return new ImageViewContext(boundImage, true,
                projectChannel || boundImage.getNChannels() <= 1,
                projectZ || boundImage.getNSlices() <= 1,
                projectTime || boundImage.getNFrames() <= 1);
    }

    private ImageViewContext subView() {
        if (subImage == null || boundImage == null) return null;
        return new ImageViewContext(subImage, false,
                projectChannel || subImage.getNChannels() <= 1,
                projectZ || subImage.getNSlices() <= 1,
                projectTime || subImage.getNFrames() <= 1);
    }

    private List<OverlayEntry> buildOverlayEntries(ExplorerNode root) {
        List<OverlayEntry> out = new ArrayList<OverlayEntry>();
        if (root == null) return out;
        if (chkContainerOr.isSelected()) {
            collectContainerOrEntries(root, out);
        } else {
            for (RoiNode node : selResolver.resolveRoiNodes(Collections.<ExplorerNode>emptyList(), root)) {
                if (node.isHidden() || isHiddenByWatershedPreview(node)) continue;
                Roi roi = node.getRoi();
                if (roi != null) out.add(new OverlayEntry(node, Collections.singletonList(roi)));
            }
        }
        return out;
    }

    private List<Roi> displayRoisForEntry(OverlayEntry entry, ImageViewContext view) {
        if (entry.target instanceof RoiNode) return entry.rois;
        Roi merged = mergeRoisForView(entry.rois, view);
        return merged != null ? Collections.singletonList(merged) : Collections.<Roi>emptyList();
    }

    private void collectContainerOrEntries(ExplorerNode node, List<OverlayEntry> out) {
        if (node == null) return;
        if (node instanceof RoiNode) {
            RoiNode rn = (RoiNode) node;
            if (!rn.isHidden() && !isHiddenByWatershedPreview(rn) && rn.getRoi() != null) {
                out.add(new OverlayEntry(rn, Collections.singletonList(rn.getRoi())));
            }
            return;
        }
        boolean hasContainerChild = false;
        boolean hasRoiChild = false;
        for (ExplorerNode child : node.getChildren()) {
            if (child instanceof FolderNode || child instanceof ZipNode) hasContainerChild = true;
            if (child instanceof RoiNode) hasRoiChild = true;
        }
        if (!hasContainerChild && hasRoiChild && (node instanceof FolderNode || node instanceof ZipNode)) {
            List<Roi> rois = new ArrayList<Roi>();
            for (ExplorerNode child : node.getChildren()) {
                if (!(child instanceof RoiNode)) continue;
                RoiNode rn = (RoiNode) child;
                if (rn.isHidden() || isHiddenByWatershedPreview(rn)) continue;
                Roi roi = rn.getRoi();
                if (roi != null) rois.add(roi);
            }
            if (!rois.isEmpty()) out.add(new OverlayEntry(node, rois));
            return;
        }
        for (ExplorerNode child : node.getChildren()) {
            if (child instanceof RoiNode) {
                RoiNode rn = (RoiNode) child;
                if (!rn.isHidden() && !isHiddenByWatershedPreview(rn) && rn.getRoi() != null) {
                    out.add(new OverlayEntry(rn, Collections.singletonList(rn.getRoi())));
                }
            } else {
                collectContainerOrEntries(child, out);
            }
        }
    }

    private Roi representativeRoiForNode(ExplorerNode node, ImageViewContext view) {
        if (node instanceof RoiNode) return ((RoiNode) node).getRoi();
        List<Roi> rois = new ArrayList<Roi>();
        for (OverlayEntry entry : buildOverlayEntries(node)) {
            rois.addAll(entry.rois);
        }
        return mergeRoisForView(rois, view);
    }

    private Roi mergeRoisForView(List<Roi> rois, ImageViewContext view) {
        ShapeRoi merged = null;
        if (rois == null) return null;
        for (Roi roi : rois) {
            if (roi == null || !matchesProjection(roi, view)) continue;
            ShapeRoi sr = new ShapeRoi((Roi) roi.clone());
            merged = merged == null ? sr : merged.or(sr);
        }
        return merged;
    }

    private boolean nodeHidden(ExplorerNode node) {
        if (node instanceof RoiNode) return ((RoiNode) node).isHidden();
        return false;
    }

    private static int[] currentImagePosition(ImagePlus imp) {
        return new int[]{imp.getC(), imp.getZ(), imp.getT()};
    }

    private static final class ImageViewContext {
        final ImagePlus image;
        final boolean main;
        final boolean projectC;
        final boolean projectZ;
        final boolean projectT;

        ImageViewContext(ImagePlus image, boolean main, boolean projectC, boolean projectZ, boolean projectT) {
            this.image = image;
            this.main = main;
            this.projectC = projectC;
            this.projectZ = projectZ;
            this.projectT = projectT;
        }
    }

    private static final class OverlayEntry {
        final ExplorerNode target;
        final List<Roi> rois;

        OverlayEntry(ExplorerNode target, List<Roi> rois) {
            this.target = target;
            this.rois = rois;
        }
    }

    private static boolean sameImagePosition(ImagePlus imp, int[] previous) {
        if (imp == null || previous == null || previous.length < 3) return false;
        return previous[0] == imp.getC() && previous[1] == imp.getZ() && previous[2] == imp.getT();
    }

    private static synchronized void ensureImageListenerInstalled() {
        if (imageListenerInstalled) return;
        ImagePlus.addImageListener(new ImageListener() {
            @Override
            public void imageOpened(ImagePlus imp) {
                LAST_IMAGE_POSITIONS.remove(imp);
            }

            @Override
            public void imageClosed(ImagePlus imp) {
                LAST_IMAGE_POSITIONS.remove(imp);
                OpenViewRegistry.getInstance().refreshOverlaysFor(imp);
            }

            @Override
            public void imageUpdated(ImagePlus imp) {
                if (overlayRefreshing) return;
                int[] previous = LAST_IMAGE_POSITIONS.get(imp);
                if (sameImagePosition(imp, previous)) return;
                LAST_IMAGE_POSITIONS.put(imp, currentImagePosition(imp));
                OpenViewRegistry.getInstance().refreshOverlaysFor(imp);
            }
        });
        imageListenerInstalled = true;
    }

    private void bindActiveImageIfPresent() {
        // WindowManager.getCurrentImage() returns null silently when no image is open.
        // IJ.getImage() would show a "No images are open" dialog, which is undesirable
        // when the panel is embedded in another plugin's UI.
        ImagePlus imp = ij.WindowManager.getCurrentImage();
        if (imp != null) bindImage(imp);
    }

    private void bindImage(ImagePlus imp) {
        uninstallPickMode();
        uninstallImageShortcuts();
        if (boundImage != imp && boundImage != null) boundImage.setOverlay((Overlay) null);
        boundImage = imp;
        boundImageLabel.setText("Image: " + imp.getTitle());
        boundImageLabel.setToolTipText(imp.getTitle());
        if (subImage != null && (subImage.getWidth() != imp.getWidth() || subImage.getHeight() != imp.getHeight())) {
            clearSubBindImage();
        }
        installImageShortcuts(imp);
        updateStatus();
        refreshOverlay();
    }

    private void clearSubOverlay() {
        if (subImage != null) {
            subImage.setOverlay((Overlay) null);
            subImage.updateAndDraw();
        }
    }

    private static boolean sameXyCalibration(ImagePlus a, ImagePlus b) {
        if (a == null || b == null || a.getCalibration() == null || b.getCalibration() == null) return true;
        double eps = 1e-9;
        return Math.abs(a.getCalibration().pixelWidth - b.getCalibration().pixelWidth) < eps
                && Math.abs(a.getCalibration().pixelHeight - b.getCalibration().pixelHeight) < eps;
    }

    private void installImageShortcuts(ImagePlus imp) {
        if (imp == null) return;
        ImageCanvas canvas = imp.getCanvas();
        ImageWindow window = imp.getWindow();
        if (canvas == null && window == null) return;
        shortcutBoundImage = imp;
        imageShortcutListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (shortcutBoundImage != boundImage) return;
                if (!isPrimaryShortcutDown(e)) return;
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_Z && e.isShiftDown()) {
                    cmdRedo();
                    e.consume();
                } else if (code == KeyEvent.VK_Z) {
                    cmdUndo();
                    e.consume();
                } else if (code == KeyEvent.VK_Y) {
                    cmdRedo();
                    e.consume();
                }
            }
        };
        if (canvas != null) canvas.addKeyListener(imageShortcutListener);
        if (window != null) window.addKeyListener(imageShortcutListener);
    }

    private void uninstallImageShortcuts() {
        if (shortcutBoundImage == null || imageShortcutListener == null) return;
        ImageCanvas canvas = shortcutBoundImage.getCanvas();
        ImageWindow window = shortcutBoundImage.getWindow();
        if (canvas != null) canvas.removeKeyListener(imageShortcutListener);
        if (window != null) window.removeKeyListener(imageShortcutListener);
        imageShortcutListener = null;
        shortcutBoundImage = null;
    }

    private boolean isPrimaryShortcutDown(KeyEvent e) {
        try {
            @SuppressWarnings("deprecation")
            int primary = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
            return (e.getModifiers() & primary) != 0;
        } catch (HeadlessException ignored) {
            return e.isControlDown() || e.isMetaDown();
        }
    }

    private void togglePickMode() {
        if (pickMode) uninstallPickMode();
        else installPickMode();
    }

    private void installPickMode() {
        if ((boundImage == null || boundImage.getCanvas() == null)
                && (subImage == null || subImage.getCanvas() == null)) return;
        pickMode = true;
        updatePickButtonState();
        pickListener = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                handlePickMove(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                clearPickHover();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                handlePickClick(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                handlePickWheel(e);
            }
        };
        addPickCanvas(boundImage);
        addPickCanvas(subImage);
        IJ.showStatus("ROI Explorer: Pick ROI on Image enabled");
    }

    private void uninstallPickMode() {
        pickMode = false;
        updatePickButtonState();
        if (pickListener != null) {
            for (ImageCanvas canvas : new ArrayList<ImageCanvas>(pickCanvases)) {
                canvas.removeMouseMotionListener(pickListener);
                canvas.removeMouseListener(pickListener);
                canvas.removeMouseWheelListener(pickListener);
            }
            pickCanvases.clear();
        }
        pickListener = null;
        clearPickHover();
        IJ.showStatus("");
    }

    private void addPickCanvas(ImagePlus image) {
        if (image == null || image.getCanvas() == null || pickListener == null) return;
        ImageCanvas canvas = image.getCanvas();
        if (pickCanvases.contains(canvas)) return;
        pickCanvases.add(canvas);
        canvas.addMouseMotionListener(pickListener);
        canvas.addMouseListener(pickListener);
        canvas.addMouseWheelListener(pickListener);
    }

    private void updatePickButtonState() {
        btnPickRoi.setText(pickMode ? "Picking..." : "Pick ROI");
        btnPickRoi.setContentAreaFilled(true);
        btnPickRoi.setBorderPainted(true);
        if (pickMode) {
            btnPickRoi.setOpaque(true);
            btnPickRoi.setBackground(PICK_BUTTON_ACTIVE_BG);
            btnPickRoi.setForeground(PICK_BUTTON_ACTIVE_FG);
        } else {
            btnPickRoi.setOpaque(false);
            btnPickRoi.setBackground(UIManager.getColor("Button.background"));
            btnPickRoi.setForeground(UIManager.getColor("Button.foreground"));
        }
        btnPickRoi.repaint();
    }

    private void handlePickMove(MouseEvent e) {
        ImageViewContext view = viewForEvent(e);
        if (!pickMode || view == null || view.image.getCanvas() == null) return;
        ImageCanvas canvas = view.image.getCanvas();
        Point point = new Point(canvas.offScreenX(e.getX()), canvas.offScreenY(e.getY()));
        updatePickCandidates(point, view);
    }

    private void handlePickWheel(MouseWheelEvent e) {
        if (!pickMode || pickCandidates.size() <= 1) return;
        int delta = e.getWheelRotation();
        if (delta == 0) return;
        int size = pickCandidates.size();
        pickCandidateIndex = (pickCandidateIndex + delta) % size;
        if (pickCandidateIndex < 0) pickCandidateIndex += size;
        hoveredPickNode = pickCandidates.get(pickCandidateIndex);
        refreshOverlay();
        e.consume();
    }

    private void handlePickClick(MouseEvent e) {
        if (!pickMode || hoveredPickNode == null) return;
        boolean additive = e.isShiftDown() || e.isControlDown() || e.isMetaDown();
        selectNodeInTree(hoveredPickNode, additive);
        if (e.getClickCount() >= 2) cmdEdit();
        uninstallPickMode();
        e.consume();
    }

    private void updatePickCandidates(Point point, ImageViewContext view) {
        if (point == null) {
            clearPickHover();
            return;
        }
        java.util.List<ExplorerNode> candidates = findPickCandidates(point, view);
        if (candidates.isEmpty()) {
            clearPickHover();
            return;
        }
        boolean samePoint = pickPoint != null && pickPoint.equals(point);
        pickCandidates = candidates;
        if (!samePoint || pickCandidateIndex >= pickCandidates.size()) {
            pickCandidateIndex = 0;
        }
        hoveredPickNode = pickCandidates.get(pickCandidateIndex);
        pickPoint = point;
        refreshOverlay();
    }

    private void clearPickHover() {
        pickCandidates = Collections.emptyList();
        pickCandidateIndex = 0;
        pickPoint = null;
        if (hoveredPickNode != null) {
            hoveredPickNode = null;
            refreshOverlay();
        }
    }

    private java.util.List<ExplorerNode> findPickCandidates(Point point, ImageViewContext view) {
        ExplorerNode root = tableModel.getViewRoot();
        if (view == null || root == null) return Collections.emptyList();
        java.util.List<ExplorerNode> candidates = new ArrayList<ExplorerNode>();
        for (OverlayEntry entry : buildOverlayEntries(root)) {
            Roi roi = representativeRoiForNode(entry.target, view);
            if (roi == null || !matchesProjection(roi, view)) continue;
            if (roiContains(roi, point.x, point.y)) candidates.add(entry.target);
        }
        Collections.sort(candidates, new Comparator<ExplorerNode>() {
            @Override
            public int compare(ExplorerNode a, ExplorerNode b) {
                double areaA = areaOf(representativeRoiForNode(a, view));
                double areaB = areaOf(representativeRoiForNode(b, view));
                return Double.compare(areaA, areaB);
            }
        });
        return candidates;
    }

    private ImageViewContext viewForEvent(MouseEvent e) {
        Object src = e.getSource();
        if (boundImage != null && src == boundImage.getCanvas()) return mainView();
        if (subImage != null && src == subImage.getCanvas()) return subView();
        return null;
    }

    private boolean roiContains(Roi roi, int x, int y) {
        try {
            return roi.contains(x, y);
        } catch (RuntimeException ex) {
            Rectangle bounds = roi.getBounds();
            return bounds != null && bounds.contains(x, y);
        }
    }

    private double areaOf(Roi roi) {
        if (roi == null) return Double.POSITIVE_INFINITY;
        try {
            return roi.getStatistics().area;
        } catch (RuntimeException ex) {
            Rectangle bounds = roi.getBounds();
            return bounds != null ? bounds.getWidth() * bounds.getHeight() : Double.POSITIVE_INFINITY;
        }
    }

    private void selectNodeInTree(ExplorerNode node, boolean additive) {
        // Pick ROI can target ZIP entries or other nodes under collapsed parents.
        // Expand the ancestor path first so the target row actually exists.
        tableModel.expandPathTo(node);
        int row = tableModel.getRowOf(node);
        if (row < 0) return;
        if (!additive) table.clearSelection();
        table.addRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        syncImagePositionToSelection();
    }


    private void showError(String msg, Exception e) {
        JOptionPane.showMessageDialog(this, msg + "\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private static final class Watershed3DSelection {
        private final List<RoiNode> roiNodes;
        private final Path outputParent;
        private final String baseName;
        private final List<Path> originalRoiPaths;
        private final Path originalFolderPath;
        private final Path originalZipPath;
        private final int defaultCPosition;
        private final int defaultTPosition;

        private Watershed3DSelection(List<RoiNode> roiNodes, Path outputParent, String baseName,
                                     List<Path> originalRoiPaths, Path originalFolderPath, Path originalZipPath,
                                     int defaultCPosition, int defaultTPosition) {
            this.roiNodes = roiNodes;
            this.outputParent = outputParent;
            this.baseName = baseName;
            this.originalRoiPaths = originalRoiPaths;
            this.originalFolderPath = originalFolderPath;
            this.originalZipPath = originalZipPath;
            this.defaultCPosition = defaultCPosition;
            this.defaultTPosition = defaultTPosition;
        }
    }

}
