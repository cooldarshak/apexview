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
        return samplePositionsPacked(times,x,y,counts,handles,null,now,state,xy,active);
    }
    public static boolean samplePositionsPacked(long[][] times, float[][] x, float[][] y,
            int[] counts, long[] timeHandles, long[] positionHandles, long now, long[] state, float[] xy, int[] active) {
        if (!available()) return false;
        try { return nativeSamplePositionsPacked(times,x,y,counts,timeHandles,positionHandles,now,state,xy,active)==1; }
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
    private static final ReferenceQueue<Object> compactQueue = new ReferenceQueue<>();
    private static final Set<CompactRef> compactRefs = ConcurrentHashMap.newKeySet();
    private static boolean compactReaperStarted;
    private static final class CompactRef extends PhantomReference<Object> {
        final long token;
        CompactRef(Object value, long handle) { super(value, compactQueue); token = handle; }
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
            compactRefs.add(new CompactRef(owner, token[0]));
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
    public static final class CompactCoordinates {
        private final long handle;
        private final ByteBuffer data;
        private final int count;
        private CompactCoordinates(long handle, ByteBuffer raw, int count) {
            this.handle = handle;
            this.data = raw.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            this.count = count;
        }
        public long handle() { return handle; }
        public int count() { return count; }
        private float at(int index, int axis) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
            int start = (index / 256) * (8 + 256 * 4);
            int offset = start + 8 + (index % 256) * 4 + axis * 2;
            try { return (float) (data.getInt(start + axis * 4) + Short.toUnsignedInt(data.getShort(offset))); }
            finally { Reference.reachabilityFence(this); }
        }
        public float xAt(int index) { return at(index, 0); }
        public float yAt(int index) { return at(index, 1); }
    }
    public static CompactCoordinates compactCoordinates(float[] xs, float[] ys, int count) {
        if (count < 64 || count > xs.length || count > ys.length || !available()) return null;
        long[] token = new long[1];
        boolean registered = false;
        try {
            ByteBuffer raw = nativeCreateCompactCoordinates(xs,ys,count,token);
            long expected = count * 4L + ((count + 255L) / 256L) * 8L;
            if (raw == null || token[0] <= 0 || raw.capacity() != expected) return null;
            CompactCoordinates owner = new CompactCoordinates(token[0],raw,count);
            ensureCompactReaper();
            compactRefs.add(new CompactRef(owner,token[0]));
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
    private static native ByteBuffer nativeCreateCompactCoordinates(float[] xs,float[] ys,int count,long[] token);

    public static final class CompactTelemetry {
        private final ByteBuffer data;
        private final int count, bits;
        private CompactTelemetry(ByteBuffer raw, int count, int bits) {
            this.data = raw.asReadOnlyBuffer();
            this.count = count; this.bits = bits;
        }
        public int at(int index) {
            if (index < 0 || index >= count) throw new IndexOutOfBoundsException();
            int bit = index * bits, offset = bit / 8, shift = bit % 8;
            try {
                int word = Byte.toUnsignedInt(data.get(offset));
                if (shift + bits > 8) word |= Byte.toUnsignedInt(data.get(offset + 1)) << 8;
                int value = (word >>> shift) & ((1 << bits) - 1);
                return bits == 4 && value == 15 ? -1 : value;
            } finally { Reference.reachabilityFence(this); }
        }
    }
    public static CompactTelemetry compactTelemetry(short[] source, int count, int bits) {
        if (count < 64 || count > source.length || (bits != 4 && bits != 9 && bits != 16) || !available()) return null;
        long[] token = new long[1];
        boolean registered = false;
        try {
            ByteBuffer raw = nativeCreateCompactTelemetry(source,count,bits,token);
            long expected = (count * (long)bits + 7L) / 8L;
            if (raw == null || token[0] <= 0 || raw.capacity() != expected) return null;
            CompactTelemetry owner = new CompactTelemetry(raw,count,bits);
            ensureCompactReaper();
            compactRefs.add(new CompactRef(owner,token[0]));
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
    private static native ByteBuffer nativeCreateCompactTelemetry(short[] source,int count,int bits,long[] token);

    public static long[] compactTimingSnapshot() {
        if (!available()) return null;
        long[] out = new long[9];
        try { return nativeCompactTimingSnapshot(out) == 1 ? out : null; }
        catch (LinkageError | RuntimeException unavailable) { return null; }
    }
    private static native int nativeCompactTimingSnapshot(long[] out);
    private static native ByteBuffer nativeCreateCompactTimes(long[] source,int count,long[] token);
    private static native void nativeReleaseCompactTimes(long token);
    /** Owner must close this handle; native registry retains no Java references. */
    public static long replayIndexCreate() {
        if (!available()) return 0;
        try { return nativeReplayIndexCreate(); }
        catch (LinkageError | RuntimeException unavailable) { return 0; }
    }
    public static boolean replayIndexMark(long handle, long time, long offset) {
        try { return nativeReplayIndexMark(handle, time, offset) == 1; }
        catch (LinkageError | RuntimeException unavailable) { return false; }
    }
    public static long replayIndexOffset(long handle, long from) {
        try { return nativeReplayIndexOffset(handle, from); }
        catch (LinkageError | RuntimeException unavailable) { return -1; }
    }
    public static void replayIndexClose(long handle) {
        if (handle == 0) return;
        try { nativeReplayIndexClose(handle); }
        catch (LinkageError | RuntimeException unavailable) { }
    }
    private static native long nativeReplayIndexCreate();
    private static native int nativeReplayIndexMark(long handle, long time, long offset);
    private static native long nativeReplayIndexOffset(long handle, long from);
    private static native void nativeReplayIndexClose(long handle);
    public static final int REPLAY_POSITION_WORDS = 3 + 512 * 4;
    public static boolean replayPositions(String json, long[] output) {
        if (output.length != REPLAY_POSITION_WORDS || json.length() > 512 * 1024 || !available()) return false;
        try { return nativeReplayPositions(json, output) == 1; }
        catch (LinkageError | RuntimeException failed) { return false; }
    }
    private static native int nativeReplayPositions(String json, long[] output);
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
    private static native int nativeSamplePositionsPacked(long[][] times,float[][] x,float[][] y,int[] counts,
                                                    long[] handles,long[] positionHandles,long now,long[] state,float[] xy,int[] active);
    private static native int nativeFloorIndices(long[][] times,int[] counts,long[] handles,long now,int[] indices);
    private static native int nativeRowOrder(int[] positions,int count,int[] order);
    private static native int nativeAdts(ByteBuffer input,int size,int rate,int channels,byte[] output);
    private static native int nativeScaleUs(long us,long timescale,long[] result);
}
