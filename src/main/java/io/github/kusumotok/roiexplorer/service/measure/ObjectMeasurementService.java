package io.github.kusumotok.roiexplorer.service.measure;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.model.ExplorerNode;
import io.github.kusumotok.roiexplorer.model.FolderNode;
import io.github.kusumotok.roiexplorer.model.RoiNode;
import io.github.kusumotok.roiexplorer.model.ZipNode;
import io.github.kusumotok.roiexplorer.service.SelectionResolver;

import java.util.ArrayList;
import java.util.List;

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

        List<MeasurementUnit> units = collectUnits(selected, root);
        List<ObjectMeasurementResult> all = new ArrayList<>();
        int spotId = 1;
        for (MeasurementUnit unit : units) {
            int unitSpotId = spotId++;
            for (ObjectMeasurementResult r : profile.measure(unit, image)) {
                all.add(withSpotId(r, unitSpotId));
            }
        }
        return all;
    }

    private List<MeasurementUnit> collectUnits(List<ExplorerNode> selected, ExplorerNode root) {
        List<ExplorerNode> unitNodes;
        if (selected == null || selected.isEmpty()) {
            unitNodes = new ArrayList<>();
            if (root != null) {
                for (ExplorerNode child : root.getChildren()) {
                    if (child instanceof FolderNode || child instanceof ZipNode) {
                        unitNodes.add(child);
                    }
                }
            }
        } else {
            unitNodes = selected;
        }

        List<MeasurementUnit> units = new ArrayList<>();
        for (ExplorerNode node : unitNodes) {
            List<RoiNode> rois = resolver.roiNodesUnder(node);
            units.add(new MeasurementUnit(node.getName(), node, rois));
        }
        return units;
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
