package io.github.kusumotok.roiexplorer.controller;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.RoiEncoder;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.EditMode;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.PathKey;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.Revision;
import io.github.kusumotok.roiexplorer.model.RoiNode;
import io.github.kusumotok.roiexplorer.service.DiskSyncService;
import io.github.kusumotok.roiexplorer.service.RoiSplitService;
import io.github.kusumotok.roiexplorer.service.SessionHistoryService;
import io.github.kusumotok.roiexplorer.ui.SelectionEditToolsDialog;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RoiEditController {
    private static final int MAX_EDIT_HISTORY = 100;
    private static final int TRACKING_INTERVAL_MS = 150;
    private static final int TRACKING_SETTLE_MS = 350;

    public interface EditHost {
        ImagePlus getBoundImage();
        void updateEditControls();
        Window getWindow();
        boolean isProjectChannel();
        boolean isProjectZ();
        boolean isProjectTime();
    }

    private final EditHost host;
    private RoiNode editingNode;
    private UUID editingSessionId;
    private Roi originalRoi;
    private long editStartRevision;
    private final Deque<SelectionSnapshot> selectionUndo = new ArrayDeque<>();
    private final Deque<SelectionSnapshot> selectionRedo = new ArrayDeque<>();
    private SelectionSnapshot lastTrackedSelection;
    private SelectionSnapshot pendingSelection;
    private long pendingSelectionSince;
    private Timer selectionTrackingTimer;
    private boolean restoringSelection;

    public RoiEditController(EditHost host) {
        this.host = host;
    }

    public boolean isEditing() {
        return editingNode != null;
    }

    public RoiNode getEditingNode() {
        return editingNode;
    }

    public Roi getOriginalRoiReference() {
        return originalRoi != null ? (Roi) originalRoi.clone() : null;
    }

    public void startEdit(RoiNode node, OpenViewRegistry registry) {
        if (editingNode != null) return;

        Window owner = host.getWindow();
        UUID sessionId = registry.tryStartEdit(PathKey.forRoiNode(node), EditMode.EDIT,
                owner instanceof io.github.kusumotok.roiexplorer.ui.RoiExplorerWindow
                        ? (io.github.kusumotok.roiexplorer.ui.RoiExplorerWindow) owner : null);
        if (sessionId == null) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "\"" + node.getName() + "\" is already being edited in another window.",
                    "Edit Locked", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Roi roi = node.getRoi();
        if (roi == null) {
            registry.endEdit(sessionId);
            JOptionPane.showMessageDialog(host.getWindow(),
                    "Cannot load ROI from disk.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        editingSessionId = sessionId;
        editingNode = node;
        originalRoi = (Roi) roi.clone();
        editStartRevision = registry.currentRevision(sessionId) != null ? registry.currentRevision(sessionId).hashCode() : 0L;

        ImagePlus imp = host.getBoundImage();
        if (imp != null) {
            setImagePosition(imp, roi, host);
            imp.setRoi((Roi) roi.clone());
            if (imp.getWindow() != null) imp.getWindow().toFront();
        }
        initializeSelectionHistory(imp, roi);

        host.updateEditControls();
    }

    public void save(DiskSyncService diskSync, SessionHistoryService history, OpenViewRegistry registry) {
        if (editingNode == null) return;

        ImagePlus imp = host.getBoundImage();
        Roi current = imp != null ? imp.getRoi() : null;
        if (current == null) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "No active selection to save.", "Save", JOptionPane.WARNING_MESSAGE);
            return;
        }

        syncEditingNodePath(registry);
        if (!checkConflict(diskSync, history, registry, current)) return;

        copyProperties(originalRoi, current);

        try {
            syncEditingNodePath(registry);
            history.runUndoable("Save ROI", historyTargetsForSave(editingNode),
                    historyHiddenTargetsForSave(editingNode), registry,
                    () -> diskSync.saveRoiToDisk(editingNode, current, registry));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SelectionEditToolsDialog.clearPendingSplitParts(imp);
        endSession(registry);
    }

    public void saveAndSplit(DiskSyncService diskSync, SessionHistoryService history, OpenViewRegistry registry) {
        if (editingNode == null) return;

        ImagePlus imp = host.getBoundImage();
        Roi current = imp != null ? imp.getRoi() : null;
        if (current == null) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "No active selection to save.", "Save and Split", JOptionPane.WARNING_MESSAGE);
            return;
        }

        syncEditingNodePath(registry);
        if (!checkConflict(diskSync, history, registry, current)) return;

        List<Roi> parts = SelectionEditToolsDialog.consumePendingSplitParts(imp, current);
        if (parts == null || parts.isEmpty()) {
            parts = RoiSplitService.decomposeConnectedParts(current);
        }
        final List<Roi> splitParts = parts;
        String baseName = editingNode.getPath().getFileName().toString();
        if (baseName.toLowerCase().endsWith(".roi")) baseName = baseName.substring(0, baseName.length() - 4);
        final String splitBaseName = baseName;

        try {
            syncEditingNodePath(registry);
            history.runUndoable("Save & Split",
                    historyTargetsForSplit(editingNode),
                    historyHiddenTargetsForSave(editingNode),
                    registry,
                    () -> diskSync.saveRoiSplit(editingNode, splitParts, splitBaseName, false, registry));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "Save failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SelectionEditToolsDialog.clearPendingSplitParts(imp);
        endSession(registry);
    }

    public void cancel(OpenViewRegistry registry) {
        if (editingNode == null) return;
        ImagePlus imp = host.getBoundImage();
        if (imp != null && originalRoi != null) {
            SelectionEditToolsDialog.clearPendingSplitParts(imp);
            imp.setRoi(originalRoi);
        }
        endSession(registry);
    }

    public boolean canUndoSelection() {
        return editingNode != null && selectionUndo.size() > 1;
    }

    public boolean canRedoSelection() {
        return editingNode != null && !selectionRedo.isEmpty();
    }

    public String getUndoSelectionLabel() {
        return canUndoSelection() ? "Selection Change" : null;
    }

    public String getRedoSelectionLabel() {
        return canRedoSelection() ? "Selection Change" : null;
    }

    public void undoSelection() {
        if (!canUndoSelection()) return;
        SelectionSnapshot current = selectionUndo.removeLast();
        selectionRedo.addLast(current);
        restoreSelectionSnapshot(selectionUndo.peekLast());
    }

    public void redoSelection() {
        if (!canRedoSelection()) return;
        SelectionSnapshot next = selectionRedo.removeLast();
        selectionUndo.addLast(next);
        restoreSelectionSnapshot(next);
    }

    private boolean checkConflict(DiskSyncService diskSync, SessionHistoryService history, OpenViewRegistry registry, Roi pending) {
        OpenViewRegistry.EditSession session = registry.getSession(editingSessionId);
        if (session != null && session.getState() == OpenViewRegistry.EditState.DELETED) {
            int choice = JOptionPane.showOptionDialog(host.getWindow(),
                    "The original ROI was deleted while editing.",
                    "Original Deleted", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE, null,
                    new String[]{"Save As New...", "Cancel"}, "Cancel");
            if (choice == 0) {
                saveAsNew(pending, diskSync, history, registry);
                endSession(registry);
            }
            return false;
        }
        Revision current = registry.currentRevision(editingSessionId);
        if (current != null && current.hashCode() == editStartRevision) return true;
        registry.markConflict(editingSessionId);

        int choice = JOptionPane.showOptionDialog(host.getWindow(),
                "The ROI was modified externally. What would you like to do?",
                "Conflict Detected", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null,
                new String[]{"Save (overwrite)", "Save As New...", "Cancel"}, "Cancel");

        if (choice == 0) {
            return true;
        } else if (choice == 1) {
            saveAsNew(pending, diskSync, history, registry);
            endSession(registry);
            return false;
        } else {
            return false;
        }
    }

    private void saveAsNew(Roi roi, DiskSyncService diskSync, SessionHistoryService history, OpenViewRegistry registry) {
        String defaultName = editingNode.getPath().getFileName().toString();
        String name = JOptionPane.showInputDialog(host.getWindow(),
                "New ROI name:", defaultName);
        if (name == null || name.trim().isEmpty()) return;
        if (!name.toLowerCase().endsWith(".roi")) name += ".roi";
        try {
            java.nio.file.Path dir = editingNode.getParent() != null
                    ? editingNode.getParent().getPath()
                    : editingNode.getPath().getParent();
            java.nio.file.Path dest = DiskSyncService.uniquePath(dir.resolve(name));
            history.runUndoable("Save ROI As New",
                    java.util.Collections.singletonList(dest),
                    java.util.Collections.singletonList(dest),
                    registry,
                    () -> {
                        new ij.io.RoiEncoder(dest.toString()).write(roi);
                        registry.notifyChildrenChanged(dir);
                    });
        } catch (Exception e) {
            JOptionPane.showMessageDialog(host.getWindow(),
                    "Save As failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<Path> historyTargetsForSave(RoiNode node) {
        Path target = node.isZipEntry() && node.getContainingZip() != null
                ? node.getContainingZip().getPath()
                : node.getPath();
        return java.util.Collections.singletonList(target);
    }

    private static List<Path> historyTargetsForSplit(RoiNode node) {
        if (node.isZipEntry() && node.getContainingZip() != null) {
            return java.util.Collections.singletonList(node.getContainingZip().getPath());
        }
        Path parent = node.getParent() != null ? node.getParent().getPath() : node.getPath().getParent();
        return java.util.Collections.singletonList(parent);
    }

    private static List<Path> historyHiddenTargetsForSave(RoiNode node) {
        Set<Path> paths = new LinkedHashSet<>();
        paths.add(node.getPath());
        return new ArrayList<>(paths);
    }

    private void initializeSelectionHistory(ImagePlus imp, Roi roi) {
        stopSelectionTracking();
        selectionUndo.clear();
        selectionRedo.clear();
        SelectionEditToolsDialog.clearPendingSplitParts(imp);
        lastTrackedSelection = SelectionSnapshot.fromRoi(roi);
        pendingSelection = null;
        pendingSelectionSince = 0L;
        selectionUndo.addLast(lastTrackedSelection);
        if (imp == null) return;
        selectionTrackingTimer = new Timer(TRACKING_INTERVAL_MS, e -> captureSelectionIfChanged());
        selectionTrackingTimer.start();
    }

    private void stopSelectionTracking() {
        if (selectionTrackingTimer != null) {
            selectionTrackingTimer.stop();
            selectionTrackingTimer = null;
        }
    }

    private void captureSelectionIfChanged() {
        if (editingNode == null || restoringSelection) return;
        ImagePlus imp = host.getBoundImage();
        if (imp == null) return;
        SelectionSnapshot current = SelectionSnapshot.fromRoi(imp.getRoi());
        if (current.equals(lastTrackedSelection)) {
            pendingSelection = null;
            pendingSelectionSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (pendingSelection == null || !pendingSelection.equals(current)) {
            pendingSelection = current;
            pendingSelectionSince = now;
            return;
        }
        if (now - pendingSelectionSince < TRACKING_SETTLE_MS) return;
        selectionUndo.addLast(current);
        while (selectionUndo.size() > MAX_EDIT_HISTORY) {
            selectionUndo.removeFirst();
        }
        selectionRedo.clear();
        lastTrackedSelection = current;
        pendingSelection = null;
        pendingSelectionSince = 0L;
        host.updateEditControls();
    }

    private void restoreSelectionSnapshot(SelectionSnapshot snapshot) {
        if (snapshot == null) return;
        ImagePlus imp = host.getBoundImage();
        if (imp == null) return;
        restoringSelection = true;
        try {
            if (snapshot.roi == null) {
                imp.killRoi();
            } else {
                imp.setRoi((Roi) snapshot.roi.clone());
            }
            lastTrackedSelection = SelectionSnapshot.fromSnapshot(snapshot);
            pendingSelection = null;
            pendingSelectionSince = 0L;
        } finally {
            restoringSelection = false;
        }
        host.updateEditControls();
    }

    private void endSession(OpenViewRegistry registry) {
        stopSelectionTracking();
        selectionUndo.clear();
        selectionRedo.clear();
        lastTrackedSelection = null;
        pendingSelection = null;
        pendingSelectionSince = 0L;
        if (editingNode != null) {
            registry.endEdit(editingSessionId);
        }
        editingNode = null;
        editingSessionId = null;
        originalRoi = null;
        editStartRevision = 0;
        host.updateEditControls();
    }

    private static void setImagePosition(ImagePlus imp, Roi roi, EditHost host) {
        if (!hasStructuredAxes(imp)) return;
        int c = roi.getCPosition(), z = roi.getZPosition(), t = roi.getTPosition();
        int nextC = host.isProjectChannel() ? imp.getC() : (c > 0 ? c : imp.getC());
        int nextZ = host.isProjectZ() ? imp.getZ() : (z > 0 ? z : imp.getZ());
        int nextT = host.isProjectTime() ? imp.getT() : (t > 0 ? t : imp.getT());
        imp.setPosition(nextC, nextZ, nextT);
    }

    private static boolean hasStructuredAxes(ImagePlus imp) {
        return imp != null && (imp.getNChannels() > 1 || imp.getNSlices() > 1 || imp.getNFrames() > 1);
    }

    private static void copyProperties(Roi from, Roi to) {
        if (from == null || to == null) return;
        if (from.getName() != null) to.setName(from.getName());
        if (from.getStrokeColor() != null) to.setStrokeColor(from.getStrokeColor());
        if (from.getFillColor() != null) to.setFillColor(from.getFillColor());
        to.setStrokeWidth(from.getStrokeWidth());
        int c = from.getCPosition(), z = from.getZPosition(), t = from.getTPosition();
        if (c > 0 || z > 0 || t > 0) to.setPosition(c, z, t);
    }

    private void syncEditingNodePath(OpenViewRegistry registry) {
        if (editingNode == null || editingSessionId == null) return;
        Path currentPath = registry.getEditPath(editingSessionId);
        if (currentPath != null) {
            editingNode.setPath(currentPath);
        }
    }

    private static final class SelectionSnapshot {
        private final Roi roi;
        private final byte[] bytes;

        private SelectionSnapshot(Roi roi, byte[] bytes) {
            this.roi = roi;
            this.bytes = bytes;
        }

        static SelectionSnapshot fromRoi(Roi roi) {
            if (roi == null) return new SelectionSnapshot(null, null);
            Roi clone = (Roi) roi.clone();
            return new SelectionSnapshot(clone, RoiEncoder.saveAsByteArray(clone));
        }

        static SelectionSnapshot fromSnapshot(SelectionSnapshot snapshot) {
            if (snapshot == null) return new SelectionSnapshot(null, null);
            return new SelectionSnapshot(snapshot.roi != null ? (Roi) snapshot.roi.clone() : null,
                    snapshot.bytes != null ? Arrays.copyOf(snapshot.bytes, snapshot.bytes.length) : null);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SelectionSnapshot)) return false;
            SelectionSnapshot that = (SelectionSnapshot) o;
            return Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
