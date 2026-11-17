package app.apexview.tiled

import androidx.media3.exoplayer.dash.DashSegmentIndex
import java.io.IOException

/** Retains an explicit requested time in a sliding live index, without requiring a VOD duration. */
internal object LiveDashSegmentWindow {
    fun near(index: DashSegmentIndex, periodDurationUs: Long, periodStartUs: Long,
             nowUnixUs: Long, positionUs: Long, behind: Long, ahead: Long): List<ArchiveDashSegmentSelector.Candidate> {
        val first = index.getFirstAvailableSegmentNum(periodDurationUs, nowUnixUs)
        val count = index.getAvailableSegmentCount(periodDurationUs, nowUnixUs)
        if (count <= 0 || count == DashSegmentIndex.INDEX_UNBOUNDED.toLong()) throw IOException("Live DVR window is not available yet")
        val last = Math.addExact(first, count - 1)
        val inPeriod = Math.subtractExact(positionUs, periodStartUs)
        if (inPeriod < index.getTimeUs(first)) throw PermanentCaptureException("Requested time has expired from the live DVR window")
        val current = index.getSegmentNum(inPeriod, periodDurationUs).coerceIn(first, last)
        return (maxOf(first, current - behind)..minOf(last, current + ahead)).map {
            ArchiveDashSegmentSelector.Candidate(it, Math.addExact(periodStartUs, index.getTimeUs(it)))
        }
    }
}
