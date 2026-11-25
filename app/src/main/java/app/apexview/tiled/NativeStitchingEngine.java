package app.apexview.tiled;

import java.nio.ByteBuffer;

/**
 * G-A native engine ownership contract. Explicitly opened by a background
 * owner; no native library load during startup, no DRM/Media3 interception.
 * Supports bounded clear conversion and atlas jobs for explicit callers.
 * Native-owned bridge views are private and invalidated before deterministic close.
 */
public final class NativeStitchingEngine implements AutoCloseable {
    public static final long INVALID=-1, STALE=-2, PANIC=-3, BACKPRESSURE=-4, DEADLINE=-5, CLOSED=-6, OUTPUT_CAPACITY=-7;
    private static final long MIB=1024L*1024L;
    private static final long RESERVE=576L*1024L;
    public static final int MAX_ATLAS_PACKET=16*1024*1024;
    private static final long MAX_ATLAS_BUDGET=192*MIB;

    public static final class Budget {
        public final int slots, inputBytes, outputBytes;
        public final long ceilingBytes, reservedBytes;
        public final boolean compactAtlas;
        private Budget(int slots,int input,int output,long ceiling) {
            this(slots,input,output,ceiling,false);
        }
        private Budget(int slots,int input,int output,long ceiling,boolean compactAtlas) {
            this.slots=slots;inputBytes=input;outputBytes=output;ceilingBytes=ceiling;
            this.compactAtlas=compactAtlas;
            reservedBytes=(slots+(compactAtlas?0L:1L))*(input+(long)output)+RESERVE;
        }
    }
    /**
     * Use observed maximum admitted input/output bytes, not device model or
     * arbitrary arena sizes. Reserve OS/codec headroom independently of Java.
     * Return null rather than silently reduce quality or overcommit memory.
     */
    public static Budget plan(boolean lowRam,long availableBytes,long osThresholdBytes,
                              long jvmHeadroom,int observedInput,int observedOutput) {
        if(availableBytes<=0 || osThresholdBytes<0 || availableBytes<=osThresholdBytes ||
           jvmHeadroom<=0 || observedInput<=0 || observedOutput<=0 ||
           observedInput>8*MIB || observedOutput>8*MIB) return null;
        long ceiling=Math.min(lowRam?12*MIB:32*MIB,
            Math.min((availableBytes-osThresholdBytes)/8,jvmHeadroom/2));
        for(int slots=lowRam?2:4;slots>=2;slots--) {
            Budget b=new Budget(slots,observedInput,observedOutput,ceiling);
            if(b.reservedBytes<=ceiling)return b;
        }
        return null;
    }
    /**
     * Atlas dispatch reserves bounded parsing/rewrite workspace in addition to
     * the G-A slot/bridge arena. Mirror atlas_job::workspace_reserve exactly;
     * do not admit an engine that is guaranteed to reject every atlas job.
     */
    public static Budget planClearAtlas(boolean lowRam,long availableBytes,long osThresholdBytes,
                                        long jvmHeadroom,int observedInput,int observedOutput) {
        if (availableBytes<=0 || osThresholdBytes<0 || availableBytes<=osThresholdBytes ||
            jvmHeadroom<=0 || observedInput<=0 || observedOutput<=0 ||
            observedInput>MAX_ATLAS_PACKET || observedOutput>MAX_ATLAS_PACKET) return null;
        // The arena lives outside the Java heap. Reserve Java packet/output
        // copies separately, and retain seven eighths of the OS surplus for
        // codecs, GL, audio, the JVM and other apps. Do not grant capacity
        // just because a device has a particular model or RAM class.
        long surplus=availableBytes-osThresholdBytes;
        long ceiling=surplus/8;
        // The archive bridge serializes jobs; two logical slots preserve
        // the foreground reservation. Rust lazily materializes the spare
        // pair only on overlap, while admission still charges both slots.
        // On low RAM use exact sizes, on other devices try bounded growth
        // headroom first, then exact sizing if the larger arena won't fit.
        int grownInput=(int)Math.min(MAX_ATLAS_PACKET,observedInput+(observedInput+3L)/4);
        int grownOutput=(int)Math.min(MAX_ATLAS_PACKET,observedOutput+(observedOutput+3L)/4);
        if (!lowRam) {
            Budget grown=atlasBudget(ceiling,jvmHeadroom,grownInput,grownOutput);
            if (grown!=null) return grown;
        }
        return atlasBudget(ceiling,jvmHeadroom,observedInput,observedOutput);
    }
    public static long atlasWorkspaceBytes(int input,int output) {
        return 4L*input+output+8*MIB;
    }
    private static Budget atlasBudget(long ceiling,long jvmHeadroom,int input,int output) {
        // Keep the conservative source/reference/output allowance, even though
        // there is no longer a concatenated Java input packet.
        long javaTransient=input+2L*output+8*MIB;
        // Charge native arena + workspace + the Java transfer staging against
        // the same OS surplus. Keep Java headroom independently checked.
        if (javaTransient>jvmHeadroom || javaTransient>=ceiling) return null;
        Budget b=new Budget(2,input,output,Math.min(MAX_ATLAS_BUDGET,ceiling-javaTransient),true);
        return b.reservedBytes+atlasWorkspaceBytes(input,output)<=b.ceilingBytes?b:null;
    }

    /** Compute bounded wall-clock budget from media buffer slack and speed. */
    public static long slackMs(long bufferedMs,long positionMs,double speed) {
        if(bufferedMs<0 || positionMs<0 || !Double.isFinite(speed) || speed<=0)return 0;
        long media=Math.max(0,bufferedMs-positionMs);
        return Math.max(1,Math.min(60_000,(long)(media/speed)));
    }

    private static native int nativeAbi();
    private static native long nativeFragmentAllocate(int size);
    private static native long nativeFragmentAllocateBounded(int size,long limit);
    private static native ByteBuffer nativeFragmentView(long id);
    private static native long nativeFragmentRelease(long id);
    private static native void nativeFragmentTrim();
    static long allocateFragment(int size) {
        System.loadLibrary("pitwall_g0");
        return nativeAbi() == 2 ? nativeFragmentAllocate(size) : INVALID;
    }
    static long allocateFragmentBounded(int size,long limit) {
        System.loadLibrary("pitwall_g0");
        return nativeAbi() == 2 ? nativeFragmentAllocateBounded(size,limit) : INVALID;
    }
    static ByteBuffer fragmentView(long id) { return nativeFragmentView(id); }
    static long releaseFragment(long id) { return nativeFragmentRelease(id); }
    static void trimFragments() { nativeFragmentTrim(); }

    private static native byte[] nativeProtectedHeader(byte[] packet);
    private static native byte[] nativeProtectedPlan(byte[] packet, int kind);
    static byte[] protectedPlan(byte[] packet, int kind) {
        if (packet == null || packet.length == 0 || packet.length > 2 * MIB ||
            (kind < 1 || kind > 4)) return null;
        System.loadLibrary("pitwall_g0");
        if (nativeAbi() != 2) return null;
        return nativeProtectedPlan(packet, kind);
    }

    /** Explicit opt-in, metadata only. The caller retains all existing
     * authorized DRM validation and compares exact output against JVM. */
    public static byte[] protectedHeader(byte[] packet) {
        if (packet == null || packet.length == 0 || packet.length > 2 * MIB) return null;
        System.loadLibrary("pitwall_g0");
        if (nativeAbi() != 2) return null;
        return nativeProtectedHeader(packet);
    }
    private static native long nativeOpen(int slots,int input,int output,long ceiling);
    private static native ByteBuffer nativeBridge(long handle,boolean output);
    private static native long nativeOpenAtlas(int slots,int input,int output,long ceiling);
    private static native long nativeSubmitAtlasParts(long handle,long generation,byte[][] parts,int length,
                                                       boolean foreground,long slackMs);
    private static native long nativeReadAtlasArray(long handle,long token,int sourceOffset,byte[] target,
                                                     int targetOffset,int length);
    private static native long nativeSubmit(long handle,long generation,ByteBuffer input,int length,
                                            int prefix,int count,boolean foreground,long slackMs);
    private static native long nativeSubmitClearAtlas(long handle, long generation, ByteBuffer input,
                                                      int length, boolean foreground, long slackMs);
    private static native long nativeAcquire(long handle,long token);
    private static native long nativeCopy(long handle,long token,int offset,ByteBuffer output,int length);
    private static native long nativeRelease(long handle,long token);
    private static native long nativeSeek(long handle);
    private static native long nativeClose(long handle);

    private long handle;
    private long generation=1;
    private ByteBuffer input,output;
    private long javaInputBytes,nativeInputBytes,nativeOutputBytes,javaOutputBytes,rejectedSubmits;
    public final Budget budget;

    /** Counts only these explicit bridge boundaries, not Media3/codec internals. */
    public static final class CopyStats {
        public final long javaInputBytes,nativeInputBytes,nativeOutputBytes,javaOutputBytes,rejectedSubmits;
        private CopyStats(long ji,long ni,long no,long jo,long rejected) {
            javaInputBytes=ji;nativeInputBytes=ni;nativeOutputBytes=no;javaOutputBytes=jo;rejectedSubmits=rejected;
        }
    }
    public synchronized CopyStats copyStats() {
        return new CopyStats(javaInputBytes,nativeInputBytes,nativeOutputBytes,javaOutputBytes,rejectedSubmits);
    }
    private static long add(long a,long b){return a>Long.MAX_VALUE-b?Long.MAX_VALUE:a+b;}
    private NativeStitchingEngine(long handle,Budget budget) {
        this.handle=handle;this.budget=budget;
        if(budget.compactAtlas)return; // no extra bridge allocation/view
        input=nativeBridge(handle,false);
        output=nativeBridge(handle,true);
        if(input==null || output==null || !input.isDirect() || !output.isDirect() ||
            input.capacity()!=budget.inputBytes || output.capacity()!=budget.outputBytes ||
            input.isReadOnly() || output.isReadOnly()) {
            input=null;output=null;
            throw new IllegalStateException("Native bridge view unavailable");
        }
    }
    /** Only call on a worker, after explicit engine admission. */
    public static NativeStitchingEngine open(Budget budget) {
        if(budget==null)throw new IllegalArgumentException("Native memory budget unavailable");
        System.loadLibrary("pitwall_g0");
        if(nativeAbi()!=2)throw new IllegalStateException("G-A ABI mismatch");
        long id=budget.compactAtlas?nativeOpenAtlas(budget.slots,budget.inputBytes,budget.outputBytes,budget.ceilingBytes)
            :nativeOpen(budget.slots,budget.inputBytes,budget.outputBytes,budget.ceilingBytes);
        if(id<=0)throw new IllegalStateException("Native admission failed: "+id);
        try{return new NativeStitchingEngine(id,budget);}
        catch(RuntimeException|Error failure){nativeClose(id);throw failure;}
    }
    public synchronized long generation(){return handle==0?CLOSED:generation;}
    public synchronized long submit(long expected,byte[] bytes,int prefix,int count,boolean foreground,long slack) {
        return submitJob(expected, bytes, prefix, count, foreground, slack, false);
    }
    public synchronized long submitClearAtlas(long expected, byte[] bytes, boolean foreground, long slack) {
        if(budget.compactAtlas)return bytes==null?INVALID:submitClearAtlasParts(expected,new byte[][]{bytes},bytes.length,foreground,slack);
        return submitJob(expected, bytes, 0, 0, foreground, slack, true);
    }
    public synchronized long submitClearAtlasParts(long expected,byte[][] parts,int length,boolean foreground,long slack) {
        if(handle==0)return CLOSED;
        if(expected!=generation)return STALE;
        if(!budget.compactAtlas || parts==null || parts.length<1 || parts.length>256 ||
            length<=0 || length>budget.inputBytes)return INVALID;
        long total=0;
        for(byte[] part:parts){
            if(part==null || part.length==0)return INVALID;
            total+=part.length;
        }
        if(total!=length)return INVALID;
        long result=nativeSubmitAtlasParts(handle,expected,parts,length,foreground,slack);
        if(result>0)nativeInputBytes=add(nativeInputBytes,length);
        else rejectedSubmits=add(rejectedSubmits,1);
        return result;
    }
    private long submitJob(long expected, byte[] bytes, int prefix, int count, boolean foreground,
                           long slack, boolean clearAtlas) {
        if (handle == 0) return CLOSED;
        if (expected != generation) return STALE;
        if (budget.compactAtlas || bytes == null || bytes.length == 0 || bytes.length > input.capacity()) return INVALID;
        input.clear();
        input.put(bytes);
        javaInputBytes = add(javaInputBytes, bytes.length);
        long result = clearAtlas
            ? nativeSubmitClearAtlas(handle, expected, input, bytes.length, foreground, slack)
            : nativeSubmit(handle, expected, input, bytes.length, prefix, count, foreground, slack);
        if (result > 0) nativeInputBytes = add(nativeInputBytes, bytes.length);
        else rejectedSubmits = add(rejectedSubmits, 1);
        return result;
    }
    public synchronized long acquire(long token){return handle==0?CLOSED:nativeAcquire(handle,token);}
    public synchronized long read(long token,int sourceOffset,byte[] target,int targetOffset,int length) {
        if(handle==0)return CLOSED;
        if(target==null || sourceOffset<0 || targetOffset<0 || length<=0 ||
            length>budget.outputBytes || targetOffset>target.length-length)return INVALID;
        if(budget.compactAtlas){
            long result=nativeReadAtlasArray(handle,token,sourceOffset,target,targetOffset,length);
            if(result==length)javaOutputBytes=add(javaOutputBytes,length);
            return result;
        }
        long result=nativeCopy(handle,token,sourceOffset,output,length);
        if(result==length){
            nativeOutputBytes=add(nativeOutputBytes,length);
            output.position(0);output.get(target,targetOffset,length);
            javaOutputBytes=add(javaOutputBytes,length);
        }
        return result;
    }
    public synchronized long release(long token){return handle==0?CLOSED:nativeRelease(handle,token);}
    public synchronized long seek(){
        if(handle==0)return CLOSED;
        long result=nativeSeek(handle);if(result>0)generation=result;
        return result;
    }
    /** Must run off UI. Native worker joins before another session can open. */
    @Override public synchronized void close(){
        if(handle==0)return;
        long id=handle;handle=0;
        // No view is exposed outside this monitor. Clear references before
        // Rust joins the worker and frees the exact backing allocations.
        input=null;output=null;
        long result=nativeClose(id);
        if(result!=1)throw new IllegalStateException("Native close failed: "+result);
    }
}
