package io.github.kusumotok.roiexplorer.service.measure;

import java.util.EnumSet;

public enum MeasurementColumn {
    VOLUME_CAL3         ("Volume (cal³)"),
    VOLUME_VOX          ("Volume (vox)"),
    SURFACE_AREA        ("Surface area"),
    SPHERICITY          ("Sphericity"),
    INTEGRATED_INTENSITY("Integrated intensity"),
    MEAN_INTENSITY      ("Mean intensity"),
    MAX_INTENSITY       ("Max intensity"),
    CENTROID            ("Centroid x/y/z"),
    MAX_FERET3D         ("Max Feret 3D"),
    FERET_ENDPOINTS     ("Feret endpoints (p1/p2)");

    public final String displayName;

    MeasurementColumn(String displayName) {
        this.displayName = displayName;
    }

    public static EnumSet<MeasurementColumn> allEnabled() {
        return EnumSet.allOf(MeasurementColumn.class);
    }
}
