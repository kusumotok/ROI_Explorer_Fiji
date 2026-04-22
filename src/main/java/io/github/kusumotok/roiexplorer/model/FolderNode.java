package io.github.kusumotok.roiexplorer.model;

import java.nio.file.Path;

public class FolderNode extends ExplorerNode {

    public FolderNode(Path path, ExplorerNode parent) {
        super(path, parent);
    }

    @Override
    public NodeType getType() {
        return NodeType.FOLDER;
    }
}
