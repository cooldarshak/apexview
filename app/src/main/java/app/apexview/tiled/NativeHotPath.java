package app.apexview.tiled;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Synchronous, bounded processing on caller-owned primitive storage.
 * Completed archive timestamps may use the separately bounded, immutable
 * Rust store; live histories and result scratch remain caller-owned.
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
        return samplePositionsWithCompactTimes(times,x,y,counts,null,now,state,xy,active);
    }
    public static boolean samplePositionsWithCompactTimes(long[][] times, float[][] x, float[][] y,
            int[] counts, long[] handles, long now, long[] state, float[] xy, int[] active) {
        if (!available()) return false;
        try { return nativeSamplePositions(times,x,y,counts,handles,now,state,xy,active)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean floorIndices(long[][] times, int[] counts, long now, int[] indices) {
        return floorIndicesWithCompactTimes(times,counts,null,now,indices);
    }
    public static boolean floorIndicesWithCompactTimes(long[][] times, int[] counts, long[] handles,
            long now, int[] indices) {
        if (!available()) return false;
        try { return nativeFloorIndices(times,counts,handles,now,indices)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }

    /** Immutable view of Rust-owned memory. No raw buffer escapes this holder.
     * The reference queue retires only the final shared owner, not an individual
     * map/tower sampler or a previous replay publication. */
    public static final class CompactTimes {
        private final long handle;
        private final ByteBuffer data;
        private final int count;
        private CompactTimes(long handle, ByteBuffer data, int count) {
            this.handle = handle;
            this.data = data.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            this.count = count;
        }
        public long handle() { return handle; }
        public int count() { return count; }
        public long at(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
            try { return data.getLong(0) + Integer.toUnsignedLong(data.getInt(8 + index * 4)); }
            finally { Reference.reachabilityFence(this); }
        }
    }
    private static final ReferenceQueue<CompactTimes> compactQueue = new ReferenceQueue<>();
    private static final Set<CompactRef> compactRefs = ConcurrentHashMap.newKeySet();
    private static boolean compactReaperStarted;
    private static final class CompactRef extends PhantomReference<CompactTimes> {
        final long token;
        CompactRef(CompactTimes value) { super(value, compactQueue); token = value.handle; }
    }
    private static synchronized void ensureCompactReaper() {
        if (compactReaperStarted) return;
        Thread worker = new Thread(() -> {
            for (;;) {
                try {
                    CompactRef ref = (CompactRef) compactQueue.remove();
                    try { nativeReleaseCompactTimes(ref.token); }
                    catch (LinkageError | RuntimeException unavailable) { /* process teardown */ }
                    finally { compactRefs.remove(ref); ref.clear(); }
                } catch (InterruptedException ignored) { /* process lifetime owner */ }
            }
        }, "PitwallCompactTimingReaper");
        worker.setDaemon(true);
        worker.start();
        compactReaperStarted = true;
    }
    public static CompactTimes compactTimes(long[] source, int count) {
        if (count < 64 || count > source.length || !available()) return null;
        long[] token = new long[1];
        boolean registered = false;
        try {
            ByteBuffer raw = nativeCreateCompactTimes(source,count,token);
            if (raw == null || token[0] <= 0 || raw.capacity() != 8L + count * 4L) return null;
            CompactTimes owner = new CompactTimes(token[0],raw,count);
            ensureCompactReaper();
            compactRefs.add(new CompactRef(owner));
            registered = true;
            return owner;
        } catch (LinkageError | RuntimeException unavailable) { return null; }
        finally {
            if (!registered && token[0] > 0) {
                try { nativeReleaseCompactTimes(token[0]); }
                catch (LinkageError | RuntimeException ignored) { }
            }
        }
    }
    public static long[] compactTimingSnapshot() {
        if (!available()) return null;
        long[] out = new long[3];
        try { return nativeCompactTimingSnapshot(out) == 1 ? out : null; }
        catch (LinkageError | RuntimeException unavailable) { return null; }
    }
    private static native int nativeCompactTimingSnapshot(long[] out);
    private static native ByteBuffer nativeCreateCompactTimes(long[] source,int count,long[] token);
    private static native void nativeReleaseCompactTimes(long token);
    public static boolean rowOrder(int[] positions, int count, int[] order) {
        if (!available()) return false;
        try { return nativeRowOrder(positions,count,order)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean avcAnnexB(ByteBuffer input,int offset,int size,byte[] output,int prefixSize) {
        if (!available() || !input.isDirect()) return false;
        try { return nativeAvcAnnexB(input,offset,size,output,prefixSize)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeAvcAnnexB(ByteBuffer input,int offset,int size,byte[] output,int prefixSize);
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
    public static boolean prepareGeometry(float[] outline, ByteBuffer packet) {
        if (!available()) return false;
        try { return nativePrepareGeometry(outline,packet)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean projectGeometry(ByteBuffer packet,float[] xy,int count,float[] fractions,int[] order) {
        if (!available()) return false;
        try { return nativeProjectGeometry(packet,xy,count,fractions,order)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean interpolateMotion(float[] points,int[] counts,float steps,float[] xy) {
        if (!available()) return false;
        try { return nativeInterpolateMotion(points,counts,steps,xy)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean pointAt(ByteBuffer packet,float fraction,float[] output) {
        if (!available()) return false;
        try { return nativePointAt(packet,fraction,output)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    public static boolean flags(ByteBuffer histories,long now,long[] out) {
        if (!available()) return false;
        try { return nativeFlags(histories,now,out)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeFlags(ByteBuffer histories,long now,long[] out);
    public static Long audioAdmit(long[] metadata,int count,long last,long remaining,int slots) {
        if (!available()) return null;
        try { return nativeAudioAdmit(metadata,count,last,remaining,slots); }
        catch (LinkageError | RuntimeException failed) { return null; }
    }
    private static native long nativeAudioAdmit(long[] metadata,int count,long last,long remaining,int slots);
    public static boolean audioMetadata(byte[][] parts,long[] defaults,long[] out) {
        if (!available()) return false;
        try { return nativeAudioMetadata(parts,defaults,out)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeAudioMetadata(byte[][] parts,long[] defaults,long[] out);
    public static boolean transport(byte[][] parts,long[] metadata,int[] continuity,ByteBuffer output) {
        if (!available()) return false;
        try { return nativeTransport(parts,metadata,continuity,output)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeTransport(byte[][] parts,long[] metadata,int[] continuity,ByteBuffer output);
    /** Low two bits are reconciled status; negative means unavailable/invalid. */
    public static int retirementState(int flags) {
        if (!available()) return -1;
        try { return nativeRetirementState(flags); }
        catch (LinkageError | RuntimeException failed) { return -1; }
    }
    private static native int nativeRetirementState(int flags);
    public static boolean normalizeSectors(int[] input,int[] counts,int[] output) {
        if (!available()) return false;
        try { return nativeNormalizeSectors(input,counts,output)==1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeNormalizeSectors(int[] input,int[] counts,int[] output);
    private static native int nativePointAt(ByteBuffer packet,float fraction,float[] output);
    private static native int nativePrepareGeometry(float[] outline,ByteBuffer packet);
    private static native int nativeProjectGeometry(ByteBuffer packet,float[] xy,int count,float[] fractions,int[] order);
    private static native int nativeInterpolateMotion(float[] points,int[] counts,float steps,float[] xy);
    private static native int nativeSamplePositions(long[][] times,float[][] x,float[][] y,int[] counts,
                                                    long[] handles,long now,long[] state,float[] xy,int[] active);
    private static native int nativeFloorIndices(long[][] times,int[] counts,long[] handles,long now,int[] indices);
    private static native int nativeRowOrder(int[] positions,int count,int[] order);
    private static native int nativeAdts(ByteBuffer input,int size,int rate,int channels,byte[] output);
    private static native int nativeScaleUs(long us,long timescale,long[] result);
}
