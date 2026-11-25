package app.apexview.tiled;

import java.nio.ByteBuffer;

/**
 * G-A native engine ownership contract. Explicitly opened by a background
 * owner; no native library load during startup, no DRM/Media3 interception.
 * Supports bounded clear conversion and atlas jobs for explicit callers.
 * Native-owned bridge views are private and invalidated before deterministic close.
 */
public final class NativeStitchingEngine implements AutoCloseable {
    public static final long INVALID=-1, STALE=-2, PANIC=-3, BACKPRESSURE=-4, DEADLINE=-5, CLOSED=-6;
    private static final long MIB=1024L*1024L;
    private static final long RESERVE=576L*1024L;

    public static final class Budget {
        public final int slots, inputBytes, outputBytes;
        public final long ceilingBytes, reservedBytes;
        private Budget(int slots,int input,int output,long ceiling) {
            this.slots=slots;inputBytes=input;outputBytes=output;ceilingBytes=ceiling;
            reservedBytes=(slots+1L)*(input+(long)output)+RESERVE;
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
        Budget candidate=plan(lowRam,availableBytes,osThresholdBytes,jvmHeadroom,observedInput,observedOutput);
        if(candidate==null)return null;
        long workspace=4L*observedInput+observedOutput+8*MIB;
        for(int slots=candidate.slots;slots>=2;slots--) {
            Budget b=new Budget(slots,observedInput,observedOutput,candidate.ceilingBytes);
            if(workspace<=b.ceilingBytes-b.reservedBytes)return b;
        }
        return null;
    }

    /** Compute bounded wall-clock budget from media buffer slack and speed. */
    public static long slackMs(long bufferedMs,long positionMs,double speed) {
        if(bufferedMs<0 || positionMs<0 || !Double.isFinite(speed) || speed<=0)return 0;
        long media=Math.max(0,bufferedMs-positionMs);
        return Math.max(1,Math.min(60_000,(long)(media/speed)));
    }

    private static native int nativeAbi();
    private static native long nativeOpen(int slots,int input,int output,long ceiling);
    private static native ByteBuffer nativeBridge(long handle,boolean output);
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
        long id=nativeOpen(budget.slots,budget.inputBytes,budget.outputBytes,budget.ceilingBytes);
        if(id<=0)throw new IllegalStateException("Native admission failed: "+id);
        try{return new NativeStitchingEngine(id,budget);}
        catch(RuntimeException|Error failure){nativeClose(id);throw failure;}
    }
    public synchronized long generation(){return handle==0?CLOSED:generation;}
    public synchronized long submit(long expected,byte[] bytes,int prefix,int count,boolean foreground,long slack) {
        return submitJob(expected, bytes, prefix, count, foreground, slack, false);
    }
    public synchronized long submitClearAtlas(long expected, byte[] bytes, boolean foreground, long slack) {
        return submitJob(expected, bytes, 0, 0, foreground, slack, true);
    }
    private long submitJob(long expected, byte[] bytes, int prefix, int count, boolean foreground,
                           long slack, boolean clearAtlas) {
        if (handle == 0) return CLOSED;
        if (expected != generation) return STALE;
        if (bytes == null || bytes.length == 0 || bytes.length > input.capacity()) return INVALID;
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
            length>output.capacity() || targetOffset>target.length-length)return INVALID;
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
