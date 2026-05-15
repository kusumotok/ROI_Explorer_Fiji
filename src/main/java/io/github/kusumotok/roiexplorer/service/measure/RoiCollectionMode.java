package io.github.kusumotok.roiexplorer.service.measure;

public enum RoiCollectionMode {
    DIRECT("Direct"),
    FLATTEN("Flatten");

    private final String label;

    RoiCollectionMode(String label) {
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
