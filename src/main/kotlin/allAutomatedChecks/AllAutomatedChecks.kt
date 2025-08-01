package allAutomatedChecks

import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.Properties

import java.io.File
import java.io.IOException
import okhttp3.Request
import Attribute
import AttributeTerm
import Category
import Media
import MediaCacheEntry
import Order
import Plugin
import Product
import ProductImage
import Tag
import Variation
import archive.client
import archive.mapper
import chatGPTRestApiKey
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import forYouFashionFtpPassword
import forYouFashionFtpUrl
import forYouFashionFtpUsername
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import org.apache.commons.net.ftp.FTPClient
import kotlin.random.Random
import kotlin.system.exitProcess
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import readOnlyConsumerKey
import readOnlyConsumerSecret
import sakisForYouFashionEmailPassword
import wordPressApplicationPassword
import wordPressUsername
import writeConsumerKey
import writeConsumerSecret

const val readOnly = false

// Slow
const val shouldPerformVariationChecks = true
const val checkMediaLibraryChecks = true

// Recurring updates
const val shouldMoveOldOutOfStockProductsToPrivate = true
const val shouldUpdatePricesToEndIn99 = true
const val shouldUpdateProsforesProductTag = true
const val shouldUpdateNeesAfikseisProductTag = true
const val shouldRemoveEmptyLinesFromDescriptions = true
const val shouldPopulateMissingImageAltText = true
const val shouldDiscountProductPriceBasedOnLastSaleDate = true
const val shouldAddGenericPhotoshootTag = true
const val shouldSyncTiktokVideoLinkFromGallery = true
const val shouldSyncWholesalePricesAcrossVariations = true
const val shouldSyncWholesaleSalePricesAcrossVariations = true
const val useChatGptForSeoSuggestions = true
const val addMissingUpDifferentColourProductUpSells = true

// One-off updates
const val shouldCheckForLargeImagesOutsideMediaLibraryFromFTP = false
const val shouldDeleteLargeImagesOutsideMediaLibraryFromFTP = false
const val shouldUpdateProductsWithoutABrandToForYou = false

val allApiUpdateVariables = listOf(
    shouldMoveOldOutOfStockProductsToPrivate,
    shouldUpdatePricesToEndIn99,
    shouldUpdateProsforesProductTag,
    shouldUpdateNeesAfikseisProductTag,
    shouldRemoveEmptyLinesFromDescriptions,
    shouldPopulateMissingImageAltText,
    shouldDiscountProductPriceBasedOnLastSaleDate,
    shouldDeleteLargeImagesOutsideMediaLibraryFromFTP,
    shouldAddGenericPhotoshootTag,
    shouldUpdateProductsWithoutABrandToForYou
)

private const val CACHE_FILE_PATH = "product_images_dimensions_cache.csv"
private const val MEDIA_LIBRARY_CACHE_FILE_PATH = "media_library_missing_files_cache.csv"
private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private const val PLUGINS_FILE_PATH = "installed_plugins.json"
val errorCountMap = mutableMapOf<String, Int>()
private const val EMAIL_THROTTLE_FILE = "email_throttle_cache.csv"
private val emailThrottleCache = loadEmailThrottleCache()
val emailMessageBuffer = mutableMapOf<String, MutableList<String>>()
val emailMessageBufferB2B = mutableMapOf<String, MutableList<String>>()

private fun registerWebPReader() {
    val registry = IIORegistry.getDefaultInstance()
    registry.registerServiceProvider(WebPImageReaderSpi())
}

fun logError(
    messageCategory: String,
    message: String,
    alsoEmail: Boolean = true,
    b2b: Boolean = false
) {
    println(message)
    errorCountMap[messageCategory] = errorCountMap.getOrDefault(messageCategory, 0) + 1
    if (alsoEmail) {
        if (b2b) {
            val list = emailMessageBufferB2B.getOrPut(messageCategory) { mutableListOf() }
            list.add(message)
        } else {
            val list = emailMessageBuffer.getOrPut(messageCategory) { mutableListOf() }
            list.add(message)
        }
    }
}

fun main() {
    val wordPressWriteCredentials =
        Base64.getEncoder().encodeToString("$wordPressUsername:$wordPressApplicationPassword".toByteArray())

    if (readOnly && (allApiUpdateVariables.any { it })) {
        throw Exception("Something set for update but readOnly is set to 'false'")
    }

    val credentials = if (allApiUpdateVariables.any { it }) {
        Credentials.basic(writeConsumerKey, writeConsumerSecret)
    } else {
        Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    }

    val allProducts = fetchAllProducts(credentials)
    checkSimilarProductColoursUpsellsForSameSkuPrefix(allProducts, credentials)
    val allMedia = getAllMedia(wordPressWriteCredentials, skipRecentMedia = false)
    if (shouldCheckForLargeImagesOutsideMediaLibraryFromFTP) {
        checkForImagesOutsideMediaLibraryFromFTP(allMedia, allProducts)
    } else {
        println("DEBUG: Total products fetched: ${allProducts.size}")
        checkPluginsList(wordPressWriteCredentials)

        if (checkMediaLibraryChecks) {
            val allNonRecentMedia = getAllMedia(wordPressWriteCredentials, skipRecentMedia = true)
            // TODO Skip this until we clear all the photos without alt text first.
            // checkForUnusedImagesInsideMediaLibrary(allNonRecentMedia, allProducts)
            checkForMissingFilesInsideMediaLibraryEntries(allNonRecentMedia)
        }

        if (shouldUpdateNeesAfikseisProductTag) {
            updateNeesAfikseisProducts(allProducts, credentials)
        }

        val allOrders = fetchAllOrders(credentials)
        checkPaymentMethodsInLastMonths(allOrders)
        val allAttributes = getAllAttributes(credentials)
        checkProductCategories(credentials)
        checkProductAttributes(allAttributes, credentials)
        checkProductTags(credentials)
        if (shouldDiscountProductPriceBasedOnLastSaleDate) {
            discountProductBasedOnLastSale(allProducts, allOrders, credentials)
        }
        for (product in allProducts) {
            // println("DEBUG: product SKU: ${product.sku}")
            checkNameModelTag(product)
            checkForInvalidDescriptions(product, credentials)
            checkForMissingGalleryVideo(product)
            checkForProductsWithImagesNotInMediaLibrary(product, allMedia)
            checkCasualForemataHasCasualStyleAttribute(product)
            checkFwtografisiStudioEkswterikiTag(product)
            checkPhotoshootTags(product, credentials)
            checkForGreekCharactersInSlug(product)
            checkOneSizeIsOnlySize(product)
            checkForMikosAttributeInForemataCategory(product)
            checkForMissingAttributesInProduct(product, allAttributes, credentials)
            checkForMissingImages(product)
            checkForMissingPromitheutisTag(product)
            checkForTyposInProductText(product)
            checkForDuplicateImagesInAProduct(product)
            checkForNonPortraitImagesWithCache(product)
            checkForImagesWithTooLowResolution(product)
            checkForImagesWithTooHighResolution(product)
            checkForImagesWithIncorrectWidthHeightRatio(product)
            checkForNonSizeAttributesUsedForVariationsEgColour(product)
            checkForStockManagementAtProductLevelOutOfVariations(product)
            checkForEmptyOrShortTitlesOrLongTitles(product)
            checkForMissingSizeGuide(product)
            checkForDraftProducts(product)
            checkForMissingToMonteloForaeiTextInDescription(product)
            checkForIncorrectPlusSizeCategorizationAndTagging(product)
            checkForSpecialOccasionOrPlusSizeCategoriesWithoutOtherCategories(product)
            checkForProductsInParentCategoryOnly(product)
            if (shouldPerformVariationChecks) {
                val productVariations = getVariations(productId = product.id, credentials)
                checkAllVariationsAreEnabled(product, productVariations)
                if (shouldUpdateProsforesProductTag) {
                    val firstVariation = productVariations.firstOrNull()
                    firstVariation?.let {
//                        println("DEBUG: variation SKU: ${it.sku}")
                        addProsforesTagForDiscountedProductsAndRemoveItForRest(product, it, 20, credentials)
                    }
                }
                checkForMissingSizeVariations(product, productVariations, credentials)
                checkForOldProductsThatAreOutOfStockAndMoveToPrivate(product, productVariations, credentials)
                checkForProductsThatHaveBeenOutOfStockForAVeryLongTime(allOrders, product, productVariations)
                checkAllVariationsHaveTheSamePrices(product, productVariations)
                checkPrivateProductsInStock(product, productVariations)
                checkAllVariationsHaveTheSamePricesB2B(product, productVariations)
                for (variation in productVariations) {
//                    println("DEBUG: variation SKU: ${variation.sku}")
                    checkStockManagementAtVariationLevel(variation, product)
                    checkForWrongPricesAndUpdateEndTo99(product, variation, credentials)
                    checkForInvalidSKUNumbers(product, variation)
                }
            }
        }
        checkAllCustomerFacingUrlsForSeoViaYoast(allProducts, wordPressWriteCredentials)
        checkAllMediaLibraryImagesForMissingAltText(allMedia)
    }
    println("\nSummary of triggered checks:")
    errorCountMap.forEach { (error, count) ->
        println("$error: $count occurrence(s)")
    }
    flushBufferedEmailAlerts()
}

fun flushBufferedEmailAlerts() {
    val truncatedBuffer = emailMessageBuffer.mapValues { (_, messages) ->
        messages.take(50)
    }
    for ((category, messages) in truncatedBuffer) {
        if (shouldSendEmailForCategory(category)) {
            val emailBody = messages.joinToString("\n\n")
            val subject = "ForYouFashion: $category"
            sendAlertEmail(subject, emailBody, b2b = false)
            emailThrottleCache[category] = LocalDate.now()
        }
    }
    val truncatedBufferB2B = emailMessageBufferB2B.mapValues { (_, messages) ->
        messages.take(50)
    }
    for ((category, messages) in truncatedBufferB2B) {
        if (shouldSendEmailForCategory(category)) {
            val emailBody = messages.joinToString("\n\n")
            val subject = "ForYouFashion: $category"
            sendAlertEmail(subject, emailBody, b2b = true)
            emailThrottleCache[category] = LocalDate.now()
        }
    }
    saveEmailThrottleCache()
}

fun checkCasualForemataHasCasualStyleAttribute(product: Product) {
    val casualCategorySlug = "casual-foremata"
    val styleAttributeName = "Στυλ"
    val casualAttributeValue = "Casual"

    // Check if the product is in the casual-foremata category
    val isInCasualCategory = product.categories.any { it.slug.equals(casualCategorySlug, ignoreCase = true) }

    // Check for the "Style" attribute and its value
    val hasCasualStyleAttribute = product.attributes.any {
        it.name.equals(styleAttributeName, ignoreCase = true) &&
                it.options!!.contains(casualAttributeValue)
    }

    if (isInCasualCategory && !hasCasualStyleAttribute) {
        logError(
            "Casual φορεμα δεν εχει casual 'Στυλ'",
            "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} βρίσκεται στην κατηγορία 'casual φορεματα' αλλά λείπει η ιδιοτητα στυλ 'Casual'.\nLINK: ${product.permalink}"
        )
    }
}

fun checkForMikosAttributeInForemataCategory(product: Product) {
    val foremataCategorySlug = "foremata"
    val mikosAttributeName = "Μήκος"

    val isInForemataCategory = product.categories.any { it.slug==foremataCategorySlug }

    if (isInForemataCategory) {
        val mikosAttribute = product.attributes.find { it.name.equals(mikosAttributeName, ignoreCase = true) }
        if (mikosAttribute==null) {
            logError(
                "Λείπει το 'Μήκος'",
                "ΣΦΑΛΜΑ: Το προϊόν με κωδικό ${product.sku} ανήκει στην κατηγορία 'Φορέματα' αλλά λείπει το 'Μήκος'. \nLINK: ${product.permalink}"
            )
        }
    }
}

fun discountProductBasedOnLastSale(allProducts: List<Product>, orders: List<Order>, credentials: String) {
    val baseSkuMap = allProducts.groupBy { it.sku.substringBefore('-') }
    baseSkuMap.forEach { (baseSku, products) ->
        // println("DEBUG: Processing baseSku :$baseSku")
        // Step 3: Find the product with the latest creation date in the group
        val monthsSinceLastSale = products.minOf { product ->
            val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)
            val today = LocalDate.now()
            val periodSinceCreationDate = Period.between(productCreationDate, today).toTotalMonths().toInt()
            val productVariations = getVariations(productId = product.id, credentials)
            val lastSaleDate = findLastSaleDateForProductOrVariations(orders, product.id, productVariations)
            val monthsSinceLastSale = if (lastSaleDate!=null) {
                Period.between(lastSaleDate, today).toTotalMonths().toInt()
            } else {
                // If never sold, consider the product's creation date
                periodSinceCreationDate
            }
            monthsSinceLastSale
        }
        // println("DEBUG: monthsSinceLastSale for group: $monthsSinceLastSale")
        if (monthsSinceLastSale <= 12) {
            // No discount for products sold within the last year
            return@forEach
        }
        var discountPercentage = monthsSinceLastSale - 12 // 1% per month after the first year
        if (discountPercentage < 5) {
            // Start discounting from 5% only
            return@forEach
        }
        if (discountPercentage > 84) {
            // Cap the discount at 84%
            discountPercentage = 84
        }

        // Step 4: Apply the discount to all variations in the group
        products.forEach { product ->
            if (product.status!="draft") {
                val productVariations = getVariations(productId = product.id, credentials)
                productVariations.forEach { variation ->
                    applyDiscountToVariationIfNecessary(
                        product,
                        variation,
                        discountPercentage,
                        monthsSinceLastSale,
                        credentials
                    )
                }
            }
        }
    }
}

fun applyDiscountToVariationIfNecessary(
    product: Product,
    variation: Variation,
    discountPercentage: Int,
    monthsSinceLastSale: Int,
    credentials: String
) {
    val regularPrice = variation.regular_price.toDoubleOrNull()
    if (regularPrice==null || regularPrice <= 0) {
        println("ERROR: Invalid regular price for variation ${variation.id} of product ${product.sku}")
        return
    }

    val existingSalePrice = variation.sale_price.toDoubleOrNull()
    val calculatedSalePrice = regularPrice * (1 - discountPercentage / 100.0)
    val adjustedSalePrice = adjustPrice(calculatedSalePrice)

    val adjustedSalePriceValue = adjustedSalePrice.toDoubleOrNull()
    if (adjustedSalePriceValue==null || adjustedSalePriceValue <= 0) {
        println("ERROR: Calculated sale price is invalid for variation ${variation.id} of product ${product.sku}")
        return
    }

    if (existingSalePrice!=null && existingSalePrice <= adjustedSalePriceValue) {
        return
    }
    println("ACTION: Updating variation ${variation.sku} of product ${product.sku} with new sale price $adjustedSalePrice euros (${discountPercentage}% discount). Previous sale price was $existingSalePrice. Months since last sale: $monthsSinceLastSale")
    updateProductPrice(product.id, variationId = variation.id, adjustedSalePrice, PriceType.SALE_PRICE, credentials)
}

fun checkForMissingAttributesInProduct(product: Product, allAttributes: List<Attribute>, credentials: String) {
    val productAttributeNames = product.attributes.map { it.name.lowercase(Locale.getDefault()) }
    val brandAttributeName = "Brand"
    allAttributes.forEach { attribute ->
        val attributeName = attribute.name.lowercase(Locale.getDefault())
        if (!productAttributeNames.contains(attributeName)) {
            if (attributeName==brandAttributeName.lowercase(Locale.getDefault())) {
                logError(
                    "Λειπει το 'brand' απο το προιον",
                    "ΣΦΑΛΜΑ: Το προιον με SKU ${product.sku} δεν εχει 'brand'\nLINK: ${product.permalink}"
                )
                if (shouldUpdateProductsWithoutABrandToForYou) {
                    addForYouBrandAttribute(product, credentials)
                }
            } else {
                logError(
                    "SAKIS MissingOtherAttributes",
                    "ERROR: Product SKU ${product.sku} is missing the required attribute '${attribute.name}'.",
                    alsoEmail = false
                )
                println("LINK: ${product.permalink}")
            }
        }
    }
    if (product.categories.any { it.slug=="foremata" || it.slug=="olosomes-formes" }) {
        val ylikoAttribute = product.attributes.find { it.slug=="pa_yliko" }
        if (ylikoAttribute==null && product.sku !in listOf(
                "59643-589", "59122-514", "59050-596", "58684-006", "58630-011", "58681-006", "58619-005",
                "58723-040"
            )
        ) {
            logError(
                "Λειπει το 'Υλικό' απο το προιον",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} είναι στην κατηγορία 'φορέματα' και λείπει το χαρακτηριστικό 'Υλικό'.\nLINK: ${product.permalink}",
            )
        }
    }
}

fun addForYouBrandAttribute(product: Product, credentials: String) {
    val brandAttribute = Attribute(
        id = 15,
        name = "Brand",
        variation = false,
        options = listOf("For You"),
        visible = true,
    )

    val updatedAttributes = product.attributes.toMutableList().apply { add(brandAttribute) }

    val data = mapper.writeValueAsString(mapOf("attributes" to updatedAttributes))
    // println("DEBUG: REQUEST BODY: $data")
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val body = data.toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            // println("DEBUG: RESPONSE BODY: $responseBody")
            if (!response.isSuccessful) {
                println("Error adding Brand attribute to product ${product.id}: $responseBody")
                throw IOException("Unexpected code $response")
            } else {
                println("ACTION: Successfully added default Brand attribute to product ${product.sku}")
            }
        }
    }
}

fun getAllAttributes(credentials: String): List<Attribute> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/attributes"
    return executeWithRetry {
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue<List<Attribute>>(response.body?.string() ?: "")
        }
    }
}

private fun checkAllVariationsHaveTheSamePrices(product: Product, productVariations: List<Variation>) {
    val allDistinctRegularPrices = productVariations.map { it.regular_price }.distinct()
    val allDistinctSalePrices = productVariations.map { it.sale_price }.distinct()

    if (allDistinctRegularPrices.size!=1 || allDistinctSalePrices.size!=1) {
        logError(
            "Λαθος Τιμές στις Παραλλαγές",
            "ΣΦΑΛΜΑ: Το προϊόν με κωδικό ${product.sku} έχει διαφορετικές τιμές στις παραλλαγές του. Σύνδεσμος: ${product.permalink}"
        )
    }
}

fun checkAllVariationsHaveTheSamePricesB2B(product: Product, productVariations: List<Variation>) {
    val wholesalePricesList = mutableListOf<BigDecimal>()
    val wholesaleSalePricesList = mutableListOf<BigDecimal>()
    productVariations.forEach { variation ->
        // println("DEBUG: meta_data: $variation.metaData")
        val wholesalePrice = variation.meta_data.find {
            it.key=="wholesale_customer_wholesale_price"
        }?.value?.toString()

        val wholesaleSalePrice = variation.meta_data.find {
            it.key=="wholesale_customer_wholesale_sale_price"
        }?.value?.toString()

        wholesalePrice?.toBigDecimalOrNull()?.let {
            wholesalePricesList.add(it.setScale(2))
        }

        wholesaleSalePrice?.toBigDecimalOrNull()?.let {
            wholesaleSalePricesList.add(it.setScale(2))
        }
    }
    val wholesalePricesSet = wholesalePricesList.toSet()
    val wholesaleSalePricesSet = wholesaleSalePricesList.toSet()

    if (wholesalePricesSet.size > 1) {
        logError(
            "Λάθος B2B Wholesale Τιμές στις Παραλλαγές",
            "ΣΦΑΛΜΑ: Το προϊόν με κωδικό ${product.sku} έχει διαφορετικές αρχικές ΧΟΝΔΡΙΚΕΣ τιμές στις παραλλαγές του. Διαφορετικές τιμές: $wholesalePricesSet\nΣύνδεσμος: ${product.permalink}",
            b2b = true
        )
    } else if (shouldSyncWholesalePricesAcrossVariations && wholesalePricesSet.size==1 && wholesalePricesList.size!=productVariations.size) {
        productVariations.forEach { variation ->
            updateVariationWholesalePrice(
                product.id,
                variation.id,
                "wholesale_customer_wholesale_price",
                wholesalePricesSet.single().toPlainString()
            )
        }
    }

    if (wholesaleSalePricesSet.size > 1) {
        logError(
            "Λάθος B2B Wholesale Sale Τιμές στις Παραλλαγές",
            "ΣΦΑΛΜΑ: Το προϊόν με κωδικό ${product.sku} έχει διαφορετικές εκπτωτικές ΧΟΝΔΡΙΚΕΣ τιμές στις παραλλαγές του. Διαφορετικές τιμές: $wholesaleSalePricesSet\nΣύνδεσμος: ${product.permalink}",
            b2b = true
        )
    } else if (shouldSyncWholesaleSalePricesAcrossVariations && wholesaleSalePricesSet.size==1 && wholesaleSalePricesList.size!=productVariations.size) {
        productVariations.forEach { variation ->
            updateVariationWholesalePrice(
                product.id,
                variation.id,
                "wholesale_customer_wholesale_sale_price",
                wholesaleSalePricesSet.single().toPlainString()
            )
        }
    }
}

fun updateVariationWholesalePrice(
    productId: Int,
    variationId: Int,
    metaKey: String,
    price: String
) {
    val credentials = Credentials.basic(writeConsumerKey, writeConsumerSecret)
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId/variations/$variationId"

    val payload = mapOf(
        "meta_data" to listOf(
            mapOf("key" to metaKey, "value" to price)
        )
    )

    val requestBody = mapper.writeValueAsString(payload).toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .put(requestBody)
        .header("Authorization", credentials)
        .build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("ERROR: Failed to update wholesale price for variation $variationId. Response: ${response.body?.string()}")
            } else {
                println("ACTION: Updated wholesale price ($metaKey) to '$price' for variation '$variationId' of product '$productId'.")
            }
        }
    }
}

fun checkForGreekCharactersInSlug(product: Product) {
    val greekCharRegex = Regex("[\u0370-\u03FF\u1F00-\u1FFF]")
    if (greekCharRegex.containsMatchIn(product.slug)) {
        logError(
            "SAKIS checkForGreekCharactersInSlug",
            "ERROR: Product SKU ${product.sku} has a slug containing Greek characters.",
            alsoEmail = false
        )
        println("LINK: ${product.permalink}")
    }
}

fun populateMissingImageAltText(product: Product, credentials: String) {
    val updatedImages = product.images.map { image ->
        if (image.alt.isEmpty()) {
            println("ACTION: Setting alt text for image ${image.id} of product SKU ${product.sku} to '${product.name}'")
            image.copy(alt = product.name)
        } else {
            image
        }
    }
    updateProductImages(product.id, updatedImages, credentials)
}

fun updateProductImages(productId: Int, images: List<ProductImage>, credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val data = mapper.writeValueAsString(mapOf("images" to images))
    val body = data.toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .put(body)
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                println("Error updating images for product $productId: $responseBody")
                throw IOException("Unexpected code $response")
            } else {
                println("ACTION: Successfully updated alt image descriptions for product $productId")
            }
        }
    }
}

fun fetchAllProducts(credentials: String): List<Product> {
    val allProducts = mutableListOf<Product>()
    var page = 1
    var products: List<Product>
    do {
        products = getProducts(page, credentials)
        allProducts.addAll(products)
        page++
    } while (products.isNotEmpty())
    return allProducts
}

fun checkForProductsThatHaveBeenOutOfStockForAVeryLongTime(
    orders: List<Order>,
    product: Product,
    productVariations: List<Variation>,
) {
    if (product.status!="draft" && product.status!="private" && product.sku !in listOf("57851-443", "58874-488")) {
        val productOutOfStock = product.stock_status=="outofstock"
        val allVariationsOutOfStock = productVariations.all { it.stock_status=="outofstock" }
        val sixMonthsAgo = LocalDate.now().minus(6, ChronoUnit.MONTHS)
        if (productOutOfStock || allVariationsOutOfStock) {
            val lastProductSaleDate = findLastSaleDateForProductOrVariations(orders, product.id, productVariations)
//            println("DEBUG: Last Product sale date $lastProductSaleDate")
            if (lastProductSaleDate!=null) {
                if (lastProductSaleDate.isBefore(sixMonthsAgo)) {
                    logError(
                        "Παλια out of stock προιοντα",
                        "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} είναι out of stock για περισσότερο από 6 μήνες από την τελευταία του πώληση στις $lastProductSaleDate. Ειναι για διαγραφη?\nLINK: ${product.permalink}"
                    )
                }
            } else {
                // println("DEBUG: SKU ${product.sku} has no sales records.")
            }
        }
    }
}

fun findLastSaleDateForProductOrVariations(
    orders: List<Order>,
    productId: Int,
    productVariations: List<Variation>
): LocalDate? {
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    var lastSaleDate: LocalDate? = null
    val variationIds = productVariations.map { it.id }.toSet()
    for (order in orders) {
        for (lineItem in order.line_items) {
            val itemMatchesProduct = lineItem.product_id==productId
            val itemMatchesVariation = lineItem.variation_id in variationIds
            if (itemMatchesProduct && itemMatchesVariation) {
                val orderDate = LocalDate.parse(order.date_created, dateFormatter)
                if (lastSaleDate==null || orderDate.isAfter(lastSaleDate)) {
                    lastSaleDate = orderDate
                }
            }
        }
    }
    return lastSaleDate
}

fun checkForImagesOutsideMediaLibraryFromFTP(allNonRecentMedia: List<Media>, allProducts: List<Product>) {
    val allImagesFromFTP = listNonRecentFTPFiles(forYouFashionFtpUsername, forYouFashionFtpPassword, "/")
    val allMainMediaUrls = allNonRecentMedia.map { it.source_url }.toSet()
    val allDifferentResolutionMediaUrls =
        allNonRecentMedia.flatMap { mediaItem -> mediaItem.media_details.sizes!!.values.map { it.source_url } }
            .toSet()
    val allMediaUrls = allMainMediaUrls + allDifferentResolutionMediaUrls
    println("DEBUG: Total main media files: ${allMainMediaUrls.size}")
    println("DEBUG: Total all media files: ${allMediaUrls.size}")
    val allProductImages = allProducts.flatMap { it.images }.map { it.src }.toSet()
    // println("DEBUG: All products images: $allProductImages")
    val imagePathsToDelete = mutableListOf<String>()
    // println("DEBUG: All media urls $allMediaUrls")
    allImagesFromFTP.forEach { ftpImagePath ->
        // Expected format example: //wp-content/uploads/2022/04/57874-PINK-21.jpg
        val imageUrl = ftpImagePath.replaceFirst("/", "https://foryoufashion.gr")
        // println("DEBUG: processing image $imageUrl")
        if (imageUrl !in allMediaUrls && imageUrl !in allProductImages) {
            imagePathsToDelete.add(ftpImagePath)
        }
    }

    if (imagePathsToDelete.isEmpty()) {
        println("No unused images outside media library detected ")
    } else {
        logError(
            "SAKIS checkForImagesOutsideMediaLibraryFromFTP",
            "ERROR: ${imagePathsToDelete.size} unused images outside media library detected",
            alsoEmail = false
        )
    }
    imagePathsToDelete.forEach {
        println("DEBUG: Image to delete $it")
    }
    if (shouldDeleteLargeImagesOutsideMediaLibraryFromFTP) {
        val ftpClient = FTPClient()
        try {

            ftpClient.controlEncoding = "UTF-8"
            ftpClient.connect(forYouFashionFtpUrl)
            ftpClient.login(forYouFashionFtpUsername, forYouFashionFtpPassword)
            imagePathsToDelete.forEach {
                if (ftpClient.deleteFile(it)) {
                    println("ACTION: Deleted unused image: $it")
                } else {
                    println("ERROR: Could not delete image: $it")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                if (ftpClient.isConnected) {
                    ftpClient.logout()
                    ftpClient.disconnect()
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }
}

fun listNonRecentFTPFiles(
    ftpUsername: String,
    ftpPassword: String,
    startDirectory: String,
): List<String> {
    val ftpClient = FTPClient()

    try {
        ftpClient.controlEncoding = "UTF-8"
        ftpClient.autodetectUTF8 = true

        ftpClient.connect(forYouFashionFtpUrl)
        ftpClient.login(ftpUsername, ftpPassword)

        val filteredResults = mutableListOf<Pair<String, Long>>()
        val allResults = mutableListOf<Pair<String, Long>>()
        listFilesRecursively(ftpClient, startDirectory, filteredResults, allResults)

        println("DEBUG: Total number of all results: ${allResults.size}")
        println("DEBUG: Total number of filtered results: ${filteredResults.size}")
        // println("Diff between the two: ${allResults - filteredResults}")
        // filteredResults.forEach { (filePath, size) ->
        // println("File: $filePath, Size: ${size / 1_048_576.0} MB")
        // println(filePath)
        // }
        val allResultsTotalSize = allResults.sumOf { it.second }
        val filteredResultsTotalSize = filteredResults.sumOf { it.second }
        println("DEBUG: All results size: ${allResultsTotalSize / 1_048_576.0} MB")
        println("DEBUG: Filtered results size: ${filteredResultsTotalSize / 1_048_576.0} MB")
        return filteredResults.map { it.first }
    } catch (e: Exception) {
        e.printStackTrace()
        return emptyList()
    } finally {
        try {
            if (ftpClient.isConnected) {
                ftpClient.logout()
                ftpClient.disconnect()
            }
        } catch (ex: Exception) {
            throw ex
        }
    }
}

fun listFilesRecursively(
    ftpClient: FTPClient,
    directory: String,
    filteredResults: MutableList<Pair<String, Long>>,
    allResults: MutableList<Pair<String, Long>>,
) {
    ftpClient.changeWorkingDirectory(directory)
    val files: Array<FTPFile> = ftpClient.listFiles()
    println(directory)
    if (directory!="/"
        && directory!="//wp-content"
        && directory!="//wp-content/uploads"
        && !directory.startsWith("//wp-content/uploads/2015")
        && !directory.startsWith("//wp-content/uploads/2016")
        && !directory.startsWith("//wp-content/uploads/2017")
        && !directory.startsWith("//wp-content/uploads/2018")
        && !directory.startsWith("//wp-content/uploads/2019")
        && !directory.startsWith("//wp-content/uploads/2020")
        && !directory.startsWith("//wp-content/uploads/2021")
        && !directory.startsWith("//wp-content/uploads/2022")
        && !directory.startsWith("//wp-content/uploads/2023")
        && !directory.startsWith("//wp-content/uploads/2024")
        && !directory.startsWith("//wp-content/uploads/2024/01")
        && !directory.startsWith("//wp-content/uploads/2024/02")
        && !directory.startsWith("//wp-content/uploads/2024/03")
        && !directory.startsWith("//wp-content/uploads/2024/04")
//        && !directory.startsWith("//wp-content/uploads/2024")
    // && !directory.startsWith("//wp-content/uploads/2025")
    // && !directory.startsWith("//wp-content/uploads/2026")
    // SKIP 2023 AND 2024
    ) {
        return
    }

    for (file in files) {
        val filePath = "$directory/${file.name}" // Construct the full file path
        if (file.isFile) {
            allResults.add(filePath to file.size)
            if (
            // file.size > 100_000
                !file.name.endsWith(".js") &&
                !file.name.endsWith(".ttf") &&
                !file.name.endsWith(".css") &&
                !file.name.endsWith(".php") &&
                (file.name.endsWith(".jpg") || file.name.endsWith(".jpeg"))
            ) {
                filteredResults.add(filePath to file.size)
            }
        } else if (file.isDirectory && file.name!="." && file.name!="..") {
            // Recursive call to process subdirectories
            println("Recursing into $filePath, so far size: ${filteredResults.size}")
            listFilesRecursively(ftpClient, filePath, filteredResults, allResults)
        }
    }
}

fun checkForMissingGalleryVideo(product: Product) {
    if (product.status!="draft" && product.status!="private" && product.sku !in listOf(
            "57849-556", "57396-550", "58205-007", "58205-293", "57402-028", "58747-246", "58746-246", "58727-040",
            "59346-002", "59356-054", "59356-003", "59435-369", "59428-246", "59427-022", "59427-003", "58223-488",
            "59425-051", "59425-005", "59345-003", "59345-022", "59388-1278", "59343-369", "58072-040", "59343-003",
            "59323-013", "59260-005", "59260-001", "59429-246", "59422-005", "59422-012", "59422-010", "59441-003",
            "59440-369", "59440-003", "59426-002", "59436-060", "59423-027", "59212-396", "59417-488", "58735-003",
            "59238-003", "58593-005", "59235-005", "59235-003", "59235-004", "59235-187", "58580-036", "59434-003",
            "58882-028", "57630-396", "59197-003", "59439-369", "59468-281", "59269-396", "59409-003", "56084-396",
            "59432-060", "59431-1282", "59432-003", "59432-369", "59438-003", "59438-060", "59293-003", "59468-561",
            "59418-005", "59430-003", "59394-003", "59430-007", "59393-011", "59396-003", "59469-281", "59333-588",
            "58736-005", "58736-001", "59397-020", "59370-003", "59395-011", "59041-595", "59324-002", "58458-007",
            "59370-040", "58696-289", "59392-281", "58585-007", "59461-031", "59433-402", "59413-020", "59047-015",
            "59195-396", "58549-028", "58458-465", "57630-556", "57630-028", "58902-465", "59208-028", "59051-017",
            "59046-596", "59065-596", "59192-028", "59186-097", "59181-097", "59193-097", "59195-097", "58869-011",
            "58551-028", "58856-051", "59067-028", "58794-289", "58794-016", "59137-293", "59138-293", "58872-488",
            "58876-519", "58548-519", "58903-051", "57459-561", "58525-028", "57140-003", "56084-281", "56080-556",
            "58218-027", "59224-028", "59230-003", "59232-281", "58504-561", "59183-514", "59182-514", "59073-514",
            "59072-514", "58758-015", "58759-015", "56634-007", "59167-013", "59175-556", "59173-027", "59172-051",
            "59050-596", "59049-013", "59047-031", "59028-004", "59043-051", "59010-007", "59112-550", "58241-514",
            "59113-550", "59114-514", "59114-550", "59111-550", "59110-514", "59110-550", "59111-514", "58242-015",
            "59109-514", "59109-550", "58515-029", "56065-547", "59016-019", "58996-005", "59030-096", "59039-281",
            "59039-003", "58995-051", "59033-013", "59064-281", "59017-004", "59017-281", "59018-013", "58301-003",
            "59040-004", "59040-289", "59023-023", "59020-027", "59020-023", "59045-097", "58210-293", "58205-027",
            "55627-561", "58956-097", "58959-097", "58957-097", "58983-005", "59117-012", "58823-488", "57661-097",
            "59501-556", "55627-016", "58201-509", "57846-519", "56071-465", "57073-028", "57382-281", "56068-396",
            "55627-027", "51746-332", "53860-015", "53860-014", "53860-289", "57385-556", "59169-019", "59490-007"
        )
    ) {
        val startTargetDate = LocalDate.of(2024, 3, 1)
        val endTargetDate = LocalDate.now().minusMonths(2)
        val productCreationDate = LocalDate.parse(product.date_created, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        if (productCreationDate.isAfter(startTargetDate) && productCreationDate.isBefore(endTargetDate)) {
            val metaData = product.meta_data
            // println("DEBUG: meta_data for SKU ${product.sku}: $metaData")
            val galleryVideoMetaData = metaData.find { it.key=="gallery_video" }
            val galleryVideo = galleryVideoMetaData?.value as? String ?: ""
            val isGalleryVideoMissing = galleryVideo.isBlank()
            val tikTokVideoMetaData = metaData.find { it.key=="_tiktok_video_url" }
            val tikTokVideo = tikTokVideoMetaData?.value as? String ?: ""
            val isTikTokVideoMissing = tikTokVideo.isBlank()
            if (isGalleryVideoMissing) {
                logError(
                    "Προιον χωρις βιντεο",
                    "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku}, δεν έχει βιντεο.\nLINK: ${product.permalink}"
                )
            }
            if (!isGalleryVideoMissing && !isTikTokVideoMissing && tikTokVideo!=galleryVideo) {
                logError(
                    "SAKIS Gallery video και TikTok video διαφέρουν",
                    "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει διαφορετικά links για gallery_video και TikTok video." +
                            "\ngallery video:$galleryVideo, tiktok video:$tikTokVideo" +
                            "\nLINK: ${product.permalink}",
                    alsoEmail = false
                )
            }
            if (!isGalleryVideoMissing && isTikTokVideoMissing && shouldSyncTiktokVideoLinkFromGallery) {
                println("ACTION: Copying gallery_video '$galleryVideo' to '_tiktok_video_url' for SKU ${product.sku}")
                updateTiktokVideoUrl(product, galleryVideo)
            }
        }
    }
}

fun updateTiktokVideoUrl(product: Product, videoUrl: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val payload = mapper.writeValueAsString(
        mapOf("meta_data" to listOf(mapOf("key" to "_tiktok_video_url", "value" to videoUrl)))
    )
    val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder()
        .url(url)
        .put(body)
        .header("Authorization", Credentials.basic(writeConsumerKey, writeConsumerSecret))
        .build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            println("ERROR: Failed to update TikTok video URL for product ${product.id}: $responseBody")
            throw IOException("Unexpected code $response")
        } else {
            println("SUCCESS: Updated TikTok video URL for product ${product.sku}")
        }
    }
}

fun checkAllMediaLibraryImagesForMissingAltText(allMedia: List<Media>) {
    for (media in allMedia) {
        if (media.media_type.startsWith("image") // Check only image media types
            && media.alt_text.isNullOrEmpty()
        ) {
            logError(
                "Λειπουν εναλλακτικα κειμενα για φωτογραφιες",
                "ΣΦΑΛΜΑ: Η φωτογραφια LINK https://foryoufashion.gr/wp-admin/post.php?post=${media.id}&action=edit δεν εχει εναλλακτικο κειμενο. " +
                        "Αν η εικονα χρειαζεται, τοτε βαλε μια συντομη περιγραφη της εικονας, αλλιως διεγραψε την.",
            )
        }
    }
}

fun checkForMissingFilesInsideMediaLibraryEntries(allNonRecentMedia: List<Media>) {
    val cache = loadMediaCache()
    val today = LocalDate.now()
    allNonRecentMedia.forEach { media ->
        val cachedEntry = cache[media.source_url]
        val isFileMissing = if (cachedEntry!=null && !isCacheExpired(cachedEntry.lastChecked, today)) {
            // Cache hit and still valid
            cachedEntry.isFileMissing
        } else {
            // Cache miss or expired, check file existence
            val fileExists = checkIfImageExists(media.source_url)
            cache[media.source_url] = MediaCacheEntry(media.source_url, !fileExists, today)
            !fileExists
        }

        if (isFileMissing) {
            logError(
                "SAKIS checkForMissingFilesInsideMediaLibraryEntries",
                "ERROR: File missing for Media ID: ${media.id}, URL: ${media.source_url}",
                alsoEmail = false
            )
            println("LINK: https://foryoufashion.gr/wp-admin/post.php?post=${media.id}&action=edit")
//            val desktop = Desktop.getDesktop()
//            desktop.browse(URI("https://foryoufashion.gr/wp-admin/post.php?post=${media.id}&action=edit"));
        }
    }
    saveMediaCache(cache)
}

private fun loadMediaCache(): MutableMap<String, MediaCacheEntry> {
    val cache = mutableMapOf<String, MediaCacheEntry>()
    val file = File(MEDIA_LIBRARY_CACHE_FILE_PATH)
    if (file.exists()) {
        file.forEachLine { line ->
            val parts = line.split(",")
            if (parts.size==3) {
                val url = parts[0]
                val isFileMissing = parts[1].toBoolean()
                val lastChecked = LocalDate.parse(parts[2], formatter)
                cache[url] = MediaCacheEntry(url, isFileMissing, lastChecked)
            }
        }
    }
    return cache
}

private fun saveMediaCache(cache: Map<String, MediaCacheEntry>) {
    val file = File(MEDIA_LIBRARY_CACHE_FILE_PATH)
    file.printWriter().use { writer ->
        cache.forEach { (_, entry) ->
            writer.println("${entry.url},${entry.isFileMissing},${entry.lastChecked.format(formatter)}")
        }
    }
}

private fun isCacheExpired(lastChecked: LocalDate, today: LocalDate): Boolean {
    return lastChecked.plusMonths(1).isBefore(today)
}

private fun checkIfImageExists(url: String): Boolean {
    println("DEBUG: downloading image to check if image exists: $url")
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    return client.newCall(request).execute().use { response ->
        response.isSuccessful // Returns true if file exists (HTTP 200), false otherwise
    }
}

fun checkForUnusedImagesInsideMediaLibrary(
    allNonRecentMedia: List<Media>,
    allProducts: List<Product>,
) {
    println("DEBUG: Total media files: ${allNonRecentMedia.size}")

    val allProductImages: Set<String> = allProducts.flatMap { it.images }.map { it.src }.toSet()
    println("DEBUG: Total product images: ${allProductImages.size}")

    val dateThreshold = LocalDate.now().minusMonths(3).withDayOfMonth(1)

    val unusedImages = findUnusedImages(allNonRecentMedia, allProductImages)
        .filter { it.id !in listOf(36692, 29045, 29044, 29043, 29042) } // Ta thelei i Olga
        // filter out images from the last few months
        .filterNot { media ->
            val url = media.source_url
            val regex = """.*/(\d{4})/(\d{2})/.*""".toRegex()
            val matchResult = regex.matchEntire(url)
            if (matchResult!=null) {
                val (yearStr, monthStr) = matchResult.destructured
                val year = yearStr.toIntOrNull()
                val month = monthStr.toIntOrNull()
                if (year!=null && month!=null) {
                    val mediaDate = LocalDate.of(year, month, 1)
                    mediaDate >= dateThreshold // Exclude media within the last 9 months
                } else {
                    true // If parsing fails, exclude the media item to be safe
                }
            } else {
                if (media.id!=38052) { // exclude a placeholder image
                    logError(
                        "SAKIS checkForUnusedImagesInsideMediaLibrary regex mismatch",
                        "ERROR: ${media.source_url} with id ${media.id} should always match regex",
                        alsoEmail = false
                    )
                }
                true // If URL doesn't match pattern, exclude the media item to be safe
            }
        }
    unusedImages.forEach {
        logError(
            "Φωτογραφιες που δεν χρησιμοποιουνται",
            "Αυτη η φωτογραφια δεν φαινεται να χρησιμοποιειται, διαγραφη? https://foryoufashion.gr/wp-admin/post.php?post=${it.id}&action=edit\n" +
                    "ΣΗΜΑΝΤΙΚΟ: Διαγραφουμε μονο φωτογραφιες που γνωριζουμε πως ηταν μεσα σε προιοντα που τα προιοντα διαγραφηκαν, τιποτα αλλο",
        )
    }
}

fun getAllMedia(wordPressWriteCredentials: String, skipRecentMedia: Boolean): List<Media> {
    val formatter = DateTimeFormatter.ISO_DATE_TIME
    val threeMonthsAgo = LocalDate.now().minusMonths(3)
    val url = "https://foryoufashion.gr/wp-json/wp/v2/media?per_page=100&page="
    val allMedia = mutableListOf<Media>()
    var page = 1
    do {
        println("DEBUG: Performing request to get media, page $page")
        val requestUrl = "$url$page"
        val media = executeWithRetry {
            val request = Request.Builder().url(requestUrl)
                .header("Authorization", "Basic $wordPressWriteCredentials")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    if (response.code==400 && errorBody?.contains("rest_post_invalid_page_number")==true) {
                        return@executeWithRetry emptyList<Media>()
                    } else {
                        println("ERROR: Error Response: $errorBody")
                        throw IOException("Unexpected code $response")
                    }
                } else {
                    mapper.readValue(response.body?.string() ?: "")
                }
            }
        }

        val filteredMedia = if (skipRecentMedia) {
            media.filter {
                val mediaDate = LocalDate.parse(it.date, formatter)
                mediaDate.isBefore(threeMonthsAgo)
            }
        } else media

        allMedia.addAll(filteredMedia)
//        println("DEBUG: $media")
        page++
    } while (media.isNotEmpty())
    return allMedia
}

fun findUnusedImages(
    allMedia: List<Media>,
    allProductImages: Set<String>
): List<Media> {
    return allMedia.filter { media ->
        media.post==null && media.source_url !in allProductImages && media.media_type=="image"
    }.filter { media -> media.id!=36631 } // For You logo
}

fun checkForImagesWithIncorrectWidthHeightRatio(product: Product) {
    if (product.sku !in listOf(
            "59665-051", "58855-012", "59664-013", "59131-029", "58798-293", "58812-556", "58459-561", "58859-488"
        )
    ) {
        val cache = loadImageCache()
        for (image in product.images) {
            val dimensions = cache[image.src] ?: getImageDimensions(image.src).also {
                cache[image.src] = it
            }

            val ratio = dimensions.second.toDouble() / dimensions.first.toDouble()
            if (ratio > 1.51 || ratio < 1.49) {
                logError(
                    "Λαθος αναλογια υψους και πλατους για φωτογραφια",
                    "ΣΦΑΛΜΑ: Η εικόνα ${image.src} του προϊόντος ${product.sku} έχει λάθος αναλογία υψους και πλατους ($ratio). " +
                            "Πλάτος εικόνας: ${dimensions.first}, ύψος εικόνας: ${dimensions.second}" +
                            "\nΤσεκαρε την φωτογραφια αν φαινεται καλα μεσα στο προιον και απο κινητο και απο υπολογιστη. " +
                            "Εαν φαινεται οκ τοτε ενημερωσε με για να την εξαιρεσω απο τον ελεγχο. Εαν κατι δεν φαινεται καλα " +
                            "τοτε πες μου για να φτιαξω τη φωτογραφια." +
                            "\nLINK: ${product.permalink}",
                )
            }
        }
        saveImageCache(cache)
    }
}

fun checkForImagesWithTooLowResolution(product: Product) {
    if (product.status!="private" && product.status!="draft" && product.sku !in listOf(
            "58747-246", "58747-246", "59183-514", "59182-514", "59073-514", "59072-514", "59049-013", "56062-465",
            "56062-097", "57459-332", "55627-443", "55627-465", "55627-396", "55627-097", "56031-488", "57401-465",
            "58028-028", "58723-040", "58635-016", "58634-016", "58631-022", "58684-006", "58630-011", "58687-003",
            "57469-007", "57384-488", "56484-501", "55077-007", "56062-556", "56127-396", "58746-246", "56032-012",
            "55627-488", "58689-003", "56062-029", "56067-003", "59050-596", "58458-556", "58859-488"
        )
    ) {
        val cache = loadImageCache()
        for (image in product.images) {
            val dimensions = cache[image.src] ?: getImageDimensions(image.src).also {
                cache[image.src] = it
            }

            if (dimensions.first < 1200) {
                logError(
                    "SAKIS checkForImagesWithTooLowResolution",
                    "ERROR: Product ${product.sku} has an image with too low resolution (width < 1200px).",
                    alsoEmail = false
                )
                println("DEBUG: Image width: ${dimensions.first}px")
                println("DEBUG: Image URL: ${image.src}")
                println("LINK: ${product.permalink}")
            }
        }
        saveImageCache(cache)
    }
}

fun checkForImagesWithTooHighResolution(product: Product) {
    if (product.sku!="57642-022" // skipped by EWWWW Image optimizer as resizing did not decrease file size
    ) {
        val cache = loadImageCache()
        for (image in product.images) {
            val dimensions = cache[image.src] ?: getImageDimensions(image.src).also {
                cache[image.src] = it
            }
            if (dimensions.first > 1500) {
                logError(
                    "SAKIS checkForImagesWithTooHighResolution",
                    "ERROR: Product ${product.sku} has an image with too high resolution (width > 1500px).",
                    alsoEmail = false
                )
                println("DEBUG: Image width: ${dimensions.first}px")
                println("DEBUG: Image URL: ${image.src}")
                println("LINK: ${product.permalink}")
            }
        }
        saveImageCache(cache)
    }
}

fun checkForProductsInParentCategoryOnly(product: Product) {
    if (product.status!="draft") {
        val parentCategories = listOf("foremata", "panoforia")
        val productCategorySlugs = product.categories.map { it.slug }.toSet()
        if ((productCategorySlugs - parentCategories).isEmpty()) {
            val errorMessage =
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} ανήκει μόνο σε βασικές κατηγορίες ('Φορέματα' ή 'Πανωφόρια') και πρέπει να προστεθεί σε πιο συγκεκριμένη υποκατηγορία.\nLINK: ${product.permalink}"
            logError("Προιον μονο σε βασικές κατηγορίες", errorMessage)
        }
    }
}

fun checkForIncorrectPlusSizeCategorizationAndTagging(product: Product) {
    if (product.status!="draft") {
        val plusSizeCategorySlug = "plus-size"
        val plusSizeForemataCategorySlug = "plus-size-foremata"
        val olosomesFormesCategorySlug = "olosomes-formes"
        val plusSizeTagSlug = "plus-size"

        val inPlusSizeCategory = product.categories.any { it.slug==plusSizeCategorySlug }
        val inPlusSizeForemataCategory = product.categories.any { it.slug==plusSizeForemataCategorySlug }
        val inOlosomesFormesCategory = product.categories.any { it.slug==olosomesFormesCategorySlug }
        val hasPlusSizeTag = product.tags.any { it.slug==plusSizeTagSlug }

        if ((inPlusSizeCategory || inPlusSizeForemataCategory) && !hasPlusSizeTag) {
            logError(
                "Προιον χωρις ετικετα plus size",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν έχει ετικέτα plus size.\nLINK: ${product.permalink}"
            )
        }

        if (inPlusSizeCategory && !inOlosomesFormesCategory && !inPlusSizeForemataCategory) {
            logError(
                "Προιον plus size χωρις συγκεκριμενη κατηγορια",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} είναι στην κατηγορία plus size αλλά δεν είναι στις κατηγορίες 'ολόσωμες φόρμες' ή 'plus size φορέματα'.\nLINK: ${product.permalink}"
            )
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
        logError(
            "Λειπουν κατηγοριες απο προιον",
            "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} βρίσκεται μόνο στην κατηγορία 'Special Occasion' ή 'Plus size' και δεν ανήκει σε καμία άλλη κατηγορία.\nLINK: ${product.permalink}"
        )
    }
}

fun checkForDraftProducts(product: Product) {
    if (product.status.equals("draft", ignoreCase = true)) {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productLastModificationDate = LocalDate.parse(product.date_modified, dateFormatter)
        val oneMonthAgo = LocalDate.now().minus(1, ChronoUnit.MONTHS)
        if (productLastModificationDate.isBefore(oneMonthAgo)) {
            logError(
                "Προϊόντα σε προσχέδιο για πολύ καιρό",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} βρίσκεται σε προσχέδιο για πάνω από 1 μήνα.\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkForInvalidSKUNumbers(product: Product, variation: Variation) {
    if (product.status!="draft" && product.status!="private") {
        if (product.sku in listOf( // Some wedding dresses - ignore
                "7489", "2345", "9101", "5678", "1234", "3821", "8Ε0053", "3858", "3885", "5832", "QC368Bt50"
            )
        ) {
            return
        }
        val finalProductRegex = Regex("^\\d{5}-\\d{3,4}$")
        if (!product.sku.matches(finalProductRegex)) {
            logError(
                "Λαθος SKU για προιον",
                "ΣΦΑΛΜΑ: Το SKU του προϊόντος ${product.sku} φαινεται να ειναι λανθασμενο\nLINK: ${product.permalink}"
            )
        }
        val finalProductVariationRegex = Regex("^\\d{5}-\\d{3,4}-\\d{1,3}$")
        if (!variation.sku.matches(finalProductVariationRegex)) {
            logError(
                "Λαθος SKU για προιον",
                "ΣΦΑΛΜΑ: Το SKU της παραλλαγης ${variation.sku} φαινεται λανθασμενο\nLINK: ${product.permalink}"
            )
        }
        if (!variation.sku.startsWith(product.sku)) {
            logError(
                "Λαθος SKU για προιον",
                "ΣΦΑΛΜΑ: Το SKU της παραλλαγης ${variation.sku} φαινεται να ειναι διαφορετικο απο του προιοντος ${product.sku}\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkOneSizeIsOnlySize(product: Product) {
    if (product.status!="publish") return

    val sizeAttribute = product.attributes.find {
        it.name.equals("Μέγεθος", ignoreCase = true)
    }

    if (sizeAttribute!=null) {
        val sizeOptions = sizeAttribute.options!!.map { it.trim().lowercase() }.toSet()
        val hasOneSize = sizeOptions.any { it=="one size" }
        val hasOtherSizes = sizeOptions.any { it!="one size" }

        if (hasOneSize && hasOtherSizes) {
            logError(
                "Προϊόν με 'One size' αλλά και άλλα μεγέθη",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει 'One size' μαζί με άλλα μεγέθη: ${sizeAttribute.options.joinToString()}.\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkAllCustomerFacingUrlsForSeoViaYoast(
    allProducts: List<Product>,
    wordPressWriteCredentials: String
) {
    val allUrls = mutableSetOf<String>()
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val productUrls = allProducts.filter { it.status=="publish" }.map { it.permalink }
    val pagesUrl = "https://foryoufashion.gr/wp-json/wp/v2/pages?per_page=100&page="
    var page = 1
    do {
        val response = executeWithRetry {
            val request = Request.Builder()
                .url("$pagesUrl$page")
                .header("Authorization", "Basic $wordPressWriteCredentials")
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@executeWithRetry emptyList<Map<String, Any>>()
                mapper.readValue(res.body?.string() ?: "")
            }
        }
        val urls = response.mapNotNull { it["link"] as? String }
        println("DEBUG: Page URLs found: $urls")
        allUrls += urls
        page++
    } while (response.isNotEmpty())

    val categoryUrl = "https://foryoufashion.gr/wp-json/wc/v3/products/categories?per_page=100"
    val categories = executeWithRetry {
        val req = Request.Builder().url(categoryUrl).header("Authorization", credentials).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Unexpected code $res")
            mapper.readValue<List<Category>>(res.body?.string() ?: "")
        }
    }
    val categoryUrls = categories.map { category ->
        val parentPath = categories.find { it.id==category.parent }?.slug?.let { "$it/" } ?: ""
        "https://foryoufashion.gr/gynaikeia-rouxa-online/$parentPath${category.slug}/"
    }
    println("DEBUG: Category URLs found: $categoryUrls")
    allUrls += categoryUrls

    val tagsUrl = "https://foryoufashion.gr/wp-json/wc/v3/products/tags?per_page=100"
    val tags = executeWithRetry {
        val req = Request.Builder().url(tagsUrl).header("Authorization", credentials).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("Unexpected code $res")
            mapper.readValue<List<Tag>>(res.body?.string() ?: "")
        }
    }

    val tagUrls = tags.map { "https://foryoufashion.gr/product-tag/${it.slug}/" }.toSet()
    println("DEBUG: Tag URLs found: $tagUrls")
    allUrls += tagUrls

    val yoastBaseUrl = "https://foryoufashion.gr/wp-json/yoast/v1/get_head?url="
    allUrls += productUrls // Process products last.
    println("DEBUG: Total public-facing URLs to check: ${allUrls.size}")
    for (url in allUrls) {
        if (url in tagUrls && url !in listOf(
                "https://foryoufashion.gr/product-tag/plus-size/",
                "https://foryoufashion.gr/product-tag/prosfores/",
                "https://foryoufashion.gr/product-tag/nees-afikseis/"
            )
        ) {
            println("DEBUG: Skipping tag URL: $url")
            continue
        }
        println("DEBUG: Processing url: $url")
        val yoastUrl = "$yoastBaseUrl$url"
        val seoRequest = Request.Builder().url(yoastUrl).get().build()
        client.newCall(seoRequest).execute().use { response ->
            val responseBody = response.body?.string()
            // println("DEBUG: $responseBody")
            if (!response.isSuccessful) {
                println("ERROR: Could not fetch SEO data for $yoastUrl, HTTP ${response.code}")
                return@use
            }

            val jsonNode = mapper.readTree(responseBody ?: return@use)
            val json = jsonNode.get("json") ?: return@use

            val robots = json.get("robots")
            // println("DEBUG: SEO robots $robots")

            if (robots==null) {
                logError(
                    "SAKIS Yoast: Missing robots meta tag",
                    "ERROR: Η σελίδα $url δεν έχει robots meta tag",
                    alsoEmail = false
                )
            }

            if (url !in listOf(
                    "https://foryoufashion.gr/efcharistoume/", "https://foryoufashion.gr/agaphmena/",
                    "https://foryoufashion.gr/ekseliksh-parangelias/", "https://foryoufashion.gr/enhmerwsh-katatheshs/",
                    "https://foryoufashion.gr/my-account/", "https://foryoufashion.gr/checkout/",
                    "https://foryoufashion.gr/cart/", "https://foryoufashion.gr/terms/",
                    "https://foryoufashion.gr/privacy-policy/"
                )
            ) {
                val title = json.get("title")?.asText()
                // println("DEBUG: SEO title $title")

                val ogDescription = json.get("og_description")?.asText()
                // println("DEBUG: SEO meta description $ogDescription")

                if (url in productUrls) {
                    val currentProduct = allProducts.first { it.permalink==url }
                    if (currentProduct.sku !in listOf(
                            "59393-011", "59041-595", "59370-040", "58696-289", "59392-281", "58585-007",
                            "59433-402", "59401-060", "59340-003", "59401-005", "59340-036", "59413-020",
                            "59414-003", "59195-396", "58956-396", "59181-097", "59193-097", "59194-097",
                            "59195-097", "59294-003", "57140-003", "55954-005", "58218-027", "59060-011",
                            "59237-1278", "58735-004", "59068-561", "59052-004", "59183-514", "59182-514",
                            "59073-514", "59072-514", "59043-051", "58996-005", "59039-281", "59039-003",
                            "58995-051", "59017-004", "59017-281", "58301-003", "59040-004", "58956-097",
                            "58958-396", "58871-465", "58957-097", "58983-005", "58823-488", "57762-003",
                            "57761-003", "58807-004", "58721-003", "58721-016", "58719-001", "58693-003",
                            "58474-396", "58474-097", "57256-003", "58608-003", "58696-003", "58699-041",
                            "58723-040", "58635-016", "58687-003", "58685-003", "58686-003", "58689-003",
                            "58679-005", "58678-006", "58677-007", "58616-003", "58615-012", "58612-006",
                            "58611-012", "58611-003", "57762-396", "58610-003", "58609-003", "58604-006",
                            "58602-003", "58603-003", "58603-012", "58315-027", "57761-396", "57763-396",
                            "58064-040", "57948-003", "57211-060", "57211-013", "58057-002", "58061-332",
                            "58069-029", "57966-003", "58071-011", "58071-029", "58002-003", "58060-003",
                            "57869-005", "57762-097", "57763-097", "57761-097", "57761-576", "57517-004",
                            "57151-023", "57144-060", "57210-060", "57155-003", "57992-097", "58444-027",
                            "58459-028", "58459-561", "57998-003"
                        )
                    ) {
                        val shortDescription = currentProduct.short_description
                        val shortDescriptionWithoutSpecialChars =
                            shortDescription.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ")
                                .replace("  ", " ").replace("&#8220;", "\"").replace("&#8221;", "\"").trim()
                        val ogDescriptionWithoutSpecialChars =
                            ogDescription?.replace("   ", " ")?.replace("  ", " ")?.trim()
                        if (shortDescriptionWithoutSpecialChars==ogDescriptionWithoutSpecialChars) {
                            logError(
                                "SAKIS Yoast: Περιγραφή ίδια με short description",
                                "ERROR: Η σελίδα $url έχει ίδια Yoast meta description με το short description του προϊόντος.",
                                alsoEmail = false
                            )
                            if (useChatGptForSeoSuggestions) {
                                println("DEBUG: Generating Yoast SEO meta description suggestion for $url...")
                                val suggestedYoastMetaDescription = generateSeoDescriptionWithChatGPT(
                                    productTitle = currentProduct.name,
                                    productUrl = url,
                                    shortDescription = shortDescription,
                                    longDescription = currentProduct.description,
                                    imageUrls = currentProduct.images.map { it.src },
                                    openAiApiKey = chatGPTRestApiKey
                                )
                                println("DEBUG: Suggested Meta Description from ChatGPT: $suggestedYoastMetaDescription")
                                if (suggestedYoastMetaDescription=="Skipping - not a dress") {
                                    logError(
                                        "SAKIS chatGPT skipped a product",
                                        "ERROR: Page $url is likely not a dress - review and add to excluded products",
                                        alsoEmail = false
                                    )
                                } else {
                                    updateYoastMetaDescription(currentProduct, suggestedYoastMetaDescription)
                                }
                            }
                        }
                    }
                } else if (url !in productUrls && ogDescription.isNullOrBlank()) {
                    println("DEBUG: Scanning og:description for page: $url")
                    logError(
                        "SAKIS Yoast: Page without meta description",
                        "ERROR: Page $url has no meta description",
                        alsoEmail = false
                    )
                }

                val canonical = json.get("canonical")?.asText()
                // println("DEBUG: SEO canonical $canonical")

                val ogImage = json.get("og_image")?.firstOrNull()
                // println("DEBUG: SEO og image $ogImage")

                val ogImageWidth = ogImage?.get("width")?.asText()
                // println("DEBUG: SEO og image width $ogImageWidth")

                val ogImageHeight = ogImage?.get("height")?.asText()
                // println("DEBUG: SEO og image height $ogImageHeight")

                val ogImageUrl = ogImage?.get("url")?.asText()
                // println("DEBUG: SEO og image url $ogImageUrl")

                val modified = json.get("article_modified_time")?.asText()
                // println("DEBUG: SEO modified $modified")

                if (title?.isBlank()!=false) {
                    logError(
                        "SAKIS Yoast: Σελίδες χωρίς SEO τίτλο",
                        "ERROR: Η σελίδα $url δεν έχει SEO title tag.",
                        alsoEmail = false
                    )
                }

                if (canonical?.isBlank()!=false || !canonical.startsWith("https://")) {
                    logError(
                        "SAKIS Yoast: Κακή canonical",
                        "ERROR: Η σελίδα $url έχει λάθος ή ανύπαρκτο canonical tag.",
                        alsoEmail = false
                    )
                }

                if (robots!=null) {
                    val index = robots.get("index")?.asText() ?: ""
                    val follow = robots.get("follow")?.asText() ?: ""
                    if (index=="noindex" || follow=="nofollow") {
                        logError(
                            "SAKIS Yoast: Robots noindex/nofollow",
                            "ERROR: Η σελίδα $url έχει robots tag με τιμές $index / $follow",
                            alsoEmail = false
                        )
                    }
                }

                if (ogImage==null || ogImageUrl==null || ogImageUrl.isBlank()) {
                    logError(
                        "SAKIS Yoast: Πρόβλημα με og:image",
                        "ERROR: Η σελίδα $url δεν έχει og:image. Check with https://developers.facebook.com/tools/debug",
                        alsoEmail = false
                    )
                } else if (ogImageWidth==null || ogImageWidth.toInt() < 600) {
                    logError(
                        "SAKIS Yoast: Πρόβλημα με og:image width",
                        "ERROR: Η σελίδα $url έχει μικρή og:image ($ogImageWidth px).",
                        alsoEmail = false
                    )
                } else if (ogImageHeight==null || ogImageHeight.toInt() < 600) {
                    logError(
                        "SAKIS Yoast: Πρόβλημα με og:image height",
                        "ERROR: Η σελίδα $url έχει μικρή og:image ($ogImageHeight px).",
                        alsoEmail = false
                    )
                }

                if (modified?.isNotBlank()==true) {
                    // println("DEBUG: modified date $modified")
                    val formatter = DateTimeFormatter.ISO_DATE_TIME
                    val modifiedDate = LocalDateTime.parse(modified, formatter)
                    if (modifiedDate.isBefore(LocalDateTime.now().minusYears(1))) {
                        logError(
                            "SAKIS Yoast: Παλιό περιεχόμενο",
                            "ERROR: Η σελίδα $url έχει παλιό content (last modified: $modified).",
                            alsoEmail = false
                        )
                    }
                }
            } else {
                if (robots!=null) {
                    val index = robots.get("index")?.asText() ?: ""
                    val follow = robots.get("follow")?.asText() ?: ""
                    if (index!="noindex" || follow!="nofollow") {
                        if (!(url=="https://foryoufashion.gr/cart/" && index=="noindex" && follow=="follow" || url=="https://foryoufashion.gr/checkout/" && index=="noindex" && follow=="follow" || url=="https://foryoufashion.gr/my-account/" && index=="noindex" && follow=="follow")) {
                            logError(
                                "SAKIS Yoast: Robots noindex/nofollow",
                                "ERROR: Η σελίδα $url έχει robots tag με τιμές $index / $follow",
                                alsoEmail = false
                            )
                        }
                    }
                }
            }
        }
    }
}

fun updateYoastMetaDescription(product: Product, yoastMetaDescription: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val payload = mapper.writeValueAsString(
        mapOf("meta_data" to listOf(mapOf("key" to "_yoast_wpseo_metadesc", "value" to yoastMetaDescription)))
    )
    val body = payload.toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder()
        .url(url)
        .put(body)
        .header("Authorization", Credentials.basic(writeConsumerKey, writeConsumerSecret))
        .build()

    client.newCall(request).execute().use { response ->
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            println("ERROR: Failed to update Yoast Meta Description for product ${product.id}: $responseBody")
            throw IOException("Unexpected code $response")
        } else {
            println("SUCCESS: Updated Yoast Meta Description for product ${product.sku}")
        }
    }
}

fun generateSeoDescriptionWithChatGPT(
    productTitle: String,
    productUrl: String,
    shortDescription: String,
    longDescription: String,
    imageUrls: List<String>,
    openAiApiKey: String
): String {
    val textContent = """
        You are an expert SEO copywriter for a premium Greek fashion brand (foryoufashion.gr) that sells elegant women’s dresses online. Your task is to generate concise, persuasive **Yoast meta descriptions** (under 155 characters) for individual product pages.
        
        Your writing should reflect a tone of **luxury, femininity, and confidence**, and be tailored to women shopping for dresses for **special occasions** such as weddings, baptisms, galas, and evening events.
        
        ### Your goals:
        - Help each product rank for relevant Greek SEO keywords.
        - Entice users to click by highlighting the dress's uniqueness and appeal.
        - Naturally weave in 2–3 SEO keywords (see list below), without keyword stuffing.
        - Vary your sentence structure across products. Avoid repetition like always starting with “Κομψό φόρεμα...”.
        - Do not repeat the product name or color unless it’s essential.
        - Assume the customer has already seen the product image.
        - Keep it elegant and clear.
        
        ### You are given:
        - A product title
        - A product link
        - A short description (may include HTML)
        - A long description (may include HTML)
        - Image(s) of the product
        - A fixed list of relevant Greek SEO keywords
        
        Return **only the final Greek meta description snippet**, optimized for both SEO and clicks. No commentary or explanation.
        If you cannot fulfill this request because the product is not a dress please reply exactly with: "Skipping - not a dress"
        If for whatever other reason, you cannot fulfill this request, then reply exactly with "I'm sorry, I can't assist with that. Reason: <the reason why you cannot fulfill the request>"
                
        Details:
        Title: $productTitle  
        Link: $productUrl  
        Short description: $shortDescription  
        Long description: $longDescription  
        
        Greek SEO Keywords:  
        βραδινά φορέματα, φορέματα για γάμο, φορέματα για βάπτιση, φορέματα κουμπάρας, επίσημα φορέματα, φορέματα για γαμήλιες δεξιώσεις, κομψά φορέματα για εκδηλώσεις online, μακριά βραδινά φορέματα, plus size φορέματα για γάμο, μοντέρνα φορέματα για εκδηλώσεις, ρούχα χονδρικής online, βραδινά φορέματα χονδρική, for you fashion, foryoufashion.gr, for you φορέματα
""".trimIndent()

    val imageContent = imageUrls.map { imageUrl ->
        mapOf(
            "type" to "image_url",
            "image_url" to mapOf(
                "url" to imageUrl,
                "detail" to "low"
            )
        )
    }

    val contentList = mutableListOf<Map<String, Any>>()
    contentList.add(mapOf("type" to "text", "text" to textContent))
    contentList.addAll(imageContent)

    val body = mapOf(
        "model" to "gpt-4o",
        "messages" to listOf(
            mapOf("role" to "user", "content" to contentList)
        ),
        "temperature" to 0.7
    )

    val jsonBody = mapper.writeValueAsString(body)

    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .header("Authorization", "Bearer $openAiApiKey")
        .header("Content-Type", "application/json")
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .build()

    return executeWithRetry {
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                throw Exception("ERROR: ChatGPT API failed with status ${res.code}")
            }

            val responseJson = mapper.readTree(res.body!!.string())
            responseJson["choices"].first().get("message").get("content").asText().trim()
        }
    }
}

fun checkForMissingToMonteloForaeiTextInDescription(product: Product) {
    if (product.status!="publish" || product.sku in listOf("53860-289-01", "53860-014-01")) {
        return
    }
    val startingCheckDate = LocalDate.of(2024, Month.AUGUST, 9)
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)

    if (productCreationDate.isAfter(startingCheckDate)) {
        // Exclude products that are explicitly one size
        val isOneSize = product.attributes.any {
            it.name.equals("Μέγεθος", ignoreCase = true) &&
                    it.options!!.any { option -> option.equals("One size", ignoreCase = true) }
        }
        if (isOneSize) {
            return
        }
        if ((product.description.contains("μοντελο", ignoreCase = true) ||
                    product.description.contains("μοντέλο", ignoreCase = true))
        ) {
            logError(
                "'Το μοντελο φοραει' σε λαθος περιγραφη",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} περιλαμβάνει πληροφορίες για το μέγεθος που φοράει το μοντέλο στην αναλυτική περιγραφή αντι για τη συντομη\nLINK: ${product.permalink}"
            )
        }
        if (!product.short_description.contains("μοντέλο φοράει", ignoreCase = true)
            && !product.short_description.contains("one size", ignoreCase = true)
        ) {
            logError(
                "Προιον με περιγραφη χωρις το μεγεθος του μοντελου που το φοραει",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν περιλαμβάνει πληροφορίες για το μέγεθος που φοράει το μοντέλο στη σύντομη περιγραφή.\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkForMissingSizeGuide(product: Product) {
//    println("DEBUG: product meta data ${product.meta_data}")
    val specialOccasionCategorySlug = "special-occasion"
    val foremataCategorySlug = "foremata"
    val plusSizeCategorySlug = "plus-size"
    val olosomesFormesCategorySlug = "olosomes-formes"
    val nyfikaForemataCategorySlug = "nyfika-foremata"
    val casualForemataCategorySlug = "casual-foremata"

    val isInSpecialOccasionCategory = product.categories.any { it.slug==specialOccasionCategorySlug }
    val isInForemataCategory = product.categories.any { it.slug==foremataCategorySlug }
    val isInPlusSizeCategory = product.categories.any { it.slug==plusSizeCategorySlug }
    val isInOlosomesFormesCategory = product.categories.any { it.slug==olosomesFormesCategorySlug }
    val isInNyfikaForemataCategory = product.categories.any { it.slug==nyfikaForemataCategorySlug }
    val isIncasualForemataCategory = product.categories.any { it.slug==casualForemataCategorySlug }

    if ((isInSpecialOccasionCategory ||
                isInForemataCategory ||
                isInPlusSizeCategory ||
                isInOlosomesFormesCategory ||
                isInNyfikaForemataCategory) && !isIncasualForemataCategory
    ) {
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
            logError(
                "Προιον χωρις οδηγο μεγεθους",
                "ΣΦΑΛΜΑ: Δεν υπάρχει οδηγός μεγεθών για το προϊόν με SKU ${product.sku}\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkForTyposInProductText(product: Product) {
    val dictionary = setOf("coctail") // Initial dictionary with incorrect words

    val title = product.name
    val shortDescription = product.short_description
    val longDescription = product.description

    checkForTypos(product, "Title", title, dictionary)
    checkForTypos(product, "Short Description", shortDescription, dictionary)
    checkForTypos(product, "Long Description", longDescription, dictionary)
}

fun checkForTypos(product: Product, field: String, content: String, dictionary: Set<String>) {
    val words = content.split("\\s+".toRegex()) // Split into words
    if (dictionary.any { it in words }) {
        logError(
            "Τυπογραφικο λαθος σε προιον",
            "ΣΦΑΛΜΑ: Πιθανό ορθογραφικό λάθος στο πεδίο '$field' του προϊόντος με SKU ${product.sku}\nLINK: ${product.permalink}"
        )
    }
}

private fun checkForEmptyOrShortTitlesOrLongTitles(product: Product) {
    val title = product.name.replace("&amp", "&")
    if (title.isEmpty() || title.length < 20) {
        logError(
            "Προιον με μικρο τιτλο",
            "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει πολύ σύντομο τίτλο (μικροτερο απο 20 χαρακτηρες): '$title'.\nLINK: ${product.permalink}"
        )
    } else if (title.length > 70) { // 65 is the meta ads recommendation
        logError(
            "Προιον με μεγαλο τιτλο",
            "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει πολύ μεγαλο τίτλο (μικροτερο απο 70 χαρακτηρες): '$title'.\nLINK: ${product.permalink}"
        )
    }
}

fun checkNameModelTag(product: Product) {
    if (product.status!="draft"
        && product.sku!="57140-003" && product.sku!="59468-281" && product.sku!="59469-281" && product.sku!="57630-556"
        && product.sku!="58747-246" && product.sku!="56068-396" && product.sku!="55627-027" && product.sku!="58205-007"
        && product.sku!="58746-246" && product.sku!="55627-016" && product.sku!="58722-436" && product.sku!="58727-040"
        && product.sku!="58458-556"
    ) {
        val formatter = DateTimeFormatter.ISO_DATE_TIME
        val targetDate = LocalDate.of(2024, 9, 20) // When we started implementing this
        val productCreationDate = LocalDate.parse(product.date_created, formatter)
        if (productCreationDate.isAfter(targetDate)) {
            val hasModelTag = product.tags.any { tag -> tag.name.startsWith("ΜΟΝΤΕΛΟ", ignoreCase = true) }
            if (!hasModelTag) {
                logError(
                    "Προιον χωρις ετικετα με ονομα μοντελου",
                    "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν έχει ετικετα με ονομα μοντέλου.\nLINK: ${product.permalink}"
                )
            }
        }
    }
}

fun checkFwtografisiStudioEkswterikiTag(product: Product) {
    if (product.status!="draft") {
        val hasFwtografisiTag = product.tags.any { tag -> tag.name.startsWith("ΦΩΤΟΓΡΑΦΙΣΗ", ignoreCase = true) }
        if (!hasFwtografisiTag) {
            logError(
                "Προιον χωρις ετικετα με τοποθεσια φωτογραφησης",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν έχει ετικετα με τοποθεσια φωτογραφησης.\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkPhotoshootTags(product: Product, credentials: String) {
    val genericFwtografisiTagSlug = "fwtografisi-ekswteriki"
    val genericFwtografisiTagName = "ΦΩΤΟΓΡΑΦΙΣΗ ΕΞΩΤΕΡΙΚΗ"

    // Check for tags that start with "ΦΩΤΟΓΡΑΦΙΣΗ ΕΞΩΤΕΡΙΚΗ"
    val specificPhotoshootTags =
        product.tags.filter { it.name.startsWith("ΦΩΤΟΓΡΑΦΙΣΗ ΕΞΩΤΕΡΙΚΗ ", ignoreCase = true) }

    // Check if the generic tag exists
    val hasGenericTag = product.tags.any { it.name.equals(genericFwtografisiTagName, ignoreCase = true) }

    if (specificPhotoshootTags.isNotEmpty() && !hasGenericTag) {
        logError(
            "SAKIS checkPhotoshootTags (actioned automatically)",
            "ERROR: Product SKU ${product.sku} has specific fwtografisi tag but is missing the generic tag '$genericFwtografisiTagName'.",
            alsoEmail = false
        )
        println("LINK: ${product.permalink}")
        if (shouldAddGenericPhotoshootTag) {
            println("ACTION: Adding generic tag '$genericFwtografisiTagName' to product SKU ${product.sku}.")
            addGenericPhotoshootTag(product, genericFwtografisiTagSlug, credentials)
        }
    }

    // Check if the generic tag exists but a specific tag is missing
    if (hasGenericTag && specificPhotoshootTags.isEmpty() &&
        product.sku !in listOf( // Old external photoshoots - ignore
            "55627-027", "57630-556", "59050-596", "55627-465", "58980-054", "58981-054", "58977-054", "58979-054",
            "58149-029", "59047-031", "59049-013", "58978-054", "58976-054", "58974-054", "57384-556", "56068-029",
            "58317-009", "58315-027", "57637-530", "57394-157", "57385-488", "57010-019", "57005-281", "56072-003",
            "56662-465", "58172-007", "56818-488", "55627-007", "56065-007"
        )
    ) {
        logError(
            "Προιον χωρις ετικετα εξωτερικης φωτογραφισης",
            "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει τη γενικό ετικετα '$genericFwtografisiTagName' αλλά λείπει η ετικετα συγκεκριμένης τοποθεσίας φωτογράφισης.\nLINK: ${product.permalink}"
        )
    }
}

fun addGenericPhotoshootTag(product: Product, genericTagSlug: String, credentials: String) {
    val genericTag = Tag(id = 1577, slug = genericTagSlug, name = "ΦΩΤΟΓΡΑΦΙΣΗ ΕΞΩΤΕΡΙΚΗ")
    val updatedTags = product.tags.toMutableList().apply { add(genericTag) }
    val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))

    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val body = data.toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                println("Error adding generic tag to product ${product.id}: $responseBody")
                throw IOException("Unexpected code $response")
            } else {
                println("ACTION: Generic tag added to product ${product.id}.")
            }
        }
    }
}

private fun checkForInvalidDescriptions(product: Product, credentials: String) {
    if (product.status!="draft" && product.status!="private") {
        //    println("DEBUG: long description ${product.description}")
        //    println("DEBUG: short description ${product.short_description}")
        val hasValidHtmlInShortDescription = isValidHtml(product.short_description)
        val hasValidHtmlInLongDescription = isValidHtml(product.description)
        if (!hasValidHtmlInShortDescription || !hasValidHtmlInLongDescription) {
            logError(
                "SAKIS Invalid HTML",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει invalid HTML στις περιγραφες.\nLINK: ${product.permalink}"
            )
        }
        if (product.short_description.equals(product.description, ignoreCase = true)) {
            logError(
                "Προιον με ιδιες συντομες και κανονικες περιγραφες",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει τις ίδιες σύντομες και κανονικες περιγραφες.\nLINK: ${product.permalink}"
            )
        }
        val startTargetDate = LocalDate.of(2025, 4, 20)
        val productCreationDate = LocalDate.parse(product.date_created, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        if (productCreationDate.isAfter(startTargetDate)) {
            val shortDescriptionLength = product.short_description.replace(Regex("<[^>]*>"), "").replace("\n","").trim().length
            if (shortDescriptionLength < 140 || shortDescriptionLength > 305) {
                logError(
                    "Προιον με συντομη περιγραφη προιοντος με λαθος μηκος",
                    "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει σύντομη περιγραφή με μη έγκυρο μήκος (${shortDescriptionLength} χαρακτήρες)" +
                            "\n Πρεπει να ειναι απο 140 μεχρι 300 χαρακτηρες. Μετρησε το εδω https://www.charactercountonline.com/\n LINK: ${product.permalink}."
                )
            }
        }
        if (product.sku !in listOf(
                "51746-332", "59431-1282", "58736-005", "58736-001", "59370-003", "59041-595", "59182-514",
                "59073-514", "59072-514", "58072-040", "51746-332", "53860-015", "53860-014", "53860-289",
                "58746-246", "59346-002", "59356-054", "59356-003", "59435-369", "59345-022", "59388-1278",
                "59343-003", "55954-007", "59260-005", "59260-001", "59441-003", "58735-003", "59238-003",
                "58593-005", "59235-005", "59235-003", "59235-004", "58580-036", "59434-003", "59432-003",
                "59432-369", "58585-007", "59433-402", "55954-005", "59237-1278", "58735-004", "59017-004",
                "59017-281", "58301-003", "57967-003", "58807-004", "58693-003", "58691-004", "57256-003",
                "58608-003", "58635-016", "58685-003", "58686-003", "58602-003", "57966-003", "58002-003",
                "57869-005", "58747-246", "59427-022", "59427-003", "59425-051", "59425-005", "58072-040",
                "59323-013", "59422-005", "59422-012", "59422-010", "59440-369", "59440-003", "59426-002",
                "59436-060", "59423-027", "59439-369", "59431-1282", "59438-003", "59450-002", "59438-060",
                "59458-005", "59454-587", "59412-1274", "59411-003", "59327-281", "59337-529", "59394-003",
                "59430-007", "59393-011", "59396-003", "58736-005", "58736-001", "59397-020", "59370-003",
                "59395-011", "59041-595", "59370-040", "58696-289", "59392-281", "59401-060", "59340-003",
                "59401-005", "59340-036", "59404-251", "59419-281", "59403-005", "59442-005", "59407-587",
                "59413-020", "59414-003", "59416-251", "59459-003", "59409-003", "59410-003", "59410-020",
                "59332-005", "59329-561", "59333-588", "59330-1282", "59331-567", "59324-002", "59338-501",
                "59335-002", "59342-003", "59183-514", "59182-514", "59072-514", "59028-004", "59043-051",
                "59016-019", "58996-005", "59039-281", "59039-003", "58995-051", "59040-004", "59020-023",
                "58983-005", "58823-488", "58721-003", "58721-016", "58719-001", "58621-012", "58696-003",
                "58699-041", "58723-040", "58684-006", "58630-011", "58682-003", "58681-006", "58619-005",
                "58620-006", "58618-006", "58064-040", "57948-003", "57211-060", "57211-013", "58057-002",
                "58071-011", "58071-029", "57210-060", "57155-003", "58689-003", "58679-005", "58678-006", "58616-003",
                "58615-012", "58612-006", "58611-012", "58611-003", "58610-003", "58609-003", "58604-006",
                "58603-003", "58603-012", "58061-332", "58069-029", "57998-003", "58060-003", "57517-004",
                "57151-023", "58687-003", "57144-060", "9101", "7489", "58677-007", "58001-003", "56405-011"
            )
        ) {
            val longDescriptionLength = product.description.replace(Regex("<[^>]*>"), "").replace("\n","").trim().length
            if (longDescriptionLength < 250 || longDescriptionLength > 550) {
                logError(
                    "Προιον με περιγραφη προιοντος με λαθος μηκος",
                    "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει περιγραφή με μη έγκυρο μήκος (${longDescriptionLength} χαρακτήρες)" +
                            "\n Πρεπει να ειναι απο 250 μεχρι 550 χαρακτηρες. Μετρησε το εδω https://www.charactercountonline.com/\n LINK: ${product.permalink}."
                )
            }
        }
        if (product.description.contains("&nbsp;")) {
            logError(
                "SAKIS checkForInvalidDescriptions 3",
                "ERROR: Product ${product.sku} has unnecessary line breaks in description",
                alsoEmail = false
            )
            println("LINK: ${product.permalink}")
            if (shouldRemoveEmptyLinesFromDescriptions) {
                updateProductDescriptions(
                    product, product.short_description, product.description.replace("&nbsp;", ""), credentials
                )
            }
        }
        if (product.short_description.contains("&nbsp;")) {
            logError(
                "Resolved automatically - checkForInvalidDescriptions 4",
                "ERROR: Product ${product.sku} has unnecessary line breaks in short description",
                alsoEmail = false
            )
            println("LINK: ${product.permalink}")
            if (shouldRemoveEmptyLinesFromDescriptions) {
                updateProductDescriptions(
                    product, product.short_description.replace("&nbsp;", ""), product.description, credentials
                )
            }
        }
    }
}

private fun checkForStockManagementAtProductLevelOutOfVariations(product: Product) {
    if (product.variations.isNotEmpty() && product.manage_stock) {
        logError(
            "SAKIS checkForStockManagementAtProductLevelOutOfVariations",
            "ERROR: Product ${product.sku} is a variable product with stock management at the product level. It should be only at the variation level",
            alsoEmail = false
        )
    }
}

fun checkAllVariationsAreEnabled(product: Product, productVariations: List<Variation>) {
    for (variation in productVariations) {
        if (variation.status.lowercase()!="publish") {
            logError(
                "Απενεργοποιημένη παραλλαγή",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει παραλλαγή (${variation.sku}) που είναι απενεργοποιημένη.\nLINK: ${product.permalink}"
            )
        }
    }
}

fun checkStockManagementAtVariationLevel(variation: Variation, product: Product) {
    if (!variation.manage_stock) {
        logError(
            "SAKIS checkStockManagementAtVariationLevel",
            "ERROR: Variation SKU ${variation.sku} of product SKU ${product.sku} does not have 'Manage stock?' enabled for stock management at the variation level.",
            alsoEmail = false
        )
        println("LINK: ${product.permalink}")
    }
}

private fun checkForWrongPricesAndUpdateEndTo99(product: Product, variation: Variation, credentials: String) {
    if (product.status!="draft") {
        val regularPriceString = variation.regular_price
        val salePriceString = variation.sale_price
        val regularPrice = regularPriceString.toDoubleOrNull()
        val salePrice = salePriceString.toDoubleOrNull()
        if (regularPrice==null || regularPrice <= 0) {
            logError(
                "Λαθος αρχικη τιμη για προιον",
                "ΣΦΑΛΜΑ: Η αρχικη τιμή για τη παραλλαγή ${variation.sku} είναι λαθος.\nLINK: ${product.permalink}"
            )
            return
        }
        if (salePrice!=null && salePrice <= 0) {
            logError(
                "Λαθος εκπτωσιακη τιμη για προιον",
                "ΣΦΑΛΜΑ: Η εκπτωσιακη τιμή για τη παραλλαγή ${variation.sku} είναι λαθος.\nLINK: ${product.permalink}"
            )
            return
        }
        if (salePrice!=null && salePrice >= regularPrice) {
            logError(
                "Λαθος εκπτωσιακη τιμη για προιον",
                "ΣΦΑΛΜΑ: Η εκπτωσιακη τιμη για τη παραλλαγή ${variation.sku} ($salePrice) δεν μπορεί να είναι μεγαλύτερη ή ίση με την κανονική τιμή ($regularPrice).\nLINK: ${product.permalink}"
            )
            return
        }

        if (regularPriceString.isNotEmpty() && !priceHasCorrectPennies(regularPriceString)) {
            logError(
                "Resolved automatically - checkForWrongPricesAndUpdateEndTo99 4",
                "ERROR: product SKU ${product.sku} regular price $regularPriceString has incorrect pennies",
                alsoEmail = false
            )
            if (shouldUpdatePricesToEndIn99) {
                val updatedRegularPrice = adjustPrice(regularPriceString.toDouble())
                if (updatedRegularPrice!=regularPriceString) {
                    println("ACTION: Updating product SKU ${product.sku} variation SKU ${variation.sku} regular price from $regularPriceString to $updatedRegularPrice")
                    if (isSignificantPriceDifference(
                            regularPriceString.toDouble(),
                            updatedRegularPrice.toDouble()
                        )
                    ) {
                        logError(
                            "SAKIS checkForWrongPricesAndUpdateEndTo99 5",
                            "ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process.",
                            alsoEmail = false
                        )
                        exitProcess(1)
                    }
                    updateProductPrice(
                        product.id,
                        variation.id,
                        updatedPrice = updatedRegularPrice,
                        PriceType.REGULAR_PRICE, credentials
                    )
                    println("LINK: ${product.permalink}")
                }
            }
        }
        if (salePriceString.isNotEmpty() && !priceHasCorrectPennies(salePriceString)) {
            logError(
                "Resolved automatically - checkForWrongPricesAndUpdateEndTo99 6",
                "ERROR: product SKU ${product.sku} salePrice price $salePriceString has incorrect pennies",
                alsoEmail = false
            )
            if (shouldUpdatePricesToEndIn99) {
                val updatedSalePrice = adjustPrice(salePriceString.toDouble())
                println("ACTION: Updating product SKU ${product.sku} variation SKU ${variation.sku} sale price from $salePriceString to $updatedSalePrice")
                if (isSignificantPriceDifference(salePriceString.toDouble(), updatedSalePrice.toDouble())) {
                    logError(
                        "SAKIS checkForWrongPricesAndUpdateEndTo99 7",
                        "ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process.",
                        alsoEmail = false
                    )
                    exitProcess(1)
                }
                updateProductPrice(product.id, variation.id, updatedSalePrice, PriceType.SALE_PRICE, credentials)
                println("LINK: ${product.permalink}")
            }
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
    return when {
        // if the price ends in .99, just return it
        price % 1==0.9899999999999949 -> {
            String.format("%.2f", price)
        }

        price % 1==0.0 -> {
            // If the price ends in .00, remove 1 cent
            String.format("%.2f", price - 0.01)
        }

        else -> {
            // If the price ends in any random decimal, e.g. 18,42 go to 17.99
            String.format("%.2f", floor(price - 1) + 0.99)
        }
    }
}

private fun adjustPriceAbove70(price: Double): String {
    // Above 70 just strip the decimals
    return String.format("%.0f", floor(price))
}

fun updateProductPrice(
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
            logError(
                "Προιον με οριζοντιες φωτογραφιες",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει εικόνα που δεν είναι κατακόρυφη.\nLINK: ${product.permalink}"
            )
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
    Thread.sleep(200)
    file.printWriter().use { writer ->
        cache.forEach { (url, dimensions) ->
            writer.println("${url},${dimensions.first},${dimensions.second}")
        }
    }
}

private fun getImageDimensions(imageUrl: String): Pair<Int, Int> {
    registerWebPReader()  // Register WebP support
    println("DEBUG: downloading image for dimensions: $imageUrl")
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

fun checkForProductsWithImagesNotInMediaLibrary(product: Product, allMedia: List<Media>) {
    val allMediaUrls = allMedia.map { it.source_url }
    for (image in product.images) {
        // println("DEBUG: Processing ${image.src}")
        if (image.src !in allMediaUrls) {
            logError(
                "SAKIS checkForProductsWithImagesNotInMediaLibrary 1",
                "ERROR: Product SKU ${product.sku} is using an image that is not in the media library: ${image.src}",
                alsoEmail = false
            )
            println("LINK: ${product.permalink}")
        }
    }
}

private fun checkForMissingImages(product: Product) {
    if (product.status!="private" && product.status!="draft" && product.sku !in listOf(
            "58747-246", "58746-246", "59183-514", "59182-514", "59117-012", "59050-596", "59043-051", "59040-004",
            "58983-005", "56062-097", "56062-443", "56062-029", "55627-443", "58210-007", "58721-016", "58691-004",
            "58608-003", "58700-011", "58631-022", "58687-003", "58681-006", "58615-012", "58603-003", "58464-013",
            "58420-009", "58407-556", "58154-097", "58248-012", "58224-488", "57719-332", "56072-003", "56114-003",
        )
    ) {
        val images = product.images
        val mainImageMissing = images.isEmpty() || images[0].src.isEmpty()
        val galleryImagesMissing = images.size < 3

        if (mainImageMissing) {
            logError(
                "Προιν χωρις αρχικη φωτογραφια",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν έχει κύρια εικόνα.\nLINK: ${product.permalink}"
            )
        }

        if (galleryImagesMissing) {
            logError(
                "Προιον με λιγες φωτογραφιες",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} έχει μόνο ${images.size} εικόνες.\nLINK: ${product.permalink}"
            )
        }

        // A product might have an image that is not part of the media library, and that might return a 404.
        val cache = loadMediaCache()
        val today = LocalDate.now()
        images.forEach { image ->
            val cachedEntry = cache[image.src]
            val isFileMissing = if (cachedEntry!=null && !isCacheExpired(cachedEntry.lastChecked, today)) {
                cachedEntry.isFileMissing
            } else {
                val fileExists = checkIfImageExists(image.src)
                cache[image.src] = MediaCacheEntry(image.src, !fileExists, today)
                !fileExists
            }
            if (isFileMissing) {
                logError(
                    "SAKIS checkForMissingImages 2",
                    "ERROR: Product ${product.sku} has an image with URL ${image.src} that does not exist",
                    alsoEmail = false
                )
            }
        }
        saveMediaCache(cache)
    }
}

private fun checkForDuplicateImagesInAProduct(product: Product) {
    val images = product.images
    if (images.isNotEmpty()) {
        val imageUrls = images.map { it.src }
        val uniqueUrls = imageUrls.distinct()

        if (imageUrls.size!=uniqueUrls.size) {
            logError(
                "Διπλές εικόνες σε προϊόν",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} περιέχει διπλές εικόνες.\nLINK: ${product.permalink}"
            )
        }
    }
}

private fun getProducts(page: Int, credentials: String): List<Product> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products?page=$page&per_page=100"
    return executeWithRetry {
        println("DEBUG: Performing request to get products, page $page")
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            mapper.readValue(response.body?.string() ?: "")
        }
    }
}

fun getVariations(productId: Int, credentials: String): List<Variation> {
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

private fun updateProductDescriptions(
    product: Product, updatedShortDescription: String, updatedDescription: String, credentials: String
) {
    println("ACTION: Updating descriptions for product ${product.sku}")
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val json = mapper.writeValueAsString(
        mapOf(
            "short_description" to updatedShortDescription,
            "description" to updatedDescription
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

fun checkForMissingPromitheutisTag(product: Product) {
    if (product.status!="private" && product.status!="draft" && product.sku !in listOf(
            "3821", "9101", "2345", "8Ε0053", "3858", "3885", "5832", "7489", "59705-606"
        )
    ) {
        val hasTag = product.tags.any { it.name.startsWith("ΠΡΟΜΗΘΕΥΤΗΣ_") }
        if (!hasTag) {
            logError(
                "Προϊόν χωρίς ετικέτα προμηθευτή",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} δεν έχει ετικέτα προμηθευτή.\nLINK: ${product.permalink}",
            )
        }
    }
}

fun updateNeesAfikseisProducts(products: List<Product>, credentials: String) {
    val nonDraftProducts = products.filter { it.status!="draft" }
    val today = LocalDate.now()
    val sixWeeksAgo = today.minusWeeks(6)
    val formatter = DateTimeFormatter.ISO_DATE_TIME
    val neesAfikseisTag = Tag(id = 1555, slug = "nees-afikseis", name = "Νέες Αφίξεις")
    val excludedCategorySlug = "papoutsia"

    val newProductsInTheLast8Weeks = nonDraftProducts.filter { product ->
        val productDate = LocalDate.parse(product.date_created, formatter)
        val isInExcludedCategory = product.categories.any { it.slug==excludedCategorySlug }
        productDate.isAfter(sixWeeksAgo) && !isInExcludedCategory
    }.sortedByDescending { product ->
        LocalDate.parse(product.date_created, formatter)
    }

    val finalNewProductsList = if (newProductsInTheLast8Weeks.size >= 80) {
        newProductsInTheLast8Weeks
    } else {
        val additionalProducts = nonDraftProducts.filter { product ->
            val isInExcludedCategory = product.categories.any { it.slug==excludedCategorySlug }
            product !in newProductsInTheLast8Weeks && !isInExcludedCategory
        }.sortedByDescending { product ->
            LocalDate.parse(product.date_created, formatter)
        }
        newProductsInTheLast8Weeks + additionalProducts.take(80 - newProductsInTheLast8Weeks.size)
    }

    nonDraftProducts.forEach { product ->
        val hasNeesAfikseisTag = product.tags.any { it.slug==neesAfikseisTag.slug }
        val shouldHaveNeesAfikseisTag = finalNewProductsList.contains(product)

        when {
            !hasNeesAfikseisTag && shouldHaveNeesAfikseisTag -> {
                if (product.sku!="59129-082") { // Product not in correct order - skip
                    addNeesAfikseisTag(product, neesAfikseisTag, credentials)
                }
            }

            hasNeesAfikseisTag && !shouldHaveNeesAfikseisTag -> {
                removeNeesAfikseisTag(product, neesAfikseisTag, credentials)
            }
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

fun addNeesAfikseisTag(product: Product, neesAfikseisTag: Tag, credentials: String) {
    val updatedTags = product.tags.toMutableList().apply { add(neesAfikseisTag) }
    val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val body = data.toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                println("Error adding 'nees-afikseis' tag to product ${product.id}: $responseBody")
                throw IOException("Unexpected code $response")
            } else {
                println("ACTION: Tag 'nees-afikseis' added to product ${product.id}")
            }
        }
    }
}

fun removeNeesAfikseisTag(product: Product, neesAfikseisTag: Tag, credentials: String) {
    val updatedTags = product.tags.filter { it.slug!=neesAfikseisTag.slug }
    val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
    val body = data.toRequestBody("application/json".toMediaTypeOrNull())
    val request = Request.Builder().url(url).put(body).header("Authorization", credentials).build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                println("Error removing 'nees-afikseis' tag from product ${product.id}: $responseBody")
                throw IOException("Unexpected code $response")
            } else {
                println("ACTION: Tag 'nees-afikseis' removed from product ${product.id}")
            }
        }
    }
}

private fun addProsforesTagForDiscountedProductsAndRemoveItForRest(
    product: Product, variation: Variation, addTagAboveThisDiscount: Int, credentials: String
) {
    if (product.status!="draft") {
        val prosforesTag = Tag(id = 1552, slug = "prosfores", name = "Προσφορές")
        val regularPrice = variation.regular_price.toDoubleOrNull()
        val salePrice = variation.sale_price.toDoubleOrNull()
//        println("DEBUG: variation regular price $regularPrice")
//        println("DEBUG: variation sale price $salePrice")
        if (regularPrice!=null && regularPrice > 0) {
            val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
            if (salePrice!=null) {
                val discountPercentage = ((regularPrice - salePrice) / regularPrice * 100).roundToInt()
//                println("DEBUG: discount found $discountPercentage%")
                if (discountPercentage >= addTagAboveThisDiscount) {
                    addTagProsfores(product, prosforesTag, url, credentials)
                } else {
                    removeTagProsfores(product, prosforesTag, url, credentials)
                }
            } else {
                removeTagProsfores(product, prosforesTag, url, credentials)
            }
        } else {
            logError(
                "SAKIS addProsforesTagForDiscountedProductsAndRemoveItForRest 1",
                "ERROR: Could not determine discount because regular price empty or negative",
                alsoEmail = false
            )
        }
    }
}

private fun addTagProsfores(product: Product, prosforesTag: Tag, url: String, credentials: String) {
    if (!product.tags.any { it.slug==prosforesTag.slug }) {
        val updatedTags = product.tags.toMutableList()
        updatedTags.add(prosforesTag)
        val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
        println("ACTION: Adding Tag prosfores for product ${product.sku}")
        // println("DEBUG: Updating product ${product.id} with tags: $data")
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
//        println("DEBUG: Prosfores tag doesn't exist anyway")
    }
}

private fun removeTagProsfores(product: Product, prosforesTag: Tag, url: String, credentials: String) {
    if (product.tags.any { it.slug==prosforesTag.slug }) {
        val updatedTags = product.tags.filter { it.slug!=prosforesTag.slug }
        val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
        println("ACTION: Removing Tag prosfores for product ${product.sku}")
        println("DEBUG: Updating product ${product.id} with tags: $data")
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
//        println("DEBUG: Prosfores tag doesn't exist anyway")
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
            logError(
                "SAKIS categories with few products",
                "ERROR: Category '${category.name}' contains only $productCount products.",
                alsoEmail = false
            )
        }
    }
}


private fun checkProductAttributes(allAttributes: List<Attribute>, credentials: String) {
    for (attribute in allAttributes) {
        val attributeName = attribute.name
        if (attributeName!="Brand") {
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
                    logError(
                        "SAKIS attributes with few products",
                        "ERROR: Attribute '$attributeName' with term '${term.name}' contains only $productCount products.",
                        alsoEmail = false
                    )
                }
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
        if (tag.name=="ΕΤΟΙΜΟ ΓΙΑ ΑΝΕΒΑΣΜΑ") continue
        val productCount = tag.count
        if (productCount!! < 10) {
            logError(
                "SAKIS tags with few products",
                "ERROR: Tag '${tag.name}' only contains $productCount products.",
                alsoEmail = false
            )
        }
    }
}

private fun checkForNonSizeAttributesUsedForVariationsEgColour(product: Product) {
    product.attributes.let { attributes ->
        if (attributes.none { it.variation }) {
            logError(
                "SAKIS checkForNonSizeAttributesUsedForVariationsEgColour 1",
                "ERROR: Product ${product.sku} has no attributes used for variations.",
                alsoEmail = false
            )
            println("LINK: ${product.permalink}")
        }
        attributes.forEach { attribute ->
            if (!attribute.name.equals("Μέγεθος", ignoreCase = true) && attribute.variation) {
                logError(
                    "SAKIS checkForNonSizeAttributesUsedForVariationsEgColour 2",
                    "ERROR: Product ${product.sku} has the '${attribute.name}' attribute marked as used for variations.",
                    alsoEmail = false
                )
                println("LINK: ${product.permalink}")
            }
        }
    }
}

fun checkForMissingSizeVariations(product: Product, productVariations: List<Variation>, credentials: String) {
    val sizeAttributeName = "Μέγεθος"
    val sizeAttribute = product.attributes.find { it.name.equals(sizeAttributeName, ignoreCase = true) }
    val availableSizes = sizeAttribute?.options ?: emptyList()

    availableSizes.forEach { size ->
        val variationExists = productVariations.any { variation ->
            variation.attributes.any { attr ->
                attr.name.equals(sizeAttributeName, ignoreCase = true) && attr.option.equals(
                    size,
                    ignoreCase = true
                )
            }
        }

        if (!variationExists) {
            logError(
                "SAKIS checkForMissingSizeVariations 1",
                "ERROR: Product SKU ${product.sku} is missing a variation for size $size.",
                alsoEmail = false
            )
            println("LINK: ${product.permalink}")
        }
    }
}

fun checkPrivateProductsInStock(product: Product, productVariations: List<Variation>) {
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)
    val threeMonthsAgo = LocalDate.now().minusMonths(3)

    if (product.status=="private" && productCreationDate.isBefore(threeMonthsAgo)) {
        val anyVariationInStock = productVariations.any { it.stock_status=="instock" }
        if (anyVariationInStock) {
            logError(
                "Private προϊόν σε στοκ",
                "ΣΦΑΛΜΑ: Το προϊόν με SKU ${product.sku} είναι ιδιωτικο, αλλά κάποια παραλλαγή του είναι σε στοκ.\nLINK: ${product.permalink}"
            )
        }
    }
}


fun checkForOldProductsThatAreOutOfStockAndMoveToPrivate(
    product: Product, productVariations: List<Variation>, credentials: String
) {
    if (product.status!="private" && product.status!="draft" && product.sku !in listOf("57851-443")) {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoYearsAgo = LocalDate.now().minus(2, ChronoUnit.YEARS)
        if (productDate.isBefore(twoYearsAgo)) {
            val allOutOfStock = productVariations.all { it.stock_status=="outofstock" }
            if (allOutOfStock) {
                logError(
                    "Παλιο προιον out of stock",
                    "ΣΦΑΛΜΑ: Το προιον ${product.sku} ειναι out of stock σε ολα τα μεγεθη και δημιουργηθηκε παραπανω απο 2 χρονια πριν\nLINK: ${product.permalink}\nΜεταφερθηκε αυτοματα στα απορρητα προιοντα",
                )
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
            println("ACTION: Successfully updated product ${product.sku} status to 'private'.")
        }
    }
}


fun checkPluginsList(wordPressWriteCredentials: String) {
    val plugins = fetchInstalledPlugins(wordPressWriteCredentials)
    val pluginsFile = File(PLUGINS_FILE_PATH)
    if (!pluginsFile.exists()) {
        savePluginsToFile(plugins, pluginsFile)
        println("ACTION: Plugins file created with the current list of installed plugins.")
    } else {
        val storedPlugins = loadPluginsFromFile(pluginsFile)
        checkForPluginChanges(storedPlugins, plugins, pluginsFile)
    }
}

fun fetchInstalledPlugins(wordPressWriteCredentials: String): List<Plugin> {
    val url = "https://foryoufashion.gr/wp-json/wp/v2/plugins"
    val client = OkHttpClient()
    val request = Request.Builder()
        .url(url)
        .header("Authorization", "Basic $wordPressWriteCredentials")
        .build()

    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")
        jacksonObjectMapper().readValue(response.body?.string() ?: "")
    }
}

fun savePluginsToFile(plugins: List<Plugin>, file: File) {
    file.writeText(jacksonObjectMapper().writeValueAsString(plugins))
}

fun loadPluginsFromFile(file: File): List<Plugin> {
    return jacksonObjectMapper().readValue(file)
}

fun checkForPluginChanges(storedPlugins: List<Plugin>, currentPlugins: List<Plugin>, pluginsFile: File) {
    val objectMapper = jacksonObjectMapper()

    // Load the stored plugins as a list of maps to preserve unknown fields like "sakis_description"
    val storedPluginsJson = objectMapper.readValue(pluginsFile, List::class.java) as List<Map<String, Any>>

    val newPlugins = currentPlugins.filterNot { plugin ->
        storedPlugins.any { stored -> stored.name==plugin.name }
    }

    val deletedPlugins = storedPlugins.filterNot { stored ->
        currentPlugins.any { plugin -> plugin.name==stored.name } || stored.status=="deleted"
    }

    val updatedPlugins = currentPlugins.filter { current ->
        storedPlugins.any { stored -> stored.name==current.name && stored.version!=current.version }
    }

    val disabledPlugins = currentPlugins.filter { current ->
        storedPlugins.any { stored -> stored.name==current.name && stored.status!=current.status }
    }

    if (newPlugins.isNotEmpty()) {
        logError(
            "SAKIS checkForPluginChanges 1",
            "ERROR: The following plugins have been installed:",
            alsoEmail = false
        )
        newPlugins.forEach {
            println("==========")
            println(it.name)
            println("v${it.version}")
            println(it.status)
            println(it.description.rendered)
            println("==========")
        }
    }

    if (deletedPlugins.isNotEmpty()) {
        logError(
            "SAKIS checkForPluginChanges 2",
            "ERROR: The following ${deletedPlugins.size} plugins have been deleted:",
            alsoEmail = false
        )
        deletedPlugins.forEach { println("- ${it.name} v${it.version}") }
    }

    if (updatedPlugins.isNotEmpty()) {
        logError(
            "SAKIS checkForPluginChanges 3",
            "ERROR: The following ${updatedPlugins.size} plugins have been updated:",
            alsoEmail = false
        )
        updatedPlugins.forEach { println("- ${it.name} updated to v${it.version}") }

        // Update the versions in storedPlugins
        val updatedStoredPlugins = storedPluginsJson.map { storedJson ->
            val storedName = storedJson["name"] as String
            val matchingCurrent = currentPlugins.find { it.name==storedName }

            if (matchingCurrent!=null && matchingCurrent.version!=storedJson["version"]) {
                // Preserve the sakis_description field if it exists
                val sakisDescription = storedJson["sakis_description"]

                // Create an updated plugin object, keeping the sakis_description
                mapOf(
                    "name" to storedName,
                    "version" to matchingCurrent.version,
                    "status" to storedJson["status"],
                    "description" to storedJson["description"],
                    "sakis_description" to sakisDescription
                )
            } else {
                storedJson
            }
        }

        // Save the updated plugins to the file
        objectMapper.writeValue(pluginsFile, updatedStoredPlugins)
        println("ACTION: Plugin versions updated in the plugins file, preserving sakis_description.")
    }

    if (disabledPlugins.isNotEmpty()) {
        logError(
            "SAKIS checkForPluginChanges 4",
            "ERROR: The following plugins have been enabled/disabled:",
            alsoEmail = false
        )
        disabledPlugins.forEach { println("- ${it.name} updated to ${it.status}") }
    }
}

fun fetchAllOrders(credentials: String): List<Order> {
    val client = OkHttpClient()
    val orders = mutableListOf<Order>()
    var page = 1
    var hasMoreOrders: Boolean

    do {
        println("DEBUG: Fetching orders page $page")
        // Add the `after` parameter to the URL to fetch orders created after the calculated date
        val url = "https://foryoufashion.gr/wp-json/wc/v3/orders?page=$page&per_page=100"
        val request = Request.Builder().url(url).header("Authorization", credentials).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string()!!
            val mapper = jacksonObjectMapper()
            val fetchedOrders: List<Order> = mapper.readValue(body)
            orders.addAll(fetchedOrders)
            hasMoreOrders = fetchedOrders.isNotEmpty()
        }
        page++
    } while (hasMoreOrders)
    return orders
}

fun checkPaymentMethodsInLastMonths(orders: List<Order>) {
    val numberOfMonths = 3L
    val allPaymentMethods = listOf(
        "Αντικαταβολή",
        "Κάρτα",
        "Klarna",
        "Google Pay",
        "PayPal",
        "Άμεση Τραπεζική Μεταφορά",
        "Apple Pay",
    )

    val dateFormatter = DateTimeFormatter.ISO_DATE_TIME
    val xMonthsAgo = LocalDate.now().minusMonths(numberOfMonths)

    val usedPaymentMethods = mutableSetOf<String>()

    orders.forEach { order ->
        val orderDate = LocalDate.parse(order.date_created, dateFormatter)
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

        if (orderDate.isAfter(xMonthsAgo)) {
            usedPaymentMethods.add(paymentMethod)
        }
    }

    allPaymentMethods.forEach { method ->
        if (!usedPaymentMethods.contains(method)) {
            logError(
                "SAKIS checkPaymentMethodsInLastMonths 1",
                "ERROR: The payment method '$method' has not been used in the last $numberOfMonths months.",
                alsoEmail = false
            )
        }
    }
}

fun checkSimilarProductColoursUpsellsForSameSkuPrefix(allProducts: List<Product>, credentials: String) {
    val idToSkuMap = allProducts.associateBy({ it.id }, { it.sku })
    val productsGroupedByPrefix = allProducts.groupBy { it.sku.substringBefore('-') }
    productsGroupedByPrefix.forEach { (prefix, productsWithSamePrefix) ->
        productsWithSamePrefix.forEach { product ->
            val currentUpsellIds = product.upsell_ids
            val expectedUpsellIds = productsWithSamePrefix.map { it.id } - product.id

            val missingUpsells = expectedUpsellIds - currentUpsellIds
            val unexpectedUpsells = currentUpsellIds - expectedUpsellIds

            if (missingUpsells.isNotEmpty()) {
                logError(
                    "Resolved automatically - Missing upsells",
                    "Product ${product.sku} is missing upsells to: ${missingUpsells.map { idToSkuMap[it] }}\nLINK: ${product.permalink}",
                    alsoEmail = false
                )
                if (addMissingUpDifferentColourProductUpSells) {
                    addMissingUpsells(product, expectedUpsellIds, credentials)
                }
            }

            // I think that deleted products end up staying in the list, even though they are invisible in the UI.
            val unexpectedSkus = unexpectedUpsells.map { idToSkuMap[it] }.toSet() - null
            if (unexpectedSkus.isNotEmpty()) {
                logError(
                    "Resolved automatically - Unexpected upsells",
                    "Product ${product.sku} has upsells to unrelated products: $unexpectedSkus\nLINK: ${product.permalink}",
                    alsoEmail = false
                )
                if (addMissingUpDifferentColourProductUpSells) {
                    addMissingUpsells(product, expectedUpsellIds, credentials)
                }
            }
        }
    }
}

fun addMissingUpsells(product: Product, updatedUpsellIds: List<Int>, credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"

    val payload = mapOf("upsell_ids" to updatedUpsellIds.toList())
    val requestBody = mapper.writeValueAsString(payload).toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .put(requestBody)
        .header("Authorization", credentials)
        .build()

    executeWithRetry {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("ERROR: Failed to update upsells for product ${product.id}. Response: ${response.body?.string()}")
            } else {
                println("ACTION: Updated upsells for product SKU ${product.sku}")
            }
        }
    }
}

fun sendAlertEmail(subject: String, message: String, b2b: Boolean) {
    val username = "sakis@foryoufashion.gr"
    val props = Properties().apply {
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.host", "mail.foryoufashion.gr")
        put("mail.smtp.port", "587")
    }

    val session = Session.getInstance(props, object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication {
            return PasswordAuthentication(username, sakisForYouFashionEmailPassword)
        }
    })

    val intro = if (b2b) "Γεια σου Δημητρα!\n\n" else "Γεια σου Ολγα!\n\n"
    val signature = "\n\nΦιλικά,\nΣάκης"
    try {
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(username))
        if (b2b) {
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("b2b@foryoufashion.gr"))
        } else {
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("olga@foryoufashion.gr"))
        }
        msg.setRecipients(Message.RecipientType.CC, InternetAddress.parse("sakis@foryoufashion.gr"))
        msg.subject = subject
        msg.setText(intro + message + signature)
        Transport.send(msg)
    } catch (e: MessagingException) {
        println("ERROR: Failed to send alert email: ${e.message}")
    }
}

fun shouldSendEmailForCategory(category: String): Boolean {
    val lastSent = emailThrottleCache[category]
    return lastSent==null || ChronoUnit.DAYS.between(lastSent, LocalDate.now()) >= 6
}

fun loadEmailThrottleCache(): MutableMap<String, LocalDate> {
    val file = File(EMAIL_THROTTLE_FILE)
    val map = mutableMapOf<String, LocalDate>()
    if (file.exists()) {
        file.readLines().forEach { line ->
            val (category, dateStr) = line.split(",", limit = 2)
            map[category] = LocalDate.parse(dateStr)
        }
    }
    return map
}

fun saveEmailThrottleCache() {
    val file = File(EMAIL_THROTTLE_FILE)
    file.printWriter().use { writer ->
        emailThrottleCache.forEach { (category, date) ->
            writer.println("$category,$date")
        }
    }
}
