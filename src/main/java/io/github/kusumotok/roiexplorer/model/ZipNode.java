package io.github.kusumotok.roiexplorer.model;

import java.nio.file.Path;

public class ZipNode extends ExplorerNode {

    public ZipNode(Path path, ExplorerNode parent) {
        super(path, parent);
    }

    @Override
    public NodeType getType() {
        return NodeType.ZIP;
    }
}
