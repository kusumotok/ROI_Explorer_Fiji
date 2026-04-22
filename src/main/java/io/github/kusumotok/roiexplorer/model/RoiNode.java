package io.github.kusumotok.roiexplorer.model;

import ij.gui.Roi;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import java.awt.Color;
import java.nio.file.Path;

public class RoiNode extends ExplorerNode {

    private final RoiLoader loader;
    private Roi cachedRoi;
    private boolean roiLoaded = false;

    public RoiNode(Path path, ExplorerNode parent, RoiLoader loader) {
        super(path, parent);
        this.loader = loader;
    }

    @Override
    public NodeType getType() {
        return NodeType.ROI;
    }

    @Override
    public boolean isLeaf() {
        return true;
    }

    @Override
    public int getRoiCount() {
        return 1;
    }

    public Roi getRoi() {
        if (!roiLoaded) {
            try {
                cachedRoi = loader.load();
            } catch (Exception e) {
                cachedRoi = null;
            }
            roiLoaded = true;
        }
        return cachedRoi;
    }

    public void setRoi(Roi roi) {
        this.cachedRoi = roi;
        this.roiLoaded = true;
    }

    public void invalidateCache() {
        cachedRoi = null;
        roiLoaded = false;
    }

    public boolean isZipEntry() {
        return getParent() instanceof ZipNode;
    }

    public ZipNode getContainingZip() {
        ExplorerNode p = getParent();
        return p instanceof ZipNode ? (ZipNode) p : null;
    }

    public int getZ() {
        Roi r = getRoi();
        return r == null ? 0 : r.getZPosition();
    }

    public int getC() {
        Roi r = getRoi();
        return r == null ? 0 : r.getCPosition();
    }

    public int getT() {
        Roi r = getRoi();
        return r == null ? 0 : r.getTPosition();
    }

    public Color getStrokeColor() {
        Roi r = getRoi();
        return r == null ? null : r.getStrokeColor();
    }

    public Color getFillColor() {
        Roi r = getRoi();
        return r == null ? null : r.getFillColor();
    }

    public boolean isHidden() {
        return OpenViewRegistry.getInstance().isHidden(getPath());
    }

    public void setHidden(boolean hidden) {
        OpenViewRegistry.getInstance().setHidden(getPath(), hidden);
    }

    public void setStrokeColor(Color color) {
        Roi r = getRoi();
        if (r != null) r.setStrokeColor(color);
    }

    public void setFillColor(Color color) {
        Roi r = getRoi();
        if (r != null) r.setFillColor(color);
    }
}
