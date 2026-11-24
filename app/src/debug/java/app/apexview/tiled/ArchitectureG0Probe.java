package app.apexview.tiled;

import java.nio.ByteBuffer;

/**
 * Debug-only, explicit diagnostic for clear synthetic fixtures.
 * Never invoked by the player, DRM, settings, or application startup.
 */
public final class ArchitectureG0Probe {
    public static final int INVALID = -1;
    public static final int STALE = -2;
    private static final int MAX_CAPACITY = 4 * 1024 * 1024;

    private ArchitectureG0Probe() {}
    private static native int nativeVersion();
    private static native int nativeConvert(ByteBuffer input, int inputLength,
        int prefix, ByteBuffer output, int outputLimit, int batchCount);

    /** Explicit probe; failure is reported to the caller, never hidden as success. */
    public static synchronized int loadAndVersion() {
        System.loadLibrary("pitwall_g0");
        return nativeVersion();
    }

    /** Test the native boundary without exposing a live session's direct buffers. */
    public static boolean rejectsBadBuffers() {
        ByteBuffer a = ByteBuffer.allocateDirect(16);
        ByteBuffer b = ByteBuffer.allocateDirect(16);
        a.put(new byte[]{2, 2, 1});
        ByteBuffer readOnly = b.asReadOnlyBuffer();
        return nativeConvert(ByteBuffer.allocate(16), 3, 1, b, 16, 1) == INVALID
            && nativeConvert(a, 3, 1, ByteBuffer.allocate(16), 16, 1) == INVALID
            && nativeConvert(a, 17, 1, b, 16, 1) == INVALID
            && nativeConvert(a, 3, 1, b, 17, 1) == INVALID
            && nativeConvert(a, 3, 1, a, 16, 1) == INVALID
            && nativeConvert(a, 3, 1, readOnly, 16, 1) == INVALID
            && nativeConvert(a, -1, 1, b, 16, 1) == INVALID
            && nativeConvert(a, 3, 5, b, 16, 1) == INVALID
            && nativeConvert(a, 3, 1, b, 16, 0) == INVALID
            && nativeConvert(a, 3, 1, b, 16, 2) == INVALID
            && nativeConvert(a, 3, 1, b, 16, 65) == INVALID;
    }

    /** One owner, bounded private direct buffers, one synchronous native call.
     * Close/invalidate serialized with work. Java direct buffers are GC-managed;
     * close drops ownership, not a deterministic free of their native pages.
     */
    public static final class Session implements AutoCloseable {
        private ByteBuffer input;
        private ByteBuffer output;
        private long generation = 1L;
        private int loadedLength;
        private int outputLength = -1;

        public Session(int capacity) {
            if (capacity <= 0 || capacity > MAX_CAPACITY) {
                throw new IllegalArgumentException("G.0 capacity out of bounds");
            }
            if (loadAndVersion() != 2) throw new IllegalStateException("G.0 ABI version mismatch");
            input = ByteBuffer.allocateDirect(capacity);
            output = ByteBuffer.allocateDirect(capacity);
        }

        public synchronized long generation() { return generation; }
        public synchronized boolean load(long expected, byte[] sample) {
            if (input == null || generation != expected) return false;
            loadedLength = 0;
            outputLength = -1;
            if (sample == null || sample.length == 0 || sample.length > input.capacity()) return false;
            input.clear();
            input.put(sample); // one explicit JVM-to-direct-buffer copy
            loadedLength = sample.length;
            return true;
        }

        public synchronized int convertLoaded(long expected, int prefix) {
            return convertBatchLoaded(expected, prefix, 1);
        }

        public synchronized int convertBatchLoaded(long expected, int prefix, int count) {
            if (input == null || generation != expected) return STALE;
            outputLength = -1;
            if (loadedLength == 0) return INVALID;
            int result = nativeConvert(input, loadedLength, prefix, output, output.capacity(), count);
            if (result >= 0) outputLength = result;
            return result;
        }

        public synchronized int copyOutput(long expected, byte[] destination) {
            if (input == null || generation != expected) return STALE;
            if (outputLength < 0 || destination == null || destination.length < outputLength) return INVALID;
            output.position(0);
            output.get(destination, 0, outputLength); // one diagnostic-only output copy
            return outputLength;
        }

        public synchronized long invalidate() {
            if (input == null) throw new IllegalStateException("G.0 session closed");
            generation = Math.addExact(generation, 1L);
            loadedLength = 0;
            outputLength = -1;
            return generation;
        }

        @Override public synchronized void close() {
            loadedLength = 0;
            outputLength = -1;
            input = null;
            output = null;
        }
    }
}
