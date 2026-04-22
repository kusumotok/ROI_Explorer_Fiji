package io.github.kusumotok.roiexplorer.model;

import ij.gui.Roi;
import java.io.IOException;

@FunctionalInterface
public interface RoiLoader {
    Roi load() throws IOException;
}
