package com.reallanguage.app.feature.recording.domain

/** Qualitative recognition evidence never changes the preserved original audio or its segment times. */
object TranscriptSegmentReviewRules {
    fun required(evidence: RecordingTranscriptEvidence?): Boolean = evidence?.segments?.any { it.needsConfirmation == true } == true

    fun initial(evidence: RecordingTranscriptEvidence): List<TranscriptSegmentReview> =
        evidence.segments.map { TranscriptSegmentReview(it.segmentId, it.text) }

    private fun separators(evidence: RecordingTranscriptEvidence): List<String> {
        var cursor = 0
        val result = evidence.segments.map { segment ->
            val from = cursor
            while (cursor < evidence.originalText.length && evidence.originalText[cursor].isWhitespace()) cursor++
            val separator = evidence.originalText.substring(from, cursor)
            require(evidence.originalText.startsWith(segment.text, cursor))
            cursor += segment.text.length
            separator
        }
        require(evidence.originalText.substring(cursor).all(Char::isWhitespace))
        return result + evidence.originalText.substring(cursor)
    }

    fun render(evidence: RecordingTranscriptEvidence, reviews: List<TranscriptSegmentReview>): String {
        require(reviews.map { it.segmentId } == evidence.segments.map { it.segmentId })
        val gaps = separators(evidence)
        return buildString {
            reviews.forEachIndexed { index, review -> append(gaps[index]); append(review.text) }
            append(gaps.last())
        }
    }

    fun valid(evidence: RecordingTranscriptEvidence, reviews: List<TranscriptSegmentReview>): Boolean = runCatching {
        render(evidence, reviews)
        reviews.zip(evidence.segments).all { (review, source) ->
            (review.status != TranscriptSegmentReviewStatus.CONFIRMED || review.text.isNotBlank()) &&
                (review.status != TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM || review.unableToConfirm) &&
                (review.inferenceText == null || (review.unableToConfirm && source.mostLikelyIsInference && review.inferenceText == source.mostLikelyText)) &&
                when (review.origin) {
                    TranscriptSegmentTextOrigin.ORIGINAL -> review.text == source.text && review.inferenceText == null
                    TranscriptSegmentTextOrigin.ALTERNATIVE -> review.text in source.alternatives && review.inferenceText == null
                    TranscriptSegmentTextOrigin.INFERENCE -> review.unableToConfirm && source.mostLikelyIsInference &&
                        source.mostLikelyText != null && review.text == source.mostLikelyText && review.inferenceText == source.mostLikelyText
                    TranscriptSegmentTextOrigin.MANUAL, TranscriptSegmentTextOrigin.AI_REPAIR -> true
                }
        }
    }.getOrDefault(false)

    fun canConfirm(evidence: RecordingTranscriptEvidence, reviews: List<TranscriptSegmentReview>): Boolean =
        valid(evidence, reviews) && reviews.zip(evidence.segments).all { (review, source) ->
            source.needsConfirmation != true || review.status == TranscriptSegmentReviewStatus.CONFIRMED
        }

    fun change(evidence: RecordingTranscriptEvidence, reviews: List<TranscriptSegmentReview>, segmentId: String, change: TranscriptSegmentChange): List<TranscriptSegmentReview> {
        require(valid(evidence, reviews))
        val index = reviews.indexOfFirst { it.segmentId == segmentId }.also { require(it >= 0) }
        val before = reviews[index]
        val source = evidence.segments[index]
        val next = when (change) {
            TranscriptSegmentChange.CannotConfirm -> before.copy(status = TranscriptSegmentReviewStatus.UNABLE_TO_CONFIRM, unableToConfirm = true)
            TranscriptSegmentChange.ChooseOriginal -> TranscriptSegmentReview(segmentId, source.text)
            is TranscriptSegmentChange.ChooseAlternative -> before.copy(
                text = source.alternatives.getOrNull(change.index) ?: error("Missing current segment candidate"),
                status = TranscriptSegmentReviewStatus.PENDING, origin = TranscriptSegmentTextOrigin.ALTERNATIVE, inferenceText = null,
            )
            TranscriptSegmentChange.ChooseInference -> {
                require(before.unableToConfirm && source.mostLikelyIsInference && !source.mostLikelyText.isNullOrBlank())
                before.copy(text = source.mostLikelyText, status = TranscriptSegmentReviewStatus.PENDING,
                    origin = TranscriptSegmentTextOrigin.INFERENCE, inferenceText = source.mostLikelyText)
            }
            is TranscriptSegmentChange.Edit -> before.copy(text = change.text, status = TranscriptSegmentReviewStatus.PENDING, origin = TranscriptSegmentTextOrigin.MANUAL)
            TranscriptSegmentChange.Confirm -> {
                require(before.text.isNotBlank())
                before.copy(status = TranscriptSegmentReviewStatus.CONFIRMED)
            }
        }
        return reviews.toMutableList().apply { this[index] = next }.also { require(valid(evidence, it)) }
    }

    /** A repair identifies one original segment and one unique literal occurrence, never the first matching duplicate. */
    fun repair(evidence: RecordingTranscriptEvidence, reviews: List<TranscriptSegmentReview>, proposal: TranscriptRepairResult.Proposed): List<TranscriptSegmentReview> {
        require(valid(evidence, reviews) && proposal.changes.isNotEmpty())
        val bySegment = proposal.changes.groupBy { it.evidenceSegmentIds.singleOrNull() ?: error("Repair crosses segment boundaries") }
        require(bySegment.keys.all { id -> reviews.any { it.segmentId == id } })
        val next = reviews.map { review ->
            val changes = bySegment[review.segmentId] ?: return@map review
            val ranges = changes.map { change ->
                require(change.originalText.isNotBlank() && change.proposedText.isNotBlank() && change.originalText != change.proposedText)
                val start = review.text.indexOf(change.originalText)
                require(start >= 0 && review.text.indexOf(change.originalText, start + 1) < 0)
                Triple(start, start + change.originalText.length, change.proposedText)
            }.sortedBy { it.first }
            var cursor = 0
            val text = buildString {
                ranges.forEach { (start, end, replacement) ->
                    require(start >= cursor)
                    append(review.text.substring(cursor, start)); append(replacement); cursor = end
                }
                append(review.text.substring(cursor))
            }
            review.copy(text = text, status = TranscriptSegmentReviewStatus.PENDING, origin = TranscriptSegmentTextOrigin.AI_REPAIR)
        }
        require(valid(evidence, next) && render(evidence, next) == proposal.repairedText)
        require(proposal.repairedText != render(evidence, reviews))
        return next
    }
}
