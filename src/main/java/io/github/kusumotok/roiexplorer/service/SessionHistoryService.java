package io.github.kusumotok.roiexplorer.service;

import io.github.kusumotok.roiexplorer.OpenViewRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

public class SessionHistoryService {

    @FunctionalInterface
    public interface IoRunnable {
        void run() throws IOException;
    }

    private interface HistoryAction {
        String getLabel();
        void undo(OpenViewRegistry registry) throws IOException;
        void redo(OpenViewRegistry registry) throws IOException;
    }

    private static final SessionHistoryService INSTANCE = new SessionHistoryService();
    private static final int DEFAULT_MAX_HISTORY = 50;

    private final Deque<HistoryAction> undoStack = new ArrayDeque<>();
    private final Deque<HistoryAction> redoStack = new ArrayDeque<>();
    private int maxHistory = DEFAULT_MAX_HISTORY;

    private SessionHistoryService() {}

    public static SessionHistoryService getInstance() {
        return INSTANCE;
    }

    public synchronized boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public synchronized String getUndoLabel() {
        return canUndo() ? undoStack.peekLast().getLabel() : null;
    }

    public synchronized String getRedoLabel() {
        return canRedo() ? redoStack.peekLast().getLabel() : null;
    }

    public synchronized void clear() {
        undoStack.clear();
        redoStack.clear();
    }

    public void runUndoable(String label,
                            Collection<Path> fileTargets,
                            Collection<Path> hiddenTargets,
                            OpenViewRegistry registry,
                            IoRunnable action) throws IOException {
        SnapshotHistoryAction op = SnapshotHistoryAction.capture(label, fileTargets, hiddenTargets, registry, action);
        if (!op.isNoOp()) {
            push(op);
        }
    }

    public synchronized void undo(OpenViewRegistry registry) throws IOException {
        if (undoStack.isEmpty()) return;
        HistoryAction action = undoStack.removeLast();
        action.undo(registry);
        redoStack.addLast(action);
    }

    public synchronized void redo(OpenViewRegistry registry) throws IOException {
        if (redoStack.isEmpty()) return;
        HistoryAction action = redoStack.removeLast();
        action.redo(registry);
        undoStack.addLast(action);
    }

    private synchronized void push(HistoryAction action) {
        undoStack.addLast(action);
        redoStack.clear();
        while (undoStack.size() > maxHistory) {
            undoStack.removeFirst();
        }
    }

    private static final class SnapshotHistoryAction implements HistoryAction {
        private final String label;
        private final List<PathState> beforeStates;
        private final List<PathState> afterStates;
        private final Map<Path, Boolean> beforeHidden;
        private final Map<Path, Boolean> afterHidden;

        private SnapshotHistoryAction(String label,
                                      List<PathState> beforeStates,
                                      List<PathState> afterStates,
                                      Map<Path, Boolean> beforeHidden,
                                      Map<Path, Boolean> afterHidden) {
            this.label = label;
            this.beforeStates = beforeStates;
            this.afterStates = afterStates;
            this.beforeHidden = beforeHidden;
            this.afterHidden = afterHidden;
        }

        static SnapshotHistoryAction capture(String label,
                                             Collection<Path> fileTargets,
                                             Collection<Path> hiddenTargets,
                                             OpenViewRegistry registry,
                                             IoRunnable action) throws IOException {
            List<Path> normalizedTargets = normalizePaths(fileTargets);
            Map<Path, Boolean> beforeHidden = captureHidden(hiddenTargets, registry);
            List<PathState> beforeStates = captureStates(normalizedTargets);
            action.run();
            List<PathState> afterStates = captureStates(normalizedTargets);
            Map<Path, Boolean> afterHidden = captureHidden(hiddenTargets, registry);
            return new SnapshotHistoryAction(label, beforeStates, afterStates, beforeHidden, afterHidden);
        }

        boolean isNoOp() {
            return beforeStates.equals(afterStates) && beforeHidden.equals(afterHidden);
        }

        @Override
        public String getLabel() {
            return label;
        }

        @Override
        public void undo(OpenViewRegistry registry) throws IOException {
            restore(beforeStates, beforeHidden, registry);
        }

        @Override
        public void redo(OpenViewRegistry registry) throws IOException {
            restore(afterStates, afterHidden, registry);
        }

        private void restore(List<PathState> states,
                             Map<Path, Boolean> hiddenStates,
                             OpenViewRegistry registry) throws IOException {
            restoreFileStates(states);
            Set<Path> hiddenKeys = new LinkedHashSet<>();
            hiddenKeys.addAll(beforeHidden.keySet());
            hiddenKeys.addAll(afterHidden.keySet());
            for (Path path : hiddenKeys) {
                registry.setHidden(path, Boolean.TRUE.equals(hiddenStates.get(path)));
            }
            registry.refreshAllWindows();
        }
    }

    private static List<PathState> captureStates(Collection<Path> targets) throws IOException {
        List<PathState> states = new ArrayList<>();
        for (Path path : normalizePaths(targets)) {
            states.add(PathState.capture(path));
        }
        return states;
    }

    private static Map<Path, Boolean> captureHidden(Collection<Path> targets, OpenViewRegistry registry) {
        Map<Path, Boolean> states = new LinkedHashMap<>();
        if (targets == null) return states;
        for (Path path : targets) {
            if (path != null) {
                states.put(path, registry.isHidden(path));
            }
        }
        return states;
    }

    private static void restoreFileStates(List<PathState> states) throws IOException {
        List<PathState> sortedForDelete = new ArrayList<>(states);
        sortedForDelete.sort(Comparator.comparingInt((PathState s) -> pathDepth(s.path)).reversed());
        for (PathState state : sortedForDelete) {
            deleteRecursively(state.path);
        }
        List<PathState> sortedForCreate = new ArrayList<>(states);
        sortedForCreate.sort(Comparator.comparingInt(s -> pathDepth(s.path)));
        for (PathState state : sortedForCreate) {
            state.restore();
        }
    }

    private static List<Path> normalizePaths(Collection<Path> paths) {
        if (paths == null || paths.isEmpty()) return Collections.emptyList();
        List<Path> ordered = new ArrayList<>();
        for (Path path : paths) {
            if (path != null && !ordered.contains(path)) {
                ordered.add(path);
            }
        }
        ordered.sort(Comparator.comparingInt(SessionHistoryService::pathDepth));
        List<Path> normalized = new ArrayList<>();
        for (Path candidate : ordered) {
            boolean covered = false;
            for (Path kept : normalized) {
                if (candidate.startsWith(kept)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                normalized.add(candidate);
            }
        }
        return normalized;
    }

    private static int pathDepth(Path path) {
        return path == null ? -1 : path.getNameCount();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static final class PathState {
        private final Path path;
        private final boolean present;
        private final boolean directory;
        private final byte[] fileBytes;
        private final List<SubEntry> entries;

        private PathState(Path path, boolean present, boolean directory, byte[] fileBytes, List<SubEntry> entries) {
            this.path = path;
            this.present = present;
            this.directory = directory;
            this.fileBytes = fileBytes;
            this.entries = entries;
        }

        static PathState capture(Path path) throws IOException {
            if (path == null || !Files.exists(path)) {
                return new PathState(path, false, false, null, Collections.<SubEntry>emptyList());
            }
            if (Files.isDirectory(path)) {
                List<SubEntry> entries = new ArrayList<>();
                Files.walk(path).forEach(p -> {
                    if (p.equals(path)) return;
                    Path rel = path.relativize(p);
                    try {
                        if (Files.isDirectory(p)) {
                            entries.add(SubEntry.directory(rel));
                        } else {
                            entries.add(SubEntry.file(rel, Files.readAllBytes(p)));
                        }
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
                entries.sort(Comparator.comparingInt(e -> pathDepth(e.relative)));
                return new PathState(path, true, true, null, entries);
            }
            return new PathState(path, true, false, Files.readAllBytes(path), Collections.<SubEntry>emptyList());
        }

        void restore() throws IOException {
            if (!present || path == null) return;
            if (!directory) {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(path, fileBytes);
                return;
            }
            Files.createDirectories(path);
            for (SubEntry entry : entries) {
                Path target = path.resolve(entry.relative);
                if (entry.directory) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.write(target, entry.bytes);
                }
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PathState)) return false;
            PathState that = (PathState) o;
            return present == that.present
                    && directory == that.directory
                    && Objects.equals(path, that.path)
                    && Arrays.equals(fileBytes, that.fileBytes)
                    && Objects.equals(entries, that.entries);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(path, present, directory, entries);
            result = 31 * result + Arrays.hashCode(fileBytes);
            return result;
        }
    }

    private static final class SubEntry {
        private final Path relative;
        private final boolean directory;
        private final byte[] bytes;

        private SubEntry(Path relative, boolean directory, byte[] bytes) {
            this.relative = relative;
            this.directory = directory;
            this.bytes = bytes;
        }

        static SubEntry directory(Path relative) {
            return new SubEntry(relative, true, null);
        }

        static SubEntry file(Path relative, byte[] bytes) {
            return new SubEntry(relative, false, bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubEntry)) return false;
            SubEntry subEntry = (SubEntry) o;
            return directory == subEntry.directory
                    && Objects.equals(relative, subEntry.relative)
                    && Arrays.equals(bytes, subEntry.bytes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(relative, directory);
            result = 31 * result + Arrays.hashCode(bytes);
            return result;
        }
    }
}
