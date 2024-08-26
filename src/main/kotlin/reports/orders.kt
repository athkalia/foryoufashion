package reports

import Order
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.axis.CategoryAxis
import org.jfree.chart.axis.CategoryLabelPositions
import org.jfree.data.category.DefaultCategoryDataset
import java.io.File
import java.io.IOException
import org.apache.commons.text.StringEscapeUtils
import readOnlyConsumerKey
import readOnlyConsumerSecret

fun main() {
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val orders = fetchAllOrders(credentials)
    val groupedOrders = orders.map { it.status }.groupBy { it }
    println("DEBUG: Grouped orders: $groupedOrders")
    println("DEBUG: Total orders fetched: ${orders.size}")

    val orderStatusMap = processOrdersByMonth(orders)
    plotOrders(orderStatusMap)

    val paymentMethodDistributionMap = processPaymentMethods(orders)
    plotPaymentMethods(paymentMethodDistributionMap)
    println("ACTION: Order cancellation percentage plot saved as 'order_cancellation_percentage_by_month.png'")
    println("ACTION: Payment method distribution plot saved as 'payment_method_distribution.png'")
}

fun fetchAllOrders(credentials: String): List<Order> {
    val client = OkHttpClient()
    val orders = mutableListOf<Order>()
    var page = 1
    var hasMoreOrders: Boolean

    do {
        val url = "https://foryoufashion.gr/wp-json/wc/v3/orders?page=$page&per_page=100"
        val request = Request.Builder().url(url).header("Authorization", credentials).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string()!!
//            val decodedBody = StringEscapeUtils.unescapeJava(body)
//                println("DEBUG: Raw JSON response for page $page:\n$body")
            val mapper = jacksonObjectMapper()
            val fetchedOrders: List<Order> = mapper.readValue(body)
            orders.addAll(fetchedOrders)
            hasMoreOrders = fetchedOrders.isNotEmpty()
        }
        page++
    } while (hasMoreOrders)

    return orders.filter { order ->
        LocalDate.parse(order.date_created, DateTimeFormatter.ISO_DATE_TIME).year >= 2024
    }
}

fun processOrdersByMonth(orders: List<Order>): Map<String, Map<String, Double>> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    val orderCountMap = mutableMapOf<String, Int>()
    val statusCountMaps = mutableMapOf<String, MutableMap<String, Int>>()

    orders.forEach { order ->
        val date = LocalDate.parse(order.date_created, dateFormatter)
        val month = monthFormatter.format(date)
        val status = order.status

        // Count total orders
        orderCountMap.merge(month, 1) { oldValue, newValue -> oldValue + newValue }

        if (status !in statusCountMaps) {
            statusCountMaps[status] = mutableMapOf()
        }
        statusCountMaps[status]!!.merge(month, 1) { oldValue, newValue -> oldValue + newValue }
    }

    val statusPercentageMap = mutableMapOf<String, MutableMap<String, Double>>()

    statusCountMaps.forEach { (status, countMap) ->
        statusPercentageMap[status] = mutableMapOf()
        countMap.forEach { (month, count) ->
            val totalOrders = orderCountMap.getValue(month)
            val percentage = (count.toDouble() / totalOrders.toDouble()) * 100
            statusPercentageMap[status]!![month] = percentage
        }
    }
    println("DEBUG: $orderCountMap")
    return statusPercentageMap
}

fun plotOrders(statusPercentageMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()

    statusPercentageMap.forEach { (status, percentageMap) ->
        val sortedPercentageMap = percentageMap.toSortedMap()
        sortedPercentageMap.forEach { (month, percentage) ->
            dataset.addValue(percentage, "$status %", month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Order Status Percentage Over Time (Grouped by Month)",
        "Month",
        "Order Status Percentage (%)",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    // Adjust the x-axis labels to show all months
    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45

    val chartFile = File("order_status_percentage_by_month.png")
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
}

fun processPaymentMethods(orders: List<Order>): Map<String, Map<String, Double>> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    val paymentMethodCountMap = mutableMapOf<String, MutableMap<String, Int>>()
    val totalOrdersPerMonthMap = mutableMapOf<String, Int>()

    val differentPaymentTypes = mutableSetOf<String>()
    orders.forEach { order ->
        val date = LocalDate.parse(order.date_created, dateFormatter)
        val month = monthFormatter.format(date)

        val decodedPaymentMethod = StringEscapeUtils.unescapeJava(order.payment_method)
        val paymentMethod = when (decodedPaymentMethod) {
            "stripe_applepay" -> "Apple Pay (Stripe)"
            "stripe_googlepay" -> "Google Pay (Stripe)"
            else -> when (order.payment_method_title) {
                "Credit / Debit Card" -> "Κάρτα"
                "Με κάρτα μέσω Πειραιώς" -> "Κάρτα"
                else -> order.payment_method_title
            }
        }
        differentPaymentTypes.add(paymentMethod)
        val monthMap = paymentMethodCountMap.getOrPut(month) { mutableMapOf() }
        monthMap.merge(paymentMethod, 1) { oldValue, newValue -> oldValue + newValue }
        totalOrdersPerMonthMap.merge(month, 1) { oldValue, newValue -> oldValue + newValue }
    }
    println("DEBUG: Payment types: $differentPaymentTypes")

    // Convert counts to percentages
    val paymentMethodPercentageMap = mutableMapOf<String, MutableMap<String, Double>>()
    paymentMethodCountMap.forEach { (month, paymentMethods) ->
        val totalOrders = totalOrdersPerMonthMap.getValue(month)
        val percentageMap = paymentMethodPercentageMap.getOrPut(month) { mutableMapOf() }
        paymentMethods.forEach { (paymentMethod, count) ->
            val percentage = (count.toDouble() / totalOrders.toDouble()) * 100
            percentageMap[paymentMethod] = percentage
        }
    }

    return paymentMethodPercentageMap
}

fun plotPaymentMethods(paymentMethodPercentageMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()

    val sortedPaymentMethodPercentageMap = paymentMethodPercentageMap.toSortedMap()

    sortedPaymentMethodPercentageMap.forEach { (month, paymentMethods) ->
        paymentMethods.forEach { (paymentMethod, percentage) ->
            dataset.addValue(percentage, paymentMethod, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Payment Method Percentage Distribution Over Time (Grouped by Month)",
        "Month",
        "Payment Method Percentage (%)",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    // Adjust the x-axis labels to show all months
    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45

    val chartFile = File("payment_method_percentage_distribution.png")
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
}
