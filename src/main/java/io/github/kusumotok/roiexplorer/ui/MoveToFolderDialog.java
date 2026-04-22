package io.github.kusumotok.roiexplorer.ui;

import io.github.kusumotok.roiexplorer.model.*;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class MoveToFolderDialog extends JDialog {

    private ExplorerNode selected = null;
    private final JTree tree;

    public MoveToFolderDialog(Window owner, ExplorerNode viewRoot, List<ExplorerNode> toMove) {
        super(owner, "Move to Folder", ModalityType.APPLICATION_MODAL);

        DefaultMutableTreeNode treeRoot = buildTree(viewRoot, toMove);
        tree = new JTree(treeRoot);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        expandAll(tree);

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(300, 300));

        JButton ok = new JButton("Move");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            TreePath tp = tree.getSelectionPath();
            if (tp != null) {
                Object last = tp.getLastPathComponent();
                if (last instanceof DefaultMutableTreeNode) {
                    Object userObj = ((DefaultMutableTreeNode) last).getUserObject();
                    if (userObj instanceof ExplorerNode) selected = (ExplorerNode) userObj;
                }
            }
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(ok);
        btnPanel.add(cancel);

        setLayout(new BorderLayout(4, 4));
        add(new JLabel("Select destination folder:"), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(btnPanel, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    public ExplorerNode getSelected() {
        return selected;
    }

    private DefaultMutableTreeNode buildTree(ExplorerNode node, List<ExplorerNode> excluded) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        for (ExplorerNode child : node.getChildren()) {
            if (excluded.contains(child)) continue;
            if (child instanceof FolderNode) {
                treeNode.add(buildTree(child, excluded));
            }
        }
        return treeNode;
    }

    private static void expandAll(JTree tree) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }
    }
}
