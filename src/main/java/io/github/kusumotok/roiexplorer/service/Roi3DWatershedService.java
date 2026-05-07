package io.github.kusumotok.roiexplorer.service;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.measure.Calibration;
import io.github.kusumotok.roiexplorer.model.ExplorerNode;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import java.awt.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.List;

/**
 * Minimal 3D watershed service for ROI Explorer.
 *
 * This initial implementation intentionally keeps the backend close to the
 * spot quantifier model:
 * - domain is the selected ROI stack converted to a 3D mask
 * - seeds are threshold-connected components inside that domain
 * - watershed itself uses MorphoLibJ at runtime when available
 *
 * Preview/manual seeds are deferred to the dedicated workflow UI. For now this
 * service executes threshold-seed watershed directly and returns per-object ROI
 * folders for saving.
 */
public class Roi3DWatershedService {

    public static final class Request {
        private final ImagePlus image;
        private final List<RoiNode> roiNodes;
        private final int seedThreshold;
        private final int connectivity;
        private final double minSeedVolumeUm3;
        private final int cPosition;
        private final int tPosition;

        public Request(ImagePlus image, List<RoiNode> roiNodes, int seedThreshold, int connectivity,
                       double minSeedVolumeUm3, int cPosition, int tPosition) {
            this.image = image;
            this.roiNodes = roiNodes;
            this.seedThreshold = seedThreshold;
            this.connectivity = connectivity;
            this.minSeedVolumeUm3 = minSeedVolumeUm3;
            this.cPosition = cPosition;
            this.tPosition = tPosition;
        }
    }

    public static final class Result {
        private final ImagePlus labelImage;
        private final ImagePlus thresholdMaskImage;
        private final Map<Integer, List<Roi>> roisByLabel;
        private final Map<Integer, List<Roi>> seedRoisByLabel;
        private final List<Roi> thresholdMaskRois;
        private final int seedCount;
        private final int cPosition;
        private final int tPosition;

        public Result(ImagePlus labelImage, ImagePlus thresholdMaskImage, Map<Integer, List<Roi>> roisByLabel,
                      Map<Integer, List<Roi>> seedRoisByLabel, List<Roi> thresholdMaskRois,
                      int seedCount, int cPosition, int tPosition) {
            this.labelImage = labelImage;
            this.thresholdMaskImage = thresholdMaskImage;
            this.roisByLabel = roisByLabel;
            this.seedRoisByLabel = seedRoisByLabel;
            this.thresholdMaskRois = thresholdMaskRois;
            this.seedCount = seedCount;
            this.cPosition = cPosition;
            this.tPosition = tPosition;
        }

        public ImagePlus getLabelImage() {
            return labelImage;
        }

        public ImagePlus getThresholdMaskImage() {
            return thresholdMaskImage;
        }

        public Map<Integer, List<Roi>> getRoisByLabel() {
            return roisByLabel;
        }

        public Map<Integer, List<Roi>> getSeedRoisByLabel() {
            return seedRoisByLabel;
        }

        public List<Roi> getThresholdMaskRois() {
            return thresholdMaskRois;
        }

        public int getSeedCount() {
            return seedCount;
        }

        public int getObjectCount() {
            return roisByLabel.size();
        }

        public boolean canRunWatershed() {
            return seedCount >= 2 && !roisByLabel.isEmpty();
        }

        public int getCPosition() {
            return cPosition;
        }

        public int getTPosition() {
            return tPosition;
        }
    }

    public Result runThresholdSeeds(Request request) {
        return buildThresholdResult(request, true);
    }

    public Result previewThresholdSeeds(Request request) {
        return buildThresholdResult(request, false);
    }

    private Result buildThresholdResult(Request request, boolean computeWatershed) {
        if (request == null) throw new IllegalArgumentException("Request is required.");
        if (request.image == null) throw new IllegalArgumentException("A bound image is required.");
        if (request.image.getNSlices() <= 1) throw new IllegalArgumentException("3D watershed requires a stack with multiple Z slices.");
        if (request.roiNodes == null || request.roiNodes.isEmpty()) throw new IllegalArgumentException("At least one ROI is required.");
        if (request.connectivity != 6 && request.connectivity != 18 && request.connectivity != 26) {
            throw new IllegalArgumentException("Connectivity must be 6, 18, or 26.");
        }
        ensureMorphoLibJAvailable();

        SelectionVolume volume = buildSelectionVolume(request.image, request.roiNodes, request.cPosition, request.tPosition);
        SeedBuild seedBuild = buildThresholdSeeds(request.image, volume.imageStack, volume,
                request.seedThreshold, request.connectivity, request.minSeedVolumeUm3);
        ImagePlus seedLabelImage = new ImagePlus(request.image.getShortTitle() + "-roi-explorer-3d-seeds", seedBuild.seedLabels);
        ImagePlus thresholdMaskImage = new ImagePlus(request.image.getShortTitle() + "-roi-explorer-3d-threshold-mask", seedBuild.thresholdMask);
        Map<Integer, List<Roi>> seedRoisByLabel = exportRoisByLabel(seedLabelImage, volume.cPosition, volume.tPosition, request.image);
        List<Roi> thresholdMaskRois = exportThresholdMaskRois(seedBuild.thresholdMask, volume.cPosition, volume.tPosition, request.image);

        if (!computeWatershed || seedBuild.seedCount < 2) {
            return new Result(null, thresholdMaskImage, Collections.<Integer, List<Roi>>emptyMap(), seedRoisByLabel, thresholdMaskRois,
                    seedBuild.seedCount, volume.cPosition, volume.tPosition);
        }

        ImageStack inverted = invertIntensity(volume.imageStack);
        ImageStack labelStack = computeWatershed(inverted, seedBuild.seedLabels, volume.domainMask, request.connectivity);
        ImagePlus labelImage = new ImagePlus(request.image.getShortTitle() + "-roi-explorer-3d-watershed", labelStack);
        Map<Integer, List<Roi>> roisByLabel = exportRoisByLabel(labelImage, volume.cPosition, volume.tPosition, request.image);
        if (roisByLabel.size() < 2) {
            return new Result(labelImage, thresholdMaskImage, roisByLabel, seedRoisByLabel, thresholdMaskRois,
                    seedBuild.seedCount, volume.cPosition, volume.tPosition);
        }
        return new Result(labelImage, thresholdMaskImage, roisByLabel, seedRoisByLabel, thresholdMaskRois,
                seedBuild.seedCount, volume.cPosition, volume.tPosition);
    }

    private static void ensureMorphoLibJAvailable() {
        try {
            Class.forName("inra.ijpb.watershed.Watershed");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("3D Watershed requires MorphoLibJ (inra.ijpb.watershed.Watershed).");
        }
    }

    private SelectionVolume buildSelectionVolume(ImagePlus image, List<RoiNode> roiNodes, int requestedC, int requestedT) {
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getNSlices();
        ImageStack domainStack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            domainStack.addSlice(new ByteProcessor(width, height));
        }

        ExplorerConstraints constraints = validateRoiNodes(roiNodes, requestedC, requestedT);
        for (RoiNode node : roiNodes) {
            Roi roi = node.getRoi();
            if (roi == null) continue;
            int z = resolveZPosition(roi, image);
            if (z < 1 || z > depth) {
                throw new IllegalArgumentException("ROI " + node.getName() + " has an invalid Z position for the bound image.");
            }
            ByteProcessor bp = (ByteProcessor) domainStack.getProcessor(z);
            paintRoi(bp, roi);
        }
        ImageStack imageStack = extractVolume(image, constraints.cPosition, constraints.tPosition);
        return new SelectionVolume(domainStack, imageStack, constraints.cPosition, constraints.tPosition);
    }

    private ExplorerConstraints validateRoiNodes(List<RoiNode> roiNodes, int requestedC, int requestedT) {
        ExplorerConstraints constraints = null;
        for (RoiNode node : roiNodes) {
            Roi roi = node.getRoi();
            if (roi == null) {
                throw new IllegalArgumentException("Could not load ROI: " + node.getName());
            }
            ExplorerNode parent = node.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("ROI " + node.getName() + " has no parent.");
            }
            if (constraints == null) {
                constraints = new ExplorerConstraints(parent, requestedC, requestedT);
            } else {
                if (constraints.parent != parent) {
                    throw new IllegalArgumentException("All ROIs for 3D watershed must belong to the same parent folder.");
                }
            }
        }
        if (constraints == null) {
            throw new IllegalArgumentException("No valid ROIs were found.");
        }
        return constraints;
    }

    private SeedBuild buildThresholdSeeds(ImagePlus image, ImageStack imageStack, SelectionVolume volume,
                                          int seedThreshold, int connectivity, double minSeedVolumeUm3) {
        int width = imageStack.getWidth();
        int height = imageStack.getHeight();
        int depth = imageStack.getSize();
        boolean[] seedMask = new boolean[width * height * depth];

        for (int z = 1; z <= depth; z++) {
            ImageProcessor domainIp = volume.domainMask.getProcessor(z);
            ImageProcessor imageIp = imageStack.getProcessor(z);
            for (int y = 0; y < height; y++) {
                int offset = (z - 1) * width * height + y * width;
                for (int x = 0; x < width; x++) {
                    if (domainIp.get(x, y) == 0) continue;
                    if (imageIp.getf(x, y) >= seedThreshold) {
                        seedMask[offset + x] = true;
                    }
                }
            }
        }

        ConnectedComponents components = labelConnectedComponents(seedMask, width, height, depth, connectivity);
        ImageStack seedLabels = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            FloatProcessor fp = new FloatProcessor(width, height);
            seedLabels.addSlice(fp);
        }
        double voxelVolumeUm3 = resolveVoxelVolume(image);
        int[] remapped = remapLabelsByVolume(components.sizes, voxelVolumeUm3, Math.max(0.0, minSeedVolumeUm3));
        for (int idx = 0; idx < components.labels.length; idx++) {
            int label = remapped[components.labels[idx]];
            if (label <= 0) continue;
            int z = idx / (width * height);
            int rem = idx % (width * height);
            int y = rem / width;
            int x = rem % width;
            seedLabels.getProcessor(z + 1).setf(x, y, label);
        }
        ImageStack thresholdMask = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            ByteProcessor bp = new ByteProcessor(width, height);
            byte[] pixels = (byte[]) bp.getPixels();
            int planeOffset = z * width * height;
            for (int i = 0; i < width * height; i++) {
                int rawLabel = components.labels[planeOffset + i];
                if (rawLabel > 0 && remapped[rawLabel] > 0) pixels[i] = (byte) 255;
            }
            thresholdMask.addSlice(bp);
        }
        int keptCount = 0;
        for (int i = 1; i < remapped.length; i++) {
            keptCount = Math.max(keptCount, remapped[i]);
        }
        return new SeedBuild(seedLabels, thresholdMask, keptCount);
    }

    private ConnectedComponents labelConnectedComponents(boolean[] mask, int width, int height, int depth, int connectivity) {
        int[] labels = new int[mask.length];
        int[][] neighbors = neighborOffsets(connectivity);
        int nextLabel = 0;
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        for (int idx = 0; idx < mask.length; idx++) {
            if (!mask[idx] || labels[idx] != 0) continue;
            nextLabel++;
            labels[idx] = nextLabel;
            queue.add(idx);
            while (!queue.isEmpty()) {
                int cur = queue.removeFirst();
                int z = cur / (width * height);
                int rem = cur % (width * height);
                int y = rem / width;
                int x = rem % width;
                for (int[] delta : neighbors) {
                    int nx = x + delta[0];
                    int ny = y + delta[1];
                    int nz = z + delta[2];
                    if (nx < 0 || ny < 0 || nz < 0 || nx >= width || ny >= height || nz >= depth) continue;
                    int nIdx = nz * width * height + ny * width + nx;
                    if (!mask[nIdx] || labels[nIdx] != 0) continue;
                    labels[nIdx] = nextLabel;
                    queue.addLast(nIdx);
                }
            }
        }
        int[] sizes = new int[nextLabel + 1];
        for (int label : labels) {
            if (label > 0) sizes[label]++;
        }
        return new ConnectedComponents(labels, sizes, nextLabel);
    }

    private int[] remapLabelsByVolume(int[] sizes, double voxelVolumeUm3, double minSeedVolumeUm3) {
        int[] remap = new int[sizes.length];
        int next = 0;
        for (int label = 1; label < sizes.length; label++) {
            if (minSeedVolumeUm3 > 0.0) {
                double seedVolumeUm3 = sizes[label] * voxelVolumeUm3;
                if (seedVolumeUm3 < minSeedVolumeUm3) continue;
            }
            next++;
            remap[label] = next;
        }
        return remap;
    }

    private double resolveVoxelVolume(ImagePlus image) {
        Calibration calibration = image.getCalibration();
        if (calibration == null) return 1.0;
        double pixelWidth = calibration.pixelWidth > 0.0 ? calibration.pixelWidth : 1.0;
        double pixelHeight = calibration.pixelHeight > 0.0 ? calibration.pixelHeight : 1.0;
        double pixelDepth = calibration.pixelDepth > 0.0 ? calibration.pixelDepth : 1.0;
        return pixelWidth * pixelHeight * pixelDepth;
    }

    private static int[][] neighborOffsets(int connectivity) {
        ArrayList<int[]> offsets = new ArrayList<int[]>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int manhattan = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (connectivity == 6 && manhattan != 1) continue;
                    if (connectivity == 18 && manhattan > 2) continue;
                    offsets.add(new int[]{dx, dy, dz});
                }
            }
        }
        return offsets.toArray(new int[0][]);
    }

    private ImageStack invertIntensity(ImageStack inputStack) {
        int width = inputStack.getWidth();
        int height = inputStack.getHeight();
        int depth = inputStack.getSize();
        float globalMin = Float.MAX_VALUE;
        float globalMax = -Float.MAX_VALUE;
        for (int z = 1; z <= depth; z++) {
            ImageProcessor ip = inputStack.getProcessor(z);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    float v = ip.getf(x, y);
                    if (v < globalMin) globalMin = v;
                    if (v > globalMax) globalMax = v;
                }
            }
        }
        float sum = globalMin + globalMax;
        ImageStack inverted = new ImageStack(width, height);
        for (int z = 1; z <= depth; z++) {
            ImageProcessor ip = inputStack.getProcessor(z);
            FloatProcessor fp = new FloatProcessor(width, height);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    fp.setf(x, y, sum - ip.getf(x, y));
                }
            }
            inverted.addSlice(fp);
        }
        return inverted;
    }

    private ImageStack extractVolume(ImagePlus image, int cPosition, int tPosition) {
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = image.getNSlices();
        int c = Math.max(1, Math.min(image.getNChannels(), cPosition));
        int t = Math.max(1, Math.min(image.getNFrames(), tPosition));
        ImageStack extracted = new ImageStack(width, height);
        for (int z = 1; z <= depth; z++) {
            int stackIndex = image.getStackIndex(c, z, t);
            extracted.addSlice(image.getStack().getProcessor(stackIndex).duplicate());
        }
        return extracted;
    }

    private ImageStack computeWatershed(ImageStack inverted, ImageStack seedLabels, ImageStack domainMask, int connectivity) {
        try {
            Class<?> watershedClass = Class.forName("inra.ijpb.watershed.Watershed");
            Method method = watershedClass.getMethod("computeWatershed",
                    ImageStack.class, ImageStack.class, ImageStack.class, int.class, boolean.class);
            return (ImageStack) method.invoke(null, inverted, seedLabels, domainMask, connectivity, false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke MorphoLibJ watershed backend.", e);
        }
    }

    private Map<Integer, List<Roi>> exportRoisByLabel(ImagePlus labelImage, int cPosition, int tPosition, ImagePlus sourceImage) {
        ImageStack stack = labelImage.getStack();
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();
        int depth = labelImage.getNSlices();
        TreeSet<Integer> labels = new TreeSet<Integer>();
        for (int z = 1; z <= depth; z++) {
            ImageProcessor ip = stack.getProcessor(z);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int v = (int) Math.round(ip.getPixelValue(x, y));
                    if (v > 0) labels.add(v);
                }
            }
        }
        LinkedHashMap<Integer, List<Roi>> out = new LinkedHashMap<Integer, List<Roi>>();
        int nChannels = Math.max(1, sourceImage.getNChannels());
        int nFrames = Math.max(1, sourceImage.getNFrames());
        int channel = Math.max(1, Math.min(nChannels, cPosition <= 0 ? sourceImage.getC() : cPosition));
        int frame = Math.max(1, Math.min(nFrames, tPosition <= 0 ? sourceImage.getT() : tPosition));
        for (int label : labels) {
            ArrayList<Roi> rois = new ArrayList<Roi>();
            for (int z = 1; z <= depth; z++) {
                ImageProcessor ip = stack.getProcessor(z);
                ByteProcessor bp = new ByteProcessor(width, height);
                byte[] pixels = (byte[]) bp.getPixels();
                boolean hasPixel = false;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        if ((int) Math.round(ip.getPixelValue(x, y)) == label) {
                            pixels[y * width + x] = (byte) 255;
                            hasPixel = true;
                        }
                    }
                }
                if (!hasPixel) continue;
                bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
                Roi roi = ThresholdToSelection.run(new ImagePlus("", bp));
                if (roi == null) continue;
                if (sourceImage.getNChannels() > 1 || sourceImage.getNFrames() > 1) {
                    roi.setPosition(channel, z, frame);
                } else {
                    roi.setPosition(z);
                }
                roi.setName(String.format("obj-%03d-z%03d", label, z));
                rois.add(roi);
            }
            if (!rois.isEmpty()) out.put(label, rois);
        }
        return out;
    }

    private List<Roi> exportThresholdMaskRois(ImageStack thresholdMask, int cPosition, int tPosition, ImagePlus sourceImage) {
        ArrayList<Roi> rois = new ArrayList<Roi>();
        int depth = thresholdMask.getSize();
        int channel = Math.max(1, Math.min(Math.max(1, sourceImage.getNChannels()), cPosition <= 0 ? sourceImage.getC() : cPosition));
        int frame = Math.max(1, Math.min(Math.max(1, sourceImage.getNFrames()), tPosition <= 0 ? sourceImage.getT() : tPosition));
        for (int z = 1; z <= depth; z++) {
            ByteProcessor bp = (ByteProcessor) thresholdMask.getProcessor(z);
            bp.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
            Roi roi = ThresholdToSelection.run(new ImagePlus("", bp));
            if (roi == null) continue;
            if (sourceImage.getNChannels() > 1 || sourceImage.getNFrames() > 1) {
                roi.setPosition(channel, z, frame);
            } else {
                roi.setPosition(z);
            }
            roi.setName(String.format("threshold-z%03d", z));
            rois.add(roi);
        }
        return rois;
    }

    private static void paintRoi(ByteProcessor target, Roi roi) {
        Rectangle bounds = roi.getBounds();
        ImageProcessor mask = roi.getMask();
        if (mask == null) {
            for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
                for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                    if (roi.contains(x, y)) {
                        target.set(x, y, 255);
                    }
                }
            }
            return;
        }
        for (int y = 0; y < bounds.height; y++) {
            for (int x = 0; x < bounds.width; x++) {
                if (mask.get(x, y) != 0) {
                    target.set(bounds.x + x, bounds.y + y, 255);
                }
            }
        }
    }

    private static int resolveZPosition(Roi roi, ImagePlus image) {
        int z = roi.getZPosition();
        if (z > 0) return z;
        if (image.getNChannels() == 1 && image.getNFrames() == 1 && roi.getPosition() > 0) {
            return roi.getPosition();
        }
        return 0;
    }

    private static final class ExplorerConstraints {
        private final Object parent;
        private final int cPosition;
        private final int tPosition;

        private ExplorerConstraints(Object parent, int cPosition, int tPosition) {
            this.parent = parent;
            this.cPosition = cPosition;
            this.tPosition = tPosition;
        }
    }

    private static final class SelectionVolume {
        private final ImageStack domainMask;
        private final ImageStack imageStack;
        private final int cPosition;
        private final int tPosition;

        private SelectionVolume(ImageStack domainMask, ImageStack imageStack, int cPosition, int tPosition) {
            this.domainMask = domainMask;
            this.imageStack = imageStack;
            this.cPosition = cPosition;
            this.tPosition = tPosition;
        }
    }

    private static final class SeedBuild {
        private final ImageStack seedLabels;
        private final ImageStack thresholdMask;
        private final int seedCount;

        private SeedBuild(ImageStack seedLabels, ImageStack thresholdMask, int seedCount) {
            this.seedLabels = seedLabels;
            this.thresholdMask = thresholdMask;
            this.seedCount = seedCount;
        }
    }

    private static final class ConnectedComponents {
        private final int[] labels;
        private final int[] sizes;
        private final int count;

        private ConnectedComponents(int[] labels, int[] sizes, int count) {
            this.labels = labels;
            this.sizes = sizes;
            this.count = count;
        }
    }
}
