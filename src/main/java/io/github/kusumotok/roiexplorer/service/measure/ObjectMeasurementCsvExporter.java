package io.github.kusumotok.roiexplorer.service.measure;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public final class ObjectMeasurementCsvExporter {

    /** Writes all columns (default behaviour). */
    public void write(List<ObjectMeasurementResult> results, Path outputPath) throws IOException {
        write(results, outputPath, MeasurementColumn.allEnabled());
    }

    /** Writes only the enabled columns. Metadata columns (spot_id, unit_name, c, t) are always included. */
    public void write(List<ObjectMeasurementResult> results, Path outputPath,
                      Set<MeasurementColumn> enabled) throws IOException {
        String unit = results.isEmpty() ? "µm" : results.get(0).calibrationUnit;
        List<ColumnSpec> specs = buildSpecs(unit, enabled, hasTimeComparisonRows(results));

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath.toFile()), StandardCharsets.UTF_8)))) {

            // Header
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < specs.size(); i++) {
                if (i > 0) header.append(',');
                header.append(specs.get(i).name);
            }
            pw.println(header);

            // Rows
            for (ObjectMeasurementResult r : results) {
                StringBuilder row = new StringBuilder();
                for (int i = 0; i < specs.size(); i++) {
                    if (i > 0) row.append(',');
                    row.append(specs.get(i).format(r));
                }
                pw.println(row);
            }
        }
    }

    // ── Column spec building ───────────────────────────────────────────

    private static List<ColumnSpec> buildSpecs(String rawUnit, Set<MeasurementColumn> enabled,
                                               boolean includeTimeComparison) {
        String u  = normalizeUnit(rawUnit);
        String u2 = u + "2";
        String u3 = u + "3";

        List<ColumnSpec> s = new ArrayList<>();

        // Metadata — always included
        s.add(fixed("spot_id",   r -> String.valueOf(r.spotId)));
        s.add(fixed("unit_name", r -> csvCell(r.unitName)));
        s.add(fixed("c",         r -> String.valueOf(r.c)));
        s.add(fixed("t",         r -> String.valueOf(r.t)));
        if (includeTimeComparison) {
            s.add(fixed("t_from", r -> String.valueOf(r.tFrom)));
            s.add(fixed("t_to",   r -> String.valueOf(r.tTo)));
        }

        // Toggleable measurement columns
        if (enabled.contains(MeasurementColumn.VOLUME_CAL3))
            s.add(fixed("volume_" + u3, r -> fmt6(r.volumeUm3)));
        if (enabled.contains(MeasurementColumn.VOLUME_VOX))
            s.add(fixed("volume_vox",   r -> String.valueOf(r.volumeVox)));
        if (includeTimeComparison && enabled.contains(MeasurementColumn.VOLUME_CAL3)) {
            s.add(fixed("volume_from_" + u3, r -> fmt6(r.volumeFromUm3)));
            s.add(fixed("volume_to_" + u3, r -> fmt6(r.volumeToUm3)));
            s.add(fixed("delta_volume_" + u3, r -> fmt6(r.deltaVolumeUm3)));
        }
        if (includeTimeComparison && enabled.contains(MeasurementColumn.VOLUME_VOX)) {
            s.add(fixed("volume_from_vox", r -> String.valueOf(r.volumeFromVox)));
            s.add(fixed("volume_to_vox", r -> String.valueOf(r.volumeToVox)));
        }
        if (enabled.contains(MeasurementColumn.SURFACE_AREA))
            s.add(fixed("surface_area_" + u2, r -> fmt6(r.surfaceAreaUm2)));
        if (enabled.contains(MeasurementColumn.SPHERICITY))
            s.add(fixed("sphericity",   r -> fmt6(r.sphericity)));
        if (enabled.contains(MeasurementColumn.INTEGRATED_INTENSITY))
            s.add(fixed("integrated_intensity", r -> fmt2(r.integratedIntensity)));
        if (enabled.contains(MeasurementColumn.MEAN_INTENSITY))
            s.add(fixed("mean_intensity",       r -> fmt4(r.meanIntensity)));
        if (enabled.contains(MeasurementColumn.MAX_INTENSITY))
            s.add(fixed("max_intensity",        r -> fmt2(r.maxIntensity)));
        if (enabled.contains(MeasurementColumn.CENTROID)) {
            s.add(fixed("centroid_x_" + u, r -> fmt4(r.centroidXUm)));
            s.add(fixed("centroid_y_" + u, r -> fmt4(r.centroidYUm)));
            s.add(fixed("centroid_z_" + u, r -> fmt4(r.centroidZUm)));
            if (includeTimeComparison) {
                s.add(fixed("centroid_from_x_" + u, r -> fmt4(r.centroidFromXUm)));
                s.add(fixed("centroid_from_y_" + u, r -> fmt4(r.centroidFromYUm)));
                s.add(fixed("centroid_from_z_" + u, r -> fmt4(r.centroidFromZUm)));
                s.add(fixed("centroid_to_x_" + u, r -> fmt4(r.centroidToXUm)));
                s.add(fixed("centroid_to_y_" + u, r -> fmt4(r.centroidToYUm)));
                s.add(fixed("centroid_to_z_" + u, r -> fmt4(r.centroidToZUm)));
                s.add(fixed("displacement_" + u, r -> fmt4(r.displacementUm)));
                s.add(fixed("interval", r -> fmt4(r.interval)));
                s.add(fixed("velocity_" + u + "_per_frame", r -> fmt4(r.velocityUmPerFrame)));
            }
        }
        if (enabled.contains(MeasurementColumn.MAX_FERET3D))
            s.add(fixed("max_feret3d_" + u, r -> fmt4(r.maxFeret3dUm)));
        if (enabled.contains(MeasurementColumn.FERET_ENDPOINTS)) {
            s.add(fixed("max_feret_p1_x_" + u, r -> fmt4(r.maxFeretP1XUm)));
            s.add(fixed("max_feret_p1_y_" + u, r -> fmt4(r.maxFeretP1YUm)));
            s.add(fixed("max_feret_p1_z_" + u, r -> fmt4(r.maxFeretP1ZUm)));
            s.add(fixed("max_feret_p2_x_" + u, r -> fmt4(r.maxFeretP2XUm)));
            s.add(fixed("max_feret_p2_y_" + u, r -> fmt4(r.maxFeretP2YUm)));
            s.add(fixed("max_feret_p2_z_" + u, r -> fmt4(r.maxFeretP2ZUm)));
        }
        return s;
    }

    private static boolean hasTimeComparisonRows(List<ObjectMeasurementResult> results) {
        for (ObjectMeasurementResult r : results) {
            if (r.tFrom > 0 && r.tTo > 0) return true;
        }
        return false;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    static String normalizeUnit(String unit) {
        if (unit == null || unit.trim().isEmpty()) return "px";
        return unit.trim()
            .replace("µ", "u").replace("Å", "A")
            .replaceAll("[^A-Za-z0-9]", "").toLowerCase();
    }

    private static ColumnSpec fixed(String name, Function<ObjectMeasurementResult, String> fn) {
        return new ColumnSpec(name, fn);
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private static String fmt2(double v) { return String.format("%.2f", v); }
    private static String fmt4(double v) { return String.format("%.4f", v); }
    private static String fmt6(double v) { return String.format("%.6f", v); }

    private static final class ColumnSpec {
        final String name;
        final Function<ObjectMeasurementResult, String> formatter;
        ColumnSpec(String n, Function<ObjectMeasurementResult, String> f) {
            name = n; formatter = f;
        }
        String format(ObjectMeasurementResult r) { return formatter.apply(r); }
    }
}
