package app.apexview.tiled

/**
 * Compares per-sample HEVC picture identity after the caller has derived full POC in decode order.
 * Container timestamps are not picture identity: matching clocks cannot make different reference
 * lists, temporal layers, or random-access pictures interchangeable.
 */
internal object HevcPictureIdentityCompatibility {
    data class PictureIdentity(
        val nalUnitType: Int,
        val temporalIdPlus1: Int,
        val pictureOrderCount: Long,
        val sliceType: Int?,
        val noOutputOfPriorPictures: Boolean?,
        val references: List<HevcSliceHeaderParser.ReferencePicture>,
        val picOutputFlag: Boolean? = null,
        val colourPlaneId: Int? = null,
        val shortTermRpsFromSps: Boolean? = null,
        val shortTermRpsIndex: Int? = null,
        val numPicTotalCurrent: Int = 0,
        val numRefIdxL0Active: Int? = null,
        val numRefIdxL1Active: Int? = null,
        val list0Modification: List<Int> = emptyList(),
        val list1Modification: List<Int> = emptyList(),
        val temporalMvpEnabled: Boolean? = null,
        val mvdL1Zero: Boolean? = null,
        val cabacInitEnabled: Boolean? = null,
        val collocatedFromL0: Boolean? = null,
        val collocatedReferenceIndex: Int? = null,
        val predictionWeightTable: HevcSliceHeaderParser.PredictionWeightTable? = null,
        val fiveMinusMaxNumMergeCand: Int? = null
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
        references = slice.references,
        picOutputFlag = slice.picOutputFlag,
        colourPlaneId = slice.colourPlaneId,
        shortTermRpsFromSps = slice.shortTermRpsFromSps,
        shortTermRpsIndex = slice.shortTermRpsIndex,
        numPicTotalCurrent = slice.numPicTotalCurrent,
        numRefIdxL0Active = slice.numRefIdxL0Active,
        numRefIdxL1Active = slice.numRefIdxL1Active,
        list0Modification = slice.list0Modification,
        list1Modification = slice.list1Modification,
        temporalMvpEnabled = slice.temporalMvpEnabled,
        mvdL1Zero = slice.mvdL1Zero,
        cabacInitEnabled = slice.cabacInitEnabled,
        collocatedFromL0 = slice.collocatedFromL0,
        collocatedReferenceIndex = slice.collocatedReferenceIndex,
        predictionWeightTable = slice.predictionWeightTable,
        fiveMinusMaxNumMergeCand = slice.fiveMinusMaxNumMergeCand
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
            if (a.picOutputFlag != b.picOutputFlag) {
                reasons += "sample $index pic_output_flag differs"
            }
            if (a.colourPlaneId != b.colourPlaneId) {
                reasons += "sample $index colour_plane_id differs"
            }
            if (a.shortTermRpsFromSps != b.shortTermRpsFromSps ||
                a.shortTermRpsIndex != b.shortTermRpsIndex) {
                reasons += "sample $index short-term RPS selection differs"
            }
            if (a.references != b.references) {
                reasons += "sample $index reference-picture set differs"
            }
            if (a.numPicTotalCurrent != b.numPicTotalCurrent) {
                reasons += "sample $index NumPicTotalCurr differs"
            }
            if (a.numRefIdxL0Active != b.numRefIdxL0Active ||
                a.numRefIdxL1Active != b.numRefIdxL1Active) {
                reasons += "sample $index active reference counts differ"
            }
            if (a.list0Modification != b.list0Modification ||
                a.list1Modification != b.list1Modification) {
                reasons += "sample $index reference-list modifications differ"
            }
            if (a.temporalMvpEnabled != b.temporalMvpEnabled) {
                reasons += "sample $index temporal MVP structure differs"
            }
            if (a.mvdL1Zero != b.mvdL1Zero || a.cabacInitEnabled != b.cabacInitEnabled) {
                reasons += "sample $index prediction/CABAC initialisation flags differ"
            }
            if (a.collocatedFromL0 != b.collocatedFromL0 ||
                a.collocatedReferenceIndex != b.collocatedReferenceIndex) {
                reasons += "sample $index collocated reference selection differs"
            }
            if (a.predictionWeightTable != b.predictionWeightTable) {
                reasons += "sample $index prediction weight table differs"
            }
            if (a.fiveMinusMaxNumMergeCand != b.fiveMinusMaxNumMergeCand) {
                reasons += "sample $index merge-candidate count differs"
            }
        }
        return Result(reasons.isEmpty(), reasons.distinct())
    }
}
