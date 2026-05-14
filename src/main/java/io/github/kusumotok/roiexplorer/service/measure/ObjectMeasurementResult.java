package io.github.kusumotok.roiexplorer.service.measure;

public final class ObjectMeasurementResult {

    public final int spotId;
    public final String unitName;
    public final int c;
    public final int t;
    public final int tFrom;
    public final int tTo;
    public final long volumeVox;
    public final double volumeUm3;
    public final long volumeFromVox;
    public final long volumeToVox;
    public final double volumeFromUm3;
    public final double volumeToUm3;
    public final double deltaVolumeUm3;
    public final double surfaceAreaUm2;
    public final double sphericity;
    public final double integratedIntensity;
    public final double meanIntensity;
    public final double maxIntensity;
    public final double centroidXUm;
    public final double centroidYUm;
    public final double centroidZUm;
    public final double centroidFromXUm;
    public final double centroidFromYUm;
    public final double centroidFromZUm;
    public final double centroidToXUm;
    public final double centroidToYUm;
    public final double centroidToZUm;
    public final double displacementUm;
    public final double interval;
    public final double velocityUmPerFrame;
    public final double maxFeret3dUm;
    public final double maxFeretP1XUm;
    public final double maxFeretP1YUm;
    public final double maxFeretP1ZUm;
    public final double maxFeretP2XUm;
    public final double maxFeretP2YUm;
    public final double maxFeretP2ZUm;
    /** Calibration unit from the source image (e.g. "µm", "nm", "px"). */
    public final String calibrationUnit;

    private ObjectMeasurementResult(Builder b) {
        spotId = b.spotId;
        unitName = b.unitName;
        c = b.c;
        t = b.t;
        tFrom = b.tFrom;
        tTo = b.tTo;
        volumeVox = b.volumeVox;
        volumeUm3 = b.volumeUm3;
        volumeFromVox = b.volumeFromVox;
        volumeToVox = b.volumeToVox;
        volumeFromUm3 = b.volumeFromUm3;
        volumeToUm3 = b.volumeToUm3;
        deltaVolumeUm3 = b.deltaVolumeUm3;
        surfaceAreaUm2 = b.surfaceAreaUm2;
        sphericity = b.sphericity;
        integratedIntensity = b.integratedIntensity;
        meanIntensity = b.meanIntensity;
        maxIntensity = b.maxIntensity;
        centroidXUm = b.centroidXUm;
        centroidYUm = b.centroidYUm;
        centroidZUm = b.centroidZUm;
        centroidFromXUm = b.centroidFromXUm;
        centroidFromYUm = b.centroidFromYUm;
        centroidFromZUm = b.centroidFromZUm;
        centroidToXUm = b.centroidToXUm;
        centroidToYUm = b.centroidToYUm;
        centroidToZUm = b.centroidToZUm;
        displacementUm = b.displacementUm;
        interval = b.interval;
        velocityUmPerFrame = b.velocityUmPerFrame;
        maxFeret3dUm = b.maxFeret3dUm;
        maxFeretP1XUm = b.maxFeretP1XUm;
        maxFeretP1YUm = b.maxFeretP1YUm;
        maxFeretP1ZUm = b.maxFeretP1ZUm;
        maxFeretP2XUm = b.maxFeretP2XUm;
        maxFeretP2YUm = b.maxFeretP2YUm;
        maxFeretP2ZUm = b.maxFeretP2ZUm;
        calibrationUnit = b.calibrationUnit != null ? b.calibrationUnit : "µm";
    }

    public static final class Builder {
        private int spotId;
        private String unitName = "";
        private int c = 1;
        private int t = 1;
        private int tFrom;
        private int tTo;
        private long volumeVox;
        private double volumeUm3;
        private long volumeFromVox;
        private long volumeToVox;
        private double volumeFromUm3;
        private double volumeToUm3;
        private double deltaVolumeUm3;
        private double surfaceAreaUm2;
        private double sphericity;
        private double integratedIntensity;
        private double meanIntensity;
        private double maxIntensity;
        private double centroidXUm;
        private double centroidYUm;
        private double centroidZUm;
        private double centroidFromXUm, centroidFromYUm, centroidFromZUm;
        private double centroidToXUm, centroidToYUm, centroidToZUm;
        private double displacementUm;
        private double interval;
        private double velocityUmPerFrame;
        private double maxFeret3dUm;
        private double maxFeretP1XUm, maxFeretP1YUm, maxFeretP1ZUm;
        private double maxFeretP2XUm, maxFeretP2YUm, maxFeretP2ZUm;
        private String calibrationUnit;

        public Builder spotId(int v)              { spotId = v; return this; }
        public Builder unitName(String v)          { unitName = v; return this; }
        public Builder c(int v)                    { c = v; return this; }
        public Builder t(int v)                    { t = v; return this; }
        public Builder tFrom(int v)                { tFrom = v; return this; }
        public Builder tTo(int v)                  { tTo = v; return this; }
        public Builder volumeVox(long v)           { volumeVox = v; return this; }
        public Builder volumeUm3(double v)         { volumeUm3 = v; return this; }
        public Builder volumeFromVox(long v)       { volumeFromVox = v; return this; }
        public Builder volumeToVox(long v)         { volumeToVox = v; return this; }
        public Builder volumeFromUm3(double v)     { volumeFromUm3 = v; return this; }
        public Builder volumeToUm3(double v)       { volumeToUm3 = v; return this; }
        public Builder deltaVolumeUm3(double v)    { deltaVolumeUm3 = v; return this; }
        public Builder surfaceAreaUm2(double v)    { surfaceAreaUm2 = v; return this; }
        public Builder sphericity(double v)        { sphericity = v; return this; }
        public Builder integratedIntensity(double v) { integratedIntensity = v; return this; }
        public Builder meanIntensity(double v)     { meanIntensity = v; return this; }
        public Builder maxIntensity(double v)      { maxIntensity = v; return this; }
        public Builder centroidXUm(double v)       { centroidXUm = v; return this; }
        public Builder centroidYUm(double v)       { centroidYUm = v; return this; }
        public Builder centroidZUm(double v)       { centroidZUm = v; return this; }
        public Builder centroidFrom(double x, double y, double z) {
            centroidFromXUm = x; centroidFromYUm = y; centroidFromZUm = z; return this;
        }
        public Builder centroidTo(double x, double y, double z) {
            centroidToXUm = x; centroidToYUm = y; centroidToZUm = z; return this;
        }
        public Builder displacementUm(double v)    { displacementUm = v; return this; }
        public Builder interval(double v)          { interval = v; return this; }
        public Builder velocityUmPerFrame(double v) { velocityUmPerFrame = v; return this; }
        public Builder maxFeret3dUm(double v)      { maxFeret3dUm = v; return this; }
        public Builder maxFeretP1(double x, double y, double z) {
            maxFeretP1XUm = x; maxFeretP1YUm = y; maxFeretP1ZUm = z; return this;
        }
        public Builder maxFeretP2(double x, double y, double z) {
            maxFeretP2XUm = x; maxFeretP2YUm = y; maxFeretP2ZUm = z; return this;
        }
        public Builder calibrationUnit(String v) { calibrationUnit = v; return this; }

        public ObjectMeasurementResult build() { return new ObjectMeasurementResult(this); }
    }
}
