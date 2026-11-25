package app.apexview.tiled

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Publishes the protected CMAF output as one live DASH video representation.
 *
 * The MPD advertises the same CBCS scheme, KID and Widevine PSSH as the source representation.
 * Encrypted sample payloads are passed through untouched. This class never decrypts media or
 * requests a license; the owning FeedPlayer supplies its existing F1 Widevine session manager.
 */
internal object ProtectedHevcComposedDashPackageBuilder {
    private val WIDEVINE_UUID: UUID =
        UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    data class Package(
        val manifest: String,
        val resources: Map<String, ByteArray>,
        val width: Int,
        val height: Int,
        val sampleCount: Int,
        val fragmentCount: Int,
        val startNumber: Long,
        val presentationTimeOffsetTicks: Long,
        val availabilityStartTimeMs: Long,
        val nativeResources: Map<String, NativeFragmentLease> = emptyMap(),
        val sharedHeapResources: Map<String, HeapFragmentLease> = emptyMap()
    ) {
        fun zero() {
            resources.values.forEach { it.fill(0) }
            nativeResources.values.forEach { it.close() }
            sharedHeapResources.values.forEach { it.close() }
        }
    }

    fun build(
        window: ProtectedHevcLiveComposition.OutputWindow,
        widevinePssh: ByteArray?,
        presentationTimeOffsetTicks: Long,
        availabilityStartTimeMs: Long,
        clearOutput: Boolean = false,
        /** Transfer ONLY an independently detached OutputWindow into the live publisher. */
        takeOwnership: Boolean = false,
        /** Fully inspect and validate the CMAF/CBCS package without cloning media.
         * Only the actual Media3 publisher may take ownership of its buffers. */
        validationOnly: Boolean = false,
    ): Package {
        require(!takeOwnership || !validationOnly) {
            "Validation cannot simultaneously transfer protected CMAF ownership"
        }
        val init = window.initializationSegment
        require(init.isNotEmpty()) { "Protected composed init segment is empty" }
        require(window.fragments.isNotEmpty()) { "Protected composed output window is empty" }
        require(presentationTimeOffsetTicks >= 0L && availabilityStartTimeMs > 0L) {
            "Protected composed DASH clock is invalid"
        }

        val initReport = CmafBoxInspector.inspect(init)
        require(initReport.errors.isEmpty() && initReport.initializationSegmentPresent) {
            "Protected composed init is invalid: " + initReport.errors.joinToString("; ")
        }
        require(initReport.tracks.size == 1) {
            "Protected composed DASH requires exactly one track"
        }
        val track = initReport.tracks.single()
        if (clearOutput) {
            require(track.handler == "vide" && track.sampleEntry in setOf("hvc1", "hev1") &&
                !track.encrypted && track.protectionScheme == null && track.trackEncryption == null &&
                !track.sampleEncryptionBoxPresent && !track.sampleAuxiliarySizesPresent &&
                !track.sampleAuxiliaryOffsetsPresent && !track.sampleGroupDescriptionPresent && !track.sampleToGroupPresent) {
                "Clear composed output rejects encrypted tracks and encryption metadata"
            }
        } else {
            require(track.handler == "vide" && track.encrypted && track.sampleEntry == "encv" &&
                track.originalFormat in setOf("hvc1", "hev1") && track.protectionScheme == "cbcs" &&
                track.trackEncryption?.defaultIsProtected == true) {
                "Protected composed DASH requires one encrypted CBCS HEVC track"
            }
        }
        val trackId = requireNotNull(track.trackId) { "Protected composed track ID is missing" }
        val timescale = requireNotNull(track.timescale)
            .takeIf { it > 0L } ?: error("Protected composed media timescale is invalid")
        val width = requireNotNull(track.width).takeIf { it > 0 }
            ?: error("Protected composed video width is invalid")
        val height = requireNotNull(track.height).takeIf { it > 0 }
            ?: error("Protected composed video height is invalid")
        val protectionXml = if (clearOutput) "" else {
            val tenc = requireNotNull(track.trackEncryption)
            require(tenc.defaultKidIdentity != null && tenc.perSampleIvSize in setOf(0, 8, 16)) {
                "Protected composed CBCS defaults are incomplete"
            }
            protectionXml(init, widevinePssh)
        }

        var previousSequence = 0L
        var previousEnd: Long? = null
        var sampleCount = 0
        var totalBytes = init.size.toLong()
        val durations = mutableListOf<Long>()
        val timeline = StringBuilder()
        val urls = StringBuilder()
        val resources = linkedMapOf<String, ByteArray>()
        val pendingNative = linkedMapOf<String, NativeFragmentLease>()
        val pendingHeap = linkedMapOf<String, HeapFragmentLease>()
        if (!validationOnly) resources["init.mp4"] = if (takeOwnership) init else init.copyOf()

        window.fragments.forEachIndexed { index, fragment ->
            require(fragment.sequenceNumber in 1..UINT32_MAX &&
                fragment.sequenceNumber > previousSequence) {
                "Protected output fragment sequence is not strictly increasing"
            }
            require(fragment.timescale == timescale && fragment.width == width &&
                fragment.height == height && fragment.baseDecodeTimeTicks >= 0L &&
                fragment.durationTicks > 0L && fragment.sampleCount > 0) {
                "Protected output fragment $index differs from its initialization"
            }
            require(fragment.baseDecodeTimeTicks >= presentationTimeOffsetTicks) {
                "Protected output fragment $index precedes the DASH presentation offset"
            }
            previousEnd?.let { expected ->
                require(fragment.baseDecodeTimeTicks == expected) {
                    "Protected output fragment $index is not decode-time contiguous"
                }
            }
            val report = fragment.inspect(initReport)
            require(report.errors.isEmpty() && !report.initializationSegmentPresent &&
                report.fragments.size == 1) {
                "Protected output fragment $index is invalid: " +
                    report.errors.joinToString("; ")
            }
            val parsed = report.fragments.single()
            require(parsed.trackId == trackId && parsed.sampleCount == fragment.sampleCount) {
                "Composed fragment differs from its initialized track"
            }
            if (clearOutput) {
                require(!parsed.sampleEncryptionBoxPresent && !parsed.sampleAuxiliarySizesPresent &&
                    !parsed.sampleAuxiliaryOffsetsPresent && !parsed.sampleGroupDescriptionPresent &&
                    !parsed.sampleToGroupPresent && !parsed.sampleGroupEncryptionPresent) {
                    "Clear composed output rejects fragment encryption metadata"
                }
            } else {
                require(parsed.sampleEncryptionBoxPresent && parsed.sampleEncryptionStructureComplete &&
                    parsed.sampleEncryptionCount == fragment.sampleCount.toLong() &&
                    parsed.subsampleEncryptionSignalled == true && parsed.sampleEncryptionRangesCoverSamples) {
                    "Protected output fragment $index lost its CBCS sample metadata"
                }
            }

            val end = Math.addExact(fragment.baseDecodeTimeTicks, fragment.durationTicks)
            previousEnd = end
            previousSequence = fragment.sequenceNumber
            sampleCount = Math.addExact(sampleCount, fragment.sampleCount)
            totalBytes = Math.addExact(totalBytes, fragment.size.toLong())
            durations += fragment.durationTicks
            timeline.append(
                """<S t="${fragment.baseDecodeTimeTicks}" d="${fragment.durationTicks}"/>"""
            )
            val name = "segment-${fragment.sequenceNumber}.m4s"
            urls.append("""<SegmentURL media="$name"/>""")
            if (!validationOnly) {
                val native = fragment.nativeMedia
                val heap = fragment.sharedHeapMedia
                if (takeOwnership && native != null) pendingNative[name] = native
                else if (takeOwnership && heap != null) pendingHeap[name] = heap
                else resources[name] = if (takeOwnership) fragment.mediaFragment else fragment.copyMediaBytes()
            }
        }

        val first = window.fragments.first()
        val last = window.fragments.last()
        val windowDuration = Math.subtractExact(
            Math.addExact(last.baseDecodeTimeTicks, last.durationTicks),
            first.baseDecodeTimeTicks
        )
        require(windowDuration > 0L) { "Protected output window has no presentation duration" }
        val bandwidth = BigDecimal.valueOf(totalBytes)
            .multiply(BigDecimal.valueOf(8L))
            .multiply(BigDecimal.valueOf(timescale))
            .divide(BigDecimal.valueOf(windowDuration), 0, RoundingMode.CEILING)
            .longValueExact()
            .coerceAtLeast(1L)
        val windowSeconds = isoDuration(windowDuration, timescale)
        val delayTicks = durations.last()
        val delaySeconds = isoDuration(delayTicks, timescale)
        val ast = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(availabilityStartTimeMs))

        val manifest = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
     xmlns:cenc="urn:mpeg:cenc:2013"
     type="dynamic"
     profiles="urn:mpeg:dash:profile:isoff-live:2011"
     availabilityStartTime="$ast"
     minimumUpdatePeriod="PT1S"
     minBufferTime="PT2S"
     timeShiftBufferDepth="PT${windowSeconds}S"
     suggestedPresentationDelay="PT${delaySeconds}S">
  <Period id="protected-hevc-grid" start="PT0S">
    <AdaptationSet id="1" contentType="video" mimeType="video/mp4"
                   segmentAlignment="true" startWithSAP="1">
      $protectionXml
      <Representation id="apexview-protected-grid"
                      bandwidth="$bandwidth"
                      codecs="${if (clearOutput) track.sampleEntry else track.originalFormat}"
                      width="$width" height="$height">
        <SegmentList timescale="$timescale"
                     startNumber="${first.sequenceNumber}"
                     presentationTimeOffset="$presentationTimeOffsetTicks">
          <Initialization sourceURL="init.mp4"/>
          <SegmentTimeline>$timeline</SegmentTimeline>
          $urls
        </SegmentList>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>"""

        if (!validationOnly) resources["manifest.mpd"] = manifest.toByteArray(Charsets.UTF_8)
        // Only retain after all validation succeeds. The store gets its own
        // lease, independent of the caller's cancellation/zero lifecycle.
        val nativeResources = linkedMapOf<String, NativeFragmentLease>()
        val sharedHeapResources = linkedMapOf<String, HeapFragmentLease>()
        try {
            pendingNative.forEach { (name, native) -> nativeResources[name] = native.retain() }
            pendingHeap.forEach { (name, heap) -> sharedHeapResources[name] = heap.retain() }
        } catch (failure: Throwable) {
            nativeResources.values.forEach { it.close() }
            sharedHeapResources.values.forEach { it.close() }
            throw failure
        }
        return Package(
            manifest = manifest,
            resources = resources,
            width = width,
            height = height,
            sampleCount = sampleCount,
            fragmentCount = window.fragments.size,
            startNumber = first.sequenceNumber,
            presentationTimeOffsetTicks = presentationTimeOffsetTicks,
            availabilityStartTimeMs = availabilityStartTimeMs,
            nativeResources = nativeResources, sharedHeapResources = sharedHeapResources
        )
    }

    /** Shared by the untouched CMAF audio representation in the local receiver manifest. */
    internal fun protectionXml(init: ByteArray, widevinePssh: ByteArray?, scheme: String = "cbcs"): String {
        require(scheme in setOf("cenc", "cbcs"))
        val defaultKid = readDefaultKid(init, scheme)
        val pssh = findWidevinePssh(init) ?: normalizeWidevinePssh(widevinePssh)
        requireNotNull(pssh) { "Widevine PSSH is missing from the source initialization" }
        val encoded = Base64.getEncoder().encodeToString(pssh)
        pssh.fill(0)
        return """<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="$scheme" cenc:default_KID="$defaultKid"/>
      <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" value="Widevine"><cenc:pssh>$encoded</cenc:pssh></ContentProtection>"""
    }

    /**
     * The default_KID in the composed DASH MPD must come from the validated encv
     * track. In both tenc versions a reserved byte precedes isProtected; version 1
     * also contains a CBCS crypt/skip pattern. Never read the pattern as isProtected.
     */
    internal fun readDefaultKid(init: ByteArray, scheme: String = "cbcs"): String {
        val report = CmafBoxInspector.inspect(init)
        require(report.errors.isEmpty() && report.initializationSegmentPresent) {
            "Protected DASH initialization is not a validated CMAF init"
        }
        // This method is also used by the untouched CMAF audio representation
        // on Pitwall Cast. Its only track may be encrypted 'enca' with scheme 'cenc'.
        val track = report.tracks.singleOrNull { it.encrypted }
            ?: throw IllegalArgumentException("Protected DASH requires one encrypted track")
        val verified = requireNotNull(track.trackEncryption) {
            "Protected DASH init is missing validated tenc defaults"
        }
        require(scheme in setOf("cenc", "cbcs") &&
            track.protectionScheme == scheme && verified.defaultIsProtected &&
            verified.defaultKidIdentity != null) {
            "Protected DASH init does not contain one verified CBCS key identity"
        }
        val box = findBoxes(init, "tenc").singleOrNull()
            ?: throw IllegalArgumentException("Protected DASH init has no unambiguous tenc box")
        val payload = box.start + box.header
        require(box.size >= box.header + 23) { "Protected DASH init tenc is truncated" }
        val version = u8(init, payload)
        require(version in 0..1 && (1..3).all { u8(init, payload + it) == 0 }) {
            "Protected DASH init tenc FullBox is unsupported"
        }
        var cursor = payload + 4
        require(u8(init, cursor++) == 0) { "Protected DASH init tenc reserved byte is invalid" }
        val pattern = if (version == 1) {
            require(box.size >= box.header + 24) { "Protected DASH version-1 tenc is truncated" }
            u8(init, cursor++)
        } else 0
        val isProtected = u8(init, cursor++)
        val ivSize = u8(init, cursor++)
        require(isProtected == 1 && ivSize in setOf(0, 8, 16) &&
            verified.version == version && verified.perSampleIvSize == ivSize &&
            verified.cryptByteBlock == (pattern ushr 4) &&
            verified.skipByteBlock == (pattern and 0x0f)) {
            "Protected DASH tenc bytes disagree with verified CMAF track defaults"
        }
        require(cursor <= box.start + box.size - 16) {
            "Protected DASH tenc KID is truncated"
        }
        val kid = init.copyOfRange(cursor, cursor + 16)
        return try {
            require(ProtectedValueFingerprint.fromBytes("kid", kid) ==
                verified.defaultKidIdentity) {
                "Protected DASH KID differs from verified CMAF track identity"
            }
            val hex = kid.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            listOf(hex.substring(0, 8), hex.substring(8, 12),
                hex.substring(12, 16), hex.substring(16, 20),
                hex.substring(20, 32)).joinToString("-")
        } finally {
            kid.fill(0)
        }
    }

    private fun findWidevinePssh(bytes: ByteArray): ByteArray? {
        val systemId = uuidBytes(WIDEVINE_UUID)
        try {
            val box = findBoxes(bytes, "pssh").firstOrNull { candidate ->
                val start = candidate.start + candidate.header
                candidate.size >= candidate.header + 20 &&
                    bytes.copyOfRange(start + 4, start + 20).contentEquals(systemId)
            } ?: return null
            return bytes.copyOfRange(box.start, box.start + box.size)
        } finally {
            systemId.fill(0)
        }
    }

    private fun normalizeWidevinePssh(source: ByteArray?): ByteArray? {
        if (source == null || source.isEmpty()) return null
        findWidevinePssh(source)?.let { return it }
        require(source.size <= MAX_PSSH_PAYLOAD) { "Source Widevine initialization data is too large" }
        val uuid = uuidBytes(WIDEVINE_UUID)
        val body = byteArrayOf(0, 0, 0, 0) + uuid + uint32(source.size.toLong()) + source
        return try {
            uint32(body.size.toLong() + 8L) +
                "pssh".toByteArray(Charsets.US_ASCII) + body
        } finally {
            uuid.fill(0)
            body.fill(0)
        }
    }

    private data class Box(val start: Int, val size: Int, val header: Int)

    private fun findBox(bytes: ByteArray, type: String): Box? =
        findBoxes(bytes, type).firstOrNull()

    private fun findBoxes(bytes: ByteArray, type: String): List<Box> {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val found = mutableListOf<Box>()
        for (typeAt in 4 until bytes.size - 3) {
            if (bytes[typeAt] != typeBytes[0] || bytes[typeAt + 1] != typeBytes[1] ||
                bytes[typeAt + 2] != typeBytes[2] || bytes[typeAt + 3] != typeBytes[3]) continue
            val start = typeAt - 4
            val size32 = u32(bytes, start)
            if (size32 == 1L) {
                if (start > bytes.size - 16) continue
                val size64 = u64(bytes, start + 8)
                if (size64 < 16L || size64 > Int.MAX_VALUE.toLong() ||
                    size64 > bytes.size - start.toLong()) continue
                found += Box(start, size64.toInt(), 16)
            } else {
                if (size32 < 8L || size32 > Int.MAX_VALUE.toLong() ||
                    size32 > bytes.size - start.toLong()) continue
                found += Box(start, size32.toInt(), 8)
            }
        }
        return found
    }

    private fun isoDuration(ticks: Long, timescale: Long): String =
        BigDecimal.valueOf(ticks)
            .divide(BigDecimal.valueOf(timescale), 6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    private fun uuidBytes(uuid: UUID): ByteArray {
        val most = uuid.mostSignificantBits
        val least = uuid.leastSignificantBits
        return byteArrayOf(
            (most ushr 56).toByte(), (most ushr 48).toByte(),
            (most ushr 40).toByte(), (most ushr 32).toByte(),
            (most ushr 24).toByte(), (most ushr 16).toByte(),
            (most ushr 8).toByte(), most.toByte(),
            (least ushr 56).toByte(), (least ushr 48).toByte(),
            (least ushr 40).toByte(), (least ushr 32).toByte(),
            (least ushr 24).toByte(), (least ushr 16).toByte(),
            (least ushr 8).toByte(), least.toByte()
        )
    }

    private fun u8(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xff

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((u8(bytes, offset).toLong()) shl 24) or
            ((u8(bytes, offset + 1).toLong()) shl 16) or
            ((u8(bytes, offset + 2).toLong()) shl 8) or
            u8(bytes, offset + 3).toLong()

    private fun u64(bytes: ByteArray, offset: Int): Long {
        val high = u32(bytes, offset)
        val low = u32(bytes, offset + 4)
        require(high <= Int.MAX_VALUE.toLong()) { "ISO-BMFF box length exceeds signed range" }
        return (high shl 32) or low
    }

    private fun uint32(value: Long): ByteArray {
        require(value in 0..UINT32_MAX)
        return byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte()
        )
    }

    private const val UINT32_MAX = 0xffff_ffffL
    private const val MAX_PSSH_PAYLOAD = 1024 * 1024
}

/**
 * Atomically publishes validated DASH resources. Each immutable resource owns
 * its own lease count: a slow manifest or fragment reader cannot pin the rest
 * of an obsolete multi-megabyte window. No decrypted data or licence is used.
 */
@OptIn(UnstableApi::class)
internal class ProtectedDashResourceStore(
    private val maxBytes: Long = ProtectedOutputHistoryPolicy.MAX_RETAINED_BYTES + 4L * 1024 * 1024,
    private val maxReaders: Int = 8
) {
    init { require(maxBytes > 0 && maxReaders > 0) }
    private class Resource(val heap: ByteArray?, val native: FragmentReadLease?) {
        init { require((heap == null) != (native == null)) }
        val size get() = heap?.size ?: requireNotNull(native).size
        val identity: Any get() = heap ?: requireNotNull(native)
        val backing: Any get() = heap ?: requireNotNull(native).storageIdentity
        fun dispose() { heap?.fill(0); native?.close() }
        var users = 0
        var retired = false
    }
    private var current = emptyMap<String, Resource>()
    private val retired = mutableSetOf<Resource>()
    private val pinned = mutableSetOf<Resource>()
    private var pinnedBytes = 0L
    private var readerCount = 0

    class Lease internal constructor(
        private val heap: ByteArray?,
        private val native: FragmentReadLease?,
        private val onClose: () -> Unit
    ) : AutoCloseable {
        // Existing heap-only callers and tests retain this contract. Native
        // Media3 reads use readTo and never materialize an entire fragment.
        val bytes: ByteArray get() = requireNotNull(heap)
        val size: Int get() = heap?.size ?: requireNotNull(native).size
        private var closed = false

        @Synchronized fun readTo(target: ByteArray, offset: Int, start: Int, count: Int) {
            check(!closed)
            require(start >= 0 && count >= 0 && start <= size-count &&
                offset >= 0 && offset <= target.size-count)
            if (heap != null) heap.copyInto(target, offset, start, start+count)
            else requireNotNull(native).readTo(target, offset, start, count)
        }

        @Synchronized override fun close() {
            if (!closed) {
                closed = true
                onClose()
            }
        }
    }

    data class Snapshot(val currentBytes: Long, val retiredReadBytes: Long, val retiredResources: Int,
                        val uniqueHeapBytes: Long, val uniqueNativeLogicalBytes: Long)
    @Synchronized fun snapshot(): Snapshot {
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        var heap = 0L
        var native = 0L
        for (resource in current.values + retired) {
            val identity = resource.heap ?: requireNotNull(resource.native).storageIdentity
            if (seen.put(identity, true) != null) continue
            if (resource.native?.nativeBacked == true) native += resource.size else heap += resource.size
        }
        return Snapshot(current.values.sumOf { it.size.toLong() }, retired.sumOf { it.size.toLong() },
            retired.size, heap, native)
    }

    private fun recordMemory() {
        val exclusive = (current.values + retired).filter { it.heap != null }
        app.apexview.MediaMemoryAccounting.process.record(this,
            app.apexview.MediaMemoryAccounting.Kind.DASH_EXCLUSIVE,
            exclusive.sumOf { it.size.toLong() }, exclusive.filter { it.users > 0 }.sumOf { it.size.toLong() })
    }
    @Synchronized fun replace(next: ProtectedHevcComposedDashPackageBuilder.Package) {
        // The publisher transfers detached, uniquely owned arrays. Reject aliasing
        // before changing the active map; otherwise retiring one name could erase
        // the bytes a different name or in-flight reader still owns.
        val retained: Map<String, FragmentReadLease> = next.nativeResources + next.sharedHeapResources
        require(retained.size == next.nativeResources.size + next.sharedHeapResources.size &&
            next.resources.keys.intersect(retained.keys).isEmpty())
        require(next.resources.values.sumOf { it.size.toLong() } +
            retained.values.sumOf { it.size.toLong() } <= maxBytes) {
            "Protected DASH window exceeds its memory budget"
        }
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        val existing = java.util.IdentityHashMap<Any, Resource>()
        (current.values + retired).forEach { seen[it.identity] = true; existing[it.backing] = it }
        val nextResources = LinkedHashMap<String, Resource>(next.resources.size+retained.size)
        val nextBackings = java.util.IdentityHashMap<Any, Boolean>()
        val redundant = mutableListOf<FragmentReadLease>()
        next.resources.forEach { (name, bytes) ->
            require(seen.put(bytes, true) == null && nextBackings.put(bytes, true) == null) {
                "Protected DASH resource ownership already transferred"
            }
            nextResources[name] = Resource(bytes, null)
        }
        retained.forEach { (name, native) ->
            require(seen.put(native, true) == null && nextBackings.put(native.storageIdentity, true) == null) {
                "Protected DASH native lease ownership already transferred"
            }
            val previous = existing[native.storageIdentity]
            if (previous != null) {
                require(previous.native != null && previous.native !== native && previous.size == native.size)
                nextResources[name] = previous
                redundant += native
            } else nextResources[name] = Resource(null, native)
        }
        val previous = current
        val nextSet = nextResources.values.toSet()
        nextSet.forEach { if (it.retired) { retired.remove(it); it.retired = false } }
        current = nextResources
        previous.values.filterNot { it in nextSet }.forEach(::retire)
        redundant.forEach { it.close() }
        recordMemory()
    }

    @Synchronized fun acquire(name: String): Lease? {
        val resource = current[name] ?: return null
        check(!resource.retired) { "Cannot acquire a retired protected DASH resource" }
        if (readerCount >= maxReaders || (resource.users == 0 && resource.size.toLong() > maxBytes - pinnedBytes)) return null
        if (resource.users++ == 0) { pinned.add(resource); pinnedBytes += resource.size }
        readerCount++
        recordMemory()
        return Lease(resource.heap, resource.native) {
            synchronized(this) {
                check(resource.users > 0) { "Protected DASH lease was released twice" }
                readerCount--
                if (--resource.users == 0) {
                    pinned.remove(resource); pinnedBytes -= resource.size
                    if (resource.retired) {
                        retired.remove(resource)
                        resource.dispose()
                    }
                }
                recordMemory()
            }
        }
    }

    @Synchronized fun close() {
        val previous = current
        current = emptyMap()
        previous.values.forEach(::retire)
        recordMemory()
    }

    private fun retire(resource: Resource) {
        check(!resource.retired) { "Protected DASH resource retired twice" }
        resource.retired = true
        if (resource.users == 0) resource.dispose()
        else retired.add(resource)
    }
}

internal class ProtectedHevcLiveDashSource(private val clearOutput: Boolean = false) {
    private val resources = ProtectedDashResourceStore()
    private val media3DeliveredBytes=java.util.concurrent.atomic.AtomicLong()
    private var presentationTimeOffsetTicks: Long? = null
    private var availabilityStartTimeMs: Long? = null
    private var released = false

    @Synchronized
    fun publish(
        window: ProtectedHevcLiveComposition.OutputWindow,
        widevinePssh: ByteArray?
    ) {
        var transferred = false
        var pending: ProtectedHevcComposedDashPackageBuilder.Package? = null
        try {
            check(!released) { "Protected live DASH source is released" }
            val first = window.fragments.firstOrNull()
                ?: throw IllegalArgumentException("No protected output fragments are available")
            val last = window.fragments.last()
            val timescale = first.timescale
            require(timescale > 0L && window.fragments.all { it.timescale == timescale }) {
                "Protected output window changes media timescale"
            }
            if (presentationTimeOffsetTicks == null) {
                presentationTimeOffsetTicks = first.baseDecodeTimeTicks
                val relativeEnd = Math.addExact(
                    Math.subtractExact(
                        Math.addExact(last.baseDecodeTimeTicks, last.durationTicks),
                        first.baseDecodeTimeTicks
                    ),
                    0L
                )
                val elapsedMs = BigDecimal.valueOf(relativeEnd)
                    .multiply(BigDecimal.valueOf(1_000L))
                    .divide(BigDecimal.valueOf(timescale), 0, RoundingMode.HALF_UP)
                    .longValueExact()
                availabilityStartTimeMs = System.currentTimeMillis() - elapsedMs
            }
            val next = ProtectedHevcComposedDashPackageBuilder.build(
                window = window,
                widevinePssh = widevinePssh,
                presentationTimeOffsetTicks = requireNotNull(presentationTimeOffsetTicks),
                availabilityStartTimeMs = requireNotNull(availabilityStartTimeMs),
                clearOutput = clearOutput,
                takeOwnership = true,
            )
            pending = next
            // No multi-MB copies between validated window and Media3 package.
            // Once published, the resource store is its sole owner and securely
            // zeroes the buffers only when the last reader releases a lease.
            resources.replace(next)
            transferred = true
            pending = null
            val retained = resources.snapshot()
            android.util.Log.i("ApexviewSingleDecoder", "PROTECTED_DASH_RESOURCE_BYTES " +
                "currentBytes=${retained.currentBytes} retiredReadBytes=${retained.retiredReadBytes} " +
                "retiredResources=${retained.retiredResources} uniqueHeapBytes=${retained.uniqueHeapBytes} " +
                "uniqueNativeLogicalBytes=${retained.uniqueNativeLogicalBytes} media3DeliveredBytesTotal=${media3DeliveredBytes.get()}")
        } finally {
            if (!transferred) {
                pending?.nativeResources?.values?.forEach { it.close() }
                pending?.sharedHeapResources?.values?.forEach { it.close() }
                window.zero()
            } else {
                // Heap ownership transferred exactly as before. Native
                // ownership is independent; release the caller's tokens.
                window.fragments.forEach { it.closeRetained() }
            }
        }
    }

    fun dataSourceFactory(): DataSource.Factory = DataSource.Factory {
        ProtectedResourceDataSource(this)
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        resources.close()
    }

    private fun acquireResource(name: String): ProtectedDashResourceStore.Lease? =
        resources.acquire(name)

    @OptIn(UnstableApi::class)
    private class ProtectedResourceDataSource(
        private val owner: ProtectedHevcLiveDashSource
    ) : DataSource {
        private var currentLease: ProtectedDashResourceStore.Lease? = null
        private var currentPosition = 0
        private var endPosition = 0
        private var currentUri: Uri? = null
        private var delivered=0L

        override fun addTransferListener(transferListener: TransferListener) = Unit

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            val name = dataSpec.uri.lastPathSegment
                ?: throw IOException("Protected composed DASH request has no resource name")
            val acquired = owner.acquireResource(name)
                ?: throw IOException("Protected composed DASH resource is not in the live window: $name")
            try {
                val size = acquired.size
                val start = dataSpec.position
                if (start < 0L || start > size.toLong()) {
                    throw EOFException("Protected composed byte range starts outside $name")
                }
                val available = size.toLong() - start
                val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
                if (requested < 0L || requested > available || requested > Int.MAX_VALUE.toLong()) {
                    throw EOFException("Protected composed byte range exceeds $name")
                }
                close()
                currentLease = acquired
                currentPosition = start.toInt()
                endPosition = currentPosition + requested.toInt()
                currentUri = dataSpec.uri
                return requested
            } catch (failure: Throwable) {
                acquired.close()
                throw failure
            }
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) {
                throw IndexOutOfBoundsException("Invalid protected DASH read bounds")
            }
            if (length == 0) return 0
            val lease = currentLease ?: throw IOException("Protected DASH data source is not open")
            if (currentPosition >= endPosition) return C.RESULT_END_OF_INPUT
            val count = minOf(length, endPosition - currentPosition)
            lease.readTo(buffer, offset, currentPosition, count)
            delivered+=count
            currentPosition += count
            return count
        }

        override fun getUri(): Uri? = currentUri

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun close() {
            if(delivered>0)owner.media3DeliveredBytes.addAndGet(delivered)
            delivered=0
            currentLease?.close()
            currentLease = null
            currentPosition = 0
            endPosition = 0
            currentUri = null
        }
    }
}
