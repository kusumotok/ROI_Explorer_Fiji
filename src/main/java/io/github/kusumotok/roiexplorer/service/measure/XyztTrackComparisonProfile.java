package io.github.kusumotok.roiexplorer.service.measure;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures a track folder as adjacent XYZ time comparisons.
 *
 * Expected unit shape:
 *   obj-001/t001/*.roi
 *   obj-001/t002/*.roi
 *
 * Each adjacent t pair emits one interval row. The base XYZ metrics are copied
 * from the destination frame, while *_from/*_to and delta columns describe the
 * interval.
 */
public final class XyztTrackComparisonProfile implements MeasurementProfile {

    public static final String ID = "xyzt_track_comparison";
    private static final Pattern T_FOLDER = Pattern.compile("^t(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final XyzObjectProfile xyzProfile;

    public XyztTrackComparisonProfile() {
        this(MeasurementColumn.allEnabled());
    }

    public XyztTrackComparisonProfile(java.util.Set<MeasurementColumn> enabled) {
        this.xyzProfile = new XyzObjectProfile(enabled);
    }

    @Override public String id() { return ID; }

    @Override public String displayName() { return "XYZT Track Comparison"; }

    @Override
    public List<ObjectMeasurementResult> measure(MeasurementUnit unit, ImagePlus image) {
        Map<Integer, List<RoiNode>> byT = groupByTime(unit);
        if (byT.size() < 2) {
            throw new IllegalArgumentException(
                "Unit '" + unit.getName() + "' requires at least two timepoints for XYZT comparison.");
        }

        Map<Integer, ObjectMeasurementResult> baseByT = new TreeMap<>();
        for (Map.Entry<Integer, List<RoiNode>> entry : byT.entrySet()) {
            MeasurementUnit tUnit = new MeasurementUnit(unit.getName(), unit.getSource(), entry.getValue());
            List<ObjectMeasurementResult> measured = xyzProfile.measure(tUnit, image);
            if (!measured.isEmpty()) baseByT.put(entry.getKey(), measured.get(0));
        }

        List<ObjectMeasurementResult> out = new ArrayList<>();
        Integer prevT = null;
        ObjectMeasurementResult prev = null;
        for (Map.Entry<Integer, ObjectMeasurementResult> entry : baseByT.entrySet()) {
            int t = entry.getKey();
            ObjectMeasurementResult cur = entry.getValue();
            if (prev != null && prevT != null) {
                out.add(intervalResult(unit.getName(), prevT, t, prev, cur));
            }
            prevT = t;
            prev = cur;
        }
        return out;
    }

    private static Map<Integer, List<RoiNode>> groupByTime(MeasurementUnit unit) {
        Map<Integer, List<RoiNode>> byT = new TreeMap<>();
        for (RoiNode node : unit.getRois()) {
            int t = resolveTime(node);
            if (t <= 0) {
                throw new IllegalArgumentException(
                    "ROI '" + node.getName() + "' in unit '" + unit.getName() + "' has no valid T position.");
            }
            List<RoiNode> rois = byT.get(t);
            if (rois == null) {
                rois = new ArrayList<>();
                byT.put(t, rois);
            }
            rois.add(node);
        }
        return byT;
    }

    private static int resolveTime(RoiNode node) {
        int t = node.getT();
        if (t > 0) return t;
        for (io.github.kusumotok.roiexplorer.model.ExplorerNode p = node.getParent();
             p != null; p = p.getParent()) {
            Matcher m = T_FOLDER.matcher(p.getName());
            if (m.matches()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    private static ObjectMeasurementResult intervalResult(String unitName, int tFrom, int tTo,
                                                          ObjectMeasurementResult from,
                                                          ObjectMeasurementResult to) {
        double dx = to.centroidXUm - from.centroidXUm;
        double dy = to.centroidYUm - from.centroidYUm;
        double dz = to.centroidZUm - from.centroidZUm;
        double displacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double interval = Math.max(1, tTo - tFrom);
        return new ObjectMeasurementResult.Builder()
            .unitName(unitName)
            .c(to.c)
            .t(tTo)
            .tFrom(tFrom)
            .tTo(tTo)
            .volumeVox(to.volumeVox)
            .volumeUm3(to.volumeUm3)
            .volumeFromVox(from.volumeVox)
            .volumeToVox(to.volumeVox)
            .volumeFromUm3(from.volumeUm3)
            .volumeToUm3(to.volumeUm3)
            .deltaVolumeUm3(to.volumeUm3 - from.volumeUm3)
            .surfaceAreaUm2(to.surfaceAreaUm2)
            .sphericity(to.sphericity)
            .integratedIntensity(to.integratedIntensity)
            .meanIntensity(to.meanIntensity)
            .maxIntensity(to.maxIntensity)
            .centroidXUm(to.centroidXUm)
            .centroidYUm(to.centroidYUm)
            .centroidZUm(to.centroidZUm)
            .centroidFrom(from.centroidXUm, from.centroidYUm, from.centroidZUm)
            .centroidTo(to.centroidXUm, to.centroidYUm, to.centroidZUm)
            .displacementUm(displacement)
            .interval(interval)
            .velocityUmPerFrame(displacement / interval)
            .maxFeret3dUm(to.maxFeret3dUm)
            .maxFeretP1(to.maxFeretP1XUm, to.maxFeretP1YUm, to.maxFeretP1ZUm)
            .maxFeretP2(to.maxFeretP2XUm, to.maxFeretP2YUm, to.maxFeretP2ZUm)
            .calibrationUnit(to.calibrationUnit)
            .build();
    }
}
