package io.github.kusumotok.roiexplorer.service.measure;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.model.ExplorerNode;
import io.github.kusumotok.roiexplorer.model.FolderNode;
import io.github.kusumotok.roiexplorer.model.RoiNode;
import io.github.kusumotok.roiexplorer.model.ZipNode;
import io.github.kusumotok.roiexplorer.service.SelectionResolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class ObjectMeasurementService {

    private static final SelectionResolver resolver = new SelectionResolver();

    /**
     * Collects units from selection (or root's direct folder/zip children when selection is empty),
     * measures each unit with the given profile, and assigns sequential spot_id across all results.
     */
    public List<ObjectMeasurementResult> measure(
            List<ExplorerNode> selected,
            ExplorerNode root,
            MeasurementProfile profile,
            ImagePlus image) {

        MeasurementTargetMode targetMode = selected == null || selected.isEmpty()
                ? MeasurementTargetMode.ROOT_CHILDREN_ONLY
                : MeasurementTargetMode.SELECTED_FOLDERS;
        return measure(selected, root, profile, image, targetMode, RoiCollectionMode.FLATTEN);
    }

    public List<ObjectMeasurementResult> measure(
            List<ExplorerNode> selected,
            ExplorerNode root,
            MeasurementProfile profile,
            ImagePlus image,
            MeasurementTargetMode targetMode,
            RoiCollectionMode collectionMode) {
        return measure(selected, root, profile, image, targetMode, collectionMode, null);
    }

    public List<ObjectMeasurementResult> measure(
            List<ExplorerNode> selected,
            ExplorerNode root,
            MeasurementProfile profile,
            ImagePlus image,
            MeasurementTargetMode targetMode,
            RoiCollectionMode collectionMode,
            Consumer<String> progress) {

        report(progress, "Collecting measurement folders...");
        List<MeasurementUnit> units = collectUnits(selected, root, targetMode, collectionMode);
        report(progress, "Measuring " + units.size() + " folder(s)...");
        List<ObjectMeasurementResult> all = new ArrayList<>();
        int spotId = 1;
        int index = 1;
        for (MeasurementUnit unit : units) {
            report(progress, "Measuring " + unit.getName() + " (" + index + "/" + units.size()
                    + ", " + unit.getRois().size() + " ROI files)...");
            int unitSpotId = spotId++;
            for (ObjectMeasurementResult r : profile.measure(unit, image)) {
                all.add(withSpotId(r, unitSpotId));
            }
            index++;
        }
        report(progress, "Measurement calculations completed: " + all.size() + " row(s).");
        return all;
    }

    private static void report(Consumer<String> progress, String message) {
        if (progress != null) progress.accept(message);
    }

    private List<MeasurementUnit> collectUnits(List<ExplorerNode> selected, ExplorerNode root,
                                               MeasurementTargetMode targetMode,
                                               RoiCollectionMode collectionMode) {
        MeasurementTargetMode mode = targetMode != null ? targetMode : MeasurementTargetMode.SELECTED_FOLDERS;
        RoiCollectionMode roiMode = collectionMode != null ? collectionMode : RoiCollectionMode.FLATTEN;
        if (mode == MeasurementTargetMode.ROOT_CHILDREN_ONLY) roiMode = RoiCollectionMode.FLATTEN;

        List<ExplorerNode> unitNodes = collectTargetNodes(selected, root, mode);
        List<MeasurementUnit> units = new ArrayList<>();
        for (ExplorerNode node : unitNodes) {
            List<RoiNode> rois = roiMode == RoiCollectionMode.DIRECT ? directRoiNodes(node) : resolver.roiNodesUnder(node);
            if (!rois.isEmpty()) units.add(new MeasurementUnit(unitName(root, node), node, rois));
        }
        return units;
    }

    private List<ExplorerNode> collectTargetNodes(List<ExplorerNode> selected, ExplorerNode root,
                                                  MeasurementTargetMode targetMode) {
        List<ExplorerNode> bases = selected != null && !selected.isEmpty()
                ? containersOnly(selected)
                : rootChildren(root);
        LinkedHashSet<ExplorerNode> out = new LinkedHashSet<ExplorerNode>();
        switch (targetMode) {
            case SELECTED_FOLDERS:
                out.addAll(bases);
                break;
            case ROOT_CHILDREN_ONLY:
                out.addAll(rootChildren(root));
                break;
            case ALL_DESCENDANT_FOLDERS:
                for (ExplorerNode base : bases) collectDescendantContainers(base, out);
                break;
            case ROI_CONTAINING_FOLDERS:
                for (ExplorerNode base : bases) collectRoiContainingContainers(base, out);
                break;
            case LEAF_FOLDERS:
                for (ExplorerNode base : bases) collectLeafContainers(base, out);
                break;
            default:
                out.addAll(bases);
                break;
        }
        return new ArrayList<ExplorerNode>(out);
    }

    private static List<ExplorerNode> containersOnly(List<ExplorerNode> nodes) {
        List<ExplorerNode> out = new ArrayList<ExplorerNode>();
        if (nodes == null) return out;
        for (ExplorerNode node : nodes) {
            if (isContainer(node)) out.add(node);
        }
        return out;
    }

    private static List<ExplorerNode> rootChildren(ExplorerNode root) {
        List<ExplorerNode> out = new ArrayList<ExplorerNode>();
        if (root == null) return out;
        for (ExplorerNode child : root.getChildren()) {
            if (isContainer(child)) out.add(child);
        }
        return out;
    }

    private static void collectDescendantContainers(ExplorerNode node, Set<ExplorerNode> out) {
        if (!isContainer(node)) return;
        out.add(node);
        for (ExplorerNode child : node.getChildren()) collectDescendantContainers(child, out);
    }

    private static void collectRoiContainingContainers(ExplorerNode node, Set<ExplorerNode> out) {
        if (!isContainer(node)) return;
        if (!directRoiNodes(node).isEmpty()) out.add(node);
        for (ExplorerNode child : node.getChildren()) collectRoiContainingContainers(child, out);
    }

    private static void collectLeafContainers(ExplorerNode node, Set<ExplorerNode> out) {
        if (!isContainer(node)) return;
        boolean hasContainerChild = false;
        for (ExplorerNode child : node.getChildren()) {
            if (isContainer(child)) {
                hasContainerChild = true;
                collectLeafContainers(child, out);
            }
        }
        if (!hasContainerChild) out.add(node);
    }

    private static boolean isContainer(ExplorerNode node) {
        return node instanceof FolderNode || node instanceof ZipNode;
    }

    private static List<RoiNode> directRoiNodes(ExplorerNode node) {
        List<RoiNode> out = new ArrayList<RoiNode>();
        if (node == null) return out;
        for (ExplorerNode child : node.getChildren()) {
            if (child instanceof RoiNode) out.add((RoiNode) child);
        }
        return out;
    }

    private static String unitName(ExplorerNode root, ExplorerNode node) {
        Path rootPath = root != null ? root.getPath() : null;
        Path nodePath = node != null ? node.getPath() : null;
        if (rootPath != null && nodePath != null) {
            try {
                return rootPath.relativize(nodePath).toString();
            } catch (IllegalArgumentException ignored) {
                // Fall back to node name when paths are not compatible.
            }
        }
        return node != null ? node.getName() : "";
    }

    private static ObjectMeasurementResult withSpotId(ObjectMeasurementResult r, int spotId) {
        return new ObjectMeasurementResult.Builder()
            .spotId(spotId)
            .unitName(r.unitName)
            .c(r.c).t(r.t)
            .tFrom(r.tFrom).tTo(r.tTo)
            .volumeVox(r.volumeVox)
            .volumeUm3(r.volumeUm3)
            .volumeFromVox(r.volumeFromVox)
            .volumeToVox(r.volumeToVox)
            .volumeFromUm3(r.volumeFromUm3)
            .volumeToUm3(r.volumeToUm3)
            .deltaVolumeUm3(r.deltaVolumeUm3)
            .surfaceAreaUm2(r.surfaceAreaUm2)
            .sphericity(r.sphericity)
            .integratedIntensity(r.integratedIntensity)
            .meanIntensity(r.meanIntensity)
            .maxIntensity(r.maxIntensity)
            .centroidXUm(r.centroidXUm)
            .centroidYUm(r.centroidYUm)
            .centroidZUm(r.centroidZUm)
            .centroidFrom(r.centroidFromXUm, r.centroidFromYUm, r.centroidFromZUm)
            .centroidTo(r.centroidToXUm, r.centroidToYUm, r.centroidToZUm)
            .displacementUm(r.displacementUm)
            .interval(r.interval)
            .velocityUmPerFrame(r.velocityUmPerFrame)
            .maxFeret3dUm(r.maxFeret3dUm)
            .maxFeretP1(r.maxFeretP1XUm, r.maxFeretP1YUm, r.maxFeretP1ZUm)
            .maxFeretP2(r.maxFeretP2XUm, r.maxFeretP2YUm, r.maxFeretP2ZUm)
            .calibrationUnit(r.calibrationUnit)
            .build();
    }
}
