package io.github.kusumotok.roiexplorer.controller;

import ij.ImagePlus;
import ij.gui.Roi;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.EditMode;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.PathKey;
import io.github.kusumotok.roiexplorer.model.RoiNode;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;
import io.github.kusumotok.roiexplorer.ui.SplitToolsDialog;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Dedicated state holder for the split / watershed workflow.
 *
 * The current split UI still reuses the legacy dialog implementation, but the
 * workflow state now lives outside RoiExplorerWindow so the eventual
 * 3D-watershed-specific controller can replace the UI without moving session
 * ownership again.
 */
public final class SplitWorkflowSession {
    private RoiNode node;
    private UUID sessionId;
    private boolean replaceOriginal;

    public boolean isActive() {
        return node != null;
    }

    public RoiNode getNode() {
        return node;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public boolean isReplaceOriginal() {
        return replaceOriginal;
    }

    public void setReplaceOriginal(boolean replaceOriginal) {
        this.replaceOriginal = replaceOriginal;
    }

    public boolean start(RoiExplorerPanel owner, RoiNode targetNode, Roi roi, ImagePlus image, OpenViewRegistry registry) {
        if (isActive()) return true;
        UUID startedSessionId = registry.tryStartEdit(PathKey.forRoiNode(targetNode), EditMode.SPLIT_EDIT, owner);
        if (startedSessionId == null) {
            JOptionPane.showMessageDialog(owner,
                    "\"" + targetNode.getName() + "\" is already being edited in another window.",
                    "Split Locked", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        this.node = targetNode;
        this.sessionId = startedSessionId;
        this.replaceOriginal = false;
        if (image != null) {
            image.setRoi((Roi) roi.clone());
            SplitToolsDialog.clearPendingSplitParts(image);
        }
        return true;
    }

    public void cancel(ImagePlus image, OpenViewRegistry registry) {
        if (!isActive()) return;
        if (image != null) {
            SplitToolsDialog.clearPendingSplitParts(image);
        }
        registry.endEdit(sessionId);
        clear();
    }

    public void finish(ImagePlus image, OpenViewRegistry registry) {
        if (image != null) {
            SplitToolsDialog.clearPendingSplitParts(image);
        }
        registry.endEdit(sessionId);
        clear();
    }

    public void syncNodePath(OpenViewRegistry registry) {
        if (!isActive() || sessionId == null) return;
        Path currentPath = registry.getEditPath(sessionId);
        if (currentPath != null) {
            node.setPath(currentPath);
        }
    }

    public OpenViewRegistry.EditSession getRegistrySession(OpenViewRegistry registry) {
        return sessionId != null ? registry.getSession(sessionId) : null;
    }

    private void clear() {
        node = null;
        sessionId = null;
        replaceOriginal = false;
    }
}
