package io.github.kusumotok.roiexplorer.service;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.process.ImageStatistics;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.util.List;

public class RoiMeasurementService {

    public void measureRois(List<RoiNode> rois, ImagePlus imp) {
        ResultsTable rt = Analyzer.getResultsTable();
        if (rt == null) rt = new ResultsTable();
        rt.reset();

        int measurements = Analyzer.getMeasurements();
        Analyzer analyzer = imp != null ? new Analyzer(imp, measurements, rt) : null;
        if (analyzer != null) analyzer.disableReset(true);
        ImageState originalState = ImageState.capture(imp);

        try {
            for (RoiNode node : rois) {
                Roi roi = node.getRoi();
                if (roi == null) continue;

                if (imp != null) {
                    setImagePosition(imp, roi);
                    imp.setRoi(roi);
                    ImageStatistics stats = imp.getStatistics(measurements);
                    analyzer.saveResults(stats, roi);
                    rt.setLabel(roi.getName() != null ? roi.getName() : node.getName(), rt.getCounter() - 1);
                } else {
                    rt.incrementCounter();
                    rt.addLabel(node.getName());
                    rt.addValue("ROI", node.getName());
                }
            }
        } finally {
            if (originalState != null) originalState.restore(imp);
        }

        rt.show("Results");
    }

    private static void setImagePosition(ImagePlus imp, Roi roi) {
        if (!hasStructuredAxes(imp)) return;
        int c = roi.getCPosition();
        int z = roi.getZPosition();
        int t = roi.getTPosition();
        if (c > 0 || z > 0 || t > 0) {
            imp.setPosition(
                    c > 0 ? c : imp.getC(),
                    z > 0 ? z : imp.getZ(),
                    t > 0 ? t : imp.getT());
        }
    }

    private static boolean hasStructuredAxes(ImagePlus imp) {
        return imp != null && (imp.getNChannels() > 1 || imp.getNSlices() > 1 || imp.getNFrames() > 1);
    }

    private static final class ImageState {
        private final int c;
        private final int z;
        private final int t;
        private final int slice;
        private final Roi roi;

        private ImageState(int c, int z, int t, int slice, Roi roi) {
            this.c = c;
            this.z = z;
            this.t = t;
            this.slice = slice;
            this.roi = roi;
        }

        static ImageState capture(ImagePlus imp) {
            if (imp == null) return null;
            return new ImageState(imp.getC(), imp.getZ(), imp.getT(), imp.getCurrentSlice(),
                    imp.getRoi() != null ? (Roi) imp.getRoi().clone() : null);
        }

        void restore(ImagePlus imp) {
            if (imp == null) return;
            if (hasStructuredAxes(imp)) {
                imp.setPosition(c, z, t);
            } else if (slice > 0 && slice <= imp.getStackSize()) {
                imp.setSlice(slice);
            }
            if (roi != null) imp.setRoi((Roi) roi.clone());
            else imp.killRoi();
        }
    }
}
