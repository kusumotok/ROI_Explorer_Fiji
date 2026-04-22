package io.github.kusumotok.roiexplorer.service;

import io.github.kusumotok.roiexplorer.model.*;

import java.util.*;

public class SelectionResolver {

    public List<RoiNode> resolveRoiNodes(List<ExplorerNode> selected, ExplorerNode viewRoot) {
        if (selected.isEmpty()) {
            return collectAllRois(viewRoot);
        }
        Set<RoiNode> result = new LinkedHashSet<>();
        for (ExplorerNode node : selected) {
            collectRois(node, result);
        }
        return new ArrayList<>(result);
    }

    public List<ExplorerNode> resolveGroupUnits(List<ExplorerNode> selected, ExplorerNode viewRoot) {
        if (selected.isEmpty()) {
            return Collections.singletonList(viewRoot);
        }
        return new ArrayList<>(selected);
    }

    private void collectRois(ExplorerNode node, Set<RoiNode> result) {
        if (node instanceof RoiNode) {
            result.add((RoiNode) node);
        } else {
            for (ExplorerNode child : node.getChildren()) {
                collectRois(child, result);
            }
        }
    }

    private List<RoiNode> collectAllRois(ExplorerNode root) {
        List<RoiNode> result = new ArrayList<>();
        if (root != null) collectRois(root, new LinkedHashSet<RoiNode>() {
            @Override
            public boolean add(RoiNode n) {
                result.add(n);
                return super.add(n);
            }
        });
        return result;
    }

    public List<RoiNode> roiNodesUnder(ExplorerNode node) {
        List<RoiNode> result = new ArrayList<>();
        collectRoisList(node, result);
        return result;
    }

    private void collectRoisList(ExplorerNode node, List<RoiNode> result) {
        if (node instanceof RoiNode) {
            result.add((RoiNode) node);
        } else {
            for (ExplorerNode child : node.getChildren()) {
                collectRoisList(child, result);
            }
        }
    }
}
