package io.github.kusumotok.roiexplorer.model;

import ij.io.RoiDecoder;
import io.github.kusumotok.roiexplorer.service.RoiZipService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class NodeLoader {

    private final RoiZipService roiZipService = new RoiZipService();

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
        List<String> names;
        try {
            names = roiZipService.listEntryNames(zipFile);
        } catch (IOException e) {
            return;
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        for (String name : names) {
            Path virtualPath = zipNode.getPath().resolve(name);
            RoiNode roiNode = new RoiNode(virtualPath, zipNode, () -> {
                return roiZipService.loadRoiFromZip(zipFile, name);
            });
            zipNode.addChild(roiNode);
        }
    }
}
