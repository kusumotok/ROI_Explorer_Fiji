package io.github.kusumotok.roiexplorer.service;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.plugin.frame.RoiManager;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.model.*;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RoiManagerInteropService {

    private final DiskSyncService diskSync = new DiskSyncService();

    public void importFromRoiManager(ExplorerNode targetParent, ImagePlus imp,
                                     OpenViewRegistry registry) throws IOException {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null) {
            throw new IOException("ROI Manager is not open.");
        }
        Roi[] rois = rm.getRoisAsArray();
        if (rois == null || rois.length == 0) {
            throw new IOException("ROI Manager is empty.");
        }

        Path dir = targetParent.getPath();
        for (Roi roi : rois) {
            String name = roi.getName();
            if (name == null || name.isEmpty()) name = "roi";
            if (!name.toLowerCase().endsWith(".roi")) name += ".roi";
            Path dest = DiskSyncService.uniquePath(dir.resolve(name));
            new RoiEncoder(dest.toString()).write(roi);
        }
        registry.notifyChildrenChanged(dir);
    }

    public void exportToRoiManager(List<RoiNode> rois, boolean addToExisting) {
        RoiManager rm = RoiManager.getRoiManager();
        if (!addToExisting) {
            rm.reset();
        }
        for (RoiNode node : rois) {
            Roi roi = node.getRoi();
            if (roi != null) {
                rm.addRoi(roi);
            }
        }
        rm.setVisible(true);
        rm.toFront();
    }

    public List<RoiZipService.RoiEntry> loadZipEntries(File zipFile) throws IOException {
        RoiManager rm = new RoiManager(true);
        try {
            if (!rm.runCommand("Open", zipFile.getAbsolutePath())) {
                throw new IOException("Failed to open ROI ZIP: " + zipFile.getAbsolutePath());
            }
            Roi[] rois = rm.getRoisAsArray();
            List<RoiZipService.RoiEntry> entries = new ArrayList<>();
            if (rois == null) {
                return entries;
            }
            for (int i = 0; i < rois.length; i++) {
                Roi roi = rois[i];
                if (roi == null) continue;
                Roi copy = (Roi) roi.clone();
                String name = copy.getName();
                if (name == null || name.isEmpty()) {
                    name = String.format("roi-%04d", i + 1);
                }
                if (!name.toLowerCase().endsWith(".roi")) {
                    name = name + ".roi";
                }
                copy.setName(name.substring(0, name.length() - 4));
                entries.add(new RoiZipService.RoiEntry(name, copy));
            }
            return entries;
        } finally {
            rm.close();
        }
    }
}
