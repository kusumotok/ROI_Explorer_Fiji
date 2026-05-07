package io.github.kusumotok.roiexplorer.service.measure;

import ij.ImagePlus;

import java.util.List;

public interface MeasurementProfile {

    String id();

    String displayName();

    /**
     * Measures one unit. Returns one result per profile row (XYZ Object = 1 row per unit).
     * Throws IllegalArgumentException for validation failures.
     */
    List<ObjectMeasurementResult> measure(MeasurementUnit unit, ImagePlus image);
}
