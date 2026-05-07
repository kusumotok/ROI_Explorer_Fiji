package io.github.kusumotok.roiexplorer.ui;

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.PriorityQueue;

public class SelectionEditToolsDialog extends JDialog {
    private static final String SPLIT_PARTS_KEY = "roiExplorer.splitParts";
    private static final String SPLIT_PARTS_ANCHOR_KEY = "roiExplorer.splitPartsAnchor";

    enum Tool {
        KNIFE("Knife"),
        SEED_SPLIT("Seed Split"),
        KEEP_LARGEST("Keep Largest Part"),
        REMOVE_SMALL("Remove Small Islands"),
        FILL_HOLES("Fill Holes"),
        EXPAND_SHRINK("Expand / Shrink");

        private final String label;

        Tool(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ImagePlus image;
    private final Roi originalRoi;
    private Roi workingRoi;
    private Roi previewRoi;
    private List<Roi> previewSplitParts;
    private boolean previewDirty;
    private Overlay baseOverlay;
    private final List<Point> seedPoints = new ArrayList<Point>();
    private ImageCanvas canvas;
    private MouseAdapter imageToolListener;
    private Point knifeStart;
    private Point knifeEnd;

    private final JList<Tool> toolList = new JList<>(Tool.values());
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JSpinner knifeAngle = new JSpinner(new SpinnerNumberModel(90.0, -180.0, 180.0, 1.0));
    private final JSpinner knifeOffset = new JSpinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 1.0));
    private final JSpinner knifeWidth = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 1));
    private final JCheckBox livePreview = new JCheckBox("Live Preview", true);
    private final JLabel imageHintLabel = new JLabel("Image input: inactive");
    private final JLabel knifeGuideLabel = new JLabel("Drag on image to define cut line");
    private final JLabel workflowHintLabel = new JLabel();
    private final JRadioButton seedRawMap = new JRadioButton("Bright objects on dark background", true);
    private final JRadioButton seedInvertMap = new JRadioButton("Dark objects on bright background");
    private final JButton seedAutoMaximaBtn = new JButton("Auto Maxima");
    private final JTextField seedField = new JTextField(18);
    private final JSpinner minIslandArea = new JSpinner(new SpinnerNumberModel(20.0, 0.0, 1_000_000.0, 1.0));
    private final JSpinner maxHoleArea = new JSpinner(new SpinnerNumberModel(20.0, 0.0, 1_000_000.0, 1.0));
    private final JSpinner expandPixels = new JSpinner(new SpinnerNumberModel(1, -1000, 1000, 1));

    public static List<Roi> consumePendingSplitParts(ImagePlus image, Roi currentRoi) {
        if (image == null) return null;
        Object partsObj = image.getProperty(SPLIT_PARTS_KEY);
        Object anchorObj = image.getProperty(SPLIT_PARTS_ANCHOR_KEY);
        if (!(partsObj instanceof List) || !(anchorObj instanceof byte[])) return null;
        byte[] currentBytes = currentRoi != null ? ij.io.RoiEncoder.saveAsByteArray(currentRoi) : null;
        if (!Arrays.equals((byte[]) anchorObj, currentBytes)) return null;
        @SuppressWarnings("unchecked")
        List<Roi> raw = (List<Roi>) partsObj;
        List<Roi> out = new ArrayList<Roi>();
        for (Roi roi : raw) {
            if (roi != null) out.add((Roi) roi.clone());
        }
        return out;
    }

    public static void clearPendingSplitParts(ImagePlus image) {
        if (image == null) return;
        image.setProperty(SPLIT_PARTS_KEY, null);
        image.setProperty(SPLIT_PARTS_ANCHOR_KEY, null);
    }

    public static void open(Window owner, ImagePlus image) {
        open(owner, image, Tool.KNIFE);
    }

    public static void open(Window owner, ImagePlus image, Tool initialTool) {
        if (image == null) {
            JOptionPane.showMessageDialog(owner, "No image is bound.", "Selection Edit Tools", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Roi roi = image.getRoi();
        if (roi == null) {
            JOptionPane.showMessageDialog(owner, "No active selection on the image.", "Selection Edit Tools", JOptionPane.WARNING_MESSAGE);
            return;
        }
        SelectionEditToolsDialog dlg = new SelectionEditToolsDialog(owner, image, roi, initialTool != null ? initialTool : Tool.KNIFE);
        dlg.setVisible(true);
    }

    private SelectionEditToolsDialog(Window owner, ImagePlus image, Roi roi, Tool initialTool) {
        super(owner, dialogTitleFor(initialTool), ModalityType.MODELESS);
        this.image = image;
        this.originalRoi = (Roi) roi.clone();
        this.workingRoi = (Roi) roi.clone();
        this.baseOverlay = cloneOverlay(image.getOverlay());
        buildUi(initialTool);
        pack();
        setMinimumSize(new Dimension(520, 280));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanupAndRestore();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                cleanupAndRestore();
            }
        });
    }

    private void buildUi(Tool initialTool) {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        toolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        toolList.setVisibleRowCount(Tool.values().length);
        toolList.setBorder(BorderFactory.createTitledBorder("Tools"));
        root.add(new JScrollPane(toolList,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.WEST);

        cardPanel.add(buildKnifePanel(), Tool.KNIFE.name());
        cardPanel.add(buildSeedSplitPanel(), Tool.SEED_SPLIT.name());
        cardPanel.add(buildMessagePanel("One-click cleanup for the current selection."), Tool.KEEP_LARGEST.name());
        cardPanel.add(buildLabeledPanel("Min area", minIslandArea, "Remove connected parts smaller than this area."), Tool.REMOVE_SMALL.name());
        cardPanel.add(buildLabeledPanel("Max hole area", maxHoleArea, "Fill enclosed holes up to this area."), Tool.FILL_HOLES.name());
        cardPanel.add(buildLabeledPanel("Pixels", expandPixels, "Positive expands, negative shrinks."), Tool.EXPAND_SHRINK.name());
        root.add(cardPanel, BorderLayout.CENTER);

        JPanel northPanel = new JPanel(new BorderLayout(8, 0));
        northPanel.add(livePreview, BorderLayout.WEST);
        northPanel.add(imageHintLabel, BorderLayout.CENTER);
        root.add(northPanel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton apply = new JButton("Apply");
        JButton reset = new JButton("Reset");
        JButton done = new JButton("Close");
        JButton cancel = new JButton("Cancel");
        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.add(workflowHintLabel, BorderLayout.NORTH);
        buttons.add(apply);
        buttons.add(reset);
        buttons.add(done);
        buttons.add(cancel);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        toolList.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting() && toolList.getSelectedValue() != null) {
                    cards.show(cardPanel, toolList.getSelectedValue().name());
                    updateImageInteractionState();
                    updateWorkflowHint();
                    updatePreviewFromControls(false);
                }
            }
        });
        apply.addActionListener(e -> applyCurrentTool());
        reset.addActionListener(e -> resetToOriginal());
        done.addActionListener(e -> {
            if (previewDirty) {
                image.setRoi((Roi) workingRoi.clone());
            }
            dispose();
        });
        cancel.addActionListener(e -> {
            image.setRoi((Roi) originalRoi.clone());
            dispose();
        });

        seedField.setText(defaultSeedText(originalRoi));
        seedPoints.clear();
        seedPoints.addAll(parseSeeds(seedField.getText()));
        wireControlListeners();
        setContentPane(root);
        toolList.setSelectedValue(initialTool, true);
        cards.show(cardPanel, initialTool.name());
        updateImageInteractionState();
        updateWorkflowHint();
        updatePreviewFromControls(false);
    }

    private JPanel buildMessagePanel(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setBorder(null);
        panel.add(area, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildLabeledPanel(String label, JComponent field, String help) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        panel.add(row, BorderLayout.NORTH);
        panel.add(buildMessagePanel(help), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildKnifePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 4));
        grid.add(new JLabel("Angle (deg)"));
        grid.add(knifeAngle);
        grid.add(new JLabel("Offset (px)"));
        grid.add(knifeOffset);
        grid.add(new JLabel("Cut width"));
        grid.add(knifeWidth);
        panel.add(grid, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.add(buildMessagePanel("Cuts the current selection with a straight line through the ROI bounds. Drag on the image to place the cut line. Width 0 keeps the cut as thin as possible."), BorderLayout.CENTER);
        center.add(knifeGuideLabel, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildSeedSplitPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JButton resetSeeds = new JButton("Reset Seeds");
        resetSeeds.addActionListener(e -> {
            seedField.setText(defaultSeedText(workingRoi));
            syncSeedsFromField();
        });
        row.add(seedField, BorderLayout.CENTER);
        JPanel rowButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rowButtons.add(seedAutoMaximaBtn);
        rowButtons.add(resetSeeds);
        row.add(rowButtons, BorderLayout.EAST);
        panel.add(row, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ButtonGroup surfaceGroup = new ButtonGroup();
        surfaceGroup.add(seedRawMap);
        surfaceGroup.add(seedInvertMap);
        modes.add(new JLabel("Surface"));
        modes.add(seedRawMap);
        modes.add(seedInvertMap);
        center.add(modes, BorderLayout.NORTH);
        center.add(buildMessagePanel("Click on the image to add seeds. Shift-click removes the nearest seed. Segmentation is limited to the current ROI and uses manual seeds with seeded watershed."), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private void applyCurrentTool() {
        Tool tool = toolList.getSelectedValue();
        ToolComputation computed = computeCurrentToolResult(workingRoi);
        Roi source = previewDirty && previewRoi != null ? previewRoi : computed.roi;
        if (source == null) {
            Toolkit.getDefaultToolkit().beep();
            JOptionPane.showMessageDialog(this, "The selected operation produced an empty selection.", "Selection Edit Tools", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        workingRoi = (Roi) source.clone();
        image.setRoi((Roi) workingRoi.clone());
        previewRoi = null;
        previewSplitParts = null;
        previewDirty = false;
        if (tool == Tool.KNIFE || tool == Tool.SEED_SPLIT) {
            storePendingSplitParts(computed.splitParts);
            if (tool == Tool.SEED_SPLIT) {
                seedField.setText(defaultSeedText(workingRoi));
                syncSeedsFromField();
            }
        } else {
            clearPendingSplitParts(image);
        }
        if (tool == Tool.KNIFE && livePreview.isSelected()) {
            knifeStart = null;
            knifeEnd = null;
        }
        updateGuideOverlay();
    }

    private void wireControlListeners() {
        ChangeListener previewListener = new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                updatePreviewFromControls(false);
            }
        };
        for (JSpinner spinner : new JSpinner[]{knifeAngle, knifeOffset, knifeWidth, minIslandArea, maxHoleArea, expandPixels}) {
            spinner.addChangeListener(previewListener);
        }
        livePreview.addActionListener(e -> updatePreviewFromControls(false));
        seedRawMap.addActionListener(e -> updatePreviewFromControls(false));
        seedInvertMap.addActionListener(e -> updatePreviewFromControls(false));
        seedAutoMaximaBtn.addActionListener(e -> autoSeedFromMaxima());
        seedField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { syncSeedsFromField(); }
            @Override public void removeUpdate(DocumentEvent e) { syncSeedsFromField(); }
            @Override public void changedUpdate(DocumentEvent e) { syncSeedsFromField(); }
        });
    }

    private void syncSeedsFromField() {
        seedPoints.clear();
        seedPoints.addAll(parseSeeds(seedField.getText()));
        updateGuideOverlay();
        updatePreviewFromControls(false);
    }

    private void updatePreviewFromControls(boolean forceImageRoi) {
        Tool tool = toolList.getSelectedValue();
        if (tool == null) return;
        ToolComputation computed = computeCurrentToolResult(workingRoi);
        previewRoi = computed.roi;
        previewSplitParts = computed.splitParts;
        previewDirty = (previewSplitParts != null && !previewSplitParts.isEmpty())
                || (previewRoi != null && !roiGeometricallyEqual(workingRoi, previewRoi));
        if (tool == Tool.KNIFE || tool == Tool.SEED_SPLIT) {
            storePendingSplitParts(previewSplitParts);
        } else {
            clearPendingSplitParts(image);
        }
        updateGuideOverlay();
        if (livePreview.isSelected() || forceImageRoi) {
            if ((tool == Tool.KNIFE || tool == Tool.SEED_SPLIT) && previewSplitParts != null && !previewSplitParts.isEmpty()) {
                image.setRoi((Roi) workingRoi.clone());
            } else {
                image.setRoi(previewRoi != null ? (Roi) previewRoi.clone() : (Roi) workingRoi.clone());
            }
        } else {
            image.setRoi((Roi) workingRoi.clone());
        }
    }

    private ToolComputation computeCurrentToolResult(Roi sourceBase) {
        if (sourceBase == null) return ToolComputation.empty();
        Tool selectedTool = toolList.getSelectedValue();
        if (selectedTool == null) selectedTool = Tool.KNIFE;
        Roi source = (Roi) sourceBase.clone();
        switch (selectedTool) {
            case KNIFE:
                return computeKnifeResult(source,
                        ((Number) knifeAngle.getValue()).doubleValue(),
                        ((Number) knifeOffset.getValue()).doubleValue(),
                        ((Number) knifeWidth.getValue()).intValue());
            case SEED_SPLIT:
                return computeSeedSplitResult(source, image, new ArrayList<Point>(seedPoints), seedInvertMap.isSelected());
            case KEEP_LARGEST:
                return ToolComputation.of(keepLargestPart(source));
            case REMOVE_SMALL:
                return ToolComputation.of(removeSmallIslands(source, ((Number) minIslandArea.getValue()).doubleValue()));
            case FILL_HOLES:
                return ToolComputation.of(fillHoles(source, ((Number) maxHoleArea.getValue()).doubleValue()));
            case EXPAND_SHRINK:
                return ToolComputation.of(expandOrShrink(source, ((Number) expandPixels.getValue()).intValue()));
            default:
                return ToolComputation.of(source);
        }
    }

    private void updateImageInteractionState() {
        uninstallImageInteraction();
        Tool tool = toolList.getSelectedValue();
        if (tool == null) return;
        canvas = image.getCanvas();
        if (canvas == null) {
            imageHintLabel.setText("Image input: no image canvas");
            return;
        }
        switch (tool) {
            case KNIFE:
                installKnifeInteraction();
                imageHintLabel.setText("Image input: drag to place knife line");
                break;
            case SEED_SPLIT:
                installSeedInteraction();
                imageHintLabel.setText("Image input: click to add seed, Shift-click to remove nearest");
                break;
            default:
                imageHintLabel.setText("Image input: inactive");
                break;
        }
        updateGuideOverlay();
    }

    private void updateWorkflowHint() {
        Tool tool = toolList.getSelectedValue();
        if (tool == Tool.KNIFE) {
            workflowHintLabel.setText("Knife: Apply updates the active selection preview for the current tool session.");
        } else if (tool == Tool.SEED_SPLIT) {
            workflowHintLabel.setText("Seed Split: Apply updates the active selection preview for the current tool session.");
        } else {
            workflowHintLabel.setText("Cleanup: Apply updates the active selection for the current edit session.");
        }
    }

    private void installKnifeInteraction() {
        imageToolListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                knifeStart = screenToImage(e);
                knifeEnd = knifeStart;
                updateKnifeFromPoints();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                knifeEnd = screenToImage(e);
                updateKnifeFromPoints();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                knifeEnd = screenToImage(e);
                updateKnifeFromPoints();
                if (livePreview.isSelected()) {
                    knifeStart = null;
                    knifeEnd = null;
                    updateGuideOverlay();
                }
            }
        };
        canvas.addMouseListener(imageToolListener);
        canvas.addMouseMotionListener(imageToolListener);
    }

    private void installSeedInteraction() {
        imageToolListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Point p = screenToImage(e);
                if (e.isShiftDown()) {
                    removeNearestSeed(p);
                } else {
                    seedPoints.add(p);
                }
                syncSeedFieldFromPoints();
                updateGuideOverlay();
                updatePreviewFromControls(false);
            }
        };
        canvas.addMouseListener(imageToolListener);
    }

    private void uninstallImageInteraction() {
        if (canvas != null && imageToolListener != null) {
            canvas.removeMouseListener(imageToolListener);
            canvas.removeMouseMotionListener(imageToolListener);
        }
        imageToolListener = null;
        canvas = null;
    }

    private void updateKnifeFromPoints() {
        if (knifeStart == null || knifeEnd == null) return;
        double dx = knifeEnd.x - knifeStart.x;
        double dy = knifeEnd.y - knifeStart.y;
        if (Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6) return;
        double tangentAngle = Math.toDegrees(Math.atan2(dy, dx));
        Rectangle b = workingRoi.getBounds();
        double cx = b.getCenterX();
        double cy = b.getCenterY();
        double theta = Math.toRadians(tangentAngle);
        double nx = -Math.sin(theta);
        double ny = Math.cos(theta);
        double mx = (knifeStart.x + knifeEnd.x) / 2.0;
        double my = (knifeStart.y + knifeEnd.y) / 2.0;
        double offset = (mx - cx) * nx + (my - cy) * ny;
        knifeAngle.setValue(normalizeAngle(tangentAngle));
        knifeOffset.setValue(offset);
        knifeGuideLabel.setText(String.format("Knife: (%.0f, %.0f) -> (%.0f, %.0f)", (double) knifeStart.x, (double) knifeStart.y, (double) knifeEnd.x, (double) knifeEnd.y));
        updatePreviewFromControls(false);
    }

    private void updateGuideOverlay() {
        Overlay merged = cloneOverlay(baseOverlay);
        if (merged == null) merged = new Overlay();
        Tool tool = toolList.getSelectedValue();
        if ((tool == Tool.KNIFE || tool == Tool.SEED_SPLIT) && previewSplitParts != null) {
            Color[] colors = new Color[]{
                    new Color(255, 140, 0, 220),
                    new Color(0, 170, 220, 220),
                    new Color(80, 200, 120, 220),
                    new Color(220, 80, 120, 220)
            };
            for (int i = 0; i < previewSplitParts.size(); i++) {
                Roi part = previewSplitParts.get(i);
                if (part == null) continue;
                Roi copy = (Roi) part.clone();
                copy.setFillColor(null);
                copy.setStrokeWidth(1.5f);
                copy.setStrokeColor(colors[i % colors.length]);
                merged.add(copy);
            }
        }
        if (tool == Tool.KNIFE && knifeStart != null && knifeEnd != null) {
            Line line = new Line(knifeStart.x, knifeStart.y, knifeEnd.x, knifeEnd.y);
            line.setStrokeColor(Color.ORANGE);
            line.setStrokeWidth(Math.max(1f, ((Number) knifeWidth.getValue()).intValue()));
            merged.add(line);
        } else if (tool == Tool.SEED_SPLIT) {
            for (Point p : seedPoints) {
                OvalRoi dot = new OvalRoi(p.x - 3, p.y - 3, 7, 7);
                dot.setFillColor(new Color(255, 120, 0, 180));
                dot.setStrokeColor(Color.BLACK);
                dot.setStrokeWidth(1f);
                merged.add(dot);
            }
            if (!seedPoints.isEmpty()) {
                PointRoi points = new PointRoi(pointsToFloatArrayX(seedPoints), pointsToFloatArrayY(seedPoints), seedPoints.size());
                points.setStrokeColor(Color.ORANGE);
                merged.add(points);
            }
        }
        image.setOverlay(merged.size() > 0 ? merged : null);
    }

    private void resetToOriginal() {
        workingRoi = (Roi) originalRoi.clone();
        previewRoi = null;
        previewSplitParts = null;
        previewDirty = false;
        knifeStart = null;
        knifeEnd = null;
        clearPendingSplitParts(image);
        seedField.setText(defaultSeedText(workingRoi));
        syncSeedsFromField();
        image.setRoi((Roi) originalRoi.clone());
    }

    private void cleanupAndRestore() {
        uninstallImageInteraction();
        clearPendingSplitParts(image);
        image.setOverlay(baseOverlay != null ? cloneOverlay(baseOverlay) : null);
        if (previewDirty) {
            image.setRoi((Roi) workingRoi.clone());
        }
    }

    private Point screenToImage(MouseEvent e) {
        ImageCanvas c = image.getCanvas();
        return new Point(c.offScreenX(e.getX()), c.offScreenY(e.getY()));
    }

    private void removeNearestSeed(Point p) {
        if (seedPoints.isEmpty()) return;
        int bestIdx = -1;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < seedPoints.size(); i++) {
            Point s = seedPoints.get(i);
            double d2 = s.distanceSq(p);
            if (d2 < best) {
                best = d2;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) seedPoints.remove(bestIdx);
    }

    private void autoSeedFromMaxima() {
        if (workingRoi == null || image == null) return;
        ByteProcessor domain = createMask(workingRoi);
        if (domain == null) return;
        Rectangle bounds = workingRoi.getBounds();
        FloatProcessor fp = extractSurface(bounds, seedInvertMap.isSelected());
        if (fp == null) return;
        MaximumFinder finder = new MaximumFinder();
        ByteProcessor maxima = finder.findMaxima(fp, 10.0, ImageProcessor.NO_THRESHOLD, MaximumFinder.SINGLE_POINTS, false, false);
        List<Point> autoSeeds = new ArrayList<Point>();
        for (int y = 0; y < maxima.getHeight(); y++) {
            for (int x = 0; x < maxima.getWidth(); x++) {
                if ((maxima.get(x, y) & 0xff) == 0) continue;
                if ((domain.get(x, y) & 0xff) == 0) continue;
                autoSeeds.add(new Point(x + bounds.x, y + bounds.y));
            }
        }
        if (autoSeeds.isEmpty()) return;
        seedPoints.clear();
        seedPoints.addAll(autoSeeds);
        syncSeedFieldFromPoints();
        updateGuideOverlay();
        updatePreviewFromControls(false);
    }

    private void syncSeedFieldFromPoints() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seedPoints.size(); i++) {
            if (i > 0) sb.append("; ");
            Point p = seedPoints.get(i);
            sb.append(p.x).append(",").append(p.y);
        }
        seedField.setText(sb.toString());
    }

    private void storePendingSplitParts(List<Roi> parts) {
        if (parts == null || parts.size() < 2) {
            clearPendingSplitParts(image);
            return;
        }
        List<Roi> stored = new ArrayList<Roi>();
        for (Roi part : parts) {
            if (part != null) stored.add((Roi) part.clone());
        }
        if (stored.size() < 2) {
            clearPendingSplitParts(image);
            return;
        }
        image.setProperty(SPLIT_PARTS_KEY, stored);
        image.setProperty(SPLIT_PARTS_ANCHOR_KEY, ij.io.RoiEncoder.saveAsByteArray(workingRoi));
    }

    private static float[] pointsToFloatArrayX(List<Point> points) {
        float[] xs = new float[points.size()];
        for (int i = 0; i < points.size(); i++) xs[i] = points.get(i).x;
        return xs;
    }

    private static float[] pointsToFloatArrayY(List<Point> points) {
        float[] ys = new float[points.size()];
        for (int i = 0; i < points.size(); i++) ys[i] = points.get(i).y;
        return ys;
    }

    private static Overlay cloneOverlay(Overlay overlay) {
        if (overlay == null) return null;
        Overlay copy = new Overlay();
        for (int i = 0; i < overlay.size(); i++) {
            Roi roi = overlay.get(i);
            if (roi != null) copy.add((Roi) roi.clone());
        }
        return copy;
    }

    private static boolean roiGeometricallyEqual(Roi a, Roi b) {
        if (a == null || b == null) return a == b;
        Rectangle ra = a.getBounds();
        Rectangle rb = b.getBounds();
        return ra.equals(rb) && a.getType() == b.getType();
    }

    private static double normalizeAngle(double angle) {
        double out = angle;
        while (out > 180.0) out -= 360.0;
        while (out <= -180.0) out += 360.0;
        return out;
    }

    private static String dialogTitleFor(Tool tool) {
        if (tool == Tool.KNIFE || tool == Tool.SEED_SPLIT) {
            return "Split Tools";
        }
        return "Cleanup Tools";
    }

    private static Roi applyKnife(Roi source, double angleDeg, double offsetPx, int cutWidth) {
        ByteProcessor mask = createMask(source);
        if (mask == null) return cloneOrNull(source);
        Rectangle bounds = source.getBounds();
        double cx = bounds.getWidth() / 2.0;
        double cy = bounds.getHeight() / 2.0;
        double theta = Math.toRadians(angleDeg);
        double nx = -Math.sin(theta);
        double ny = Math.cos(theta);
        if (cutWidth <= 0) {
            clearZeroWidthKnifeLine(mask, bounds, angleDeg, offsetPx);
            return componentMaskToRoi(source, mask);
        }
        double halfWidth = cutWidth / 2.0;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (!isForeground(mask, x, y)) continue;
                double dist = Math.abs(((x + 0.5) - cx) * nx + ((y + 0.5) - cy) * ny - offsetPx);
                if (dist <= halfWidth) {
                    mask.set(x, y, 0);
                }
            }
        }
        return componentMaskToRoi(source, mask);
    }

    private static ToolComputation computeKnifeResult(Roi source, double angleDeg, double offsetPx, int cutWidth) {
        List<Roi> parts = knifeSplitParts(source, angleDeg, offsetPx);
        if (parts.size() >= 2) {
            return ToolComputation.of(joinRois(parts, source), parts);
        }
        return ToolComputation.of(applyKnife(source, angleDeg, offsetPx, cutWidth));
    }

    private static List<Roi> knifeSplitParts(Roi source, double angleDeg, double offsetPx) {
        ByteProcessor mask = createMask(source);
        if (mask == null) return new ArrayList<Roi>();
        Rectangle bounds = source.getBounds();
        double cx = bounds.getWidth() / 2.0;
        double cy = bounds.getHeight() / 2.0;
        double theta = Math.toRadians(angleDeg);
        double nx = -Math.sin(theta);
        double ny = Math.cos(theta);
        ByteProcessor a = new ByteProcessor(mask.getWidth(), mask.getHeight());
        ByteProcessor b = new ByteProcessor(mask.getWidth(), mask.getHeight());
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (!isForeground(mask, x, y)) continue;
                double signed = ((x + 0.5) - cx) * nx + ((y + 0.5) - cy) * ny - offsetPx;
                if (signed < 0) a.set(x, y, 255);
                else b.set(x, y, 255);
            }
        }
        List<Roi> parts = new ArrayList<Roi>();
        Roi ra = componentMaskToRoi(source, a);
        Roi rb = componentMaskToRoi(source, b);
        if (ra != null) parts.add(ra);
        if (rb != null) parts.add(rb);
        return parts;
    }

    private static void clearZeroWidthKnifeLine(ByteProcessor mask, Rectangle bounds, double angleDeg, double offsetPx) {
        Point[] endpoints = knifeLineEndpoints(bounds, angleDeg, offsetPx);
        if (endpoints == null) return;
        rasterizeMinimalCut(mask,
                endpoints[0].x - bounds.x, endpoints[0].y - bounds.y,
                endpoints[1].x - bounds.x, endpoints[1].y - bounds.y);
    }

    private static Point[] knifeLineEndpoints(Rectangle bounds, double angleDeg, double offsetPx) {
        double cx = bounds.getCenterX();
        double cy = bounds.getCenterY();
        double theta = Math.toRadians(angleDeg);
        double tx = Math.cos(theta);
        double ty = Math.sin(theta);
        double nx = -Math.sin(theta);
        double ny = Math.cos(theta);
        double mx = cx + offsetPx * nx;
        double my = cy + offsetPx * ny;
        List<Point> hits = new ArrayList<Point>();

        addIntersection(hits, mx, my, tx, ty, bounds.x, true, bounds);
        addIntersection(hits, mx, my, tx, ty, bounds.x + bounds.width - 1, true, bounds);
        addIntersection(hits, mx, my, tx, ty, bounds.y, false, bounds);
        addIntersection(hits, mx, my, tx, ty, bounds.y + bounds.height - 1, false, bounds);

        if (hits.size() < 2) return null;
        return new Point[]{hits.get(0), hits.get(1)};
    }

    private static void addIntersection(List<Point> hits, double mx, double my, double tx, double ty,
                                        double fixed, boolean vertical, Rectangle bounds) {
        double t;
        double x;
        double y;
        if (vertical) {
            if (Math.abs(tx) < 1e-9) return;
            t = (fixed - mx) / tx;
            x = fixed;
            y = my + t * ty;
            if (y < bounds.y - 0.5 || y > bounds.y + bounds.height - 0.5) return;
        } else {
            if (Math.abs(ty) < 1e-9) return;
            t = (fixed - my) / ty;
            y = fixed;
            x = mx + t * tx;
            if (x < bounds.x - 0.5 || x > bounds.x + bounds.width - 0.5) return;
        }
        Point p = new Point((int) Math.round(x), (int) Math.round(y));
        for (Point existing : hits) {
            if (existing.equals(p)) return;
        }
        hits.add(p);
    }

    private static void rasterizeMinimalCut(ByteProcessor mask, int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            clearIfInside(mask, x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private static void clearIfInside(ByteProcessor mask, int x, int y) {
        if (x >= 0 && x < mask.getWidth() && y >= 0 && y < mask.getHeight()) {
            mask.set(x, y, 0);
        }
    }

    private static Roi applySeedSplit(Roi source, ImagePlus image, List<Point> seeds, boolean invertMap) {
        if (image == null || seeds.size() < 2) return cloneOrNull(source);
        ByteProcessor domain = createMask(source);
        if (domain == null) return cloneOrNull(source);
        Rectangle bounds = source.getBounds();
        int w = domain.getWidth();
        int h = domain.getHeight();
        SeedLabelBuild seedBuild = buildSeedLabels(bounds, domain, seeds, w, h);
        int[] seedLabels = seedBuild.seedLabels;
        int nextLabel = seedBuild.nextLabel;
        if (nextLabel <= 2) return cloneOrNull(source);

        FloatProcessor surface = extractSurface(image, bounds, invertMap);
        int[] labels = runSeededWatershed(surface, domain, seedLabels);
        ByteProcessor output = labelsToSeparatedMask(labels, domain, w, h);
        return componentMaskToRoi(source, output);
    }

    private static ToolComputation computeSeedSplitResult(Roi source, ImagePlus image, List<Point> seeds, boolean invertMap) {
        List<Roi> parts = seedSplitParts(source, image, seeds, invertMap);
        if (parts.size() >= 2) {
            return ToolComputation.of(joinRois(parts, source), parts);
        }
        return ToolComputation.of(applySeedSplit(source, image, seeds, invertMap));
    }

    private static List<Roi> seedSplitParts(Roi source, ImagePlus image, List<Point> seeds, boolean invertMap) {
        List<Roi> parts = new ArrayList<Roi>();
        if (image == null || seeds.size() < 2) return parts;
        ByteProcessor domain = createMask(source);
        if (domain == null) return parts;
        Rectangle bounds = source.getBounds();
        int w = domain.getWidth();
        int h = domain.getHeight();
        SeedLabelBuild seedBuild = buildSeedLabels(bounds, domain, seeds, w, h);
        int[] seedLabels = seedBuild.seedLabels;
        int nextLabel = seedBuild.nextLabel;
        if (nextLabel <= 2) return parts;
        FloatProcessor surface = extractSurface(image, bounds, invertMap);
        int[] labels = runSeededWatershed(surface, domain, seedLabels);
        for (int label = 1; label < nextLabel; label++) {
            ByteProcessor partMask = new ByteProcessor(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((domain.get(x, y) & 0xff) == 0) continue;
                    if (labels[y * w + x] == label) {
                        partMask.set(x, y, 255);
                    }
                }
            }
            Roi part = componentMaskToRoi(source, partMask);
            if (part != null) parts.add(part);
        }
        return parts;
    }

    private static SeedLabelBuild buildSeedLabels(Rectangle bounds, ByteProcessor domain,
                                                  List<Point> seeds, int w, int h) {
        int[] seedLabels = new int[w * h];
        int nextLabel = 1;
        for (Point seed : seeds) {
            if (!bounds.contains(seed)) continue;
            int sx = seed.x - bounds.x;
            int sy = seed.y - bounds.y;
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            // Keep the spot quantifier model: the ROI mask is only the watershed
            // domain/constraint, and explicit seed points alone become markers.
            if ((domain.get(sx, sy) & 0xff) == 0) continue;
            if (seedLabels[sy * w + sx] != 0) continue;
            seedLabels[sy * w + sx] = nextLabel++;
        }
        return new SeedLabelBuild(seedLabels, nextLabel);
    }

    private static Roi keepLargestPart(Roi source) {
        List<Component> components = componentsFromRoi(source);
        if (components.isEmpty()) return cloneOrNull(source);
        Component largest = null;
        for (Component component : components) {
            if (largest == null || component.area > largest.area) largest = component;
        }
        return componentMaskToRoi(source, largest.mask);
    }

    private static Roi removeSmallIslands(Roi source, double minArea) {
        List<Component> components = componentsFromRoi(source);
        if (components.isEmpty()) return cloneOrNull(source);
        ByteProcessor merged = createEmptyMask(source);
        for (Component component : components) {
            if (component.area + 1e-9 < minArea) continue;
            orMask(merged, component.mask);
        }
        return componentMaskToRoi(source, merged);
    }

    private static Roi fillHoles(Roi source, double maxHoleArea) {
        ByteProcessor mask = createMask(source);
        if (mask == null) return cloneOrNull(source);
        boolean[][] visited = new boolean[mask.getHeight()][mask.getWidth()];
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (visited[y][x] || isForeground(mask, x, y)) continue;
                HoleComponent hole = floodBackground(mask, visited, x, y);
                if (hole == null || hole.touchesEdge) continue;
                if (maxHoleArea > 0 && hole.area - 1e-9 > maxHoleArea) continue;
                for (Point p : hole.points) {
                    mask.set(p.x, p.y, 255);
                }
            }
        }
        return componentMaskToRoi(source, mask);
    }

    private static Roi expandOrShrink(Roi source, int pixels) {
        ByteProcessor mask = createMask(source);
        if (mask == null) return cloneOrNull(source);
        int steps = Math.abs(pixels);
        for (int i = 0; i < steps; i++) {
            if (pixels >= 0) mask.dilate();
            else mask.erode();
        }
        return componentMaskToRoi(source, mask);
    }

    private static List<Component> componentsFromRoi(Roi source) {
        ByteProcessor mask = createMask(source);
        if (mask == null) return new ArrayList<Component>();
        boolean[][] visited = new boolean[mask.getHeight()][mask.getWidth()];
        List<Component> components = new ArrayList<Component>();
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if (visited[y][x] || !isForeground(mask, x, y)) continue;
                components.add(floodForeground(mask, visited, x, y));
            }
        }
        return components;
    }

    private static Component floodForeground(ByteProcessor mask, boolean[][] visited, int startX, int startY) {
        ByteProcessor componentMask = new ByteProcessor(mask.getWidth(), mask.getHeight());
        Deque<Point> queue = new ArrayDeque<Point>();
        queue.add(new Point(startX, startY));
        visited[startY][startX] = true;
        int area = 0;
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            componentMask.set(p.x, p.y, 255);
            area++;
            for (Point next : neighbors(p.x, p.y, mask.getWidth(), mask.getHeight())) {
                if (visited[next.y][next.x] || !isForeground(mask, next.x, next.y)) continue;
                visited[next.y][next.x] = true;
                queue.addLast(next);
            }
        }
        return new Component(componentMask, area);
    }

    private static HoleComponent floodBackground(ByteProcessor mask, boolean[][] visited, int startX, int startY) {
        Deque<Point> queue = new ArrayDeque<Point>();
        List<Point> points = new ArrayList<Point>();
        queue.add(new Point(startX, startY));
        visited[startY][startX] = true;
        boolean touchesEdge = false;
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            points.add(p);
            if (p.x == 0 || p.y == 0 || p.x == mask.getWidth() - 1 || p.y == mask.getHeight() - 1) {
                touchesEdge = true;
            }
            for (Point next : neighbors(p.x, p.y, mask.getWidth(), mask.getHeight())) {
                if (visited[next.y][next.x] || isForeground(mask, next.x, next.y)) continue;
                visited[next.y][next.x] = true;
                queue.addLast(next);
            }
        }
        return new HoleComponent(points, touchesEdge);
    }

    private static List<Point> neighbors(int x, int y, int width, int height) {
        List<Point> out = new ArrayList<Point>(4);
        if (x > 0) out.add(new Point(x - 1, y));
        if (x + 1 < width) out.add(new Point(x + 1, y));
        if (y > 0) out.add(new Point(x, y - 1));
        if (y + 1 < height) out.add(new Point(x, y + 1));
        return out;
    }

    private static boolean isForeground(ByteProcessor mask, int x, int y) {
        return (mask.get(x, y) & 0xff) != 0;
    }

    private static ByteProcessor createEmptyMask(Roi roi) {
        Rectangle bounds = roi.getBounds();
        int w = Math.max(1, bounds.width);
        int h = Math.max(1, bounds.height);
        return new ByteProcessor(w, h);
    }

    private static ByteProcessor createMask(Roi roi) {
        if (roi == null) return null;
        Rectangle bounds = roi.getBounds();
        int w = Math.max(1, bounds.width);
        int h = Math.max(1, bounds.height);
        ByteProcessor bp = new ByteProcessor(w, h);
        ImageProcessor mask = roi.getMask();
        if (mask != null) {
            for (int y = 0; y < mask.getHeight(); y++) {
                for (int x = 0; x < mask.getWidth(); x++) {
                    if ((mask.get(x, y) & 0xff) != 0) bp.set(x, y, 255);
                }
            }
        } else {
            byte[] pixels = (byte[]) bp.getPixels();
            Arrays.fill(pixels, (byte) 255);
        }
        return bp;
    }

    private FloatProcessor extractSurface(Rectangle bounds, boolean invertMap) {
        return extractSurface(image, bounds, invertMap);
    }

    private static FloatProcessor extractSurface(ImagePlus image, Rectangle bounds, boolean invertMap) {
        if (image == null || bounds == null) return null;
        ImageProcessor ip = image.getProcessor().convertToFloatProcessor();
        FloatProcessor fp = new FloatProcessor(Math.max(1, bounds.width), Math.max(1, bounds.height));
        for (int y = 0; y < fp.getHeight(); y++) {
            for (int x = 0; x < fp.getWidth(); x++) {
                float v = ip.getPixelValue(x + bounds.x, y + bounds.y);
                fp.setf(x, y, v);
            }
        }
        if (invertMap) {
            float[] px = (float[]) fp.getPixels();
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float v : px) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            float sum = min + max;
            for (int i = 0; i < px.length; i++) {
                px[i] = sum - px[i];
            }
        }
        return fp;
    }

    private static int[] runSeededWatershed(FloatProcessor surface, ByteProcessor domain, int[] seedLabels) {
        int w = surface.getWidth();
        int h = surface.getHeight();
        int size = w * h;
        float[] topo = (float[]) surface.getPixels();
        int[] labels = new int[size];
        Arrays.fill(labels, -1);
        PriorityQueue<WsNode> pq = new PriorityQueue<>(Comparator
                .comparingDouble((WsNode n) -> n.priority)
                .thenComparingLong(n -> n.seq));
        long seq = 0;
        for (int i = 0; i < size; i++) {
            if ((domain.get(i % w, i / w) & 0xff) == 0) {
                labels[i] = 0;
                continue;
            }
            // Voxels inside the domain are not implicit seeds. Only seedLabels[]
            // may initialize flooding, which prevents the whole mask acting as one seed.
            if (seedLabels[i] > 0) {
                labels[i] = seedLabels[i];
                pq.add(new WsNode(i, seedLabels[i], topo[i], seq++));
            }
        }
        int[] dx = {-1, 0, 1, 0, -1, 1, -1, 1};
        int[] dy = {0, -1, 0, 1, -1, -1, 1, 1};
        while (!pq.isEmpty()) {
            WsNode n = pq.poll();
            int x = n.idx % w;
            int y = n.idx / w;
            for (int k = 0; k < 8; k++) {
                int nx = x + dx[k];
                int ny = y + dy[k];
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                if ((domain.get(nx, ny) & 0xff) == 0) continue;
                int ni = ny * w + nx;
                if (labels[ni] != -1) continue;
                labels[ni] = n.label;
                float nextPriority = Math.max(n.priority, topo[ni]);
                pq.add(new WsNode(ni, n.label, nextPriority, seq++));
            }
        }
        for (int i = 0; i < size; i++) {
            if (labels[i] < 0) labels[i] = 0;
        }
        return labels;
    }

    private static ByteProcessor labelsToSeparatedMask(int[] labels, ByteProcessor domain, int w, int h) {
        ByteProcessor out = new ByteProcessor(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((domain.get(x, y) & 0xff) == 0) continue;
                int idx = y * w + x;
                int label = labels[idx];
                if (label <= 0) continue;
                boolean border = false;
                for (Point next : neighbors(x, y, w, h)) {
                    if ((domain.get(next.x, next.y) & 0xff) == 0) continue;
                    int nextLabel = labels[next.y * w + next.x];
                    if (nextLabel > 0 && nextLabel != label) {
                        border = true;
                        break;
                    }
                }
                if (!border) out.set(x, y, 255);
            }
        }
        return out;
    }

    private static void orMask(ByteProcessor target, ByteProcessor source) {
        for (int y = 0; y < target.getHeight(); y++) {
            for (int x = 0; x < target.getWidth(); x++) {
                if ((source.get(x, y) & 0xff) != 0) target.set(x, y, 255);
            }
        }
    }

    private static Roi componentMaskToRoi(Roi source, ByteProcessor mask) {
        if (mask == null) return null;
        if (countForeground(mask) == 0) return null;
        mask.setThreshold(255, 255, ImageProcessor.NO_LUT_UPDATE);
        Roi roi = new ThresholdToSelection().convert(mask);
        if (roi == null) return null;
        Rectangle bounds = source.getBounds();
        roi.setLocation(bounds.x, bounds.y);
        copyVisualProperties(source, roi);
        return roi;
    }

    private static int countForeground(ByteProcessor mask) {
        int count = 0;
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                if ((mask.get(x, y) & 0xff) != 0) count++;
            }
        }
        return count;
    }

    private static void copyVisualProperties(Roi source, Roi target) {
        if (source.getName() != null) target.setName(source.getName());
        target.setFillColor(source.getFillColor());
        target.setStrokeColor(source.getStrokeColor());
        target.setStrokeWidth(source.getStrokeWidth());
        int c = source.getCPosition();
        int z = source.getZPosition();
        int t = source.getTPosition();
        if (c > 0 || z > 0 || t > 0) target.setPosition(c, z, t);
    }

    private static Roi cloneOrNull(Roi roi) {
        return roi != null ? (Roi) roi.clone() : null;
    }

    private static Roi joinRois(List<Roi> parts, Roi source) {
        if (parts == null || parts.isEmpty()) return null;
        ij.gui.ShapeRoi merged = null;
        for (Roi part : parts) {
            if (part == null) continue;
            ij.gui.ShapeRoi shape = new ij.gui.ShapeRoi(part);
            merged = merged == null ? shape : merged.or(shape);
        }
        if (merged == null) return null;
        copyVisualProperties(source, merged);
        return merged;
    }

    private static final class ToolComputation {
        private final Roi roi;
        private final List<Roi> splitParts;

        private ToolComputation(Roi roi, List<Roi> splitParts) {
            this.roi = roi;
            this.splitParts = splitParts;
        }

        static ToolComputation empty() {
            return new ToolComputation(null, null);
        }

        static ToolComputation of(Roi roi) {
            return new ToolComputation(roi, null);
        }

        static ToolComputation of(Roi roi, List<Roi> splitParts) {
            return new ToolComputation(roi, splitParts);
        }
    }

    private static List<Point> parseSeeds(String text) {
        List<Point> seeds = new ArrayList<Point>();
        if (text == null || text.trim().isEmpty()) return seeds;
        String[] parts = text.split(";");
        for (String part : parts) {
            String[] xy = part.trim().split(",");
            if (xy.length != 2) continue;
            try {
                int x = Integer.parseInt(xy[0].trim());
                int y = Integer.parseInt(xy[1].trim());
                seeds.add(new Point(x, y));
            } catch (NumberFormatException ignored) {
            }
        }
        return seeds;
    }

    private static String defaultSeedText(Roi roi) {
        Rectangle b = roi.getBounds();
        int y = b.y + Math.max(0, b.height / 2);
        int x1 = b.x + Math.max(0, b.width / 3);
        int x2 = b.x + Math.max(0, (b.width * 2) / 3);
        return x1 + "," + y + "; " + x2 + "," + y;
    }

    private static int nearestSeedIndex(List<Point> seeds, int x, int y) {
        int best = -1;
        double bestD2 = Double.POSITIVE_INFINITY;
        for (int i = 0; i < seeds.size(); i++) {
            Point p = seeds.get(i);
            double dx = x - p.x;
            double dy = y - p.y;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    private static final class Component {
        final ByteProcessor mask;
        final double area;

        Component(ByteProcessor mask, double area) {
            this.mask = mask;
            this.area = area;
        }
    }

    private static final class HoleComponent {
        final List<Point> points;
        final boolean touchesEdge;
        final double area;

        HoleComponent(List<Point> points, boolean touchesEdge) {
            this.points = points;
            this.touchesEdge = touchesEdge;
            this.area = points.size();
        }
    }

    private static final class WsNode {
        final int idx;
        final int label;
        final float priority;
        final long seq;

        WsNode(int idx, int label, float priority, long seq) {
            this.idx = idx;
            this.label = label;
            this.priority = priority;
            this.seq = seq;
        }
    }

    private static final class SeedLabelBuild {
        final int[] seedLabels;
        final int nextLabel;

        SeedLabelBuild(int[] seedLabels, int nextLabel) {
            this.seedLabels = seedLabels;
            this.nextLabel = nextLabel;
        }
    }
}
