package swapdescriptions

import Product
import Variation
import com.fasterxml.jackson.module.kotlin.readValue
import com.opencsv.CSVWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import okhttp3.Credentials
import okhttp3.Request
import skuchecks.client
import skuchecks.mapper

fun main() {
    val readOnlyConsumerKey = ""
    val readOnlyConsumerSecret = ""

    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)

    val csvFile = File("times.csv")
    val csvWriter = CSVWriter(FileWriter(csvFile))

    // Write CSV header
    csvWriter.writeNext(
        arrayOf(
            "Κωδικος προιοντος (<κωδικος><κωδικος χρωματος>-<κωδικος μεγεθους>)",
            "Regular price",
            "Sale price"
        )
    )

    var page = 1
    var products: List<Product>
    outerLoop@ do {
        products = getProducts(page, credentials)
        println("Products size: ${products.size}")
        for (product in products) {
            println("product SKU: ${product.sku}")
            val productVariations = getVariations(productId = product.id, credentials)
            for (variation in productVariations) {
                println("variation SKU: ${variation.sku}")
                println("variation regular price: ${variation.regular_price}")
                println("variation sale price: ${variation.sale_price}")
                if (variation.regular_price.isEmpty()) {
                    println("WARNING regular price empty")
                }
                val record = mutableListOf(variation.sku, variation.regular_price)
                if (variation.sale_price.isNotEmpty()) {
                    record.add(variation.sale_price)
                }
                csvWriter.writeNext(record.toTypedArray())
            }
        }
        page++
    } while (products.isNotEmpty())

    csvWriter.close()
}

private fun getProducts(page: Int, credentials: String): List<Product> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products?page=$page&per_page=100"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        return mapper.readValue(response.body?.string() ?: "")
    }
}

private fun getVariations(productId: Int, credentials: String): List<Variation> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId/variations"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        val responseBody = response.body?.string() ?: ""
        return mapper.readValue(responseBody)
    }
}
