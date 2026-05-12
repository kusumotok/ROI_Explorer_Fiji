package io.github.kusumotok.roiexplorer.ui;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementRequest;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade.MeasurementResult;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

/**
 * Thin JFrame wrapper around RoiExplorerPanel.
 *
 * All application logic lives in RoiExplorerPanel.
 * This class handles window lifecycle (position, close, title) only.
 * Use {@link #getPanel()} to obtain the panel for embedding.
 */
public class RoiExplorerWindow extends JFrame {

    private static final int WINDOW_STAGGER = 24;

    private final RoiExplorerPanel panel = new RoiExplorerPanel();

    public RoiExplorerWindow() {
        super("ROI Explorer");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                panel.onWindowClosing();
                dispose();
            }
        });
        add(panel);
        pack();
        setMinimumSize(new Dimension(360, 280));
        positionWindow();
    }

    /** Returns the embedded panel (use this to embed ROI Explorer in another container). */
    public RoiExplorerPanel getPanel() {
        return panel;
    }

    // ── Delegation of public API ──────────────────────────────────────────────

    public void openFolder(Path path)                             { panel.openFolder(path); }
    public Path getCurrentRoot()                                  { return panel.getCurrentRoot(); }
    public boolean hasLoadedRoot()                                { return panel.hasLoadedRoot(); }
    public boolean hasActivePreview()                             { return panel.hasActivePreview(); }
    public void cleanupPreview()                                  { panel.cleanupPreview(); }
    public void setBindImage(ImagePlus imp)                       { panel.setBindImage(imp); }
    public void setSubBindImage(ImagePlus imp)                    { panel.setSubBindImage(imp); }
    public void clearSubBindImage()                               { panel.clearSubBindImage(); }
    public void setContainerOrMode(boolean enabled)               { panel.setContainerOrMode(enabled); }
    public ImagePlus getBoundImage()                              { return panel.getBoundImage(); }
    public void measureCurrentRoot()                              { panel.measureCurrentRoot(); }
    public MeasurementResult measureCurrentRoot(MeasurementRequest r) { return panel.measureCurrentRoot(r); }
    public void launch3DWatershed()                               { panel.launch3DWatershed(); }
    public void reloadFromDisk()                                  { panel.reloadFromDisk(); }
    public void refreshOverlay()                                  { panel.refreshOverlay(); }
    public void onExternalChange(Path p)                          { panel.onExternalChange(p); }
    public void onPathRenamed(Path oldPath, Path newPath)         { panel.onPathRenamed(oldPath, newPath); }
    public void onPathDeleted(Path path)                          { panel.onPathDeleted(path); }

    // ── Window positioning ────────────────────────────────────────────────────

    private void positionWindow() {
        java.util.List<RoiExplorerPanel> panels = OpenViewRegistry.getInstance().getPanels();
        if (panels.size() <= 1) {
            setLocationRelativeTo(null);
            return;
        }
        // Find the most recently registered panel that isn't this one
        RoiExplorerPanel anchorPanel = null;
        for (int i = panels.size() - 1; i >= 0; i--) {
            if (panels.get(i) != panel) { anchorPanel = panels.get(i); break; }
        }
        if (anchorPanel == null) { setLocationRelativeTo(null); return; }

        Window anchorWindow = SwingUtilities.getWindowAncestor(anchorPanel);
        if (anchorWindow == null || anchorWindow == this) {
            setLocationRelativeTo(null);
            return;
        }
        Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int x = anchorWindow.getX() + WINDOW_STAGGER;
        int y = anchorWindow.getY() + WINDOW_STAGGER;
        if (x + getWidth()  > bounds.x + bounds.width)  x = bounds.x + Math.max(0, bounds.width  - getWidth());
        if (y + getHeight() > bounds.y + bounds.height) y = bounds.y + Math.max(0, bounds.height - getHeight());
        if (x < bounds.x) x = bounds.x;
        if (y < bounds.y) y = bounds.y;
        setLocation(x, y);
    }
}
