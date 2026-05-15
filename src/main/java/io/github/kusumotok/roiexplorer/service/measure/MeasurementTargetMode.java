package io.github.kusumotok.roiexplorer.service.measure;

public enum MeasurementTargetMode {
    SELECTED_FOLDERS("Selected folders"),
    ROOT_CHILDREN_ONLY("Root children only"),
    ROI_CONTAINING_FOLDERS("ROI-containing folders"),
    LEAF_FOLDERS("Leaf folders"),
    ALL_DESCENDANT_FOLDERS("All descendant folders");

    private final String label;

    MeasurementTargetMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
