package reports


import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.chart.ChartUtils
import org.jfree.data.xy.XYSeries
import org.jfree.data.xy.XYSeriesCollection
import java.io.File

import Order
import Product
import allAutomatedChecks.fetchAllProducts
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.awt.BasicStroke
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.axis.CategoryAxis
import org.jfree.chart.axis.CategoryLabelPositions
import org.jfree.data.category.DefaultCategoryDataset
import java.io.IOException
import org.apache.commons.text.StringEscapeUtils
import org.jfree.chart.plot.CategoryPlot
import org.jfree.chart.renderer.category.LineAndShapeRenderer
import readOnlyConsumerKey

import java.time.YearMonth
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.axis.SymbolAxis
import java.awt.geom.Ellipse2D
import readOnlyConsumerSecret
import java.time.temporal.ChronoUnit
import java.util.TreeSet

fun main() {
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val orders = fetchAllOrdersSince2024(credentials)
    println("DEBUG: Total orders fetched: ${orders.size}")
    val orderStatusMap = generateOrderStatusData(orders)
    plotOrdersStatusesByMonth(orderStatusMap)

    val paymentMethodDistributionMap = generatePaymentMethodsData(orders)
    plotPaymentMethodsByMonth(paymentMethodDistributionMap)

    val productAges = generateProductAgesAtSaleData(orders, credentials)
    plotProductAgesWithAverageByMonth(productAges)

    val productSalesMap = generateProductSalesData(orders, credentials)
    plotProductSalesData(productSalesMap)

    val adjustedProductSalesMap = generateAdjustedProductSalesData(orders, credentials)
    plotAdjustedProductSalesData(adjustedProductSalesMap)

    val monetarySalesMap = generateMonetarySalesData(orders, credentials)
    plotMonetarySalesData(monetarySalesMap)

    val adjustedMonetarySalesMap = generateAdjustedMonetarySalesData(orders, credentials)
    plotAdjustedMonetarySalesData(adjustedMonetarySalesMap)
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

fun generateAdjustedProductSalesData(orders: List<Order>, credentials: String): Map<String, Map<String, Double>> {
    val modelSalesMap = mutableMapOf<String, MutableMap<String, Int>>()
    val modelProductCountMap = mutableMapOf<String, Int>()  // Track total number of products for each model
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    val allProducts = fetchAllProducts(credentials)

    // Count the number of products for each model
    allProducts.forEach { product ->
        product.tags.forEach { tag ->
            if (tag.name.startsWith("ΜΟΝΤΕΛΟ ")) {
                val modelName = tag.name.removePrefix("ΜΟΝΤΕΛΟ ").trim()
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
            productTags.forEach { tag ->
                if (tag.startsWith("ΜΟΝΤΕΛΟ ")) {
                    val modelName = tag.removePrefix("ΜΟΝΤΕΛΟ ").trim()
                    modelSalesMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, 1) { old, new -> old + new }
                }
            }
        }
    }

    // Now, calculate the adjusted sales (Sales per Product Photographed)
    val adjustedSalesMap = mutableMapOf<String, MutableMap<String, Double>>()

    modelSalesMap.forEach { (month, models) ->
        models.forEach { (model, sales) ->
            // Get the total number of products associated with the model
            val productCount = modelProductCountMap[model]!!

            // Calculate the sales per product photographed for the model
            val adjustedSales = sales.toDouble() / productCount

            // Store the adjusted sales in the map
            adjustedSalesMap.getOrPut(month) { mutableMapOf() }[model] = adjustedSales
        }
    }
    return adjustedSalesMap.toSortedMap()
}

fun generateMonetarySalesData(orders: List<Order>, credentials: String): Map<String, Map<String, Double>> {
    val modelMonetarySalesMap = mutableMapOf<String, MutableMap<String, Double>>() // Sales in monetary terms
    val modelProductCountMap = mutableMapOf<String, Int>() // Count of products per model
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    // Fetch all products to calculate the total number of products associated with each model
    val allProducts = fetchAllProducts(credentials)

    // Count the number of products for each model
    allProducts.forEach { product ->
        product.tags.forEach { tag ->
            if (tag.name.startsWith("ΜΟΝΤΕΛΟ ")) {
                val modelName = tag.name.removePrefix("ΜΟΝΤΕΛΟ ").trim()
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

            productTags.forEach { tag ->
                if (tag.startsWith("ΜΟΝΤΕΛΟ ")) {
                    val modelName = tag.removePrefix("ΜΟΝΤΕΛΟ ").trim()

                    // Calculate monetary sales for each model
                    val sales = item.quantity * productPrice  // Total monetary sales for this product and quantity
                    modelMonetarySalesMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, sales) { old, new -> old + new }
                }
            }
        }
    }
    return modelMonetarySalesMap.toSortedMap()
}

fun getProductPrice(productId: Int, credentials: String): Double {
    println("DEBUG: Fetching product price for product $productId")
    val client = OkHttpClient()
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val body = response.body?.string()!!
        val mapper = jacksonObjectMapper()
        val product: Product = mapper.readValue(body)
        return product.price.toDouble()
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

fun plotMonetarySalesData(modelMonetarySalesMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()

    modelMonetarySalesMap.forEach { (month, models) ->
        models.forEach { (model, monetarySales) ->
            dataset.addValue(monetarySales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Monetary Sales per Model Over Time",
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
    val fileName = "report_models_monetary_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Monetary sales per model plot saved as $fileName")
}

fun generateProductSalesData(orders: List<Order>, credentials: String): Map<String, Map<String, Int>> {
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
                if (tag.startsWith("ΜΟΝΤΕΛΟ ")) {
                    val modelName = tag.removePrefix("ΜΟΝΤΕΛΟ ").trim()

                    // Update the product count per model in the given month
                    modelProductCountPerMonthMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, item.quantity) { old, new -> old + new }
                }
            }
        }
    }

    return modelProductCountPerMonthMap.toSortedMap()
}

fun plotProductSalesData(modelProductCountPerMonthMap: Map<String, Map<String, Int>>) {
    val dataset = DefaultCategoryDataset()

    modelProductCountPerMonthMap.forEach { (month, models) ->
        models.forEach { (model, productCount) ->
            dataset.addValue(productCount.toDouble(), model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Number of Products per Model Over Time",
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
    val fileName = "report_models_product_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Number of products per model plot saved as $fileName")
}

fun generateAdjustedMonetarySalesData(orders: List<Order>, credentials: String): Map<String, Map<String, Double>> {
    val modelMonetarySalesMap = mutableMapOf<String, MutableMap<String, Double>>() // Sales in monetary terms
    val modelProductCountMap = mutableMapOf<String, Int>() // Count of products per model
    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val monthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    val allProducts = fetchAllProducts(credentials)

    // Count the number of products for each model
    allProducts.forEach { product ->
        product.tags.forEach { tag ->
            if (tag.name.startsWith("ΜΟΝΤΕΛΟ ")) {
                val modelName = tag.name.removePrefix("ΜΟΝΤΕΛΟ ").trim()
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
            val productPrice = getProductPrice(item.product_id, credentials)

            productTags.forEach { tag ->
                if (tag.startsWith("ΜΟΝΤΕΛΟ ")) {
                    val modelName = tag.removePrefix("ΜΟΝΤΕΛΟ ").trim()

                    // Calculate monetary sales for each model
                    val sales = item.quantity * productPrice  // Total monetary sales for this product and quantity
                    modelMonetarySalesMap.getOrPut(orderMonth) { mutableMapOf() }
                        .merge(modelName, sales) { old, new -> old + new }
                }
            }
        }
    }

    val adjustedMonetarySalesMap = mutableMapOf<String, MutableMap<String, Double>>()

    modelMonetarySalesMap.forEach { (month, models) ->
        models.forEach { (model, sales) ->
            // Get the total number of products associated with the model
            val productCount = modelProductCountMap[model]!!

            // Calculate the adjusted monetary sales per product
            val adjustedSales = sales / productCount

            // Store the adjusted sales in the map
            adjustedMonetarySalesMap.getOrPut(month) { mutableMapOf() }[model] = adjustedSales
        }
    }

    return adjustedMonetarySalesMap.toSortedMap()
}

fun plotAdjustedMonetarySalesData(modelMonetarySalesMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()

    modelMonetarySalesMap.forEach { (month, models) ->
        models.forEach { (model, adjustedSales) ->
            dataset.addValue(adjustedSales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Adjusted Monetary Sales per Model Over Time",
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
    val fileName = "report_models_adjusted_monetary_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Adjusted monetary sales per model plot saved as $fileName")
}


fun plotAdjustedProductSalesData(modelSalesMap: Map<String, Map<String, Double>>) {
    val dataset = DefaultCategoryDataset()
    modelSalesMap.forEach { (month, models) ->
        models.forEach { (model, adjustedSales) ->
            dataset.addValue(adjustedSales, model, month)
        }
    }

    val chart = ChartFactory.createLineChart(
        "Adjusted Best-Selling Models Over Time",
        "Month",
        "Adjusted Sales (Sales per Product)",
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
    val fileName = "report_models_adjusted_product_sales.png"
    val chartFile = File(fileName)
    ChartUtils.saveChartAsPNG(chartFile, chart, 800, 600)
    println("ACTION: Adjusted best-selling models plot saved as $fileName")
}
