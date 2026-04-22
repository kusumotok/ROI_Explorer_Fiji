package io.github.kusumotok.roiexplorer.ui;

import io.github.kusumotok.roiexplorer.model.*;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public class TreeColumnRenderer extends JComponent implements TableCellRenderer {

    public static final int INDENT = 18;
    public static final int TRIANGLE_W = 16;
    private static final int ICON_SIZE = 13;

    private ExplorerNode currentNode;
    private ExplorerNode viewRoot;
    private boolean selected;
    private Color selBg, selFg, bg, fg;
    private boolean editingBadge;

    private final Map<String, Image> iconCache = new HashMap<>();

    public void setViewRoot(ExplorerNode root) {
        this.viewRoot = root;
    }

    public void markEditingNode(boolean badge) {
        this.editingBadge = badge;
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
                                                   boolean isSelected, boolean hasFocus,
                                                   int row, int col) {
        currentNode = value instanceof ExplorerNode ? (ExplorerNode) value : null;
        selected = isSelected;
        selBg = table.getSelectionBackground();
        selFg = table.getSelectionForeground();
        bg = table.getBackground();
        fg = table.getForeground();
        setFont(table.getFont());
        setPreferredSize(new Dimension(200, table.getRowHeight()));
        return this;
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (currentNode == null) return;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        int w = getWidth(), h = getHeight();
        g2.setColor(selected ? selBg : bg);
        g2.fillRect(0, 0, w, h);

        int depth = viewRoot != null ? currentNode.getDepthRelativeTo(viewRoot) : 0;
        int x = depth * INDENT + 2;
        int mid = h / 2;

        // disclosure triangle
        if (!currentNode.isLeaf()) {
            g2.setColor(selected ? selFg : new Color(100, 100, 100));
            drawTriangle(g2, x, mid, currentNode.isExpanded());
        }
        x += TRIANGLE_W;

        // node icon
        Image icon = getIcon(currentNode);
        if (icon != null) {
            g2.drawImage(icon, x, mid - ICON_SIZE / 2, ICON_SIZE, ICON_SIZE, null);
        }
        x += ICON_SIZE + 3;

        // editing badge
        if (editingBadge) {
            g2.setColor(new Color(30, 120, 200));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f));
            g2.drawString("\u270F", x, mid + 4);
            x += 14;
        }

        // name
        g2.setColor(selected ? selFg : fg);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String name = currentNode.getName();
        g2.drawString(name, x, mid + fm.getAscent() / 2 - 1);

        g2.dispose();
    }

    private void drawTriangle(Graphics2D g2, int x, int mid, boolean expanded) {
        if (expanded) {
            int[] px = {x + 2, x + 11, x + 6};
            int[] py = {mid - 4, mid - 4, mid + 3};
            g2.fillPolygon(px, py, 3);
        } else {
            int[] px = {x + 3, x + 3, x + 10};
            int[] py = {mid - 5, mid + 4, mid};
            g2.fillPolygon(px, py, 3);
        }
    }

    private Image getIcon(ExplorerNode node) {
        String key = iconKey(node);
        return iconCache.computeIfAbsent(key, k -> buildIcon(node));
    }

    private static String iconKey(ExplorerNode node) {
        if (node instanceof RoiNode) {
            RoiNode rn = (RoiNode) node;
            Color sc = rn.getStrokeColor();
            Color fc = rn.getFillColor();
            boolean hidden = rn.isHidden();
            int stroke = sc != null ? sc.getRGB() : 0;
            int fill = fc != null ? fc.getRGB() : 0;
            return "roi_" + stroke + "_" + fill + "_" + hidden;
        }
        return node.getType().name();
    }

    private static Image buildIcon(ExplorerNode node) {
        BufferedImage img = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (node instanceof RoiNode) {
            drawRoiIcon(g, (RoiNode) node);
        } else if (node instanceof ZipNode) {
            drawZipIcon(g);
        } else {
            drawFolderIcon(g);
        }
        g.dispose();
        return img;
    }

    private static void drawRoiIcon(Graphics2D g, RoiNode node) {
        Color sc = node.getStrokeColor();
        Color fc = node.getFillColor();
        boolean hidden = node.isHidden();

        Color fill = hidden ? new Color(180, 180, 180) : (fc != null ? fc : new Color(200, 225, 255));
        Color stroke = hidden ? new Color(120, 120, 120) : (sc != null ? sc : new Color(80, 160, 240));

        g.setColor(fill);
        g.fillRoundRect(1, 1, ICON_SIZE - 2, ICON_SIZE - 2, 3, 3);
        g.setColor(stroke);
        g.drawRoundRect(1, 1, ICON_SIZE - 2, ICON_SIZE - 2, 3, 3);

        if (hidden) {
            g.setColor(new Color(200, 0, 0, 200));
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(2, 2, ICON_SIZE - 2, ICON_SIZE - 2);
            g.drawLine(ICON_SIZE - 2, 2, 2, ICON_SIZE - 2);
        }
    }

    private static void drawFolderIcon(Graphics2D g) {
        g.setColor(new Color(255, 210, 80));
        int[] px = {1, 4, 4, ICON_SIZE - 1, ICON_SIZE - 1, 1};
        int[] py = {5, 5, 3, 3, ICON_SIZE - 1, ICON_SIZE - 1};
        g.fillPolygon(px, py, 6);
        g.setColor(new Color(200, 160, 40));
        g.drawPolygon(px, py, 6);
    }

    private static void drawZipIcon(Graphics2D g) {
        g.setColor(new Color(255, 210, 80));
        int[] px = {1, 4, 4, ICON_SIZE - 1, ICON_SIZE - 1, 1};
        int[] py = {5, 5, 3, 3, ICON_SIZE - 1, ICON_SIZE - 1};
        g.fillPolygon(px, py, 6);
        g.setColor(new Color(200, 160, 40));
        g.drawPolygon(px, py, 6);
        // zip indicator: small zigzag strip
        g.setColor(new Color(160, 100, 20));
        g.setStroke(new BasicStroke(1f));
        int cx = ICON_SIZE / 2;
        for (int y = 4; y < ICON_SIZE - 1; y += 2) {
            g.drawLine(cx - 1, y, cx + 1, y + 1);
        }
    }
}
