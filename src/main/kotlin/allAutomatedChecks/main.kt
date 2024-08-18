package allAutomatedChecks

import java.io.File
import java.io.IOException
import okhttp3.Request
import Attribute
import AttributeTerm
import Category
import Product
import Tag
import Variation
import com.fasterxml.jackson.module.kotlin.readValue
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.system.exitProcess
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import skuchecks.client
import skuchecks.mapper

const val readOnly = true
const val skipVariationChecks = false
const val shouldMoveOldOutOfStockProductsToPrivate = false
const val shouldSwapDescriptions = false
const val shouldUpdateProsforesProductTag = false
const val shouldUpdatePricesToEndIn99 = false

private const val CACHE_FILE_PATH = "image_cache.csv"

private fun registerWebPReader() {
    val registry = IIORegistry.getDefaultInstance()
    registry.registerServiceProvider(WebPImageReaderSpi())
}

fun main() {
    val readOnlyConsumerKey = ""
    val readOnlyConsumerSecret = ""
    val writeConsumerKey = ""
    val writeConsumerSecret = ""

    val credentials =
        if (readOnly && !shouldUpdatePricesToEndIn99 && !shouldSwapDescriptions && !shouldUpdateProsforesProductTag && !shouldMoveOldOutOfStockProductsToPrivate) {
            Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
        } else {
            Credentials.basic(
                writeConsumerKey, writeConsumerSecret
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
//            checkForInvalidDescriptions(product, credentials, shouldSwapDescriptions)
            checkForMissingImages(product)
            checkForNonPortraitImagesWithCache(product)
            checkForNonSizeAttributesUsedForVariations(product)
            checkForStockManagementAtProductLevel(product)
            checkForEmptyOrShortTitlesOrLongTitles(product)
            checkForMissingSizeGuide(product)
            checkForDraftProducts(product)
            checkForMissingToMonteloForaeiTextInDescription(product)
            checkForIncorrectPlusSizeCategorizationAndTagging(product)
            checkForSpecialOccasionOrPlusSizeCategoriesWithoutOtherCategories(product)
            checkForProductsInParentCategoryOnly(product)
            if (!skipVariationChecks) {
                val productVariations = getVariations(productId = product.id, credentials)
                if (shouldUpdateProsforesProductTag) {
                    val firstVariation = productVariations.firstOrNull()
                    firstVariation?.let {
//                        println("variation SKU: ${it.sku}")
                        addTagForDiscountedProductsAndRemoveTagForRest(product, it, 30, credentials)
                    }
                }
                checkForOldProductsThatAreOutOfStockAndMoveToPrivate(product, productVariations, credentials)
                for (variation in productVariations) {
//                    println("variation SKU: ${variation.sku}")
                    checkForMissingOrWrongPricesAndUpdateEndTo99(product, variation, credentials)
                    checkForInvalidSKUNumbers(product, variation)
                }
            }
        }
        page++
    } while (products.isNotEmpty())
    checkProductCategories(credentials)
    checkProductAttributes(credentials)
    checkProductTags(credentials)
}

fun checkForProductsInParentCategoryOnly(product: Product) {
    val parentCategories = listOf("foremata", "panoforia")
    val productCategorySlugs = product.categories.map { it.slug }.toSet()
    if ((productCategorySlugs - parentCategories).isEmpty()) {
        println("WARNING: Product SKU ${product.sku} is only in parent categories but should be in a more specific sub-category.")
        println(product.permalink)
    }
}

fun checkForIncorrectPlusSizeCategorizationAndTagging(product: Product) {
    val plusSizeCategorySlug = "plus-size"
    val plusSizeForemataCategorySlug = "plus-size-foremata"
    val olosomesFormesCategorySlug = "olosomes-formes"
    val plusSizeTagSlug = "plus-size"

    val inPlusSizeCategory = product.categories.any { it.slug==plusSizeCategorySlug }
    val inPlusSizeForemataCategory = product.categories.any { it.slug==plusSizeForemataCategorySlug }
    val inOlosomesFormesCategory = product.categories.any { it.slug==olosomesFormesCategorySlug }
    val hasPlusSizeTag = product.tags.any { it.slug==plusSizeTagSlug }

    if (inPlusSizeCategory) {
        if (!((inOlosomesFormesCategory || inPlusSizeForemataCategory) && hasPlusSizeTag)) {
            println("WARNING: Product SKU ${product.sku} is in 'Plus size' category but is not in 'Plus Size' or 'Ολόσωμες Φόρμες' categories and or does not have 'Plus Size' tag.")
        }
    }
}

fun checkForSpecialOccasionOrPlusSizeCategoriesWithoutOtherCategories(product: Product) {
    val specialOccasionCategorySlug = "special-occasion"
    val plusSizeCategorySlug = "plus-size"

    val inSpecialOccasionCategory = product.categories.any { it.slug==specialOccasionCategorySlug }
    val inPlusSizeCategory = product.categories.any { it.slug==plusSizeCategorySlug }
    val inAnyOtherCategories =
        product.categories.any { it.slug!=specialOccasionCategorySlug && it.slug!=plusSizeCategorySlug }


    if ((inSpecialOccasionCategory || inPlusSizeCategory) && !inAnyOtherCategories) {
        println("WARNING: Product SKU ${product.sku} is only in the 'Special Occasion' or 'Plus size' category and not in any other category.")
    }
}

fun checkForDraftProducts(product: Product) {
    if (product.status.equals("draft", ignoreCase = true)) {
        println("WARNING Product SKU ${product.sku} is draft.")
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoWeeksAgo = LocalDate.now().minus(2, ChronoUnit.WEEKS)
        if (productCreationDate.isBefore(twoWeeksAgo)) {
            println("WARNING: Product SKU ${product.sku} has been in draft status for more than 2 weeks.")
        }
    }
}

fun checkForInvalidSKUNumbers(product: Product, variation: Variation) {
    // Some wedding dresses - ignore
    if (product.sku !in listOf("3821", "8Ε0053", "3858", "3885", "5832", "QC368Bt50")) {
        val finalProductRegex = Regex("^\\d{5}-\\d{3}\$")
        if (!product.sku.matches(finalProductRegex)) {
            println("WARNING Product SKU ${product.sku} does not match Product SKU regex ")
        }
        val finalProductVariationRegex = Regex("^\\d{5}-\\d{3}-\\d{1,3}$")
        if (!variation.sku.matches(finalProductVariationRegex)) {
            println("WARNING Variation SKU ${variation.sku} does not match Variation SKU regex ")
        }
    }
}

fun checkForMissingToMonteloForaeiTextInDescription(product: Product) {
    // Define the target date
    val startingCheckDate = LocalDate.of(2024, Month.AUGUST, 9)
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)

    // Check if the product was created on or after the target date
    if (productCreationDate.isAfter(startingCheckDate)) {
        if (product.description.contains("μοντελο", ignoreCase = true) ||
            product.description.contains("μοντέλο", ignoreCase = true)
        ) {
            println("WARNING: Product SKU ${product.sku} has info about the size the model is wearing in the long description.")
        }
        if (!product.short_description.contains("το μοντέλο φοράει", ignoreCase = true)) {
            println("WARNING: Product SKU ${product.sku} does not have info about the size the model is wearing in the short description.")
        }
    }
}

fun checkForMissingSizeGuide(product: Product) {
//    println(product.meta_data)
    val metaData = product.meta_data
    val sizeGuideMetaData = metaData.find { it.key=="size_guide" }
    val isEmpty = if (sizeGuideMetaData!=null) {
        when (val sizeGuideMetaDataValue = sizeGuideMetaData.value) {
            is List<*> -> sizeGuideMetaDataValue.isEmpty()
            is String -> sizeGuideMetaDataValue.isBlank()
            else -> true
        }
    } else {
        true
    }
    if (isEmpty) {
//    println(product.meta_data)
        println("WARNING: No size guide for product with SKU ${product.sku}")
    }
}

private fun checkForEmptyOrShortTitlesOrLongTitles(product: Product) {
    val title = product.name.replace("&amp", "&")
    if (title.isEmpty() || title.length < 20) {
        println("WARNING Product ${product.sku} has an empty or too short title: '$title'.")
//        println(product.permalink)
    } else if (title.length > 65) {
        println("WARNING Product ${product.sku} has a too long title: '$title'.")
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
        println("WARNING short description longer than long description for product: ${product.sku}")
        println(product.permalink)
//        println("long description ${product.description}")
//        println("short description ${product.short_description}")
        if (shouldSwapDescriptions) {
            swapProductDescriptions(product, credentials)
        }
    }
    if (product.short_description.length < 120 || product.short_description.length > 250) {
        println("WARNING Product ${product.sku} has a short description of invalid length, with length ${product.short_description.length}")
        println(product.permalink + " συντομη περιγραφη μηκος:" + product.short_description.length)
    }
    if (product.description.length < 250 || product.description.length > 550) {
        println("WARNING Product ${product.sku} has a long description of invalid length, with length ${product.description.length}")
        println(product.permalink + " μεγαλη περιγραφη μηκος:" + product.description.length)
    }
}

private fun checkForStockManagementAtProductLevel(product: Product) {
    if (product.variations.isNotEmpty() && product.manage_stock) {
        println("WARNING Product ${product.sku} is a variable product with stock management at the product level.")
    }
}

private fun checkForMissingOrWrongPricesAndUpdateEndTo99(product: Product, variation: Variation, credentials: String) {
    val regularPrice = variation.regular_price
    val salePrice = variation.sale_price

    if (regularPrice.isEmpty()) {
        println("WARNING product SKU ${product.sku} regular price empty")
    }
    if (regularPrice.isNotEmpty() && !priceHasCorrectPennies(regularPrice)) {
        println("WARNING product SKU ${product.sku} regular price $regularPrice has incorrect pennies")
        if (shouldUpdatePricesToEndIn99) {
            val updatedRegularPrice = adjustPrice(regularPrice.toDouble())
            if (updatedRegularPrice!=regularPrice) {
                println("Updating product SKU ${product.sku} variation SKU ${variation.sku} regular price from $regularPrice to $updatedRegularPrice")
                if (isSignificantPriceDifference(regularPrice.toDouble(), updatedRegularPrice.toDouble())) {
                    println("ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process.")
                    exitProcess(1)
                }
                updateProductPrice(
                    product.id,
                    variation.id,
                    updatedPrice = updatedRegularPrice,
                    PriceType.REGULAR_PRICE, credentials
                )
                println(product.permalink)
            }
        }
    }
    if (salePrice.isNotEmpty() && !priceHasCorrectPennies(salePrice)) {
        println("WARNING product SKU ${product.sku} salePrice price $salePrice has incorrect pennies")
        if (shouldUpdatePricesToEndIn99) {
            val updatedSalePrice = adjustPrice(salePrice.toDouble())
            println("Updating product SKU ${product.sku} variation SKU {${variation.sku} sale price from $salePrice to $updatedSalePrice")
            if (isSignificantPriceDifference(salePrice.toDouble(), updatedSalePrice.toDouble())) {
                println("ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process.")
                exitProcess(1)
            }
            updateProductPrice(product.id, variation.id, updatedSalePrice, PriceType.SALE_PRICE, credentials)
            println(product.permalink)
        }
    }
}

private fun priceHasCorrectPennies(regularPrice: String): Boolean {
    val regularPriceValue = regularPrice.toDouble()
    if (regularPriceValue < 70) {
        if (regularPrice.endsWith(".99")) {
            return true
        }
    } else {
        if (regularPriceValue % 1==0.0) {
            return true
        }
    }
    return false
}

fun isSignificantPriceDifference(oldPrice: Double, newPrice: Double): Boolean {
    return Math.abs(oldPrice - newPrice) > 1.00
}

fun adjustPrice(price: Double): String {
    return if (price < 70) {
        adjustPriceBelow70(price)
    } else {
        adjustPriceAbove70(price)
    }
}

private fun adjustPriceBelow70(price: Double): String {
    val majorDigits = price.toInt()

    return when {
        majorDigits % 10==9 -> {
            // If the major digits end in 9, add .99
            String.format("%.2f", Math.floor(price) + 0.99)
        }

        price % 1==0.0 -> {
            // If the price ends in .00, remove 1 cent
            String.format("%.2f", price - 0.01)
        }

        else -> {
            // Otherwise, make the price end in .99
            String.format("%.2f", Math.floor(price) + 0.99)
        }
    }
}

private fun adjustPriceAbove70(price: Double): String {
    val majorDigits = price.toInt()

    return if (price % 1!=0.0) {
        if (majorDigits % 10==9) {
            // If the major digits end in 9, strip the pennies
            String.format("%.0f", Math.floor(price))
        } else {
            // Otherwise, round up to the next major digit
            String.format("%.0f", Math.ceil(price))
        }
    } else {
        // If there are no pennies, return the price as is
        String.format("%.0f", price)
    }
}

private fun updateProductPrice(
    productId: Int,
    variationId: Int,
    updatedPrice: String,
    priceType: PriceType,
    credentials: String
) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId/variations/$variationId"
    val json =
        if (priceType==PriceType.REGULAR_PRICE) mapper.writeValueAsString(mapOf("regular_price" to updatedPrice)) else mapper.writeValueAsString(
            mapOf("sale_price" to updatedPrice)
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

enum class PriceType {
    REGULAR_PRICE, SALE_PRICE
}

fun checkForNonPortraitImagesWithCache(product: Product) {
    val cache = loadImageCache()
    for (image in product.images) {
        val cachedDimensions = cache[image.src]
        val dimensions = if (cachedDimensions!=null) {
//            println("DEBUG: Cache hit found!")
            cachedDimensions
        } else {
            getImageDimensions(image.src).also {
                cache[image.src] = it
            }
        }

        if (dimensions.first > dimensions.second) {
            println("WARNING: Product ${product.sku} has a non-portrait image.")
            println(product.permalink)
        }
    }
    saveImageCache(cache)
}

private fun loadImageCache(): MutableMap<String, Pair<Int, Int>> {
    val cache = mutableMapOf<String, Pair<Int, Int>>()
    val file = File(CACHE_FILE_PATH)
    if (file.exists()) {
        file.forEachLine { line ->
            val parts = line.split(",")
            if (parts.size==3) {
                val url = parts[0]
                val width = parts[1].toIntOrNull()
                val height = parts[2].toIntOrNull()
                if (width!=null && height!=null) {
                    cache[url] = width to height
                }
            }
        }
    }
    return cache
}

private fun saveImageCache(cache: Map<String, Pair<Int, Int>>) {
    val file = File(CACHE_FILE_PATH)
    file.printWriter().use { writer ->
        cache.forEach { (url, dimensions) ->
            writer.println("${url},${dimensions.first},${dimensions.second}")
        }
    }
}

private fun getImageDimensions(imageUrl: String): Pair<Int, Int> {
    registerWebPReader()  // Register WebP support
    println("DEBUG: downloading image: $imageUrl")
    val request = Request.Builder().url(imageUrl).build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        val imageBytes = response.body?.bytes()
        val image = ImageIO.read(imageBytes?.inputStream())

        image?.let {
            return it.width to it.height
        } ?: throw IOException("Failed to read image dimensions")
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
    product: Product, credentials: String
) {
    println("reversing descriptions for product ${product.sku}")

    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val json = mapper.writeValueAsString(
        mapOf(
            "short_description" to product.description, "description" to product.short_description
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

fun checkForOldProductsThatAreOutOfStockAndMoveToPrivate(
    product: Product, productVariations: List<Variation>, credentials: String
) {
    if (product.status!="private") {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoYearsAgo = LocalDate.now().minus(2, ChronoUnit.YEARS)
        if (productDate.isBefore(twoYearsAgo)) {
            val allOutOfStock = productVariations.all { it.stock_status=="outofstock" }
            if (allOutOfStock) {
                println("WARNING Product ${product.sku} is out of stock on all sizes and was added more than 2 years ago.")
                println(product.permalink)
                if (shouldMoveOldOutOfStockProductsToPrivate) {
                    updateProductStatusToPrivate(product, credentials)
                }
            }
        }
    }
}

private fun updateProductStatusToPrivate(
    product: Product, credentials: String
) {
    println("Updating status for product ${product.sku} to 'private'")

    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val json = mapper.writeValueAsString(
        mapOf(
            "status" to "private"
        )
    )
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
    val request = Request.Builder().url(url).put(body).header("Authorization", credentials)
        .header("Content-Type", "application/json").build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response, body: $responseBody")
        } else {
            println("Successfully updated product ${product.sku} status to 'private'.")
        }
    }
}
