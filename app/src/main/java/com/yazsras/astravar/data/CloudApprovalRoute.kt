package com.yazsras.astravar.data

internal val reviewDigest = Regex("[0-9a-f]{64}")
private val listReviewPath = Regex("review-inbox\\?limit=(?:[1-9]|[1-4][0-9]|50)(?:&after=[A-Za-z0-9][A-Za-z0-9._:-]{0,127})?")
private val detailReviewPath = Regex("review-inbox/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\\?digest=[0-9a-f]{64}")
internal fun validApprovalViewSuffix(suffix: String) = listReviewPath.matches(suffix) || detailReviewPath.matches(suffix)
internal fun approvalViewSuffix(commandId: String, digest: String): String {
    require(cloudIdentifier.matches(commandId) && reviewDigest.matches(digest))
    return "review-inbox/$commandId?digest=$digest"
}
