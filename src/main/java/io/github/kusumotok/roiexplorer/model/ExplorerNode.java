package io.github.kusumotok.roiexplorer.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class ExplorerNode {

    private Path path;
    private ExplorerNode parent;
    private final List<ExplorerNode> children = new ArrayList<>();
    private boolean expanded = false;

    protected ExplorerNode(Path path, ExplorerNode parent) {
        this.path = path;
        this.parent = parent;
    }

    public abstract NodeType getType();

    public boolean isLeaf() {
        return false;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public ExplorerNode getParent() {
        return parent;
    }

    public void setParent(ExplorerNode parent) {
        this.parent = parent;
    }

    public List<ExplorerNode> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public void addChild(ExplorerNode child) {
        children.add(child);
    }

    public void insertChild(int index, ExplorerNode child) {
        children.add(index, child);
    }

    public void removeChild(ExplorerNode child) {
        children.remove(child);
    }

    public void clearChildren() {
        children.clear();
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public String getName() {
        Path fn = path.getFileName();
        return fn != null ? fn.toString() : path.toString();
    }

    public int getDepthRelativeTo(ExplorerNode root) {
        int depth = 0;
        ExplorerNode n = parent;
        while (n != null && n != root) {
            depth++;
            n = n.getParent();
        }
        return depth;
    }

    public int getRoiCount() {
        int count = 0;
        for (ExplorerNode child : children) {
            count += child.getRoiCount();
        }
        return count;
    }

    public void updatePathPrefix(Path oldPrefix, Path newPrefix) {
        if (path.startsWith(oldPrefix)) {
            path = newPrefix.resolve(oldPrefix.relativize(path));
        }
        for (ExplorerNode child : children) {
            child.updatePathPrefix(oldPrefix, newPrefix);
        }
    }

    @Override
    public String toString() {
        return getType() + ":" + getName();
    }
}
