package other

import Order
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import readOnlyConsumerKey
import readOnlyConsumerSecret

const val thisMonth = true

fun main(args: Array<String>) {
    val agencyFee = 400.0
    val marketingSpend = 5000
    val totalMarketingSpendIncludingMonthlyPayment = agencyFee + marketingSpend
    val adjustedMarketingSpend = if (thisMonth) {
        val today = LocalDate.now()
        val daysInMonth = today.lengthOfMonth()
        val daysPassed = today.dayOfMonth
        totalMarketingSpendIncludingMonthlyPayment * (daysPassed.toDouble() / daysInMonth.toDouble())
    } else {
        totalMarketingSpendIncludingMonthlyPayment
    }
    println("DEBUG: Calculated marketing spend: $adjustedMarketingSpend")

    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val month = if (thisMonth) getCurrentMonth() else getPreviousMonthDate()
    val orders = fetchOrders(month, credentials)
    val refundedOrderCountPercentage = calculateRefundedOrderCountPercentage(orders)
    val refundedMoneyPercentage = calculateRefundedOrderMoneyPercentage(orders)
    val completedRevenue = calculateCompletedRevenue(orders)
    val refundedRevenue = calculateRefundedRevenue(orders)

    val monthAndYear = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    val content = generateContent(
        refundedOrderCountPercentage,
        refundedMoneyPercentage,
        completedRevenue,
        refundedRevenue,
        monthAndYear,
        adjustedMarketingSpend
    )
    println(content)
}

fun calculateRefundedOrderMoneyPercentage(orders: List<Order>): Double {
    val refundedOrdersMoney = orders.filter { it.status=="refunded" }.sumOf { it.total.toDouble() }
    val completedOrdersMoney =
        orders.filter { it.status=="completed" || it.status=="processing" }.sumOf { it.total.toDouble() }
    return (refundedOrdersMoney / (completedOrdersMoney + refundedOrdersMoney)) * 100
}

private fun getPreviousMonthDate(): LocalDate = LocalDate.now().minusMonths(1)
private fun getCurrentMonth(): LocalDate = LocalDate.now()

fun fetchOrders(previousMonthDate: LocalDate, credentials: String): List<Order> {
    println("Fetching orders..")
    val client = OkHttpClient()
    val orders = mutableListOf<Order>()
    val currentMonth = previousMonthDate.monthValue
    val currentYear = previousMonthDate.year
    var page = 1
    var hasMoreOrders: Boolean

    do {
        val url = "https://foryoufashion.gr/wp-json/wc/v3/orders?page=$page&per_page=100"
        val request = Request.Builder().url(url).header("Authorization", credentials).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string()!!
            val mapper = jacksonObjectMapper()
            val fetchedOrders: List<Order> = mapper.readValue(body)
            orders.addAll(fetchedOrders.filter {
                val orderDate = LocalDate.parse(it.date_created.substring(0, 10))
                orderDate.monthValue==currentMonth && orderDate.year==currentYear
            })
            hasMoreOrders = fetchedOrders.isNotEmpty()
        }
        page++
    } while (hasMoreOrders)

    return orders
}

fun calculateRefundedOrderCountPercentage(orders: List<Order>): Double {
    val refundedOrders = orders.count { it.status=="refunded" }
    val completedOrders = orders.count { it.status=="completed" || it.status=="processing" }
    return (refundedOrders.toDouble() / (completedOrders + refundedOrders).toDouble()) * 100
}

fun calculateCompletedRevenue(orders: List<Order>): Double {
    val completedOrders = orders.filter { it.status=="completed" || it.status=="processing" }
    return completedOrders.sumOf { it.total.toDouble() }
}

fun calculateRefundedRevenue(orders: List<Order>): Double {
    return orders.filter { it.status=="refunded" }.sumOf { it.total.toDouble() }
}

fun generateContent(
    refundedOrderCountPercentage: Double,
    refundedMoneyPercentage: Double,
    completedOrdersRevenue: Double,
    refundedRevenue: Double,
    monthAndYear: String,
    marketingSpend: Double
): String {
    return """
        $monthAndYear:
        - Έσοδα από ολοκληρωμένες παραγγελίες: €${"%.2f".format(completedOrdersRevenue + refundedRevenue)}
        - Επιστροφές χρημάτων: €${"%.2f".format(refundedRevenue)}
        - Έσοδα μετά τις επιστροφές: €${"%.2f".format(completedOrdersRevenue)}
        - Ποσοστό επιστροφών σε αριθμο παραγγελιων: ${"%.2f".format(refundedOrderCountPercentage)}%
        - Ποσοστό επιστροφών σε λεφτα: ${"%.2f".format(refundedMoneyPercentage)}%
        - Συνολικά έξοδα marketing για το μήνα: €${"%.2f".format(marketingSpend)} 
        - Συνολικό πραγματικό ROAS για όλο το site: ${"%.2f".format(completedOrdersRevenue / marketingSpend)} 
    """.trimIndent()
}
