package app.apexview.cast.local

/** A dynamic DASH window can slide back to position zero while the archive clock keeps moving. */
internal object ReceiverTimeline {
    fun position(originMs: Long?, windowOffsetMs: Long, positionMs: Long): Long =
        if (originMs == null) positionMs else Math.addExact(originMs, Math.addExact(windowOffsetMs, positionMs))
    fun restorePosition(remoteBaseUtcMs: Long?, remotePositionMs: Long, localWindowUtcMs: Long?): Long =
        if (remoteBaseUtcMs != null && localWindowUtcMs != null) Math.subtractExact(Math.addExact(remoteBaseUtcMs, remotePositionMs), localWindowUtcMs).coerceAtLeast(0)
        else remotePositionMs
    fun seek(targetMs: Long, durationMs: Long): Long = if (durationMs > 0) targetMs.coerceIn(0, durationMs) else targetMs.coerceAtLeast(0)
}
