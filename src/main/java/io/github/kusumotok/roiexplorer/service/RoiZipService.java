package io.github.kusumotok.roiexplorer.service;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.*;

public class RoiZipService {

    public enum ZipMode {
        COMPRESSED,
        FAST
    }

    public boolean isRoiZip(File f) {
        if (!f.isFile() || !f.getName().toLowerCase().endsWith(".zip")) return false;
        try (ZipFile zipFile = new ZipFile(f)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
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
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".roi")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    public List<RoiEntry> loadEntries(File zipFile) throws IOException {
        List<RoiEntry> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".roi")) {
                    continue;
                }
                byte[] bytes = readAllBytes(zis);
                Roi roi = new RoiDecoder(bytes, entry.getName()).getRoi();
                if (roi != null) {
                    entries.add(new RoiEntry(entry.getName(), roi));
                }
            }
        }
        return entries;
    }

    public Roi loadRoiFromZip(File zipFile, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry != null) {
                try (InputStream is = zip.getInputStream(entry)) {
                    byte[] bytes = readAllBytes(is);
                    RoiDecoder dec = new RoiDecoder(bytes, entryName);
                    return dec.getRoi();
                }
            }
        }
        throw new IOException("Entry not found in ZIP: " + entryName);
    }

    public void repackZip(File zipFile, List<RoiEntry> entries) throws IOException {
        repackZip(zipFile, entries, ZipMode.COMPRESSED);
    }

    public void repackZip(File zipFile, List<RoiEntry> entries, ZipMode mode) throws IOException {
        Path tmp = Files.createTempFile(zipFile.toPath().getParent(), "repack_", ".zip");
        try {
            // Repack keeps the existing call sites intact while routing all ZIP writes
            // through the newer memory-archive -> single-write path below.
            writeZip(entries, tmp.toFile(), mode);
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
        zipFolder(sourceFolder, targetZip, ZipMode.COMPRESSED);
    }

    public void zipFolder(Path sourceFolder, Path targetZip, ZipMode mode) throws IOException {
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
        // Folder -> ZIP now shares the same writer core as repack/save so compression
        // policy and entry formatting stay aligned across ROI Explorer workflows.
        writeZip(entries, targetZip.toFile(), mode);
    }

    public void writeZip(List<RoiEntry> entries, File zipFile, ZipMode mode) throws IOException {
        byte[] zipBytes = buildZipBytes(entries, mode);
        Files.write(zipFile.toPath(), zipBytes);
    }

    public byte[] buildZipBytes(List<RoiEntry> entries, ZipMode mode) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {
            // This is the canonical ZIP writer path. Older entry-by-entry write callers
            // are intentionally funneled here to avoid multiple independent ZIP writers.
            for (RoiEntry entry : entries) {
                byte[] bytes = RoiEncoder.saveAsByteArray(entry.roi);
                if (bytes == null) continue;
                writeEntry(zos, entry.name, bytes, mode);
            }
            zos.finish();
            return baos.toByteArray();
        }
    }

    private static void writeEntry(ZipOutputStream zos, String entryName, byte[] bytes, ZipMode mode) throws IOException {
        ZipEntry ze = new ZipEntry(entryName);
        if (mode == ZipMode.FAST) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            ze.setMethod(ZipEntry.STORED);
            ze.setSize(bytes.length);
            ze.setCompressedSize(bytes.length);
            ze.setCrc(crc.getValue());
        } else {
            ze.setMethod(ZipEntry.DEFLATED);
        }
        zos.putNextEntry(ze);
        zos.write(bytes);
        zos.closeEntry();
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
