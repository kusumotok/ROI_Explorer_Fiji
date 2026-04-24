package io.github.kusumotok.roiexplorer.service;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import io.github.kusumotok.roiexplorer.model.ExplorerNode;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.awt.Rectangle;
import java.util.*;

public class GroupMeasurementService {

    public static class Options {
        public boolean groupByC = false;
        public boolean groupByT = false;
        public boolean groupByZ = false;

        public boolean roiCount = true;
        public boolean areaSum = true;
        public boolean areaMean = false;
        public boolean areaMin = false;
        public boolean areaMax = false;

        public boolean volumeTotal = false;
        public boolean volumeVox = false;
        public boolean surfaceAreaTotal = false;
        public boolean sphericity = false;
        public boolean integratedIntensity = false;
        public boolean meanIntensity = false;
        public boolean maxIntensity = false;
        public boolean centroidX = false;
        public boolean centroidY = false;
        public boolean centroidZ = false;
        public boolean farthestPair = false;
        public boolean principalAxesXY = false;
        public boolean zMin = false;
        public boolean zMax = false;
        public boolean zSpan = false;
        public boolean occupiedSlices = false;

        public Options copy() {
            Options out = new Options();
            out.groupByC = groupByC;
            out.groupByT = groupByT;
            out.groupByZ = groupByZ;
            out.roiCount = roiCount;
            out.areaSum = areaSum;
            out.areaMean = areaMean;
            out.areaMin = areaMin;
            out.areaMax = areaMax;
            out.volumeTotal = volumeTotal;
            out.volumeVox = volumeVox;
            out.surfaceAreaTotal = surfaceAreaTotal;
            out.sphericity = sphericity;
            out.integratedIntensity = integratedIntensity;
            out.meanIntensity = meanIntensity;
            out.maxIntensity = maxIntensity;
            out.centroidX = centroidX;
            out.centroidY = centroidY;
            out.centroidZ = centroidZ;
            out.farthestPair = farthestPair;
            out.principalAxesXY = principalAxesXY;
            out.zMin = zMin;
            out.zMax = zMax;
            out.zSpan = zSpan;
            out.occupiedSlices = occupiedSlices;
            return out;
        }
    }

    private final SelectionResolver resolver = new SelectionResolver();

    public void measure(List<ExplorerNode> units, Options opts, ImagePlus imp) {
        ResultsTable rt = new ResultsTable();
        ImageState originalState = ImageState.capture(imp);

        try {
            for (ExplorerNode unit : units) {
                List<RoiNode> rois = resolver.roiNodesUnder(unit);
                if (rois.isEmpty() && unit instanceof RoiNode) {
                    rois = Collections.singletonList((RoiNode) unit);
                }

                if (opts.groupByC || opts.groupByT || opts.groupByZ) {
                    Map<GroupKey, List<RoiNode>> grouped = groupRois(rois, opts);
                    for (Map.Entry<GroupKey, List<RoiNode>> entry : grouped.entrySet()) {
                        addRow(rt, unit.getName(), entry.getValue(), entry.getKey(), opts, imp);
                    }
                } else {
                    addRow(rt, unit.getName(), rois, GroupKey.NONE, opts, imp);
                }
            }
        } finally {
            if (originalState != null) originalState.restore(imp);
        }

        rt.show("Folder Results");
    }

    private Map<GroupKey, List<RoiNode>> groupRois(List<RoiNode> rois, Options opts) {
        Map<GroupKey, List<RoiNode>> map = new LinkedHashMap<>();
        for (RoiNode node : rois) {
            GroupKey key = new GroupKey(
                    opts.groupByC ? node.getC() : -1,
                    opts.groupByT ? node.getT() : -1,
                    opts.groupByZ ? node.getZ() : -1);
            map.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        return map;
    }

    private void addRow(ResultsTable rt, String unitName, List<RoiNode> rois,
                        GroupKey key, Options opts, ImagePlus imp) {
        String lengthUnitSuffix = lengthUnitSuffix(imp);
        VoxelMetrics metrics = needsVoxelMetrics(opts, imp) ? buildVoxelMetrics(rois, imp) : null;
        rt.incrementCounter();
        rt.addLabel(unitName);

        if (key.c >= 0) rt.addValue("C", key.c);
        if (key.t >= 0) rt.addValue("T", key.t);
        if (key.z >= 0) rt.addValue("Z", key.z);

        List<double[]> areas = measureAreas(rois, imp);
        double areaSum = 0;
        for (double[] a : areas) areaSum += a[0];

        if (opts.roiCount) rt.addValue("ROI_count", rois.size());
        if (opts.areaSum) rt.addValue("Area_sum", areaSum);
        if (opts.areaMean) rt.addValue("Area_mean", areas.isEmpty() ? 0 : areaSum / areas.size());
        if (opts.areaMin) {
            double min = areas.stream().mapToDouble(a -> a[0]).min().orElse(0);
            rt.addValue("Area_min", min);
        }
        if (opts.areaMax) {
            double max = areas.stream().mapToDouble(a -> a[0]).max().orElse(0);
            rt.addValue("Area_max", max);
        }
        if (opts.principalAxesXY && metrics != null) {
            rt.addValue("Long_axis_xy_" + lengthUnitSuffix, metrics.majorAxisXYUm);
            rt.addValue("Short_axis_xy_" + lengthUnitSuffix, metrics.minorAxisXYUm);
        }

        if (use3DMetrics(opts, imp)) {
            add3DMetrics(rt, metrics, opts, lengthUnitSuffix);
        } else if (useIntensityMetrics(opts) && metrics != null) {
            addIntensityMetrics(rt, metrics, opts, lengthUnitSuffix);
        }
    }

    private List<double[]> measureAreas(List<RoiNode> rois, ImagePlus imp) {
        List<double[]> result = new ArrayList<>();
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi == null) continue;
            double area;
            if (imp != null) {
                imp.setRoi(roi);
                Calibration cal = imp.getCalibration();
                area = roi.getStatistics().area * cal.pixelWidth * cal.pixelHeight;
            } else {
                area = roi.getStatistics().area;
            }
            result.add(new double[]{area});
        }
        return result;
    }

    private void add3DMetrics(ResultsTable rt, VoxelMetrics metrics,
                              Options opts, String lengthUnitSuffix) {
        if (metrics == null) return;
        Set<Integer> zSlices = new TreeSet<>();
        zSlices.addAll(metrics.zSlices);

        if (opts.occupiedSlices) rt.addValue("Occupied_slices", zSlices.size());

        int zMinVal = zSlices.isEmpty() ? 0 : Collections.min(zSlices);
        int zMaxVal = zSlices.isEmpty() ? 0 : Collections.max(zSlices);

        if (opts.zMin) rt.addValue("Z_min", zMinVal);
        if (opts.zMax) rt.addValue("Z_max", zMaxVal);
        if (opts.zSpan) rt.addValue("Z_span", zMaxVal - zMinVal + (zSlices.isEmpty() ? 0 : 1));

        if (opts.volumeTotal) {
            rt.addValue("Volume_total", metrics.volumeUm3);
        }
        if (opts.volumeVox) {
            rt.addValue("Volume_vox", metrics.volumeVox);
        }
        if (opts.surfaceAreaTotal) {
            rt.addValue("Surface_area_total", metrics.surfaceAreaUm2);
        }
        if (opts.sphericity) {
            rt.addValue("Sphericity", metrics.sphericity);
        }
        if (opts.integratedIntensity) {
            rt.addValue("Integrated_intensity", metrics.integratedIntensity);
        }
        if (opts.meanIntensity) {
            rt.addValue("Mean_intensity", metrics.meanIntensity);
        }
        if (opts.maxIntensity) {
            rt.addValue("Max_intensity", metrics.maxIntensity);
        }
        if (opts.centroidX) {
            rt.addValue("Centroid_x_" + lengthUnitSuffix, metrics.centroidXUm);
        }
        if (opts.centroidY) {
            rt.addValue("Centroid_y_" + lengthUnitSuffix, metrics.centroidYUm);
        }
        if (opts.centroidZ) {
            rt.addValue("Centroid_z_" + lengthUnitSuffix, metrics.centroidZUm);
        }
        if (opts.farthestPair) {
            addFarthestPair(rt, metrics, lengthUnitSuffix);
        }
    }

    private boolean use3DMetrics(Options opts, ImagePlus imp) {
        if (opts.groupByZ) return false;
        if (imp == null || imp.getNSlices() <= 1) return false;
        return opts.volumeTotal || opts.volumeVox || opts.surfaceAreaTotal || opts.sphericity
                || opts.integratedIntensity || opts.meanIntensity || opts.maxIntensity
                || opts.centroidX || opts.centroidY || opts.centroidZ || opts.farthestPair
                || opts.zMin || opts.zMax || opts.zSpan || opts.occupiedSlices;
    }

    private boolean useIntensityMetrics(Options opts) {
        return opts.integratedIntensity || opts.meanIntensity || opts.maxIntensity
                || opts.centroidX || opts.centroidY || opts.centroidZ || opts.farthestPair;
    }

    private void addIntensityMetrics(ResultsTable rt, VoxelMetrics metrics, Options opts, String lengthUnitSuffix) {
        if (metrics == null) return;
        if (opts.integratedIntensity) rt.addValue("Integrated_intensity", metrics.integratedIntensity);
        if (opts.meanIntensity) rt.addValue("Mean_intensity", metrics.meanIntensity);
        if (opts.maxIntensity) rt.addValue("Max_intensity", metrics.maxIntensity);
        if (opts.centroidX) rt.addValue("Centroid_x_" + lengthUnitSuffix, metrics.centroidXUm);
        if (opts.centroidY) rt.addValue("Centroid_y_" + lengthUnitSuffix, metrics.centroidYUm);
        if (opts.centroidZ) rt.addValue("Centroid_z_" + lengthUnitSuffix, metrics.centroidZUm);
        if (opts.farthestPair) addFarthestPair(rt, metrics, lengthUnitSuffix);
    }

    private VoxelMetrics buildVoxelMetrics(List<RoiNode> rois, ImagePlus imp) {
        if (rois.isEmpty()) return new VoxelMetrics();
        Calibration cal = imp != null ? imp.getCalibration() : null;
        double pixelWidth = cal != null && cal.pixelWidth > 0 ? cal.pixelWidth : 1.0;
        double pixelHeight = cal != null && cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double pixelDepth = cal != null && cal.pixelDepth > 0 ? cal.pixelDepth : 1.0;
        double areaXY = pixelWidth * pixelHeight;
        double areaXZ = pixelWidth * pixelDepth;
        double areaYZ = pixelHeight * pixelDepth;

        Set<Voxel> voxels = new HashSet<Voxel>();
        List<double[]> xyPoints = new ArrayList<double[]>();
        Set<Integer> zSlices = new TreeSet<Integer>();
        double integrated = 0.0;
        double maxIntensity = Double.NEGATIVE_INFINITY;
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi == null) continue;
            int z = node.getZ() > 0 ? node.getZ() : 1;
            zSlices.add(z);
            int c = node.getC() > 0 ? node.getC() : (imp != null ? imp.getC() : 1);
            int t = node.getT() > 0 ? node.getT() : 1;
            Rectangle bounds = roi.getBounds();
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi.contains(x, y)) {
                        Voxel voxel = new Voxel(x, y, z);
                        if (voxels.add(voxel)) {
                            double value = sampleIntensity(imp, x, y, z, c, t);
                            integrated += value;
                            if (value > maxIntensity) maxIntensity = value;
                            double xUm = (x + 0.5) * pixelWidth;
                            double yUm = (y + 0.5) * pixelHeight;
                            double zUm = z * pixelDepth;
                            sumX += xUm;
                            sumY += yUm;
                            sumZ += zUm;
                            xyPoints.add(new double[]{xUm, yUm});
                        }
                    }
                }
            }
        }

        double surface = 0.0;
        for (Voxel voxel : voxels) {
            if (!voxels.contains(new Voxel(voxel.x - 1, voxel.y, voxel.z))) surface += areaYZ;
            if (!voxels.contains(new Voxel(voxel.x + 1, voxel.y, voxel.z))) surface += areaYZ;
            if (!voxels.contains(new Voxel(voxel.x, voxel.y - 1, voxel.z))) surface += areaXZ;
            if (!voxels.contains(new Voxel(voxel.x, voxel.y + 1, voxel.z))) surface += areaXZ;
            if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z - 1))) surface += areaXY;
            if (!voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z + 1))) surface += areaXY;
        }
        VoxelMetrics metrics = new VoxelMetrics();
        metrics.zSlices.addAll(zSlices);
        metrics.volumeVox = voxels.size();
        metrics.volumeUm3 = voxels.size() * pixelWidth * pixelHeight * pixelDepth;
        metrics.surfaceAreaUm2 = surface;
        metrics.integratedIntensity = integrated;
        metrics.meanIntensity = voxels.isEmpty() ? 0.0 : integrated / voxels.size();
        metrics.maxIntensity = voxels.isEmpty() ? 0.0 : maxIntensity;
        metrics.centroidXUm = voxels.isEmpty() ? 0.0 : sumX / voxels.size();
        metrics.centroidYUm = voxels.isEmpty() ? 0.0 : sumY / voxels.size();
        metrics.centroidZUm = voxels.isEmpty() ? 0.0 : sumZ / voxels.size();
        if (metrics.surfaceAreaUm2 > 0.0 && metrics.volumeUm3 > 0.0) {
            metrics.sphericity = Math.cbrt(Math.PI) * Math.pow(6.0 * metrics.volumeUm3, 2.0 / 3.0) / metrics.surfaceAreaUm2;
        }
        computeFarthestPair(metrics, voxels, pixelWidth, pixelHeight, pixelDepth);
        computePrincipalAxesXY(metrics, xyPoints);
        return metrics;
    }

    private void addFarthestPair(ResultsTable rt, VoxelMetrics metrics, String lengthUnitSuffix) {
        rt.addValue("Farthest_pair_distance_" + lengthUnitSuffix, metrics.farthestPairDistanceUm);
        rt.addValue("End1_x_" + lengthUnitSuffix, metrics.end1XUm);
        rt.addValue("End1_y_" + lengthUnitSuffix, metrics.end1YUm);
        rt.addValue("End1_z_" + lengthUnitSuffix, metrics.end1ZUm);
        rt.addValue("End2_x_" + lengthUnitSuffix, metrics.end2XUm);
        rt.addValue("End2_y_" + lengthUnitSuffix, metrics.end2YUm);
        rt.addValue("End2_z_" + lengthUnitSuffix, metrics.end2ZUm);
    }

    private String lengthUnitSuffix(ImagePlus imp) {
        if (imp == null || imp.getCalibration() == null) return "px";
        String unit = imp.getCalibration().getUnit();
        if (unit == null || unit.trim().isEmpty()) return "px";
        String normalized = unit.trim().replaceAll("[^A-Za-z0-9]+", "_");
        return normalized.isEmpty() ? "px" : normalized;
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
            if (imp.getNChannels() > 1 || imp.getNSlices() > 1 || imp.getNFrames() > 1) {
                imp.setPosition(c, z, t);
            } else if (slice > 0 && slice <= imp.getStackSize()) {
                imp.setSlice(slice);
            }
            if (roi != null) imp.setRoi((Roi) roi.clone());
            else imp.killRoi();
        }
    }

    private void computeFarthestPair(VoxelMetrics metrics, Set<Voxel> voxels,
                                     double pixelWidth, double pixelHeight, double pixelDepth) {
        List<Voxel> boundary = new ArrayList<Voxel>();
        for (Voxel voxel : voxels) {
            boolean surface = !voxels.contains(new Voxel(voxel.x - 1, voxel.y, voxel.z))
                    || !voxels.contains(new Voxel(voxel.x + 1, voxel.y, voxel.z))
                    || !voxels.contains(new Voxel(voxel.x, voxel.y - 1, voxel.z))
                    || !voxels.contains(new Voxel(voxel.x, voxel.y + 1, voxel.z))
                    || !voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z - 1))
                    || !voxels.contains(new Voxel(voxel.x, voxel.y, voxel.z + 1));
            if (surface) boundary.add(voxel);
        }
        double best = -1.0;
        for (int i = 0; i < boundary.size(); i++) {
            Voxel a = boundary.get(i);
            double ax = (a.x + 0.5) * pixelWidth;
            double ay = (a.y + 0.5) * pixelHeight;
            double az = a.z * pixelDepth;
            for (int j = i + 1; j < boundary.size(); j++) {
                Voxel b = boundary.get(j);
                double bx = (b.x + 0.5) * pixelWidth;
                double by = (b.y + 0.5) * pixelHeight;
                double bz = b.z * pixelDepth;
                double dx = ax - bx;
                double dy = ay - by;
                double dz = az - bz;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > best) {
                    best = d;
                    metrics.farthestPairDistanceUm = d;
                    metrics.end1XUm = ax;
                    metrics.end1YUm = ay;
                    metrics.end1ZUm = az;
                    metrics.end2XUm = bx;
                    metrics.end2YUm = by;
                    metrics.end2ZUm = bz;
                }
            }
        }
        if (best < 0.0 && !boundary.isEmpty()) {
            Voxel v = boundary.get(0);
            metrics.end1XUm = metrics.end2XUm = (v.x + 0.5) * pixelWidth;
            metrics.end1YUm = metrics.end2YUm = (v.y + 0.5) * pixelHeight;
            metrics.end1ZUm = metrics.end2ZUm = v.z * pixelDepth;
        }
    }

    private void computePrincipalAxesXY(VoxelMetrics metrics, List<double[]> points) {
        if (points.isEmpty()) return;
        double meanX = 0.0;
        double meanY = 0.0;
        for (double[] p : points) {
            meanX += p[0];
            meanY += p[1];
        }
        meanX /= points.size();
        meanY /= points.size();
        double a = 0.0, b = 0.0, c = 0.0;
        for (double[] p : points) {
            double dx = p[0] - meanX;
            double dy = p[1] - meanY;
            a += dx * dx;
            b += dx * dy;
            c += dy * dy;
        }
        a /= points.size();
        b /= points.size();
        c /= points.size();
        double trace = a + c;
        double detTerm = Math.sqrt(Math.max(0.0, (a - c) * (a - c) + 4.0 * b * b));
        double lambda1 = Math.max(0.0, 0.5 * (trace + detTerm));
        double lambda2 = Math.max(0.0, 0.5 * (trace - detTerm));
        metrics.majorAxisXYUm = 4.0 * Math.sqrt(lambda1);
        metrics.minorAxisXYUm = 4.0 * Math.sqrt(lambda2);
    }

    private boolean needsVoxelMetrics(Options opts, ImagePlus imp) {
        return opts.principalAxesXY || use3DMetrics(opts, imp) || useIntensityMetrics(opts);
    }

    private double sampleIntensity(ImagePlus imp, int x, int y, int z, int c, int t) {
        if (imp == null) return 0.0;
        int safeC = Math.max(1, Math.min(c, Math.max(1, imp.getNChannels())));
        int safeZ = Math.max(1, Math.min(z, Math.max(1, imp.getNSlices())));
        int safeT = Math.max(1, Math.min(t, Math.max(1, imp.getNFrames())));
        int index = imp.getStackIndex(safeC, safeZ, safeT);
        return imp.getStack().getProcessor(index).getPixelValue(x, y);
    }

    private static class GroupKey {
        static final GroupKey NONE = new GroupKey(-1, -1, -1);
        final int c, t, z;

        GroupKey(int c, int t, int z) {
            this.c = c;
            this.t = t;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof GroupKey)) return false;
            GroupKey k = (GroupKey) o;
            return c == k.c && t == k.t && z == k.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(c, t, z);
        }
    }

    private static class Voxel {
        final int x;
        final int y;
        final int z;

        Voxel(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Voxel)) return false;
            Voxel v = (Voxel) o;
            return x == v.x && y == v.y && z == v.z;
        }

        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }

    private static class VoxelMetrics {
        final Set<Integer> zSlices = new TreeSet<Integer>();
        int volumeVox;
        double volumeUm3;
        double surfaceAreaUm2;
        double sphericity;
        double integratedIntensity;
        double meanIntensity;
        double maxIntensity;
        double centroidXUm;
        double centroidYUm;
        double centroidZUm;
        double farthestPairDistanceUm;
        double end1XUm;
        double end1YUm;
        double end1ZUm;
        double end2XUm;
        double end2YUm;
        double end2ZUm;
        double majorAxisXYUm;
        double minorAxisXYUm;
    }
}
