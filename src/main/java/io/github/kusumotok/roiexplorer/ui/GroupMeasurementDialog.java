package io.github.kusumotok.roiexplorer.ui;

import ij.ImagePlus;
import io.github.kusumotok.roiexplorer.service.GroupMeasurementService.Options;

import javax.swing.*;
import java.awt.*;

public class GroupMeasurementDialog extends JDialog {

    private boolean confirmed = false;
    private final Options opts;

    private final JCheckBox chGroupC = new JCheckBox("Group by C");
    private final JCheckBox chGroupT = new JCheckBox("Group by T");
    private final JCheckBox chGroupZ = new JCheckBox("Group by Z");

    private final JCheckBox ch2DCount = new JCheckBox("ROI_count", true);
    private final JCheckBox ch2DAreaSum = new JCheckBox("Area_sum", true);
    private final JCheckBox ch2DAreaMean = new JCheckBox("Area_mean");
    private final JCheckBox ch2DAreaMin = new JCheckBox("Area_min");
    private final JCheckBox ch2DAreaMax = new JCheckBox("Area_max");
    private final JCheckBox ch2DAxesXY = new JCheckBox("Major/Minor_axis_xy_um");

    private final JCheckBox ch3DVolume = new JCheckBox("Volume_total");
    private final JCheckBox ch3DVolumeVox = new JCheckBox("Volume_vox");
    private final JCheckBox ch3DSurface = new JCheckBox("Surface_area_total");
    private final JCheckBox ch3DSphericity = new JCheckBox("Sphericity");
    private final JCheckBox ch3DIntegrated = new JCheckBox("Integrated_intensity");
    private final JCheckBox ch3DMean = new JCheckBox("Mean_intensity");
    private final JCheckBox ch3DMax = new JCheckBox("Max_intensity");
    private final JCheckBox ch3DCentroidX = new JCheckBox("Centroid_x_um");
    private final JCheckBox ch3DCentroidY = new JCheckBox("Centroid_y_um");
    private final JCheckBox ch3DCentroidZ = new JCheckBox("Centroid_z_um");
    private final JCheckBox ch3DFarthestPair = new JCheckBox("Farthest_pair_distance+endpoints_um");
    private final JCheckBox ch3DZMin = new JCheckBox("Z_min");
    private final JCheckBox ch3DZMax = new JCheckBox("Z_max");
    private final JCheckBox ch3DZSpan = new JCheckBox("Z_span");
    private final JCheckBox ch3DSlices = new JCheckBox("Occupied_slices");

    public GroupMeasurementDialog(Window owner, ImagePlus imp, Options initial) {
        super(owner, "Set Group Measurements", ModalityType.APPLICATION_MODAL);
        this.opts = initial != null ? initial.copy() : new Options();
        setLayout(new BorderLayout(6, 6));

        boolean hasC = imp != null && imp.getNChannels() > 1;
        boolean hasT = imp != null && imp.getNFrames() > 1;
        boolean hasZ = imp != null && imp.getNSlices() > 1;

        chGroupC.setEnabled(hasC);
        chGroupT.setEnabled(hasT);
        chGroupZ.setEnabled(hasZ);
        loadFromOptions();

        boolean has3D = hasZ;
        update3DMetricAvailability(has3D);

        add(buildGroupByPanel(), BorderLayout.NORTH);
        add(buildMetricsPanel(), BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);

        chGroupZ.addActionListener(e -> update3DMetricAvailability(has3D));

        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private JPanel buildGroupByPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
        p.setBorder(BorderFactory.createTitledBorder("Grouping"));
        p.add(chGroupC);
        p.add(chGroupT);
        p.add(chGroupZ);
        return p;
    }

    private JPanel buildMetricsPanel() {
        JPanel outer = new JPanel(new GridLayout(1, 2, 6, 0));

        JPanel p2d = new JPanel(new GridLayout(0, 1, 2, 2));
        p2d.setBorder(BorderFactory.createTitledBorder("2D Metrics"));
        p2d.add(ch2DCount);
        p2d.add(ch2DAreaSum);
        p2d.add(ch2DAreaMean);
        p2d.add(ch2DAreaMin);
        p2d.add(ch2DAreaMax);
        p2d.add(ch2DAxesXY);

        JPanel p3d = new JPanel(new GridLayout(0, 1, 2, 2));
        p3d.setBorder(BorderFactory.createTitledBorder("3D Metrics (requires Z grouping OFF)"));
        p3d.add(ch3DVolume);
        p3d.add(ch3DVolumeVox);
        p3d.add(ch3DSurface);
        p3d.add(ch3DSphericity);
        p3d.add(ch3DIntegrated);
        p3d.add(ch3DMean);
        p3d.add(ch3DMax);
        p3d.add(ch3DCentroidX);
        p3d.add(ch3DCentroidY);
        p3d.add(ch3DCentroidZ);
        p3d.add(ch3DFarthestPair);
        p3d.add(ch3DZMin);
        p3d.add(ch3DZMax);
        p3d.add(ch3DZSpan);
        p3d.add(ch3DSlices);

        outer.add(p2d);
        outer.add(p3d);
        return outer;
    }

    private JPanel buildButtonPanel() {
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            storeToOptions();
            confirmed = true;
            dispose();
        });
        cancel.addActionListener(e -> dispose());
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        p.add(ok);
        p.add(cancel);
        return p;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public Options getOptions() {
        storeToOptions();
        return opts.copy();
    }

    private void loadFromOptions() {
        chGroupC.setSelected(opts.groupByC);
        chGroupT.setSelected(opts.groupByT);
        chGroupZ.setSelected(opts.groupByZ);
        ch2DCount.setSelected(opts.roiCount);
        ch2DAreaSum.setSelected(opts.areaSum);
        ch2DAreaMean.setSelected(opts.areaMean);
        ch2DAreaMin.setSelected(opts.areaMin);
        ch2DAreaMax.setSelected(opts.areaMax);
        ch2DAxesXY.setSelected(opts.principalAxesXY);
        ch3DVolume.setSelected(opts.volumeTotal);
        ch3DVolumeVox.setSelected(opts.volumeVox);
        ch3DSurface.setSelected(opts.surfaceAreaTotal);
        ch3DSphericity.setSelected(opts.sphericity);
        ch3DIntegrated.setSelected(opts.integratedIntensity);
        ch3DMean.setSelected(opts.meanIntensity);
        ch3DMax.setSelected(opts.maxIntensity);
        ch3DCentroidX.setSelected(opts.centroidX);
        ch3DCentroidY.setSelected(opts.centroidY);
        ch3DCentroidZ.setSelected(opts.centroidZ);
        ch3DFarthestPair.setSelected(opts.farthestPair);
        ch3DZMin.setSelected(opts.zMin);
        ch3DZMax.setSelected(opts.zMax);
        ch3DZSpan.setSelected(opts.zSpan);
        ch3DSlices.setSelected(opts.occupiedSlices);
    }

    private void storeToOptions() {
        opts.groupByC = chGroupC.isSelected();
        opts.groupByT = chGroupT.isSelected();
        opts.groupByZ = chGroupZ.isSelected();
        opts.roiCount = ch2DCount.isSelected();
        opts.areaSum = ch2DAreaSum.isSelected();
        opts.areaMean = ch2DAreaMean.isSelected();
        opts.areaMin = ch2DAreaMin.isSelected();
        opts.areaMax = ch2DAreaMax.isSelected();
        opts.principalAxesXY = ch2DAxesXY.isSelected();
        opts.volumeTotal = ch3DVolume.isSelected();
        opts.volumeVox = ch3DVolumeVox.isSelected();
        opts.surfaceAreaTotal = ch3DSurface.isSelected();
        opts.sphericity = ch3DSphericity.isSelected();
        opts.integratedIntensity = ch3DIntegrated.isSelected();
        opts.meanIntensity = ch3DMean.isSelected();
        opts.maxIntensity = ch3DMax.isSelected();
        opts.centroidX = ch3DCentroidX.isSelected();
        opts.centroidY = ch3DCentroidY.isSelected();
        opts.centroidZ = ch3DCentroidZ.isSelected();
        opts.farthestPair = ch3DFarthestPair.isSelected();
        opts.zMin = ch3DZMin.isSelected();
        opts.zMax = ch3DZMax.isSelected();
        opts.zSpan = ch3DZSpan.isSelected();
        opts.occupiedSlices = ch3DSlices.isSelected();
    }

    private void update3DMetricAvailability(boolean has3D) {
        boolean enabled = has3D && !chGroupZ.isSelected();
        for (JCheckBox box : new JCheckBox[]{
                ch3DVolume, ch3DVolumeVox, ch3DSurface, ch3DSphericity,
                ch3DIntegrated, ch3DMean, ch3DMax, ch3DCentroidX, ch3DCentroidY, ch3DCentroidZ,
                ch3DFarthestPair,
                ch3DZMin, ch3DZMax, ch3DZSpan, ch3DSlices}) {
            box.setEnabled(enabled);
            if (!enabled) box.setSelected(false);
        }
    }
}
