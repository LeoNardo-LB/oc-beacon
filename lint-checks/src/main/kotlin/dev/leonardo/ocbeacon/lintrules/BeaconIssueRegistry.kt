package dev.leonardo.ocbeacon.lintrules

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/** #391 切片8 / backlog #397：oc-beacon 自定义 lint 规则注册表。 */
class BeaconIssueRegistry : IssueRegistry() {

    override val api: Int = CURRENT_API

    override val issues: List<Issue> = listOf(
        ServerTypeWhitelistDetector.ISSUE,
        ServerTypeUiBoundaryDetector.ISSUE,
        TokenBypassDetector.ISSUE,
        SpacingTokenBypassDetector.ISSUE,
    )

    override val vendor: Vendor = Vendor(
        vendorName = "oc-beacon",
        identifier = "dev.leonardo.ocbeacon.lint",
        feedbackUrl = "https://github.com/Leonardo-LB/oc-beacon/issues",
    )
}
