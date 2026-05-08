package io.github.kusumotok.roiexplorer.service.measure;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.awt.Rectangle;
import java.util.*;

public final class XyzObjectProfile implements MeasurementProfile {

    public static final String ID = "xyz_object";

    private final Set<MeasurementColumn> enabled;

    /** All columns enabled (default). */
    public XyzObjectProfile() {
        this.enabled = MeasurementColumn.allEnabled();
    }

    /** Only compute and return the specified columns. */
    public XyzObjectProfile(Set<MeasurementColumn> enabled) {
        this.enabled = (enabled != null && !enabled.isEmpty())
            ? EnumSet.copyOf(enabled) : MeasurementColumn.allEnabled();
    }

    @Override
    public String id() { return ID; }

    @Override
    public String displayName() { return "XYZ Object"; }

    @Override
    public List<ObjectMeasurementResult> measure(MeasurementUnit unit, ImagePlus image) {
        validate(unit, image);

        Calibration cal = image.getCalibration();
        double vw = cal != null && cal.pixelWidth  > 0 ? cal.pixelWidth  : 1.0;
        double vh = cal != null && cal.pixelHeight > 0 ? cal.pixelHeight : 1.0;
        double vd = cal != null && cal.pixelDepth  > 0 ? cal.pixelDepth  : 1.0;

        // Decide what to compute based on enabled columns
        boolean needIntensity = enabled.contains(MeasurementColumn.INTEGRATED_INTENSITY)
            || enabled.contains(MeasurementColumn.MEAN_INTENSITY)
            || enabled.contains(MeasurementColumn.MAX_INTENSITY);
        boolean needCentroid  = enabled.contains(MeasurementColumn.CENTROID);
        boolean needSurface   = enabled.contains(MeasurementColumn.SURFACE_AREA)
            || enabled.contains(MeasurementColumn.SPHERICITY);
        boolean needFeret     = enabled.contains(MeasurementColumn.MAX_FERET3D)
            || enabled.contains(MeasurementColumn.FERET_ENDPOINTS);

        int imageWidth  = image.getWidth();
        int imageHeight = image.getHeight();

        int c = resolveChannel(unit.getRois(), image);
        int t = resolveFrame(unit.getRois(), image);

        Set<Long> voxelSet = new HashSet<>();
        double sumX = 0, sumY = 0, sumZ = 0;
        double sumIntensity = 0;
        double maxIntensity = Double.NEGATIVE_INFINITY;

        int safeC = Math.max(1, Math.min(c, image.getNChannels()));
        int safeT = Math.max(1, Math.min(t, image.getNFrames()));

        for (RoiNode node : unit.getRois()) {
            int z = resolveZ(node, image);
            if (z <= 0 || z > image.getNSlices()) {
                throw new IllegalArgumentException(
                    "ROI '" + node.getName() + "' in unit '" + unit.getName() +
                    "' has no valid Z position.");
            }
            Roi roi = node.getRoi();
            if (roi == null) continue;

            // Only acquire pixel values when intensity columns are enabled
            ImageProcessor ip = needIntensity
                ? image.getStack().getProcessor(image.getStackIndex(safeC, z, safeT))
                : null;

            Rectangle bounds = roi.getBounds();
            for (int py = bounds.y; py < bounds.y + bounds.height; py++) {
                for (int px = bounds.x; px < bounds.x + bounds.width; px++) {
                    if (!roi.contains(px, py)) continue;
                    // voxelSet needed for volume; also for surface/feret if required
                    long key = encodeVoxel(px, py, z, imageWidth, imageHeight);
                    if (!voxelSet.add(key)) continue;
                    if (needIntensity && ip != null) {
                        double value = ip.getPixelValue(px, py);
                        sumIntensity += value;
                        if (value > maxIntensity) maxIntensity = value;
                    }
                    if (needCentroid) {
                        sumX += (px + 0.5) * vw;
                        sumY += (py + 0.5) * vh;
                        sumZ += (z  - 0.5) * vd;
                    }
                }
            }
        }

        long nVox = voxelSet.size();
        if (nVox == 0) {
            throw new IllegalArgumentException(
                "Unit '" + unit.getName() + "' yielded no voxels.");
        }

        double volumeUm3   = nVox * vw * vh * vd;
        double surfaceArea = needSurface
            ? computeSurfaceArea(voxelSet, imageWidth, imageHeight, vw, vh, vd) : 0.0;
        double sphericity  = 0.0;
        if (needSurface && surfaceArea > 0.0) {
            sphericity = Math.cbrt(Math.PI) * Math.pow(6.0 * volumeUm3, 2.0 / 3.0) / surfaceArea;
        }
        FeretResult feret = needFeret
            ? computeFeret(voxelSet, imageWidth, imageHeight, vw, vh, vd)
            : new FeretResult(0, 0, 0, 0, 0, 0, 0);

        ObjectMeasurementResult result = new ObjectMeasurementResult.Builder()
            .spotId(0)
            .unitName(unit.getName())
            .c(c).t(t)
            .volumeVox(nVox)
            .volumeUm3(volumeUm3)
            .surfaceAreaUm2(surfaceArea)
            .sphericity(sphericity)
            .integratedIntensity(sumIntensity)
            .meanIntensity(sumIntensity / nVox)
            .maxIntensity(maxIntensity == Double.NEGATIVE_INFINITY ? 0.0 : maxIntensity)
            .centroidXUm(sumX / nVox)
            .centroidYUm(sumY / nVox)
            .centroidZUm(sumZ / nVox)
            .maxFeret3dUm(feret.distance)
            .maxFeretP1(feret.p1x, feret.p1y, feret.p1z)
            .maxFeretP2(feret.p2x, feret.p2y, feret.p2z)
            .calibrationUnit(resolveCalUnit(image))
            .build();

        return Collections.singletonList(result);
    }

    private void validate(MeasurementUnit unit, ImagePlus image) {
        if (image == null) throw new IllegalArgumentException("Bound image is required.");
        if (image.getNSlices() <= 1)
            throw new IllegalArgumentException("XYZ Object measurement requires a 3D stack.");
        if (unit.isEmpty())
            throw new IllegalArgumentException("Unit '" + unit.getName() + "' has no ROIs.");

        int firstC = -1, firstT = -1;
        for (RoiNode node : unit.getRois()) {
            int c = node.getC();
            int t = node.getT();
            if (c > 0) {
                if (firstC < 0) firstC = c;
                else if (c != firstC) throw new IllegalArgumentException(
                    "Unit '" + unit.getName() + "' has ROIs across different channels (C=" +
                    firstC + " and C=" + c + "). XYZ Object requires a single channel.");
            }
            if (t > 0) {
                if (firstT < 0) firstT = t;
                else if (t != firstT) throw new IllegalArgumentException(
                    "Unit '" + unit.getName() + "' has ROIs across different frames (T=" +
                    firstT + " and T=" + t + "). XYZ Object requires a single frame.");
            }
        }
    }

    private int resolveChannel(List<RoiNode> rois, ImagePlus image) {
        for (RoiNode node : rois) {
            int c = node.getC();
            if (c > 0) return c;
        }
        return image.getC();
    }

    private int resolveFrame(List<RoiNode> rois, ImagePlus image) {
        for (RoiNode node : rois) {
            int t = node.getT();
            if (t > 0) return t;
        }
        return image.getT();
    }

    private int resolveZ(RoiNode node, ImagePlus image) {
        int z = node.getZ();
        if (z > 0) return z;
        Roi roi = node.getRoi();
        if (roi != null && image.getNChannels() == 1 && image.getNFrames() == 1) {
            int pos = roi.getPosition();
            if (pos > 0) return pos;
        }
        return 0;
    }

    private static String resolveCalUnit(ImagePlus image) {
        if (image == null) return "µm";
        Calibration cal = image.getCalibration();
        if (cal == null) return "µm";
        String unit = cal.getUnit();
        return (unit == null || unit.trim().isEmpty()) ? "px" : unit.trim();
    }

    private static long encodeVoxel(int x, int y, int z, int width, int height) {
        return (long) z * width * height + (long) y * width + x;
    }

    private double computeSurfaceArea(Set<Long> voxelSet, int width, int height,
                                      double vw, double vh, double vd) {
        double yzFace = vh * vd;
        double xzFace = vw * vd;
        double xyFace = vw * vh;
        double surface = 0.0;
        for (long key : voxelSet) {
            int z   = (int) (key / ((long) width * height));
            int rem = (int) (key % ((long) width * height));
            int y   = rem / width;
            int x   = rem % width;
            if (!voxelSet.contains(encodeVoxel(x - 1, y,     z,     width, height))) surface += yzFace;
            if (!voxelSet.contains(encodeVoxel(x + 1, y,     z,     width, height))) surface += yzFace;
            if (!voxelSet.contains(encodeVoxel(x,     y - 1, z,     width, height))) surface += xzFace;
            if (!voxelSet.contains(encodeVoxel(x,     y + 1, z,     width, height))) surface += xzFace;
            if (!voxelSet.contains(encodeVoxel(x,     y,     z - 1, width, height))) surface += xyFace;
            if (!voxelSet.contains(encodeVoxel(x,     y,     z + 1, width, height))) surface += xyFace;
        }
        return surface;
    }

    private FeretResult computeFeret(Set<Long> voxelSet, int width, int height,
                                     double vw, double vh, double vd) {
        List<double[]> boundary = new ArrayList<>();
        for (long key : voxelSet) {
            int z   = (int) (key / ((long) width * height));
            int rem = (int) (key % ((long) width * height));
            int y   = rem / width;
            int x   = rem % width;
            boolean onBoundary =
                !voxelSet.contains(encodeVoxel(x - 1, y,     z,     width, height)) ||
                !voxelSet.contains(encodeVoxel(x + 1, y,     z,     width, height)) ||
                !voxelSet.contains(encodeVoxel(x,     y - 1, z,     width, height)) ||
                !voxelSet.contains(encodeVoxel(x,     y + 1, z,     width, height)) ||
                !voxelSet.contains(encodeVoxel(x,     y,     z - 1, width, height)) ||
                !voxelSet.contains(encodeVoxel(x,     y,     z + 1, width, height));
            if (onBoundary) {
                boundary.add(new double[]{(x + 0.5) * vw, (y + 0.5) * vh, (z - 0.5) * vd});
            }
        }

        if (boundary.isEmpty()) return new FeretResult(0, 0, 0, 0, 0, 0, 0);

        double maxDist2 = 0.0;
        double[] bestA = boundary.get(0), bestB = boundary.get(0);
        for (int i = 0; i < boundary.size(); i++) {
            double[] a = boundary.get(i);
            for (int j = i + 1; j < boundary.size(); j++) {
                double[] b = boundary.get(j);
                double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > maxDist2) {
                    maxDist2 = d2;
                    bestA = a;
                    bestB = b;
                }
            }
        }
        return new FeretResult(Math.sqrt(maxDist2),
            bestA[0], bestA[1], bestA[2], bestB[0], bestB[1], bestB[2]);
    }

    private static final class FeretResult {
        final double distance;
        final double p1x, p1y, p1z;
        final double p2x, p2y, p2z;

        FeretResult(double distance,
                    double p1x, double p1y, double p1z,
                    double p2x, double p2y, double p2z) {
            this.distance = distance;
            this.p1x = p1x; this.p1y = p1y; this.p1z = p1z;
            this.p2x = p2x; this.p2y = p2y; this.p2z = p2z;
        }
    }
}
