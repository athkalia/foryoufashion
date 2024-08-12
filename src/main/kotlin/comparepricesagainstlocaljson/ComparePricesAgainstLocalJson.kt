package comparepricesagainstlocaljson

import Product
import Variation
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import okhttp3.Credentials
import okhttp3.Request
import skuchecks.client
import skuchecks.mapper

@JsonIgnoreProperties(ignoreUnknown = true)
data class LocalProduct(
    val mtrl: String,
    val code: String,
    val whouse: String,
    val colorname: String,
    val colorcode: String,
    val sizename: String? = null,
    val sizecode: String,
    val qty: String,
    val name: String,
    val pricer: String
)

fun main() {
    val readOnlyConsumerKey = ""
    val readOnlyConsumerSecret = ""

    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)

    // Load local JSON data
    val localJsonFile = File("old_prices.json")
    val mapper = jacksonObjectMapper()
    val localProducts: List<LocalProduct> = mapper.readValue(localJsonFile)

    var page = 1
    var products: List<Product>
    outerLoop@ do {
        products = getProducts(page, credentials)
        println("Products size: ${products.size}")
        for (product in products) {
//            println("product SKU: ${product.sku}")
            val productVariations = getVariations(productId = product.id, credentials)
            for (variation in productVariations) {
//                println("variation SKU: ${variation.sku}")
//                println("variation regular price: ${variation.regular_price}")

                // Construct the SKU key for comparison
                val skuParts = variation.sku.split("-")
                if (skuParts.size < 3) {
                    println("Skipping variation with invalid SKU format: ${variation.sku}")
                    continue
                }
                val code = skuParts[0]
                val colorcode = skuParts[1]
                val sizecode = skuParts[2]

                // Find the matching local product
                val matchingLocalProduct = localProducts.find {
                    it.code==code && it.colorcode==colorcode && it.sizecode==sizecode
                }

                if (matchingLocalProduct==null) {
//                    println("WARNING1: No matching local product found for SKU: ${variation.sku}")
                    continue
                }

                // Compare prices using BigDecimal for accurate comparison
                val apiPrice = BigDecimal(variation.regular_price)
                val localPrice = BigDecimal(matchingLocalProduct.pricer)
                if (apiPrice.compareTo(localPrice)!=0) {
                    println("WARNING2: Price mismatch for SKU ${variation.sku}. API price: ${variation.regular_price}, Local price: ${matchingLocalProduct.pricer}")
                } else {
//                    println("price match!")
                }
            }
        }
        page++
    } while (products.isNotEmpty())
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
