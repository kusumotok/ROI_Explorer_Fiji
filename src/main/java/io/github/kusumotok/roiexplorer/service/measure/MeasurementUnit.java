package io.github.kusumotok.roiexplorer.service.measure;

import io.github.kusumotok.roiexplorer.model.ExplorerNode;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.util.Collections;
import java.util.List;

public final class MeasurementUnit {

    private final String name;
    private final ExplorerNode source;
    private final List<RoiNode> rois;

    public MeasurementUnit(String name, ExplorerNode source, List<RoiNode> rois) {
        this.name = name;
        this.source = source;
        this.rois = Collections.unmodifiableList(rois);
    }

    public String getName() { return name; }
    public ExplorerNode getSource() { return source; }
    public List<RoiNode> getRois() { return rois; }
    public boolean isEmpty() { return rois.isEmpty(); }
}
