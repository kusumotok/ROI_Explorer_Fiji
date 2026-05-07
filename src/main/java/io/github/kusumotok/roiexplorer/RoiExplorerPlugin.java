package io.github.kusumotok.roiexplorer;

import ij.plugin.PlugIn;
import io.github.kusumotok.roiexplorer.service.RoiExplorerFacade;
import io.github.kusumotok.roiexplorer.ui.RoiExplorerWindow;

import javax.swing.*;

public class RoiExplorerPlugin implements PlugIn {

    @Override
    public void run(String arg) {
        SwingUtilities.invokeLater(() -> {
            RoiExplorerWindow window = RoiExplorerFacade.getInstance().showWindow();
            window.setVisible(true);
        });
    }
}
