package io.github.kusumotok.roiexplorer;

import io.github.kusumotok.roiexplorer.model.RoiNode;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerPanel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class OpenViewRegistry {

    public enum EditMode {
        EDIT,
        SPLIT_EDIT
    }

    public enum EditState {
        ACTIVE,
        MOVED,
        RENAMED,
        DELETED,
        CONFLICT
    }

    public enum TargetType {
        FS_ROI,
        ZIP_ENTRY
    }

    public static final class PathKey {
        private final TargetType targetType;
        private final Path filePath;
        private final Path zipContainerPath;
        private final String zipEntryName;

        private PathKey(TargetType targetType, Path filePath, Path zipContainerPath, String zipEntryName) {
            this.targetType = targetType;
            this.filePath = filePath;
            this.zipContainerPath = zipContainerPath;
            this.zipEntryName = zipEntryName;
        }

        public static PathKey forFsRoi(Path filePath) {
            return new PathKey(TargetType.FS_ROI, filePath, null, null);
        }

        public static PathKey forZipEntry(Path zipContainerPath, String entryName) {
            return new PathKey(TargetType.ZIP_ENTRY, null, zipContainerPath, entryName);
        }

        public static PathKey forRoiNode(RoiNode node) {
            if (node == null) return null;
            if (node.isZipEntry() && node.getContainingZip() != null) {
                return forZipEntry(node.getContainingZip().getPath(), node.getPath().getFileName().toString());
            }
            return forFsRoi(node.getPath());
        }

        public TargetType getTargetType() {
            return targetType;
        }

        public Path getCurrentPath() {
            return targetType == TargetType.ZIP_ENTRY
                    ? (zipContainerPath != null && zipEntryName != null ? zipContainerPath.resolve(zipEntryName) : null)
                    : filePath;
        }

        public Path getFilePath() {
            return filePath;
        }

        public Path getZipContainerPath() {
            return zipContainerPath;
        }

        public String getZipEntryName() {
            return zipEntryName;
        }

        public PathKey withFilePath(Path newFilePath) {
            return forFsRoi(newFilePath);
        }

        public PathKey withZipEntry(Path newZipContainerPath, String newEntryName) {
            return forZipEntry(newZipContainerPath, newEntryName);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PathKey)) return false;
            PathKey pathKey = (PathKey) o;
            return targetType == pathKey.targetType
                    && Objects.equals(filePath, pathKey.filePath)
                    && Objects.equals(zipContainerPath, pathKey.zipContainerPath)
                    && Objects.equals(zipEntryName, pathKey.zipEntryName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetType, filePath, zipContainerPath, zipEntryName);
        }
    }

    public static final class Revision {
        private final TargetType targetType;
        private final long lastModified;
        private final long fileSize;
        private final Path zipContainerPath;
        private final String zipEntryName;

        private Revision(TargetType targetType, long lastModified, long fileSize, Path zipContainerPath, String zipEntryName) {
            this.targetType = targetType;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
            this.zipContainerPath = zipContainerPath;
            this.zipEntryName = zipEntryName;
        }

        public static Revision forPathKey(PathKey key) {
            if (key == null) return null;
            try {
                if (key.targetType == TargetType.ZIP_ENTRY) {
                    Path zipPath = key.getZipContainerPath();
                    long modified = zipPath != null && Files.exists(zipPath) ? Files.getLastModifiedTime(zipPath).toMillis() : -1L;
                    long size = zipPath != null && Files.exists(zipPath) ? Files.size(zipPath) : -1L;
                    return new Revision(TargetType.ZIP_ENTRY, modified, size, zipPath, key.getZipEntryName());
                }
                Path filePath = key.getFilePath();
                long modified = filePath != null && Files.exists(filePath) ? Files.getLastModifiedTime(filePath).toMillis() : -1L;
                long size = filePath != null && Files.exists(filePath) ? Files.size(filePath) : -1L;
                return new Revision(TargetType.FS_ROI, modified, size, null, null);
            } catch (Exception e) {
                return new Revision(key.targetType, -1L, -1L, key.getZipContainerPath(), key.getZipEntryName());
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Revision)) return false;
            Revision revision = (Revision) o;
            return lastModified == revision.lastModified
                    && fileSize == revision.fileSize
                    && targetType == revision.targetType
                    && Objects.equals(zipContainerPath, revision.zipContainerPath)
                    && Objects.equals(zipEntryName, revision.zipEntryName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(targetType, lastModified, fileSize, zipContainerPath, zipEntryName);
        }
    }

    public static final class EditSession {
        private final UUID editSessionId;
        private final EditMode mode;
        private final int ownerWindowId;
        private final RoiExplorerPanel ownerWindow;
        private final Path startPath;
        private Path currentPath;
        private final Revision baseRevision;
        private EditState state;
        private final long startedAt;
        private final TargetType targetType;
        private Path zipContainerPath;
        private String zipEntryName;

        private EditSession(UUID editSessionId, EditMode mode, RoiExplorerPanel ownerWindow,
                            PathKey key, Revision baseRevision) {
            this.editSessionId = editSessionId;
            this.mode = mode;
            this.ownerWindow = ownerWindow;
            this.ownerWindowId = ownerWindow != null ? System.identityHashCode(ownerWindow) : 0;
            this.startPath = key != null ? key.getCurrentPath() : null;
            this.currentPath = key != null ? key.getCurrentPath() : null;
            this.baseRevision = baseRevision;
            this.state = EditState.ACTIVE;
            this.startedAt = System.currentTimeMillis();
            this.targetType = key != null ? key.getTargetType() : TargetType.FS_ROI;
            this.zipContainerPath = key != null ? key.getZipContainerPath() : null;
            this.zipEntryName = key != null ? key.getZipEntryName() : null;
        }

        public UUID getEditSessionId() {
            return editSessionId;
        }

        public EditMode getMode() {
            return mode;
        }

        public int getOwnerWindowId() {
            return ownerWindowId;
        }

        public RoiExplorerPanel getOwnerWindow() {
            return ownerWindow;
        }

        public Path getStartPath() {
            return startPath;
        }

        public Path getCurrentPath() {
            return currentPath;
        }

        public Revision getBaseRevision() {
            return baseRevision;
        }

        public EditState getState() {
            return state;
        }

        public long getStartedAt() {
            return startedAt;
        }

        public TargetType getTargetType() {
            return targetType;
        }

        public Path getZipContainerPath() {
            return zipContainerPath;
        }

        public String getZipEntryName() {
            return zipEntryName;
        }

        public PathKey getCurrentKey() {
            return targetType == TargetType.ZIP_ENTRY
                    ? PathKey.forZipEntry(zipContainerPath, zipEntryName)
                    : PathKey.forFsRoi(currentPath);
        }

        private void updateKey(PathKey key, EditState nextState) {
            this.currentPath = key != null ? key.getCurrentPath() : null;
            this.zipContainerPath = key != null ? key.getZipContainerPath() : null;
            this.zipEntryName = key != null ? key.getZipEntryName() : null;
            this.state = nextState;
        }

        private void markDeleted() {
            this.state = EditState.DELETED;
        }

        private void markConflict() {
            this.state = EditState.CONFLICT;
        }
    }

    private static final OpenViewRegistry INSTANCE = new OpenViewRegistry();

    private final List<RoiExplorerPanel> windows = new ArrayList<>();
    private final Map<UUID, EditSession> sessionsById = new HashMap<>();
    private final Map<PathKey, UUID> activeSessionByPath = new HashMap<>();
    private final Set<Path> hiddenRois = new HashSet<>();

    private OpenViewRegistry() {}

    public static OpenViewRegistry getInstance() {
        return INSTANCE;
    }

    public void register(RoiExplorerPanel window) {
        if (!windows.contains(window)) {
            windows.add(window);
        }
    }

    public void unregister(RoiExplorerPanel window) {
        windows.remove(window);
        List<UUID> ownedSessions = new ArrayList<>();
        for (Map.Entry<UUID, EditSession> entry : sessionsById.entrySet()) {
            if (entry.getValue().getOwnerWindow() == window) {
                ownedSessions.add(entry.getKey());
            }
        }
        for (UUID sessionId : ownedSessions) {
            endEdit(sessionId);
        }
    }

    public UUID tryStartEdit(PathKey key, EditMode mode, RoiExplorerPanel window) {
        if (key == null || activeSessionByPath.containsKey(key)) return null;
        UUID sessionId = UUID.randomUUID();
        EditSession session = new EditSession(sessionId, mode, window, key, Revision.forPathKey(key));
        sessionsById.put(sessionId, session);
        activeSessionByPath.put(key, sessionId);
        return sessionId;
    }

    public void endEdit(UUID sessionId) {
        if (sessionId == null) return;
        EditSession session = sessionsById.remove(sessionId);
        if (session != null) {
            activeSessionByPath.remove(session.getCurrentKey());
        }
    }

    public boolean isBeingEdited(PathKey key) {
        return key != null && activeSessionByPath.containsKey(key);
    }

    public EditSession getSession(UUID sessionId) {
        return sessionsById.get(sessionId);
    }

    public Path getEditPath(UUID sessionId) {
        EditSession session = sessionsById.get(sessionId);
        return session != null ? session.getCurrentPath() : null;
    }

    public Revision currentRevision(UUID sessionId) {
        EditSession session = sessionsById.get(sessionId);
        return session != null ? Revision.forPathKey(session.getCurrentKey()) : null;
    }

    public void markConflict(UUID sessionId) {
        EditSession session = sessionsById.get(sessionId);
        if (session != null) {
            session.markConflict();
        }
    }

    public void notifyChildrenChanged(Path folderPath) {
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.onExternalChange(folderPath);
        }
    }

    public void notifyPathRenamed(Path oldPath, Path newPath) {
        moveHiddenPathPrefix(oldPath, newPath);
        moveFsSessionsByPrefix(oldPath, newPath);
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.onPathRenamed(oldPath, newPath);
        }
    }

    public void notifyTargetRenamed(PathKey oldKey, PathKey newKey) {
        if (oldKey == null || newKey == null) return;
        if (oldKey.getTargetType() == TargetType.FS_ROI && newKey.getTargetType() == TargetType.FS_ROI) {
            notifyPathRenamed(oldKey.getFilePath(), newKey.getFilePath());
            return;
        }
        UUID sessionId = activeSessionByPath.remove(oldKey);
        if (sessionId != null) {
            EditSession session = sessionsById.get(sessionId);
            if (session != null) {
                EditState nextState = oldKey.getCurrentPath() != null
                        && newKey.getCurrentPath() != null
                        && Objects.equals(oldKey.getCurrentPath().getParent(), newKey.getCurrentPath().getParent())
                        ? EditState.RENAMED : EditState.MOVED;
                session.updateKey(newKey, nextState);
                activeSessionByPath.put(newKey, sessionId);
            }
        }
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.onPathRenamed(oldKey.getCurrentPath(), newKey.getCurrentPath());
        }
    }

    public void notifyPathDeleted(Path path) {
        clearHiddenInSubtree(path);
        clearFsSessionsInSubtree(path);
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.onPathDeleted(path);
        }
    }

    public void notifyTargetDeleted(PathKey key) {
        if (key == null) return;
        if (key.getTargetType() == TargetType.FS_ROI) {
            notifyPathDeleted(key.getFilePath());
            return;
        }
        UUID sessionId = activeSessionByPath.remove(key);
        if (sessionId != null) {
            EditSession session = sessionsById.get(sessionId);
            if (session != null) {
                session.markDeleted();
            }
        }
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.onPathDeleted(key.getCurrentPath());
        }
    }

    public List<RoiExplorerPanel> getPanels() {
        return Collections.unmodifiableList(windows);
    }

    public void refreshOverlaysFor(ij.ImagePlus image) {
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            if (w.getBoundImage() == image) {
                w.refreshOverlay();
            }
        }
    }

    public void refreshAllWindows() {
        for (RoiExplorerPanel w : new ArrayList<>(windows)) {
            w.reloadFromDisk();
        }
    }

    public boolean isHidden(Path roiPath) {
        return roiPath != null && hiddenRois.contains(roiPath);
    }

    public void setHidden(Path roiPath, boolean hidden) {
        if (roiPath == null) return;
        if (hidden) hiddenRois.add(roiPath);
        else hiddenRois.remove(roiPath);
    }

    public void clearHiddenInSubtree(Path path) {
        if (path == null) return;
        Set<Path> toRemove = new HashSet<>();
        for (Path hidden : hiddenRois) {
            if (hidden.startsWith(path)) {
                toRemove.add(hidden);
            }
        }
        hiddenRois.removeAll(toRemove);
    }

    private void moveHiddenPathPrefix(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) return;
        Map<Path, Path> remapped = new HashMap<>();
        for (Path hidden : hiddenRois) {
            if (hidden.startsWith(oldPath)) {
                remapped.put(hidden, newPath.resolve(oldPath.relativize(hidden)));
            }
        }
        hiddenRois.removeAll(remapped.keySet());
        hiddenRois.addAll(remapped.values());
    }

    private void moveFsSessionsByPrefix(Path oldPath, Path newPath) {
        if (oldPath == null || newPath == null) return;
        List<EditSession> sessions = new ArrayList<>(sessionsById.values());
        for (EditSession session : sessions) {
            PathKey currentKey = session.getCurrentKey();
            if (currentKey.getTargetType() != TargetType.FS_ROI) continue;
            Path currentPath = currentKey.getFilePath();
            if (currentPath != null && currentPath.startsWith(oldPath)) {
                activeSessionByPath.remove(currentKey);
                Path updatedPath = newPath.resolve(oldPath.relativize(currentPath));
                EditState nextState = Objects.equals(currentPath.getParent(), updatedPath.getParent())
                        ? EditState.RENAMED : EditState.MOVED;
                PathKey updatedKey = PathKey.forFsRoi(updatedPath);
                session.updateKey(updatedKey, nextState);
                activeSessionByPath.put(updatedKey, session.getEditSessionId());
            }
        }
    }

    private void clearFsSessionsInSubtree(Path path) {
        if (path == null) return;
        List<EditSession> sessions = new ArrayList<>(sessionsById.values());
        for (EditSession session : sessions) {
            PathKey key = session.getCurrentKey();
            if (key.getTargetType() == TargetType.FS_ROI && key.getFilePath() != null && key.getFilePath().startsWith(path)) {
                activeSessionByPath.remove(key);
                session.markDeleted();
            }
        }
    }
}
