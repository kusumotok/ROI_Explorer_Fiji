package io.github.kusumotok.roiexplorer.service;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import io.github.kusumotok.roiexplorer.OpenViewRegistry;
import io.github.kusumotok.roiexplorer.OpenViewRegistry.PathKey;
import io.github.kusumotok.roiexplorer.model.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class DiskSyncService {

    private final RoiZipService roiZipService = new RoiZipService();

    // ── New Folder ──────────────────────────────────────────────────────────

    public FolderNode newFolder(FolderNode parent, OpenViewRegistry registry) throws IOException {
        String name = uniqueName(parent.getPath(), "New Folder", false);
        Path newPath = parent.getPath().resolve(name);
        Files.createDirectory(newPath);
        FolderNode node = new FolderNode(newPath, parent);
        parent.addChild(node);
        registry.notifyChildrenChanged(parent.getPath());
        return node;
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    public void rename(ExplorerNode node, String newName, OpenViewRegistry registry) throws IOException {
        if (newName == null || newName.trim().isEmpty()) return;
        newName = newName.trim();

        Path oldPath = node.getPath();
        Path newPath;

        if (node instanceof ZipNode) {
            newPath = oldPath.getParent().resolve(ensureZipExt(newName));
        } else if (node instanceof RoiNode && !((RoiNode) node).isZipEntry()) {
            newPath = oldPath.getParent().resolve(ensureRoiExt(newName));
        } else {
            newPath = oldPath.getParent().resolve(newName);
        }

        if (newPath.equals(oldPath)) return;

        if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
            renameZipEntry((RoiNode) node, newName, registry);
            return;
        }

        Files.move(oldPath, newPath);

        Path oldPrefix = oldPath;
        Path newPrefix = newPath;
        node.updatePathPrefix(oldPrefix, newPrefix);

        registry.notifyPathRenamed(oldPrefix, newPrefix);
    }

    private void renameZipEntry(RoiNode node, String newName, OpenViewRegistry registry) throws IOException {
        ZipNode zip = node.getContainingZip();
        if (zip == null) return;
        List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
        String oldEntry = node.getPath().getFileName().toString();
        String newEntry = uniqueZipEntry(entries, ensureRoiExt(newName));
        if (newEntry.equals(oldEntry)) return;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name.equals(oldEntry)) {
                entries.set(i, new RoiZipService.RoiEntry(newEntry, entries.get(i).roi));
                break;
            }
        }
        roiZipService.repackZip(zip.getPath().toFile(), entries);
        PathKey oldKey = PathKey.forZipEntry(zip.getPath(), oldEntry);
        PathKey newKey = PathKey.forZipEntry(zip.getPath(), newEntry);
        node.setPath(newKey.getCurrentPath());
        registry.notifyTargetRenamed(oldKey, newKey);
        registry.notifyChildrenChanged(zip.getPath());
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    public void delete(ExplorerNode node, OpenViewRegistry registry) throws IOException {
        if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
            deleteZipEntry((RoiNode) node, registry);
            return;
        }
        Path path = node.getPath();
        deleteRecursive(path.toFile());
        if (node.getParent() != null) {
            node.getParent().removeChild(node);
            registry.notifyChildrenChanged(node.getParent().getPath());
        }
        registry.notifyPathDeleted(path);
    }

    private void deleteZipEntry(RoiNode node, OpenViewRegistry registry) throws IOException {
        ZipNode zip = node.getContainingZip();
        if (zip == null) return;
        String entryName = node.getPath().getFileName().toString();
        List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
        entries.removeIf(e -> e.name.equals(entryName));
        roiZipService.repackZip(zip.getPath().toFile(), entries);
        zip.removeChild(node);
        registry.notifyTargetDeleted(PathKey.forZipEntry(zip.getPath(), entryName));
        registry.notifyChildrenChanged(zip.getPath());
    }

    private static void deleteRecursive(File f) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        if (!f.delete()) {
            throw new IOException("Cannot delete: " + f.getAbsolutePath());
        }
    }

    // ── Move ─────────────────────────────────────────────────────────────────

    public void moveNodes(List<ExplorerNode> nodes, ExplorerNode targetParent,
                          OpenViewRegistry registry) throws IOException {
        for (ExplorerNode node : nodes) {
            if (isAncestorOf(node, targetParent)) continue;
            if (node.getParent() == targetParent) continue;

            if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
                moveZipEntryToFolder((RoiNode) node, targetParent, registry);
            } else if (targetParent instanceof ZipNode && node instanceof RoiNode) {
                moveRoiToZip((RoiNode) node, (ZipNode) targetParent, registry);
            } else {
                moveFsNode(node, targetParent, registry);
            }
        }
    }

    private void moveFsNode(ExplorerNode node, ExplorerNode targetParent,
                            OpenViewRegistry registry) throws IOException {
        Path src = node.getPath();
        String name = src.getFileName().toString();
        Path dst = uniquePath(targetParent.getPath().resolve(name));
        Files.move(src, dst);
        ExplorerNode oldParent = node.getParent();
        if (oldParent != null) oldParent.removeChild(node);
        node.updatePathPrefix(src, dst);
        node.setParent(targetParent);
        targetParent.addChild(node);
        registry.notifyPathRenamed(src, dst);
    }

    private void moveZipEntryToFolder(RoiNode node, ExplorerNode targetParent,
                                      OpenViewRegistry registry) throws IOException {
        ZipNode zip = node.getContainingZip();
        if (zip == null) return;
        String entryName = node.getPath().getFileName().toString();
        Roi roi = roiZipService.loadRoiFromZip(zip.getPath().toFile(), entryName);

        List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
        entries.removeIf(e -> e.name.equals(entryName));
        roiZipService.repackZip(zip.getPath().toFile(), entries);
        zip.removeChild(node);

        Path destPath = uniquePath(targetParent.getPath().resolve(entryName));
        new RoiEncoder(destPath.toString()).write(roi);

        Path finalPath = destPath;
        RoiNode newNode = new RoiNode(finalPath, targetParent, () -> {
            RoiDecoder dec = new RoiDecoder(finalPath.toString());
            return dec.getRoi();
        });
        newNode.setRoi(roi);
        targetParent.addChild(newNode);

        registry.notifyTargetRenamed(PathKey.forZipEntry(zip.getPath(), entryName), PathKey.forFsRoi(finalPath));
        registry.notifyChildrenChanged(zip.getPath());
        registry.notifyChildrenChanged(targetParent.getPath());
    }

    private void moveRoiToZip(RoiNode node, ZipNode targetZip,
                               OpenViewRegistry registry) throws IOException {
        Roi roi = node.getRoi();
        if (roi == null) return;
        String entryName = node.getPath().getFileName().toString();
        List<RoiZipService.RoiEntry> entries = loadZipEntries(targetZip.getPath().toFile());
        String uniqueEntry = uniqueZipEntry(entries, entryName);
        entries.add(new RoiZipService.RoiEntry(uniqueEntry, roi));
        roiZipService.repackZip(targetZip.getPath().toFile(), entries);

        ExplorerNode oldParent = node.getParent();
        if (oldParent != null) {
            Path oldPath = node.getPath();
            deleteRecursive(node.getPath().toFile());
            oldParent.removeChild(node);
            registry.notifyTargetRenamed(PathKey.forFsRoi(oldPath), PathKey.forZipEntry(targetZip.getPath(), uniqueEntry));
            registry.notifyChildrenChanged(oldParent.getPath());
        }
        registry.notifyChildrenChanged(targetZip.getPath());
    }

    // ── Duplicate ────────────────────────────────────────────────────────────

    public Path duplicate(ExplorerNode node, OpenViewRegistry registry) throws IOException {
        if (node instanceof RoiNode && ((RoiNode) node).isZipEntry()) {
            return duplicateZipEntry((RoiNode) node, registry);
        }
        Path src = node.getPath();
        String baseName = src.getFileName().toString();
        String copyName = buildCopyName(baseName);
        Path dst = uniquePath(src.getParent().resolve(copyName));

        if (node instanceof FolderNode || node instanceof ZipNode) {
            copyDirectory(src.toFile(), dst.toFile());
        } else {
            Files.copy(src, dst);
        }

        ExplorerNode parent = node.getParent();
        if (parent != null) registry.notifyChildrenChanged(parent.getPath());
        return dst;
    }

    private Path duplicateZipEntry(RoiNode node, OpenViewRegistry registry) throws IOException {
        ZipNode zip = node.getContainingZip();
        if (zip == null) return null;
        String entryName = node.getPath().getFileName().toString();
        Roi roi = roiZipService.loadRoiFromZip(zip.getPath().toFile(), entryName);
        List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
        String copyEntry = uniqueZipEntry(entries, buildCopyName(entryName));
        entries.add(new RoiZipService.RoiEntry(copyEntry, roi));
        roiZipService.repackZip(zip.getPath().toFile(), entries);
        registry.notifyChildrenChanged(zip.getPath());
        return zip.getPath().resolve(copyEntry);
    }

    // ── ROI save / split ─────────────────────────────────────────────────────

    public void saveRoiToDisk(RoiNode node, Roi roi, OpenViewRegistry registry) throws IOException {
        node.setRoi(roi);
        if (node.isZipEntry()) {
            ZipNode zip = node.getContainingZip();
            if (zip == null) throw new IOException("ZipNode missing");
            String entryName = node.getPath().getFileName().toString();
            List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
            boolean replaced = false;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).name.equals(entryName)) {
                    entries.set(i, new RoiZipService.RoiEntry(entryName, roi));
                    replaced = true;
                    break;
                }
            }
            if (!replaced) entries.add(new RoiZipService.RoiEntry(entryName, roi));
            roiZipService.repackZip(zip.getPath().toFile(), entries);
            registry.notifyChildrenChanged(zip.getPath());
        } else {
            new RoiEncoder(node.getPath().toString()).write(roi);
            registry.notifyChildrenChanged(node.getParent().getPath());
        }
    }

    public Path saveNewRoi(ExplorerNode target, Roi roi, String preferredName, OpenViewRegistry registry) throws IOException {
        if (target instanceof ZipNode) {
            ZipNode zip = (ZipNode) target;
            List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
            String entryName = uniqueZipEntry(entries, ensureRoiExt(preferredName));
            Roi copy = (Roi) roi.clone();
            copy.setName(stripRoiExt(entryName));
            entries.add(new RoiZipService.RoiEntry(entryName, copy));
            roiZipService.repackZip(zip.getPath().toFile(), entries);
            registry.notifyChildrenChanged(zip.getPath());
            return zip.getPath().resolve(entryName);
        }
        if (!(target instanceof FolderNode)) {
            throw new IOException("Unsupported ROI target: " + target);
        }
        Path out = uniquePath(target.getPath().resolve(ensureRoiExt(preferredName)));
        Roi copy = (Roi) roi.clone();
        copy.setName(stripRoiExt(out.getFileName().toString()));
        new RoiEncoder(out.toString()).write(copy);
        registry.notifyChildrenChanged(target.getPath());
        return out;
    }

    public List<Path> saveRoiSplit(RoiNode original, List<Roi> parts,
                                   String baseName, boolean replaceOriginal,
                                   OpenViewRegistry registry) throws IOException {
        ExplorerNode parent = original.getParent();
        List<Path> saved = new ArrayList<>();

        if (original.isZipEntry()) {
            ZipNode zip = original.getContainingZip();
            if (zip == null) throw new IOException("ZipNode missing");
            List<RoiZipService.RoiEntry> entries = loadZipEntries(zip.getPath().toFile());
            String origEntry = original.getPath().getFileName().toString();
            if (replaceOriginal) {
                entries.removeIf(e -> e.name.equals(origEntry));
            }
            for (int i = 0; i < parts.size(); i++) {
                Roi part = parts.get(i);
                String entry = uniqueZipEntry(entries,
                        stripRoiExt(baseName) + "_" + (i + 1) + ".roi");
                part.setName(stripRoiExt(entry));
                entries.add(new RoiZipService.RoiEntry(entry, part));
                saved.add(zip.getPath().resolve(entry));
            }
            roiZipService.repackZip(zip.getPath().toFile(), entries);
            registry.notifyChildrenChanged(zip.getPath());
        } else {
            if (replaceOriginal) {
                Files.deleteIfExists(original.getPath());
            }
            for (int i = 0; i < parts.size(); i++) {
                Roi part = parts.get(i);
                String name = stripRoiExt(baseName) + "_" + (i + 1);
                Path out = uniquePath(parent.getPath().resolve(name + ".roi"));
                part.setName(stripRoiExt(out.getFileName().toString()));
                new RoiEncoder(out.toString()).write(part);
                saved.add(out);
            }
            registry.notifyChildrenChanged(parent.getPath());
        }
        return saved;
    }

    // ── ZIP / Unzip ──────────────────────────────────────────────────────────

    public void zipFolder(FolderNode folder, OpenViewRegistry registry) throws IOException {
        Path folderPath = folder.getPath();
        Path zipPath = folderPath.getParent().resolve(folderPath.getFileName().toString() + ".zip");
        if (Files.exists(zipPath)) {
            zipPath = uniquePath(zipPath);
        }
        roiZipService.zipFolder(folderPath, zipPath);
        deleteRecursive(folderPath.toFile());
        registry.notifyPathDeleted(folderPath);
        registry.notifyChildrenChanged(folderPath.getParent());
    }

    public void unzipToFolder(ZipNode zipNode, OpenViewRegistry registry) throws IOException {
        Path zipPath = zipNode.getPath();
        String folderName = stripZipExt(zipPath.getFileName().toString());
        Path folderPath = zipPath.getParent().resolve(folderName);
        if (Files.exists(folderPath)) {
            folderPath = uniquePath(folderPath);
        }
        roiZipService.unzipToFolder(zipPath.toFile(), folderPath);
        Files.delete(zipPath);
        registry.notifyPathDeleted(zipPath);
        registry.notifyChildrenChanged(zipPath.getParent());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static String uniqueName(Path dir, String preferred, boolean isFolder) {
        Path candidate = dir.resolve(preferred);
        if (!candidate.toFile().exists()) return preferred;
        String base = preferred;
        String ext = "";
        int dot = preferred.lastIndexOf('.');
        if (!isFolder && dot >= 0) {
            base = preferred.substring(0, dot);
            ext = preferred.substring(dot);
        }
        for (int i = 2; i < 9999; i++) {
            String name = base + " " + i + ext;
            if (!dir.resolve(name).toFile().exists()) return name;
        }
        return preferred + "_" + System.currentTimeMillis();
    }

    public static Path uniquePath(Path path) {
        if (!path.toFile().exists()) return path;
        String name = path.getFileName().toString();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 9999; i++) {
            Path candidate = path.getParent().resolve(base + " " + i + ext);
            if (!candidate.toFile().exists()) return candidate;
        }
        return path.getParent().resolve(name + "_" + System.currentTimeMillis());
    }

    private static boolean isAncestorOf(ExplorerNode ancestor, ExplorerNode node) {
        ExplorerNode n = node.getParent();
        while (n != null) {
            if (n == ancestor) return true;
            n = n.getParent();
        }
        return false;
    }

    private List<RoiZipService.RoiEntry> loadZipEntries(File zipFile) throws IOException {
        List<String> names = roiZipService.listEntryNames(zipFile);
        List<RoiZipService.RoiEntry> entries = new ArrayList<>();
        for (String name : names) {
            Roi roi = roiZipService.loadRoiFromZip(zipFile, name);
            if (roi != null) entries.add(new RoiZipService.RoiEntry(name, roi));
        }
        return entries;
    }

    private static String uniqueZipEntry(List<RoiZipService.RoiEntry> entries, String preferred) {
        Set<String> existing = new HashSet<>();
        for (RoiZipService.RoiEntry e : entries) existing.add(e.name);
        if (!existing.contains(preferred)) return preferred;
        String base = stripRoiExt(preferred);
        for (int i = 2; i < 9999; i++) {
            String name = base + " " + i + ".roi";
            if (!existing.contains(name)) return name;
        }
        return preferred + "_" + System.currentTimeMillis() + ".roi";
    }

    private static String buildCopyName(String name) {
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        return base + " copy" + ext;
    }

    private static String ensureRoiExt(String name) {
        return name.toLowerCase().endsWith(".roi") ? name : name + ".roi";
    }

    private static String ensureZipExt(String name) {
        return name.toLowerCase().endsWith(".zip") ? name : name + ".zip";
    }

    private static String stripRoiExt(String name) {
        return name.toLowerCase().endsWith(".roi") ? name.substring(0, name.length() - 4) : name;
    }

    private static String stripZipExt(String name) {
        return name.toLowerCase().endsWith(".zip") ? name.substring(0, name.length() - 4) : name;
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] children = src.listFiles();
            if (children != null) {
                for (File c : children) copyDirectory(c, new File(dst, c.getName()));
            }
        } else {
            Files.copy(src.toPath(), dst.toPath());
        }
    }
}
