package io.github.kusumotok.roiexplorer.ui;

import io.github.kusumotok.roiexplorer.model.*;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class ExplorerTreeTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"Name", "Z", "C", "T", "Date", "ROI count"};
    public static final int COL_NAME = 0;
    public static final int COL_Z = 1;
    public static final int COL_C = 2;
    public static final int COL_T = 3;
    public static final int COL_DATE = 4;
    public static final int COL_ROI_COUNT = 5;

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");

    private ExplorerNode viewRoot;
    private final List<ExplorerNode> visibleRows = new ArrayList<>();

    private int sortColumn = -1;
    private boolean sortAscending = true;

    public void setRoot(ExplorerNode root) {
        this.viewRoot = root;
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    public ExplorerNode getViewRoot() {
        return viewRoot;
    }

    public ExplorerNode getNodeAt(int row) {
        if (row < 0 || row >= visibleRows.size()) return null;
        return visibleRows.get(row);
    }

    public int getRowOf(ExplorerNode node) {
        return visibleRows.indexOf(node);
    }

    public List<Path> snapshotSelection(int[] rows) {
        List<Path> paths = new ArrayList<>();
        for (int row : rows) {
            ExplorerNode node = getNodeAt(row);
            if (node != null) paths.add(node.getPath());
        }
        return paths;
    }

    public int[] restoreSelection(List<Path> paths) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < visibleRows.size(); i++) {
            if (paths.contains(visibleRows.get(i).getPath())) {
                rows.add(i);
            }
        }
        int[] arr = new int[rows.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = rows.get(i);
        return arr;
    }

    public void toggleExpansion(int row) {
        ExplorerNode node = getNodeAt(row);
        if (node == null || node.isLeaf()) return;
        node.setExpanded(!node.isExpanded());
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    public void expand(ExplorerNode node) {
        if (node == null || node.isLeaf() || node.isExpanded()) return;
        node.setExpanded(true);
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    public void expandAll(ExplorerNode from) {
        expandRecursive(from);
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    private void expandRecursive(ExplorerNode node) {
        if (node.isLeaf()) return;
        node.setExpanded(true);
        for (ExplorerNode child : node.getChildren()) {
            expandRecursive(child);
        }
    }

    public void fullRefresh() {
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    /** Returns paths of all currently expanded non-leaf nodes (entire tree, not just visible). */
    public Set<Path> snapshotExpandedPaths() {
        Set<Path> result = new HashSet<>();
        collectExpanded(viewRoot, result);
        return result;
    }

    private void collectExpanded(ExplorerNode node, Set<Path> result) {
        if (node == null || node.isLeaf()) return;
        if (node.isExpanded()) result.add(node.getPath());
        for (ExplorerNode child : node.getChildren()) collectExpanded(child, result);
    }

    /** Re-expands nodes whose paths are in the given set, then refreshes the view. */
    public void restoreExpanded(Set<Path> paths) {
        if (paths.isEmpty()) return;
        restoreExpandedRecursive(viewRoot, paths);
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    private void restoreExpandedRecursive(ExplorerNode node, Set<Path> paths) {
        if (node == null || node.isLeaf()) return;
        if (paths.contains(node.getPath())) node.setExpanded(true);
        for (ExplorerNode child : node.getChildren()) restoreExpandedRecursive(child, paths);
    }

    public void sortBy(int col, boolean ascending) {
        sortColumn = col;
        sortAscending = ascending;
        applySort(viewRoot);
        rebuildVisibleRows();
        fireTableDataChanged();
    }

    private void applySort(ExplorerNode parent) {
        if (parent == null) return;
        Comparator<ExplorerNode> cmp = buildComparator(sortColumn, sortAscending);
        parent.getChildren(); // trigger read
        List<ExplorerNode> sortable = new ArrayList<>();
        List<ExplorerNode> fixed = new ArrayList<>();
        for (ExplorerNode child : parent.getChildren()) {
            if (child instanceof RoiNode) sortable.add(child);
            else fixed.add(child);
        }
        sortable.sort(cmp);
        parent.clearChildren();
        for (ExplorerNode f : fixed) parent.addChild(f);
        for (ExplorerNode s : sortable) parent.addChild(s);
        for (ExplorerNode child : parent.getChildren()) {
            if (!child.isLeaf()) applySort(child);
        }
    }

    private Comparator<ExplorerNode> buildComparator(int col, boolean asc) {
        Comparator<ExplorerNode> cmp;
        switch (col) {
            case COL_Z:
                cmp = Comparator.comparingInt(n -> n instanceof RoiNode ? ((RoiNode) n).getZ() : 0);
                break;
            case COL_C:
                cmp = Comparator.comparingInt(n -> n instanceof RoiNode ? ((RoiNode) n).getC() : 0);
                break;
            case COL_T:
                cmp = Comparator.comparingInt(n -> n instanceof RoiNode ? ((RoiNode) n).getT() : 0);
                break;
            case COL_DATE:
                cmp = Comparator.comparingLong(n -> n.getPath().toFile().lastModified());
                break;
            default:
                cmp = Comparator.comparing(n -> n.getName().toLowerCase());
        }
        return asc ? cmp : cmp.reversed();
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        if (viewRoot != null) collectVisible(viewRoot);
    }

    private void collectVisible(ExplorerNode parent) {
        for (ExplorerNode child : parent.getChildren()) {
            visibleRows.add(child);
            if (!child.isLeaf() && child.isExpanded()) {
                collectVisible(child);
            }
        }
    }

    @Override
    public int getRowCount() {
        return visibleRows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int col) {
        return COLUMNS[col];
    }

    @Override
    public Class<?> getColumnClass(int col) {
        if (col == COL_NAME) return ExplorerNode.class;
        return Object.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return false;
    }

    @Override
    public Object getValueAt(int row, int col) {
        ExplorerNode node = getNodeAt(row);
        if (node == null) return null;
        switch (col) {
            case COL_NAME:
                return node;
            case COL_Z:
                return node instanceof RoiNode ? posStr(((RoiNode) node).getZ()) : null;
            case COL_C:
                return node instanceof RoiNode ? posStr(((RoiNode) node).getC()) : null;
            case COL_T:
                return node instanceof RoiNode ? posStr(((RoiNode) node).getT()) : null;
            case COL_DATE:
                long ts = node.getPath().toFile().lastModified();
                return ts > 0 ? dateFmt.format(new java.util.Date(ts)) : null;
            case COL_ROI_COUNT:
                return !(node instanceof RoiNode) ? node.getRoiCount() : null;
            default:
                return null;
        }
    }

    private static String posStr(int pos) {
        return pos == 0 ? "" : String.valueOf(pos);
    }
}
