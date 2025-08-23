package reports


import Order
import Product
import allAutomatedChecks.fetchAllProducts
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.awt.BasicStroke
import java.awt.geom.Ellipse2D
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TreeSet
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.text.StringEscapeUtils
import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.chart.axis.CategoryAxis
import org.jfree.chart.axis.CategoryLabelPositions
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.axis.SymbolAxis
import org.jfree.chart.plot.CategoryPlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.category.LineAndShapeRenderer
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import readOnlyConsumerKey
import readOnlyConsumerSecret

fun main() {
    println("Start time: ${LocalTime.now()}")
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val orders = fetchAllOrdersSince2024(credentials)
    println("DEBUG: Total orders fetched: ${orders.size}")

    val monthlyReturns = generateMonthlyReturnRatesSince2025(orders)
    saveMonthlyReturnRatesTxt(monthlyReturns)

    val topRefunders = generateTopRefundingCustomers(orders)
    saveTopRefundersCsv(topRefunders)

    val orderStatusMap = generateOrderStatusData(orders)
    plotOrdersStatusesByMonth(orderStatusMap)

    val paymentMethodDistributionMap = generatePaymentMethodsData(orders)
    plotPaymentMethodsByMonth(paymentMethodDistributionMap)

    val productAges = generateProductAgesAtSaleData(orders, credentials)
    plotProductAgesWithAverageByMonth(productAges)

    val modeloTagToFilterBy = "ΜΟΝΤΕΛΟ"
    val modelProductSalesMap = generateProductSalesData(orders, modeloTagToFilterBy, credentials)
    plotProductSalesData(modelProductSalesMap, modeloTagToFilterBy)

    val modelAdjustedProductSalesMap =
        generateAdjustedProductSalesDataByMonthlyTag(orders, modeloTagToFilterBy, credentials)
    plotAdjustedProductSalesDataMonthly(modelAdjustedProductSalesMap, modeloTagToFilterBy)

    val modeloMonetarySalesMap = generateMonetarySalesData(orders, modeloTagToFilterBy, credentials)
    plotMonetarySalesData(modeloMonetarySalesMap, modeloTagToFilterBy)

    val modeloAdjustedMonetarySalesMap =
        generateAdjustedMonetarySalesDataByMonthlyTag(orders, modeloTagToFilterBy, credentials)
    plotAdjustedMonetarySalesData(modeloAdjustedMonetarySalesMap, modeloTagToFilterBy)

    val fwtografisiTagToFilterBy = "ΦΩΤΟΓΡΑΦΙΣΗ"
    val fwtografisiProductSalesMap = generateProductSalesData(orders, fwtografisiTagToFilterBy, credentials)
    plotProductSalesData(fwtografisiProductSalesMap, fwtografisiTagToFilterBy)

    val fwtografisiAdjustedProductSalesMap =
        generateAdjustedProductSalesDataByMonthlyTag(orders, fwtografisiTagToFilterBy, credentials)
    plotAdjustedProductSalesDataMonthly(fwtografisiAdjustedProductSalesMap, fwtografisiTagToFilterBy)

    val fwtografisiMonetarySalesMap = generateMonetarySalesData(orders, fwtografisiTagToFilterBy, credentials)
    plotMonetarySalesData(fwtografisiMonetarySalesMap, fwtografisiTagToFilterBy)

    val fwtografisiAdjustedMonetarySalesMap =
        generateAdjustedMonetarySalesDataByMonthlyTag(orders, fwtografisiTagToFilterBy, credentials)
    plotAdjustedMonetarySalesData(fwtografisiAdjustedMonetarySalesMap, fwtografisiTagToFilterBy)
    println("Finish time: ${LocalTime.now()}")
}

data class MonthlyReturns(
    val month: String,
    val totalOrders: Int,
    val refundedOrders: Int,
    val returnRatePct: Double
)

fun generateMonthlyReturnRatesSince2025(orders: List<Order>): List<MonthlyReturns> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    val start = LocalDate.of(2025, 4, 1)
    val currentMonth = YearMonth.now()

    // Aggregate totals and refunds per month
    val totals = mutableMapOf<String, Int>()
    val refunds = mutableMapOf<String, Int>()

    orders.forEach { order ->
        val d = LocalDate.parse(order.date_created, dateFormatter)
        if (!d.isBefore(start)) {
            val ym = YearMonth.from(d)
            if (ym!=currentMonth) { // exclude current month
                val monthEntry = monthFormatter.format(d)
                totals.merge(monthEntry, 1) { a, b -> a + b }
                if (isRefunded(order)) {
                    refunds.merge(monthEntry, 1) { a, b -> a + b }
                }
            }
        }
    }

    // Build rows, sorted by month
    return totals.keys.sorted()
        .map { m ->
            val totalCount = totals[m] ?: 0
            val refundCount = refunds[m] ?: 0
            val refundPercentage = if (totalCount==0) 0.0 else (refundCount.toDouble() / totalCount.toDouble()) * 100.0
            MonthlyReturns(
                month = m,
                totalOrders = totalCount,
                refundedOrders = refundCount,
                returnRatePct = String.format("%.2f", refundPercentage).toDouble()
            )
        }
}

fun saveMonthlyReturnRatesTxt(
    rows: List<MonthlyReturns>,
    fileName: String = "report_monthly_return_rates_since_2025.txt"
) {
    val file = File(fileName)
    file.printWriter().use { out ->
        out.println("Monthly Return Rates")
        out.println("Month      | Total | Refunded | Return Rate (%)")
        out.println("-----------+-------+----------+----------------")
        rows.forEach { r ->
            out.println(
                String.format(
                    "%-10s | %5d | %8d | %14.2f",
                    r.month,
                    r.totalOrders,
                    r.refundedOrders,
                    r.returnRatePct
                )
            )
        }
    }
    println("ACTION: Monthly return rates TXT saved as $fileName")
}


fun fetchAllOrdersSince2024(credentials: String): List<Order> {
    val client = OkHttpClient()
    val orders = mutableListOf<Order>()
    var page = 1
    var hasMoreOrders: Boolean

    // Fetching all orders of 2024 onwards
    val afterDate = "2024-01-01T00:00:00"

    do {
        println("DEBUG: Fetching orders page $page")
        // Add the `after` parameter to the URL to fetch orders created after the specified date
        val url = "https://foryoufashion.gr/wp-json/wc/v3/orders?page=$page&per_page=100&after=$afterDate"
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

    return orders
}

fun generateOrderStatusData(orders: List<Order>): Map<String, Map<String, Double>> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    val orderCountMap = mutableMapOf<String, Int>()
    val statusCountMaps = mutableMapOf<String, MutableMap<String, Int>>()
    orders.forEach { order ->
        val date = LocalDate.parse(order.date_created, dateFormatter)
        val month = monthFormatter.format(date)
        val status = order.status

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
    println("DEBUG: Orders by month: $orderCountMap")
    return statusPercentageMap
}

fun plotOrdersStatusesByMonth(statusPercentageMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()

    val filteredStatusPercentageMap = statusPercentageMap.filter { it.key!="on-hold" && it.key!="processing" }
    val allMonths = TreeSet<YearMonth>()

    filteredStatusPercentageMap.forEach { (_, percentageMap) ->
        percentageMap.keys.forEach { month ->
            allMonths.add(YearMonth.parse(month))
        }
    }

    filteredStatusPercentageMap.forEach { (status, percentageMap) ->
        allMonths.forEach { yearMonth ->
            val monthStr = yearMonth.toString()
            val percentage = percentageMap[monthStr] ?: 0.0
            dataset.addValue(percentage, "$status %", monthStr)
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

    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45
    thickenLines(categoryPlot, dataset)

    val fileName = "report_order_status_percentage_by_month.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Order cancellation percentage plot saved as $fileName")
}

fun generatePaymentMethodsData(orders: List<Order>): Map<String, Map<String, Double>> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    val paymentMethodCountMap = mutableMapOf<String, MutableMap<String, Int>>()
    val totalOrdersPerMonthMap = mutableMapOf<String, Int>()

    val differentPaymentTypes = mutableSetOf<String>()
    val completedOrders = orders.filter { it.status=="completed" }
    completedOrders.forEach { order ->
        val date = LocalDate.parse(order.date_created, dateFormatter)
        val month = monthFormatter.format(date)
//            println("DEBUG: Order payment method: ${order.payment_method} payment method title: ${order.payment_method_title}")
        val paymentMethod = when (StringEscapeUtils.unescapeJava(order.payment_method)) {
            "stripe_applepay" -> "Apple Pay"
            "stripe_googlepay" -> "Google Pay"
            "stripe_klarna" -> "Klarna"
            "klarna_payments" -> "Klarna"
            else -> when (order.payment_method_title) {
                "Credit / Debit Card" -> "Κάρτα"
                "Με κάρτα μέσω Πειραιώς" -> "Κάρτα"
                "Apple Pay (Stripe)" -> "Apple Pay"
                else -> order.payment_method_title
            }
        }
        differentPaymentTypes.add(paymentMethod)
        val monthMap = paymentMethodCountMap.getOrPut(month) { mutableMapOf() }
        monthMap.merge(paymentMethod, 1) { oldValue, newValue -> oldValue + newValue }
        totalOrdersPerMonthMap.merge(month, 1) { oldValue, newValue -> oldValue + newValue }
    }
//    println("DEBUG: Payment types: $differentPaymentTypes")
//    println("DEBUG: Payments map: $totalOrdersPerMonthMap")

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
//    println("DEBUG: Payments percentage map: $paymentMethodPercentageMap")
    return paymentMethodPercentageMap
}

fun plotPaymentMethodsByMonth(paymentMethodPercentageMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()
    val sortedPaymentMethodPercentageMap = paymentMethodPercentageMap.toSortedMap()

    // Find all months that appear in the data
    val allMonths = sortedPaymentMethodPercentageMap.keys.toSortedSet()

    // Find all payment methods
    val allPaymentMethods = mutableSetOf<String>()
    sortedPaymentMethodPercentageMap.values.forEach { paymentMethods ->
        allPaymentMethods.addAll(paymentMethods.keys)
    }

    // Ensure each payment method appears in each month, filling in 0 for missing months
    allPaymentMethods.forEach { paymentMethod ->
        allMonths.forEach { month ->
            val percentage = sortedPaymentMethodPercentageMap[month]?.get(paymentMethod) ?: 0.0
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

    thickenLines(categoryPlot, dataset)

    val fileName = "report_payment_method_percentage_distribution.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Payment method distribution plot saved as $fileName")
}

fun generateProductAgesAtSaleData(orders: List<Order>, credentials: String): List<Pair<LocalDate, Long>> {
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val productAges = mutableListOf<Pair<LocalDate, Long>>()  // List of pairs (order date, product age)
    orders.sortedBy { it.date_created }.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
        order.line_items.forEach { item ->
            if (item.product_id==0) {
                println("WARNING: Skipping line item with invalid product_id 0 for order ${order.id}")
                return@forEach
            }
            try {
                // Fetch product creation date
                val productCreationDate = getProductCreationDate(item.product_id, credentials)

                // Calculate the age of the product at the time of the order (in days)
                val productAgeAtSale = ChronoUnit.DAYS.between(productCreationDate, orderDate)

                // Add the order date and product age to the list
                productAges.add(orderDate to productAgeAtSale)
            } catch (e: IOException) {
                println("ERROR: Failed to fetch product creation date for product_id ${item.product_id} in order ${order.id}. Error: ${e.message}")
            }
        }
    }
    return productAges
}

fun getProductCreationDate(productId: Int, credentials: String): LocalDate {
    val client = OkHttpClient()
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val body = response.body?.string()!!
        val mapper = jacksonObjectMapper()
        val product: Product = mapper.readValue(body)
        return LocalDate.parse(product.date_created, DateTimeFormatter.ISO_DATE_TIME)
    }
}

fun plotProductAgesWithAverageByMonth(productAges: List<Pair<LocalDate, Long>>) {
    val dataset = XYSeriesCollection()
    val scatterSeries = XYSeries("Sold product age")
    val averageSeries = XYSeries("Average sold product Age for month")

    val monthFormatter = DateTimeFormatter.ofPattern("MM-yyyy")
    val months = mutableListOf<String>()
    val monthlyAges = mutableMapOf<String, MutableList<Double>>()  // To store product ages for each month

    // Calculate the X-axis position for each order date
    productAges.forEach { (orderDate, productAgeInDays) ->
        val orderMonth = monthFormatter.format(orderDate)

        // Add month to the list if it's not already present
        if (orderMonth !in months) {
            months.add(orderMonth)
        }

        // Get the index of the month (X-axis position)
        val monthIndex = months.indexOf(orderMonth).toDouble()

        // Calculate the position within the month based on the day of the month
        val yearMonth = YearMonth.of(orderDate.year, orderDate.month)
        val daysInMonth = yearMonth.lengthOfMonth()
        val dayPosition = orderDate.dayOfMonth.toDouble() / daysInMonth

        // Final X-axis position: month index + fractional position within the month
        val xAxisPosition = monthIndex + dayPosition

        // Convert the product age in days to months
        val productAgeInMonths = productAgeInDays / 30.0  // Approximate conversion from days to months

        // Add the point to the scatter series
        scatterSeries.add(xAxisPosition, productAgeInMonths)

        // Add to the list of ages for this month
        monthlyAges.computeIfAbsent(orderMonth) { mutableListOf() }.add(productAgeInMonths)
    }

    // Calculate average product age for each month and add it to the average series
    monthlyAges.forEach { (month, ages) ->
        val monthIndex = months.indexOf(month).toDouble()
        val averageAge = ages.average()

        // Add the average age as a point for each month
        averageSeries.add(monthIndex + 0.5, averageAge)  // Position in the middle of the month
    }

    // Add both series to the dataset
    dataset.addSeries(scatterSeries)
    dataset.addSeries(averageSeries)

    val chart = ChartFactory.createScatterPlot(
        "Sold product age (with Monthly Averages)",
        "Order Month",
        "Product age (Months listed on site)",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    val plot = chart.xyPlot as XYPlot

    // Renderer for the scatter plot (individual product ages)
    val scatterRenderer = XYLineAndShapeRenderer(false, true)  // Only show shapes (dots) for the scatter plot
    plot.setRenderer(0, scatterRenderer)

    // Set the shape to a circle and adjust its size for the scatter plot
    val circle = Ellipse2D.Double(-2.0, -2.0, 4.0, 4.0)  // Circle of radius 2 (change 4.0 to control size)
    scatterRenderer.setSeriesShape(0, circle)  // Set the shape for the first (scatter) series
    scatterRenderer.setSeriesShapesVisible(0, true)  // Make sure shapes are visible for the scatter series
    scatterRenderer.defaultStroke = BasicStroke(1.0f)

    // Renderer for the average points (monthly averages)
    val averageRenderer = XYLineAndShapeRenderer(false, true)  // No lines, just points
    val square = Ellipse2D.Double(-3.0, -3.0, 6.0, 6.0)  // Slightly larger circle for the average points
    averageRenderer.setSeriesShape(1, square)
    averageRenderer.setSeriesShapesVisible(1, true)
    averageRenderer.defaultStroke = BasicStroke(2.0f)  // Thicker stroke for average points
    plot.setRenderer(1, averageRenderer)  // Set the renderer for the average series

    // X-axis: use the months as labels
    val monthAxis = SymbolAxis("Order Month", months.toTypedArray())
    plot.domainAxis = monthAxis

    // Y-axis: label in months
    val yAxis = NumberAxis("Product age (Months listed on site)")
    plot.rangeAxis = yAxis

    val fileName = "report_sold-products-age.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("Scatter plot with order product age saved as $fileName")
}

private fun thickenLines(
    categoryPlot: CategoryPlot, dataset: DefaultCategoryDataset
) {
    val renderer = categoryPlot.renderer as LineAndShapeRenderer
    val seriesCount = dataset.rowCount
    for (i in 0..<seriesCount) {
        renderer.setSeriesStroke(i, BasicStroke(3.0f))
    }
}

fun generateMonetarySalesData(
    orders: List<Order>,
    tagToFilterBy: String,
    credentials: String
): Map<String, Map<String, Double>> {
    val modelMonetarySalesMap = mutableMapOf<String, MutableMap<String, Double>>() // Sales in monetary terms
    val modelProductCountMap = mutableMapOf<String, Int>() // Count of products per model
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    // Fetch all products to calculate the total number of products associated with each model
    val allProducts = fetchAllProducts(credentials)

    // Count the number of products for each model
    allProducts.forEach { product ->
        product.tags.forEach { tag ->
            if (tag.name.startsWith("$tagToFilterBy ")) {
                val modelName = tag.name.removePrefix("$tagToFilterBy ").trim()
                modelProductCountMap.merge(modelName, 1) { old, new -> old + new }
            }
        }
    }

    orders.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
        val orderMonth = monthFormatter.format(orderDate)

        order.line_items.forEach { item ->
            if (item.product_id==0) {
                println("WARNING: Skipping line item with invalid product_id 0 for order ${order.id}")
                return@forEach
            }

            val product = allProducts.find { it.id==item.product_id }
            // There are some products (e.g. deleted ones), that are not part of all the products when fetched in bulk.
            val productTags = product?.tags?.map { it.name } ?: getProductTags(item.product_id, credentials)
            val productPrice = getProductPrice(item.product_id, credentials)  // Get the product price

            if (productPrice!=null) {
                productTags.forEach { tag ->
                    if (tag.startsWith("$tagToFilterBy ")) {
                        val modelName = tag.removePrefix("$tagToFilterBy ").trim()

                        // Calculate monetary sales for each model
                        val sales = item.quantity * productPrice  // Total monetary sales for this product and quantity
                        modelMonetarySalesMap.getOrPut(orderMonth) { mutableMapOf() }
                            .merge(modelName, sales) { old, new -> old + new }
                    }
                }
            } else {
                println("Skipping null product price")
            }
        }
    }
    return modelMonetarySalesMap.toSortedMap()
}

fun getProductPrice(productId: Int, credentials: String): Double? {
    println("DEBUG: Fetching product price for product $productId")
    val client = OkHttpClient()
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val body = response.body?.string()!!
        val mapper = jacksonObjectMapper()
        val product: Product = mapper.readValue(body)
        val price = product.price
        return if (product.price.isNotBlank()) price.toDouble() else null
    }
}

fun getProductTags(productId: Int, credentials: String): List<String> {
    println("DEBUG: Fetching product tag for product $productId")
    val client = OkHttpClient()
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val body = response.body?.string()!!
        val mapper = jacksonObjectMapper()
        val product: Product = mapper.readValue(body)
        return product.tags.map { it.name }
    }
}

fun plotMonetarySalesData(modelMonetarySalesMap: Map<String, Map<String, Double>>, tagToFilterBy: String) {
    val dataset = DefaultCategoryDataset()

    modelMonetarySalesMap.forEach { (month, models) ->
        models.forEach { (model, monetarySales) ->
            dataset.addValue(monetarySales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Monetary Sales Over Time-$tagToFilterBy",
        "Month",
        "Monetary Sales (€)",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    // Customize chart appearance
    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45
    thickenLines(categoryPlot, dataset)

    // Save the chart
    val fileName = "report_${tagToFilterBy}_monetary_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Monetary sales per model plot saved as $fileName")
}

fun generateProductSalesData(
    orders: List<Order>,
    tagToFilterBy: String,
    credentials: String,
): Map<String, Map<String, Int>> {
    val modelProductCountPerMonthMap = mutableMapOf<String, MutableMap<String, Int>>()
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    val allProducts = fetchAllProducts(credentials)

    orders.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
        val orderMonth = monthFormatter.format(orderDate)

        order.line_items.forEach { item ->
            if (item.product_id==0) {
                println("WARNING: Skipping line item with invalid product_id 0 for order ${order.id}")
                return@forEach
            }

            val product = allProducts.find { it.id==item.product_id }
            // There are some products (e.g. deleted ones), that are not part of all the products when fetched in bulk.
            val productTags = product?.tags?.map { it.name } ?: getProductTags(item.product_id, credentials)
            productTags.forEach { tag ->
                if (tag.startsWith("$tagToFilterBy ")) {
                    val modelName = tag.removePrefix("$tagToFilterBy ").trim()

                    // Update the product count per model in the given month
                    modelProductCountPerMonthMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, item.quantity) { old, new -> old + new }
                }
            }
        }
    }

    return modelProductCountPerMonthMap.toSortedMap()
}

fun plotProductSalesData(modelProductCountPerMonthMap: Map<String, Map<String, Int>>, tagToFilterBy: String) {
    val dataset = DefaultCategoryDataset()

    modelProductCountPerMonthMap.forEach { (month, models) ->
        models.forEach { (model, productCount) ->
            dataset.addValue(productCount.toDouble(), model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Number of Products Over Time - $tagToFilterBy",
        "Month",
        "Number of Products",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    // Customize chart appearance
    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45
    thickenLines(categoryPlot, dataset)

    // Save the chart
    val fileName = "report_${tagToFilterBy}_product_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Number of products per model plot saved as $fileName")
}

fun generateAdjustedMonetarySalesDataByMonthlyTag(
    orders: List<Order>,
    tagToFilterBy: String,
    credentials: String
): Map<String, Map<String, Double>> {
    val monthlyProductCounts = generateMonthlyProductCountsByTag(credentials) // Get monthly product counts by tag
    println("Monthly product tag counts: $monthlyProductCounts")
    val modelMonetarySalesMap = mutableMapOf<String, MutableMap<String, Double>>() // Adjusted sales data
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    orders.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
        val orderMonth = orderDate.format(monthFormatter)
        order.line_items.forEach { item ->
            if (item.product_id==0) {
                println("WARNING: Skipping line item with invalid product_id 0 for order ${order.id}")
                return@forEach
            }
            val productTags = getProductTags(item.product_id, credentials)
            val productPrice = getProductPrice(item.product_id, credentials)
            if (productPrice!=null) {
                productTags.forEach { tag ->
                    if (tag.startsWith("$tagToFilterBy ")) {
                        val modelName = tag.removePrefix("$tagToFilterBy ").trim()

                        // Get the number of products associated with the tag (model) for the month
                        val productCountForTagInMonth =
                            monthlyProductCounts[orderMonth]?.get("$tagToFilterBy $modelName") ?: 1

                        // Calculate adjusted monetary sales
                        val adjustedMonetarySales =
                            (item.quantity.toDouble() * productPrice) / productCountForTagInMonth
                        modelMonetarySalesMap.getOrPut(orderMonth) { mutableMapOf() }
                            .merge(modelName, adjustedMonetarySales) { old, new -> old + new }
                    }
                }
            } else {
                println("Skipping null product price")
            }
        }
    }
    return modelMonetarySalesMap.toSortedMap()
}

fun plotAdjustedMonetarySalesData(modelMonetarySalesMap: Map<String, Map<String, Double>>, tagToFilterBy: String) {
    val dataset = DefaultCategoryDataset()

    modelMonetarySalesMap.forEach { (month, models) ->
        models.forEach { (model, adjustedSales) ->
            dataset.addValue(adjustedSales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Adjusted Monetary Sales Over Time-$tagToFilterBy",
        "Month",
        "Adjusted Monetary Sales (€ per Product)",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    // Customize chart appearance
    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45
    thickenLines(categoryPlot, dataset)

    // Save the chart
    val fileName = "report_${tagToFilterBy}_adjusted_monetary_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Adjusted monetary sales per model plot saved as $fileName")
}


fun generateMonthlyProductCountsByTag(credentials: String): Map<String, Map<String, Int>> {
    val client = OkHttpClient()
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss") // ISO8601 format
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM") // Year-Month format
    val historicalProductCounts = mutableMapOf<String, MutableMap<String, Int>>()

    val startDate = LocalDate.of(2024, 1, 1).atStartOfDay() // Start of your analysis
    val endDate = LocalDate.now().atStartOfDay()
    var currentDate = startDate

    while (currentDate.isBefore(endDate)) {
        val nextMonth = currentDate.plusMonths(1)
        val url =
            "https://foryoufashion.gr/wp-json/wc/v3/products?before=${nextMonth.format(dateFormatter)}&per_page=100"
        println(
            "DEBUG: Fetching products between ${currentDate.format(dateFormatter)} and ${
                nextMonth.format(
                    dateFormatter
                )
            }"
        )

        var page = 1
        var hasMoreProducts: Boolean
        // println("DEBUG: Start product fetch")
        do {
            val pagedUrl = "$url&page=$page"
            val request = Request.Builder().url(pagedUrl).header("Authorization", credentials).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                val body = response.body?.string()!!
                val mapper = jacksonObjectMapper()
                val fetchedProducts: List<Product> = mapper.readValue(body)

                // Process fetched products
                // println("DEBUG: Number of fetched products: ${fetchedProducts.size}")
                fetchedProducts.forEach { product ->
                    product.tags.forEach { tag ->
                        val tagName = tag.name.trim()
                        val monthKey = currentDate.format(monthFormatter)

                        // Count the number of products associated with each tag (model) for the month
                        historicalProductCounts.getOrPut(monthKey) { mutableMapOf() }
                            .merge(tagName, 1) { old, new -> old + new }
                    }
                }

                hasMoreProducts = fetchedProducts.size==100 // More products to fetch if page is full
            }
            page++
        } while (hasMoreProducts)

        currentDate = nextMonth // Move to the next month
    }

    println("Monthly product tag counts: $historicalProductCounts")
    return historicalProductCounts
}

fun generateAdjustedProductSalesDataByMonthlyTag(
    orders: List<Order>,
    tagToFilterBy: String,
    credentials: String
): Map<String, Map<String, Double>> {
    val monthlyProductCounts = generateMonthlyProductCountsByTag(credentials) // Get monthly snapshots
    val modelSalesMap = mutableMapOf<String, MutableMap<String, Double>>() // Adjusted sales data
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM") // Year-Month format

    orders.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
        val orderMonth = orderDate.format(monthFormatter)

        order.line_items.forEach { item ->
            if (item.product_id==0) return@forEach

            val productTags = getProductTags(item.product_id, credentials)
            productTags.forEach { tag ->
                if (tag.startsWith("$tagToFilterBy ")) {
                    val modelName = tag.removePrefix("$tagToFilterBy ").trim()

                    // Get the number of products associated with the tag (model) for the month
                    val productCountForTagInMonth =
                        monthlyProductCounts[orderMonth]?.get("$tagToFilterBy $modelName") ?: 1
                    // println("DEBUG: division by $productCountForTagInMonth for month $orderMonth for model $modelName")
                    // println("DEBUG before division ${item.quantity.toDouble()}")
                    val adjustedSales = item.quantity.toDouble() / productCountForTagInMonth
                    // println("AFTER division $adjustedSales")
                    modelSalesMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, adjustedSales) { old, new ->
                            old + new
                        }
                }
            }
        }
    }
    return modelSalesMap.toSortedMap()
}

fun plotAdjustedProductSalesDataMonthly(modelSalesMap: Map<String, Map<String, Double>>, tagToFilterBy: String) {
    val dataset = DefaultCategoryDataset()

    modelSalesMap.forEach { (month, models) ->
        models.forEach { (model, adjustedSales) ->
            dataset.addValue(adjustedSales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Adjusted Sales (Monthly) - $tagToFilterBy",
        "Month",
        "Adjusted Sales",
        dataset,
        PlotOrientation.VERTICAL,
        true,
        true,
        false
    )

    val categoryPlot = chart.categoryPlot
    val domainAxis = categoryPlot.domainAxis as CategoryAxis
    domainAxis.categoryLabelPositions = CategoryLabelPositions.UP_45
    thickenLines(categoryPlot, dataset)

    val fileName = "report_${tagToFilterBy}_adjusted_product_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Adjusted monthly product sales plot saved as $fileName")
}

data class CustomerRefunds(val name: String, val email: String, val count: Int)

private fun isRefunded(order: Order): Boolean {
    // Count fully refunded OR partially refunded orders
    return order.status=="refunded" || (order.refunds?.isNotEmpty()==true)
}

fun generateTopRefundingCustomers(orders: List<Order>): List<CustomerRefunds> {
    data class Acc(val name: String, var count: Int)

    val byEmail = mutableMapOf<String, Acc>()

    orders.asSequence()
        .filter(::isRefunded)
        .forEach { order ->
            val email = order.billing?.email?.trim()?.lowercase().orEmpty().ifBlank { "(missing)" }
            val first = order.billing?.first_name?.trim().orEmpty()
            val last = order.billing?.last_name?.trim().orEmpty()
            val name = (listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")).ifBlank { "(no name)" }

            val acc = byEmail.getOrPut(email) { Acc(name = name, count = 0) }
            // Prefer the longer/more informative name if we see a different one later
            if (name.length > acc.name.length && name!="(no name)") {
                byEmail[email] = acc.copy(name = name)
            }
            acc.count += 1
        }

    return byEmail.entries
        .sortedByDescending { it.value.count }
        .map { CustomerRefunds(name = it.value.name, email = it.key, count = it.value.count) }
}

fun saveTopRefundersCsv(rows: List<CustomerRefunds>) {
    val fileName = "report_top_refunders.csv"
    val file = File(fileName)
    file.printWriter().use { out ->
        out.println("Customer Name,Email,Refunded Order Count")
        rows.forEach { r ->
            val safeName = "\"${r.name.replace("\"", "\"\"")}\""
            val safeEmail = "\"${r.email.replace("\"", "\"\"")}\""
            out.println("$safeName,$safeEmail,${r.count}")
        }
    }
    println("ACTION: Top refunding customers CSV saved as $fileName")
}
