package io.github.kusumotok.roiexplorer.service.measure;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public final class ObjectMeasurementCsvExporter {

    public void write(List<ObjectMeasurementResult> results, Path outputPath) throws IOException {
        String unit = results.isEmpty() ? "µm" : results.get(0).calibrationUnit;
        String header = buildHeader(unit);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(
                    new FileOutputStream(outputPath.toFile()), StandardCharsets.UTF_8)))) {
            pw.println(header);
            for (ObjectMeasurementResult r : results) {
                pw.printf("%d,%s,%d,%d,%.6f,%d,%.6f,%.6f,%.2f,%.4f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    r.spotId,
                    csvCell(r.unitName),
                    r.c,
                    r.t,
                    r.volumeUm3,
                    r.volumeVox,
                    r.surfaceAreaUm2,
                    r.sphericity,
                    r.integratedIntensity,
                    r.meanIntensity,
                    r.maxIntensity,
                    r.centroidXUm,
                    r.centroidYUm,
                    r.centroidZUm,
                    r.maxFeret3dUm,
                    r.maxFeretP1XUm,
                    r.maxFeretP1YUm,
                    r.maxFeretP1ZUm,
                    r.maxFeretP2XUm,
                    r.maxFeretP2YUm,
                    r.maxFeretP2ZUm);
            }
        }
    }

    private static String buildHeader(String rawUnit) {
        String u  = normalizeUnit(rawUnit);
        String u2 = u + "2";
        String u3 = u + "3";
        return "spot_id,unit_name,c,t,"
            + "volume_" + u3 + ",volume_vox,"
            + "surface_area_" + u2 + ",sphericity,"
            + "integrated_intensity,mean_intensity,max_intensity,"
            + "centroid_x_" + u + ",centroid_y_" + u + ",centroid_z_" + u + ","
            + "max_feret3d_" + u + ","
            + "max_feret_p1_x_" + u + ",max_feret_p1_y_" + u + ",max_feret_p1_z_" + u + ","
            + "max_feret_p2_x_" + u + ",max_feret_p2_y_" + u + ",max_feret_p2_z_" + u;
    }

    /**
     * Normalizes a calibration unit string to an ASCII-safe column name suffix.
     * e.g. "µm" → "um", "nm" → "nm", "Å" → "A", "px" → "px"
     */
    static String normalizeUnit(String unit) {
        if (unit == null || unit.trim().isEmpty()) return "px";
        return unit.trim()
            .replace("µ", "u")
            .replace("Å", "A")
            .replaceAll("[^A-Za-z0-9]", "")
            .toLowerCase();
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
