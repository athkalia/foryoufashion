package reports

import com.google.analytics.data.v1beta.*
import com.google.analytics.data.v1beta.BetaAnalyticsDataClient
import com.google.analytics.data.v1beta.DateRange
import com.google.analytics.data.v1beta.Dimension
import com.google.analytics.data.v1beta.Metric
import com.google.analytics.data.v1beta.OrderBy
import com.google.analytics.data.v1beta.RunReportRequest
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import java.io.FileInputStream

fun main() {
    val propertyId = "352936422"
    val credentialsPath = "for-you-fashion-7287925df27e.json"

    // Authenticate with Google Analytics Data API using Service Account
    val credentials = GoogleCredentials
        .fromStream(FileInputStream(credentialsPath))
        .createScoped(listOf("https://www.googleapis.com/auth/analytics.readonly"))

    BetaAnalyticsDataClient.create(
        BetaAnalyticsDataSettings.newBuilder()
            .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
            .build()
    ).use { analyticsData ->

        val response = analyticsData.runReport(
            RunReportRequest.newBuilder()
                .setProperty("properties/$propertyId")
                .addDateRanges(DateRange.newBuilder().setStartDate("15daysAgo").setEndDate("today"))
                .addDimensions(Dimension.newBuilder().setName("pagePath"))
                .addMetrics(Metric.newBuilder().setName("screenPageViews"))
                .addOrderBys(OrderBy.newBuilder()
                    .setMetric(OrderBy.MetricOrderBy.newBuilder().setMetricName("screenPageViews"))
                    .setDesc(true))
                .setLimit(100)
                .build()
        )
        val baseUrl = "https://foryoufashion.gr"
        val prefix = "/e-shop-gynaikeia-rouxa-online/"

        println("Top Visited Product Pages (Last 15 Days):")
        response.rowsList
            .filter { row -> row.dimensionValuesList[0].value.startsWith(prefix) }
            .take(10)
            .forEachIndexed { index, row ->
                val fullUrl = baseUrl + row.dimensionValuesList[0].value
                println("${index + 1}. $fullUrl - ${row.metricValuesList[0].value} views")
            }    }
}
