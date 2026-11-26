package app.apexview.tiled;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;

/** Synchronous, bounded processing on caller-owned primitive storage.
 * No histories or JNI references are retained by native code. All arrays
 * remain private to a single overlay/audio owner and are reused.
 */
@Keep
public final class NativeHotPath {
    private NativeHotPath() {}
    private static volatile boolean loadAttempted;
    private static volatile boolean available;

    public static boolean available() {
        return loadAttempted ? available : loadOnce();
    }

    private static synchronized boolean loadOnce() {
        if (!loadAttempted) {
            try {
                NativeStitchingEngine.ensureLibraryLoaded();
                available = NativeStitchingEngine.isLibraryReady();
            } catch (LinkageError | RuntimeException unavailable) {
                available = false;
            } finally { loadAttempted = true; }
        }
        return available;
    }
    public static boolean samplePositions(long[][] times, float[][] x, float[][] y, int[] counts,
                                          long now, long[] state, float[] xy, int[] active) {
        if (!available()) return false;
        try { return nativeSamplePositions(times,x,y,counts,now,state,xy,active)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean floorIndices(long[][] times, int[] counts, long now, int[] indices) {
        if (!available()) return false;
        try { return nativeFloorIndices(times,counts,now,indices)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean rowOrder(int[] positions, int count, int[] order) {
        if (!available()) return false;
        try { return nativeRowOrder(positions,count,order)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean adts(ByteBuffer input, int size, int rate, int channels, byte[] output) {
        if (!available()) return false;
        try { return nativeAdts(input,size,rate,channels,output)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean scaleUs(long us, long timescale, long[] result) {
        if (!available()) return false;
        try { return nativeScaleUs(us,timescale,result)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeSamplePositions(long[][] times,float[][] x,float[][] y,int[] counts,
                                                    long now,long[] state,float[] xy,int[] active);
    private static native int nativeFloorIndices(long[][] times,int[] counts,long now,int[] indices);
    private static native int nativeRowOrder(int[] positions,int count,int[] order);
    private static native int nativeAdts(ByteBuffer input,int size,int rate,int channels,byte[] output);
    private static native int nativeScaleUs(long us,long timescale,long[] result);
}
