package app.apexview.tiled

/**
 * Compares per-sample HEVC picture identity after the caller has derived full POC in decode order.
 * This is separate from container timestamp matching: two feeds at the same timestamp can still
 * carry different pictures, random-access boundaries, temporal layers, or reference pictures.
 */
internal object HevcPictureIdentityCompatibility {
    data class PictureIdentity(
        val nalUnitType: Int,
        val temporalIdPlus1: Int,
        val pictureOrderCount: Long,
        val sliceType: Int?,
        val noOutputOfPriorPictures: Boolean?,
        val references: List<HevcSliceHeaderParser.ReferencePicture>
    )

    data class Result(val compatible: Boolean, val reasons: List<String>)

    fun identify(
        slice: HevcSliceHeaderParser.SliceHeader,
        fullPictureOrderCount: Long
    ): PictureIdentity = PictureIdentity(
        nalUnitType = slice.nalUnitType,
        temporalIdPlus1 = slice.temporalIdPlus1,
        pictureOrderCount = fullPictureOrderCount,
        sliceType = slice.sliceType,
        noOutputOfPriorPictures = slice.noOutputOfPriorPictures,
        references = slice.references
    )

    fun compare(
        first: List<PictureIdentity>,
        second: List<PictureIdentity>
    ): Result {
        val reasons = mutableListOf<String>()
        if (first.isEmpty() || second.isEmpty()) {
            reasons += "Both feeds must provide at least one parsed picture identity"
        }
        if (first.size != second.size) {
            reasons += "Per-sample HEVC picture identity counts differ"
        }
        first.zip(second).forEachIndexed { index, pair ->
            val a = pair.first
            val b = pair.second
            if (a.nalUnitType != b.nalUnitType) {
                reasons += "sample $index NAL/IDR/CRA picture type differs"
            }
            if (a.temporalIdPlus1 != b.temporalIdPlus1) {
                reasons += "sample $index temporal_id_plus1 differs"
            }
            if (a.pictureOrderCount != b.pictureOrderCount) {
                reasons += "sample $index full POC differs"
            }
            if (a.sliceType != b.sliceType) {
                reasons += "sample $index slice type differs"
            }
            if (a.noOutputOfPriorPictures != b.noOutputOfPriorPictures) {
                reasons += "sample $index random-access no-output flag differs"
            }
            if (a.references != b.references) {
                reasons += "sample $index reference-picture set differs"
            }
        }
        return Result(reasons.isEmpty(), reasons.distinct())
    }
}
