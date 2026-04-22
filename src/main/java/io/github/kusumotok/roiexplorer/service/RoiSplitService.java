package io.github.kusumotok.roiexplorer.service;

import ij.gui.Roi;
import ij.gui.ShapeRoi;

import java.util.ArrayList;
import java.util.List;

public final class RoiSplitService {

    private RoiSplitService() {}

    public static List<Roi> decomposeConnectedParts(Roi roi) {
        List<Roi> parts = new ArrayList<>();
        if (roi == null) return parts;
        try {
            ShapeRoi shape = new ShapeRoi(roi);
            Roi[] rois = shape.getRois();
            if (rois != null && rois.length > 0) {
                for (Roi part : rois) {
                    if (part != null) parts.add(part);
                }
                if (!parts.isEmpty()) return parts;
            }
        } catch (Exception ignored) {
        }
        parts.add((Roi) roi.clone());
        return parts;
    }
}
