package allAutomatedChecks

import Attribute
import AttributeTerm
import Category
import Product
import Tag
import Variation
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import skuchecks.client
import skuchecks.mapper

const val skipVariationChecks = false

fun main() {
    val readOnly = true
    val shouldSwapDescriptions = false
    val shouldUpdateProsforesProductTag = false

    val readOnlyConsumerKey = ""
    val readOnlyConsumerSecret = ""
    val writeConsumerKey = ""
    val writeConsumerSecret = ""

    val credentials =
        if (readOnly && !shouldSwapDescriptions && !shouldUpdateProsforesProductTag) {
            Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
        } else {
            Credentials.basic(
                writeConsumerKey,
                writeConsumerSecret
            )
        }
    var page = 1
    var products: List<Product>
    do {
        products = getProducts(page, credentials)
        println("Products size: ${products.size}")
        for (product in products) {
            // offer and discount empty products, not sure what these are.
            if (product.id==27948 || product.id==27947) {
                continue
            }
//            println("product SKU: ${product.sku}")
            checkForInvalidDescriptions(product, credentials, shouldSwapDescriptions)
            checkForMissingImages(product)
            checkForNonSizeAttributesUsedForVariations(product)
            checkForStockManagementAtProductLevel(product)
            checkForEmptyOrShortTitles(product)
            checkForOldProductsThatAreOutOfStock(product, credentials)
            if (!skipVariationChecks) {
                val productVariations = getVariations(productId = product.id, credentials)
                if (shouldUpdateProsforesProductTag) {
                    val firstVariation = productVariations.firstOrNull()
                    firstVariation?.let {
                        println("variation SKU: ${it.sku}")
                        addTagForDiscountedProductsAndRemoveTagForRest(product, it, 30, credentials)
                    }
                }
                for (variation in productVariations) {
                    println("variation SKU: ${variation.sku}")
                    checkForMissingPrices(variation)
                }
            }
        }
        page++
    } while (products.isNotEmpty())
    checkProductCategories(credentials)
    checkProductAttributes(credentials)
    checkProductTags(credentials)
}

private fun checkForEmptyOrShortTitles(product: Product) {
    val title = product.name
    if (title.isEmpty() || title.length < 20) {
        println("WARNING Product ${product.sku} has an empty or too short title: '$title'.")
        println(product.permalink)
    }
}

private fun checkForInvalidDescriptions(product: Product, credentials: String, shouldSwapDescriptions: Boolean) {
//    println("long description ${product.description}")
//    println("short description ${product.short_description}")
    isValidHtml(product.short_description)
    isValidHtml(product.description)
    if (product.short_description.equals(product.description, ignoreCase = true)) {
        println("WARNING same short and long descriptions found for product: ${product.sku}")
        println(product.permalink)
    }
    if (product.short_description.length > product.description.length) {
        println("WARNING short description logner than long description for product: ${product.sku}")
        println(product.permalink)
//        println("long description ${product.description}")
//        println("short description ${product.short_description}")
        if (shouldSwapDescriptions) {
            swapProductDescriptions(product, credentials)
        }
    }
    if (product.short_description.length < 120 || product.short_description.length > 250) {
        println("WARNING Product ${product.sku} has a short description of invalid length, with length ${product.short_description.length}")
        println(product.permalink + " μηκος:" + product.short_description.length)
    }
    if (product.description.length < 150) {
        println("WARNING Product ${product.sku} has a short long description, with length ${product.description.length}")
        println(product.permalink)
    }
}

private fun checkForStockManagementAtProductLevel(product: Product) {
    if (
        product.variations.isNotEmpty() && product.manage_stock
    ) {
        println("WARNING Product ${product.sku} is a variable product with stock management at the product level.")
    }
}

private fun checkForMissingPrices(variation: Variation) {
    if (variation.regular_price.isEmpty()) {
        println("WARNING regular price empty")
    }
}

private fun checkForMissingImages(product: Product) {
    val images = product.images
    val mainImageMissing = images.isEmpty() || images[0].src.isEmpty()
    val galleryImagesMissing = images.size < 3

    if (mainImageMissing) {
        println("WARNING Product ${product.sku} is missing a main image.")
        println(product.permalink)
    }

    if (galleryImagesMissing) {
        println("WARNING Product ${product.sku} only has ${images.size} images.")
        println(product.permalink)
    }
}

private fun getProducts(page: Int, credentials: String): List<Product> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products?page=$page&per_page=100"
    return executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue(response.body?.string() ?: "")
        }
    }
}

private fun getVariations(productId: Int, credentials: String): List<Variation> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId/variations"
    return executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val responseBody = response.body?.string() ?: ""
            mapper.readValue(responseBody)
        }
    }
}

private fun isValidHtml(html: String): Boolean {
    return try {
        Jsoup.parse(html, "", Parser.xmlParser())
        true
    } catch (e: Exception) {
        println("Invalid HTML: $html, Error: ${e.message}")
        false
    }
}

private fun swapProductDescriptions(
    product: Product,
    credentials: String
) {
    println("reversing descriptions for product ${product.sku}")

    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val json = mapper.writeValueAsString(
        mapOf(
            "short_description" to product.description,
            "description" to product.short_description
        )
    )
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    val request = Request.Builder()
        .url(url)
        .put(body)
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response, body: $responseBody")
        }
    }
}

private fun <T> executeWithRetry(action: () -> T): T {
    val maxRetries = 5
    val initialDelay = 1000L
    val maxDelay = 16000L

    repeat(maxRetries) { attempt ->
        try {
            return action()
        } catch (e: IOException) {
            val delay = (initialDelay * 2.0.pow(attempt.toDouble())).toLong() + Random.nextLong(0, 1000)
            if (attempt >= maxRetries - 1) {
                throw e
            } else {
                println("Retry attempt ${attempt + 1} failed: ${e.message}. Retrying in ${delay}ms...")
                Thread.sleep(minOf(delay, maxDelay))
            }
        }
    }
    throw IOException("Failed after $maxRetries retries")
}

private fun addTagForDiscountedProductsAndRemoveTagForRest(
    product: Product, variation: Variation, addTagAboveThisDiscount: Int, credentials: String
) {
    val regularPrice = variation.regular_price.toDoubleOrNull()
    val salePrice = variation.sale_price.toDoubleOrNull()
    println("variation regular price $regularPrice")
    println("variation sale price $salePrice")

    if (regularPrice!=null && salePrice!=null && regularPrice > 0) {
        val discountPercentage = ((regularPrice - salePrice) / regularPrice * 100).roundToInt()
        println("discount found $discountPercentage")
        val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
        if (discountPercentage >= addTagAboveThisDiscount) {
            println("Adding tag 'prosfores'")
            if (!product.tags.any { it.name=="prosfores" }) {
                val updatedTags = product.tags.toMutableList()
                updatedTags.add(Tag(id = 1552, name = "prosfores"))
                val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
                println("Updating product ${product.id} with tags: $data")
                val body = data.toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()
                executeWithRetry {
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            println("Error updating product ${product.id}: $responseBody")
                            throw IOException("Unexpected code $response")
                        }
                    }
                }
            } else {
                println("Tag already exists")
            }
        } else {
            println("Removing tag 'prosfores'")
            if (product.tags.any { it.name=="prosfores" }) {
                val updatedTags = product.tags.filter { it.name!="prosfores" }
                val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
                println("Updating product ${product.id} with tags: $data")
                val body = data.toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()
                executeWithRetry {
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string() ?: ""
                        if (!response.isSuccessful) {
                            println("Error updating product ${product.id}: $responseBody")
                            throw IOException("Unexpected code $response")
                        }
                    }
                }
            } else {
                println("Tag doesn't exist anyway")
            }
        }
    } else {
        println("Warning could not determine discount")
    }
}

private fun checkProductCategories(credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/categories"
    val categories = executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue<List<Category>>(response.body?.string() ?: "")
        }
    }

    for (category in categories) {
        val productCount = category.count
        if (productCount < 10) {
            println("WARNING Category '${category.name}' contains only $productCount products.")
        }
    }
}


private fun checkProductAttributes(credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/attributes"
    val attributes = executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue<List<Attribute>>(response.body?.string() ?: "")
        }
    }

    for (attribute in attributes) {
        val termsUrl = "https://foryoufashion.gr/wp-json/wc/v3/products/attributes/${attribute.id}/terms"
        val terms = executeWithRetry {
            val request = Request.Builder().url(termsUrl).header("Authorization", credentials).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                mapper.readValue<List<AttributeTerm>>(response.body?.string() ?: "")
            }
        }

        for (term in terms) {
            val productCount = term.count
            if (productCount < 10) {
                println("WARNING Attribute '${attribute.name}' with term '${term.name}' contains only $productCount products.")
            }
        }
    }
}

private fun checkProductTags(credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/tags"
    val tags = executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue<List<Tag>>(response.body?.string() ?: "")
        }
    }

    for (tag in tags) {
        val productCount = tag.count
        if (productCount!! < 10) {
            println("WARNING Tag '${tag.name}' contains $productCount products.")
        }
    }
}

private fun checkForNonSizeAttributesUsedForVariations(product: Product) {
    product.attributes.let { attributes ->
        attributes.forEach { attribute ->
            if (!attribute.name.equals("Μέγεθος", ignoreCase = true) && attribute.variation) {
                println("WARNING Product ${product.sku} has the '${attribute.name}' attribute marked as used for variations.")
                println(product.permalink)
            }
        }
    }
}

fun checkForOldProductsThatAreOutOfStock(product: Product, credentials: String) {    // TODO
    if (!skipVariationChecks) {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoYearsAgo = LocalDate.now().minus(4, ChronoUnit.YEARS)
        if (productDate.isBefore(twoYearsAgo)) {
            val productVariations = getVariations(product.id, credentials)
            val allOutOfStock = productVariations.all { it.stock_status=="outofstock" }

            if (allOutOfStock) {
                println("WARNING Product ${product.sku} is out of stock on all sizes and was added more than 2 years ago.")
                println(product.permalink)
            }
        }
    }
}
