package io.github.kusumotok.roiexplorer.ui;

import ij.ImagePlus;
import ij.gui.Roi;

import java.awt.*;
import java.util.List;

/**
 * Split tools have their own entry point because their lifecycle differs from
 * cleanup tools: they start from one ROI and produce multiple ROI results.
 * The current dialog internals are still shared until the dedicated split
 * workflow is rebuilt fully.
 */
public final class SplitToolsDialog {

    public enum Tool {
        KNIFE,
        SEED_SPLIT
    }

    private SplitToolsDialog() {
    }

    public static void open(Window owner, ImagePlus image) {
        open(owner, image, Tool.KNIFE);
    }

    public static void open(Window owner, ImagePlus image, Tool initialTool) {
        SelectionEditToolsDialog.open(owner, image, map(initialTool));
    }

    public static List<Roi> consumePendingSplitParts(ImagePlus image, Roi currentRoi) {
        return SelectionEditToolsDialog.consumePendingSplitParts(image, currentRoi);
    }

    public static void clearPendingSplitParts(ImagePlus image) {
        SelectionEditToolsDialog.clearPendingSplitParts(image);
    }

    private static SelectionEditToolsDialog.Tool map(Tool tool) {
        if (tool == null) return SelectionEditToolsDialog.Tool.KNIFE;
        switch (tool) {
            case SEED_SPLIT:
                return SelectionEditToolsDialog.Tool.SEED_SPLIT;
            case KNIFE:
            default:
                return SelectionEditToolsDialog.Tool.KNIFE;
        }
    }
}
