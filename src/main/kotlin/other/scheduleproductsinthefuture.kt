package other

import Product
import archive.client
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.Credentials
import okhttp3.Request
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import writeConsumerKey
import writeConsumerSecret

fun main() {
    val credentials = Credentials.basic(writeConsumerKey, writeConsumerSecret)
    val tagEtoimoGiaAnevasmaId = 1554

    var page = 1
    val draftProducts: MutableList<Product> = mutableListOf()

    do {
        val products = getDraftProductsThatAreReadyToSchedule(page, credentials, tagEtoimoGiaAnevasmaId)
        draftProducts.addAll(products)
        page++
    } while (products.isNotEmpty())

    println("Total draft products: ${draftProducts.size}")

    if (draftProducts.isNotEmpty()) {
        // Get the last publish date (if any) or start from today
        var lastPublishDate = getLastPublishDate(credentials) ?: LocalDateTime.now()

        // If the last publish date is in the past, reset it to today
        if (lastPublishDate==null || lastPublishDate.isBefore(LocalDateTime.now())) {
            lastPublishDate = LocalDateTime.now()
        }

        var scheduleDate = lastPublishDate.plusDays(1)

        // Schedule each product
        draftProducts.forEach { product ->
            val updatedTags = product.tags.filter { it.id!=tagEtoimoGiaAnevasmaId }

            val updateData = mapOf(
                "date_created" to scheduleDate.format(DateTimeFormatter.ISO_DATE_TIME),
                "status" to "publish",
                "tags" to updatedTags.map { mapOf("id" to it.id) }
            )

            val response = updateProduct(product.id, updateData, credentials)
            if (response) {
                println("Scheduled product '${product.name}' to go live on $scheduleDate")
                scheduleDate = scheduleDate.plusDays(1)
            } else {
                println("Failed to schedule product '${product.name}'")
            }
        }
    }
}

private fun getDraftProductsThatAreReadyToSchedule(
    page: Int,
    credentials: String,
    tagEtoimoGiaAnevasmaId: Int
): List<Product> {
    val url =
        "https://foryoufashion.gr/wp-json/wc/v3/products?page=$page&per_page=100&status=draft&tag=$tagEtoimoGiaAnevasmaId"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        return jacksonObjectMapper().readValue(response.body?.string() ?: "")
    }
}

private fun updateProduct(productId: Int, updateData: Map<String, Any>, credentials: String): Boolean {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val requestBody = okhttp3.RequestBody.create(
        "application/json; charset=utf-8".toMediaTypeOrNull(),
        jacksonObjectMapper().writeValueAsString(updateData)
    )

    val request = Request.Builder().url(url)
        .header("Authorization", credentials)
        .put(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            println("Error updating product: ${response.body?.string()}")
            return false
        }
        return true
    }
}

private fun getLastPublishDate(credentials: String): LocalDateTime? {
    // Fetch the most recently published or scheduled product
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products?status=publish&orderby=date&order=desc&per_page=1"
    val request = Request.Builder().url(url).header("Authorization", credentials).build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val products: List<Product> = jacksonObjectMapper().readValue(response.body?.string() ?: "")

        // Check if there are any published or scheduled products
        val lastPublishDate = products.firstOrNull()?.date_created?.let {
            LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME)
        }

        println("Last publish date was $lastPublishDate")
        // If no publish date is found, return null (indicating no products have been published/scheduled)
        return lastPublishDate
    }
}
