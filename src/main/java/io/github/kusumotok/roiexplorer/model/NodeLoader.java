package io.github.kusumotok.roiexplorer.model;

import ij.io.RoiDecoder;
import ij.gui.Roi;
import io.github.kusumotok.roiexplorer.service.RoiManagerInteropService;
import io.github.kusumotok.roiexplorer.service.RoiZipService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class NodeLoader {

    private final RoiZipService roiZipService = new RoiZipService();
    private final RoiManagerInteropService roiManagerInteropService = new RoiManagerInteropService();

    public FolderNode loadFolder(Path root) {
        FolderNode rootNode = new FolderNode(root, null);
        loadChildren(rootNode);
        return rootNode;
    }

    private void loadChildren(ExplorerNode parent) {
        File dir = parent.getPath().toFile();
        if (!dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        Arrays.sort(files, Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase()));

        for (File f : files) {
            if (f.isDirectory()) {
                FolderNode child = new FolderNode(f.toPath(), parent);
                parent.addChild(child);
                loadChildren(child);
            } else if (f.getName().toLowerCase().endsWith(".roi")) {
                Path roiPath = f.toPath();
                RoiNode child = new RoiNode(roiPath, parent, () -> {
                    RoiDecoder dec = new RoiDecoder(roiPath.toString());
                    return dec.getRoi();
                });
                parent.addChild(child);
            } else if (f.getName().toLowerCase().endsWith(".zip")) {
                if (roiZipService.isRoiZip(f)) {
                    ZipNode child = new ZipNode(f.toPath(), parent);
                    parent.addChild(child);
                    loadZipChildren(child, f);
                }
            }
        }
    }

    private void loadZipChildren(ZipNode zipNode, File zipFile) {
        try {
            // ZIP root opening prefers the ImageJ/ROI Manager path because it is much
            // faster for bulk load. ZIP entry edit/save paths still use RoiZipService
            // directly, so we keep the fallback reader below for compatibility.
            List<RoiZipService.RoiEntry> entries = roiManagerInteropService.loadZipEntries(zipFile);
            entries.sort(Comparator.comparing(e -> e.name.toLowerCase()));
            for (RoiZipService.RoiEntry entry : entries) {
                Path virtualPath = zipNode.getPath().resolve(entry.name);
                Roi roi = entry.roi;
                RoiNode roiNode = new RoiNode(virtualPath, zipNode, () -> (Roi) roi.clone());
                roiNode.setRoi(roi);
                zipNode.addChild(roiNode);
            }
        } catch (IOException e) {
            try {
                // Fallback to the older direct ZIP reader if ROI Manager based loading
                // is unavailable for a given file/runtime.
                List<String> names = roiZipService.listEntryNames(zipFile);
                names.sort(String.CASE_INSENSITIVE_ORDER);
                for (String name : names) {
                    Path virtualPath = zipNode.getPath().resolve(name);
                    RoiNode roiNode = new RoiNode(virtualPath, zipNode, () -> roiZipService.loadRoiFromZip(zipFile, name));
                    zipNode.addChild(roiNode);
                }
            } catch (IOException ignored) {
                // Ignore unreadable ZIPs during tree construction.
            }
        }
    }
}
