package io.github.kusumotok.roiexplorer.service;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;

public class RoiZipService {

    public boolean isRoiZip(File f) {
        if (!f.isFile() || !f.getName().toLowerCase().endsWith(".zip")) return false;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(f))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) return false;
                if (name.contains("/") || name.contains("\\")) return false;
                if (!name.toLowerCase().endsWith(".roi")) return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public List<String> listEntryNames(File zipFile) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".roi")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    public Roi loadRoiFromZip(File zipFile, String entryName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    byte[] bytes = readAllBytes(zis);
                    RoiDecoder dec = new RoiDecoder(bytes, entryName);
                    return dec.getRoi();
                }
            }
        }
        throw new IOException("Entry not found in ZIP: " + entryName);
    }

    public void repackZip(File zipFile, List<RoiEntry> entries) throws IOException {
        Path tmp = Files.createTempFile(zipFile.toPath().getParent(), "repack_", ".zip");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp.toFile()))) {
                for (RoiEntry e : entries) {
                    byte[] bytes = RoiEncoder.saveAsByteArray(e.roi);
                    if (bytes == null) continue;
                    ZipEntry ze = new ZipEntry(e.name);
                    zos.putNextEntry(ze);
                    zos.write(bytes);
                    zos.closeEntry();
                }
            }
            Files.move(tmp, zipFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
    }

    public void unzipToFolder(File zipFile, Path targetFolder) throws IOException {
        Files.createDirectories(targetFolder);
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".roi")) {
                    byte[] bytes = readAllBytes(zis);
                    RoiDecoder dec = new RoiDecoder(bytes, entry.getName());
                    Roi roi = dec.getRoi();
                    if (roi == null) continue;
                    Path out = targetFolder.resolve(entry.getName());
                    new RoiEncoder(out.toString()).write(roi);
                }
            }
        }
    }

    public void zipFolder(Path sourceFolder, Path targetZip) throws IOException {
        List<RoiEntry> entries = new ArrayList<>();
        File[] files = sourceFolder.toFile().listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".roi")) {
                    RoiDecoder dec = new RoiDecoder(f.getAbsolutePath());
                    Roi roi = dec.getRoi();
                    if (roi != null) {
                        entries.add(new RoiEntry(f.getName(), roi));
                    }
                }
            }
        }
        repackZip(targetZip.toFile(), entries);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    public static class RoiEntry {
        public final String name;
        public final Roi roi;

        public RoiEntry(String name, Roi roi) {
            this.name = name;
            this.roi = roi;
        }
    }
}
