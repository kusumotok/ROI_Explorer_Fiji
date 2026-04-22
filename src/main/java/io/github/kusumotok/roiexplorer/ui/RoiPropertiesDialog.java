package io.github.kusumotok.roiexplorer.ui;

import ij.gui.Roi;
import io.github.kusumotok.roiexplorer.model.RoiNode;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class RoiPropertiesDialog extends JDialog {

    private boolean confirmed = false;
    private final boolean single;

    private final JTextField nameField        = new JTextField(20);
    private final JSpinner strokeWidthSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.0, 100.0, 0.5));
    private final JSpinner cSpinner           = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JSpinner zSpinner           = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));
    private final JSpinner tSpinner           = new JSpinner(new SpinnerNumberModel(0, 0, 9999, 1));

    private Color strokeColor;
    private Color fillColor;
    private final JButton strokeColorBtn;
    private final JButton fillColorBtn;

    public RoiPropertiesDialog(Window owner, List<RoiNode> nodes) {
        super(owner,
              nodes.size() == 1 ? "ROI Properties" : "ROI Properties (" + nodes.size() + " ROIs)",
              ModalityType.APPLICATION_MODAL);
        this.single = nodes.size() == 1;

        RoiNode first = nodes.get(0);
        Roi roi = first.getRoi();

        strokeColor = roi != null ? roi.getStrokeColor() : null;
        fillColor   = roi != null ? roi.getFillColor()   : null;
        strokeColorBtn = makeColorButton(strokeColor);
        fillColorBtn   = makeColorButton(fillColor);

        if (roi != null) {
            double width = roi.getStrokeWidth();
            if (width <= 0.0 && (roi.getStrokeColor() != null || roi.getFillColor() != null)) {
                width = 1.0;
            }
            strokeWidthSpinner.setValue(width);
            if (single) {
                nameField.setText(roi.getName() != null ? roi.getName() : "");
                cSpinner.setValue(roi.getCPosition());
                zSpinner.setValue(roi.getZPosition());
                tSpinner.setValue(roi.getTPosition());
            }
        }

        if (!single) {
            nameField.setText("(複数選択)");
            nameField.setEnabled(false);
            cSpinner.setEnabled(false);
            zSpinner.setEnabled(false);
            tSpinner.setEnabled(false);
        }

        buildUI();
        wireActions();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    private void buildUI() {
        setLayout(new BorderLayout(6, 6));

        JPanel main = new JPanel(new GridBagLayout());
        main.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets  = new Insets(2, 2, 2, 4);
        gc.anchor  = GridBagConstraints.WEST;
        gc.fill    = GridBagConstraints.NONE;

        int row = 0;

        // Name
        addRow(main, gc, row++, "Name:", nameField);

        // Stroke Color
        JPanel strokePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JButton strokeClear = new JButton("Clear");
        strokeClear.setFocusable(false);
        strokeClear.setMargin(new Insets(0, 4, 0, 4));
        strokeClear.addActionListener(e -> { strokeColor = null; updateColorButton(strokeColorBtn, null); });
        strokePanel.add(strokeColorBtn);
        strokePanel.add(strokeClear);
        addRow(main, gc, row++, "Stroke Color:", strokePanel);

        // Fill Color
        JPanel fillPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JButton fillClear = new JButton("Clear");
        fillClear.setFocusable(false);
        fillClear.setMargin(new Insets(0, 4, 0, 4));
        fillClear.addActionListener(e -> { fillColor = null; updateColorButton(fillColorBtn, null); });
        fillPanel.add(fillColorBtn);
        fillPanel.add(fillClear);
        addRow(main, gc, row++, "Fill Color:", fillPanel);

        // Stroke Width
        ((JSpinner.DefaultEditor) strokeWidthSpinner.getEditor()).getTextField().setColumns(5);
        addRow(main, gc, row++, "Stroke Width:", strokeWidthSpinner);

        // Position
        for (JSpinner s : new JSpinner[]{cSpinner, zSpinner, tSpinner}) {
            ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(4);
        }
        JPanel posPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        posPanel.add(new JLabel("C:")); posPanel.add(cSpinner);
        posPanel.add(new JLabel("Z:")); posPanel.add(zSpinner);
        posPanel.add(new JLabel("T:")); posPanel.add(tSpinner);
        posPanel.add(new JLabel("  (0 = all slices)"));
        addRow(main, gc, row++, "Position:", posPanel);

        add(main, BorderLayout.CENTER);
        add(buildButtonPanel(), BorderLayout.SOUTH);
    }

    private void addRow(JPanel p, GridBagConstraints gc, int row, String label, JComponent comp) {
        gc.gridx = 0; gc.gridy = row; gc.weightx = 0;
        p.add(new JLabel(label), gc);
        gc.gridx = 1; gc.weightx = 1;
        p.add(comp, gc);
    }

    private JPanel buildButtonPanel() {
        JButton ok     = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> { confirmed = true; dispose(); });
        cancel.addActionListener(e -> dispose());
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        p.add(ok);
        p.add(cancel);
        return p;
    }

    private void wireActions() {
        strokeColorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Stroke Color",
                    strokeColor != null ? strokeColor : Color.YELLOW);
            if (c != null) { strokeColor = c; updateColorButton(strokeColorBtn, c); }
        });
        fillColorBtn.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Fill Color",
                    fillColor != null ? fillColor : Color.YELLOW);
            if (c != null) { fillColor = c; updateColorButton(fillColorBtn, c); }
        });
    }

    public boolean isConfirmed() { return confirmed; }

    /** Apply dialog values to a RoiNode's underlying Roi (caller must save to disk). */
    public void applyTo(RoiNode node) {
        Roi roi = node.getRoi();
        if (roi == null) return;

        if (single) {
            String name = nameField.getText().trim();
            if (!name.isEmpty()) roi.setName(name);
            roi.setPosition(
                (Integer) cSpinner.getValue(),
                (Integer) zSpinner.getValue(),
                (Integer) tSpinner.getValue());
        }

        roi.setFillColor(fillColor);
        roi.setStrokeColor(strokeColor);
        float strokeWidth = ((Double) strokeWidthSpinner.getValue()).floatValue();
        if (strokeWidth <= 0f && (strokeColor != null || fillColor != null)) {
            strokeWidth = 1f;
        }
        roi.setStrokeWidth(strokeWidth);
    }

    // ── Color button helpers ──────────────────────────────────────────────────

    private static JButton makeColorButton(Color c) {
        JButton btn = new JButton();
        btn.setPreferredSize(new Dimension(52, 20));
        btn.setFocusable(false);
        updateColorButton(btn, c);
        return btn;
    }

    private static void updateColorButton(JButton btn, Color c) {
        if (c == null) {
            btn.setIcon(null);
            btn.setText("None");
        } else {
            final Color col = c;
            btn.setText("");
            btn.setIcon(new Icon() {
                public void paintIcon(Component comp, Graphics g, int x, int y) {
                    g.setColor(col);
                    g.fillRect(x + 1, y + 1, getIconWidth() - 2, getIconHeight() - 2);
                    g.setColor(Color.DARK_GRAY);
                    g.drawRect(x + 1, y + 1, getIconWidth() - 2, getIconHeight() - 2);
                }
                public int getIconWidth()  { return 28; }
                public int getIconHeight() { return 14; }
            });
        }
    }
}
