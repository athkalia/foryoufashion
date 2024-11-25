package allAutomatedChecks

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
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi
import forYouFashionFtpPassword
import forYouFashionFtpUrl
import forYouFashionFtpUsername
import java.time.LocalDate
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import readOnlyConsumerKey
import readOnlyConsumerSecret
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

// One-off updates
const val shouldSwapDescriptions = false
const val shouldAutomaticallyDeleteUnusedImages = false
const val shouldCheckForLargeImagesOutsideMediaLibraryFromFTP = false
const val shouldDeleteLargeImagesOutsideMediaLibraryFromFTP = false

val allApiUpdateVariables = listOf(
    shouldMoveOldOutOfStockProductsToPrivate,
    shouldUpdatePricesToEndIn99,
    shouldUpdateProsforesProductTag,
    shouldUpdateNeesAfikseisProductTag,
    shouldRemoveEmptyLinesFromDescriptions,
    shouldPopulateMissingImageAltText,
    shouldDiscountProductPriceBasedOnLastSaleDate,
    shouldSwapDescriptions,
    shouldDeleteLargeImagesOutsideMediaLibraryFromFTP,
)

private const val CACHE_FILE_PATH = "product_images_dimensions_cache.csv"
private const val MEDIA_LIBRARY_CACHE_FILE_PATH = "media_library_missing_files_cache.csv"
private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private const val PLUGINS_FILE_PATH = "installed_plugins.json"
val errorCountMap = mutableMapOf<String, Int>()

private fun registerWebPReader() {
    val registry = IIORegistry.getDefaultInstance()
    registry.registerServiceProvider(WebPImageReaderSpi())
}

fun logError(messageCategory: String, message: String) {
    println(message)
    errorCountMap[messageCategory] = errorCountMap.getOrDefault(messageCategory, 0) + 1
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

    val allMedia = getAllMedia(wordPressWriteCredentials, nonRecent = false)
    val allProducts = fetchAllProducts(credentials)

    if (shouldCheckForLargeImagesOutsideMediaLibraryFromFTP) {
        checkForImagesOutsideMediaLibraryFromFTP(allMedia, allProducts)
    } else {
        println("DEBUG: Total products fetched: ${allProducts.size}")
        checkPluginsList(wordPressWriteCredentials)

        if (checkMediaLibraryChecks) {
            val allNonRecentMedia = getAllMedia(wordPressWriteCredentials, nonRecent = true)
            checkForUnusedImagesInsideMediaLibrary(allNonRecentMedia, allProducts, wordPressWriteCredentials)
            checkForMissingFilesInsideMediaLibraryEntries(allNonRecentMedia)
        }

        if (shouldUpdateNeesAfikseisProductTag) {
            updateNeesAfikseisProducts(allProducts, credentials)
        }

        checkForDuplicateImagesAcrossProducts(allProducts)
        val allOrders = fetchAllOrders(credentials)
        checkPaymentMethodsInLastTwoMonths(allOrders)

        val allAttributes = getAllAttributes(credentials)
        checkProductCategories(credentials)
        checkProductAttributes(allAttributes, credentials)
        checkProductTags(credentials)
        if (shouldDiscountProductPriceBasedOnLastSaleDate) {
            discountProductBasedOnLastSale(allProducts, allOrders, credentials)
        }
        for (product in allProducts) {
            // offer and discount empty products, not sure what these are.
            if (product.id==27948 || product.id==27947) {
                continue
            }
            println("DEBUG: product SKU: ${product.sku}")
            checkNameModelTag(product)
            checkForInvalidDescriptions(product, credentials)
            checkForMissingImagesAltText(product, credentials)
            checkForMissingGalleryVideo(product)
            checkForProductsWithImagesNotInMediaLibrary(product, allMedia)
            checkCasualForemataHasCasualStyleAttribute(product)
            checkFwtografisiStudioEkswterikiTag(product)
            checkForGreekCharactersInSlug(product)
            checkForMikosAttributeInForemataCategory(product)
            checkForMissingAttributesInProduct(product, allAttributes)
            checkCasualProductsInCasualForemataCategory(product)
            checkForMissingImages(product)
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
                if (shouldUpdateProsforesProductTag) {
                    val firstVariation = productVariations.firstOrNull()
                    firstVariation?.let {
                        println("DEBUG: variation SKU: ${it.sku}")
                        addProsforesTagForDiscountedProductsAndRemoveItForRest(product, it, 10, credentials)
                    }
                }
                checkForMissingSizeVariations(product, productVariations, credentials)
                checkForOldProductsThatAreOutOfStockAndMoveToPrivate(product, productVariations, credentials)
                checkForProductsThatHaveBeenOutOfStockForAVeryLongTime(allOrders, product, productVariations)
                checkAllVariationsHaveTheSamePrices(product, productVariations)
                for (variation in productVariations) {
//                    println("DEBUG: variation SKU: ${variation.sku}")
                    checkForWrongPricesAndUpdateEndTo99(product, variation, credentials)
                    checkForInvalidSKUNumbers(product, variation)
                }
            }
        }
    }
    println("\nSummary of triggered checks:")
    errorCountMap.forEach { (error, count) ->
        println("$error: $count occurrence(s)")
    }
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
            "checkCasualForemataHasCasualStyleAttribute",
            "ERROR: Product SKU ${product.sku} in 'casual-foremata' category but is missing the 'Casual' style attribute."
        )
        println("LINK: ${product.permalink}")
    }
}

fun checkCasualProductsInCasualForemataCategory(product: Product) {
    val casualCategorySlug = "casual-foremata"
    val excludedCategories = setOf("foremata", "plus-size") // Use lowercase for comparison
    val isInCasualCategory = product.categories.any { it.slug.equals(casualCategorySlug, ignoreCase = true) }
    val hasOtherCategories = product.categories.size > 1
    val isInExcludedCategory =
        product.categories.any { excludedCategories.contains(it.slug.lowercase(Locale.getDefault())) }

    if (isInCasualCategory && hasOtherCategories && !isInExcludedCategory) {
        logError(
            "checkCasualProductsInCasualForemataCategory",
            "ERROR: Product SKU ${product.sku} is in 'casual' category and also in another category: ${product.permalink}"
        )
    }
}

fun checkForMikosAttributeInForemataCategory(product: Product) {
    val forematoCategorySlug = "foremata" // Category slug for 'Φορέματα'
    val mikosAttributeName = "Μήκος"

    val isInForemataCategory = product.categories.any { it.slug==forematoCategorySlug }

    if (isInForemataCategory) {
        val mikosAttribute = product.attributes.find { it.name.equals(mikosAttributeName, ignoreCase = true) }
        if (mikosAttribute==null) {
            logError(
                "checkForMikosAttributeInForemataCategory",
                "ERROR: Product SKU ${product.sku} is missing the 'Μήκος' attribute."
            )
            println("LINK: ${product.permalink}")
        }
    }
}

fun discountProductBasedOnLastSale(allProducts: List<Product>, orders: List<Order>, credentials: String) {
    val baseSkuMap = allProducts.groupBy { it.sku.substringBefore('-') }
    baseSkuMap.forEach { (baseSku, products) ->
        println("DEBUG: Processing baseSku :$baseSku")
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
        println("DEBUG: monthsSinceLastSale for group: $monthsSinceLastSale")
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

fun checkForMissingAttributesInProduct(product: Product, allAttributes: List<Attribute>) {
    val productAttributeNames = product.attributes.map { it.name.lowercase(Locale.getDefault()) }
    val forematoCategorySlug = "foremata"
    val mikosAttributeName = "Μήκος"
    val brandAttributeName = "Brand"
    val isInForemataCategory = product.categories.any { it.slug==forematoCategorySlug }
    allAttributes.forEach { attribute ->
        val attributeName = attribute.name.lowercase(Locale.getDefault())
        if (attributeName==mikosAttributeName.lowercase(Locale.getDefault())) {
            if (isInForemataCategory && !productAttributeNames.contains(attributeName)) {
                logError(
                    "MissingMikosAttribute",
                    "ERROR: Product SKU ${product.sku} is missing the required 'Μήκος' attribute."
                )
                println("LINK: ${product.permalink}")
            }
        } else if (attributeName==brandAttributeName.lowercase(Locale.getDefault())) {
            if (!productAttributeNames.contains(attributeName) && product.sku !in listOf(
                    // Old codes that don't need a brand.
                    "59404-251", "59419-281", "59406-011", "59403-005", "59442-005", "59407-587", "59413-020",
                    "59408-397", "59415-001", "59451-443", "59414-003", "59416-251", "59405-1274", "59459-003",
                    "59409-003", "59410-003", "59410-020", "59293-003", "59294-003", "57140-003", "59128-097",
                    "59126-566", "58758-015", "58759-015", "57110-561", "59117-012", "59132-247", "59171-543",
                    "59131-325", "59130-597", "59127-597", "59126-096", "59118-293", "59125-514", "58999-022",
                    "59007-004", "59012-590", "59043-051", "59010-007", "59112-550", "58241-014", "58241-514",
                    "59113-550", "59114-514", "59114-550", "59111-550", "59110-514", "59110-550", "59111-514",
                    "58242-015", "58242-550", "59109-514", "59109-550", "59035-594", "59003-004", "59042-281",
                    "58998-023", "59032-281", "59034-023", "59034-022", "59034-561", "59034-003", "58996-005",
                    "59006-004", "59030-031", "59030-004", "59030-003", "59030-096", "59030-293", "59039-281",
                    "59039-003", "59002-004", "58995-051", "59009-506", "59004-004", "59005-013", "59017-004",
                    "59017-281", "59008-006", "58301-003", "59040-004", "59040-289", "59023-023", "59023-013",
                    "59026-052", "59023-561", "59023-019", "59026-014", "59026-247", "59026-293", "59020-013",
                    "59020-027", "59020-097", "59020-561", "59020-003", "59020-023", "59020-029", "59029-293",
                    "59029-096", "59029-003", "59029-031", "59000-006", "49640-013", "49471-029", "58990-005",
                    "58990-023", "58991-004", "58989-023", "58987-007", "58994-096", "58993-015", "58992-004",
                    "58992-027", "58992-005", "58992-023", "58986-096", "58986-523", "58986-097", "58985-029",
                    "58985-007", "58985-023", "58985-022", "58984-023", "58984-096", "58988-005", "58988-004",
                    "58988-023", "58983-005", "58982-293", "58823-561", "58982-005", "58982-530", "58982-281",
                    "58823-488", "8Ε0053", "3858", "3885", "5832", "7489", "58733-005", "58733-003", "58696-003",
                    "58697-003", "58698-003", "58699-041", "58701-060", "58700-011", "58609-003", "57971-011",
                    "57973-011", "57948-003", "57211-060", "57211-013", "57945-060", "57944-002", "57947-003",
                    "58069-029", "58020-040", "57679-022", "57517-004", "57151-023", "57144-060", "57210-060",
                    "57110-058", "57155-003",
                )
            ) {
                logError(
                    "MissingBrandAttribute",
                    "ERROR: Product SKU ${product.sku} is missing the required 'Brand' attribute."
                )
                println("LINK: ${product.permalink}")
            } else if (!productAttributeNames.contains(attributeName)) {
                println("WARNING: Product SKU ${product.sku} is missing the required attribute '${attribute.name}'.")
//            println("LINK: ${product.permalink}")
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
            "checkAllVariationsHaveTheSamePrices",
            "ERROR: Multiple variation prices for product SKU ${product.sku}."
        )
        println("LINK: ${product.permalink}")
    }
}

fun checkForGreekCharactersInSlug(product: Product) {
    val greekCharRegex = Regex("[\u0370-\u03FF\u1F00-\u1FFF]")
    if (greekCharRegex.containsMatchIn(product.slug)) {
        logError(
            "checkForGreekCharactersInSlug",
            "ERROR: Product SKU ${product.sku} has a slug containing Greek characters."
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
    if (product.status!="draft") {
        val productOutOfStock = product.stock_status=="outofstock"
        val allVariationsOutOfStock = productVariations.all { it.stock_status=="outofstock" }
        val sixMonthsAgo = LocalDate.now().minus(6, ChronoUnit.MONTHS)
        if (productOutOfStock || allVariationsOutOfStock) {
            val lastProductSaleDate = findLastSaleDateForProductOrVariations(orders, product.id, productVariations)
//            println("DEBUG: Last Product sale date $lastProductSaleDate")
            if (lastProductSaleDate!=null) {
                if (lastProductSaleDate.isBefore(sixMonthsAgo)) {
                    println("ERROR: SKU ${product.sku} has been out of stock for more than 6 months since its last sale on $lastProductSaleDate.")
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
            "checkForImagesOutsideMediaLibraryFromFTP",
            "ERROR: ${imagePathsToDelete.size} unused images outside media library detected"
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
    val targetDate = LocalDate.of(2024, 3, 1)
    val productCreationDate = LocalDate.parse(product.date_created, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    if (productCreationDate.isAfter(targetDate)) {
        val galleryVideoMetaData = product.meta_data.find { it.key=="gallery_video" }
        val isGalleryVideoMissing = galleryVideoMetaData==null || (galleryVideoMetaData.value as String).isBlank()
        if (isGalleryVideoMissing) {
            logError(
                "checkForMissingGalleryVideo",
                "ERROR: Product SKU ${product.sku} created after 1st March 2024 is missing the 'gallery_video' custom field."
            )
            println("LINK: ${product.permalink}")
        }
    }
}

fun checkForMissingImagesAltText(product: Product, credentials: String) {
    if (product.status!="draft") {
        var hasMissingAltText = false
        for (image in product.images) {
            if (image.alt.isEmpty()) {
                hasMissingAltText = true
                logError(
                    "checkForMissingImagesAltText",
                    "ERROR: Product SKU ${product.sku} has an image with missing alt text."
                )
                println("LINK: (product) ${product.permalink}")
                println("LINK: (image in media gallery) https://foryoufashion.gr/wp-admin/post.php?post=${image.id}&action=edit")
            }
        }
        if (shouldPopulateMissingImageAltText && hasMissingAltText) {
            populateMissingImageAltText(product, credentials)
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
                "checkForMissingFilesInsideMediaLibraryEntries",
                "ERROR: File missing for Media ID: ${media.id}, URL: ${media.source_url}"
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
    wordPressWriteCredentials: String
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
                logError(
                    "checkForUnusedImagesInsideMediaLibrary regex mismatch",
                    "ERROR: ${media.source_url} should always match regex"
                )
                true // If URL doesn't match pattern, exclude the media item to be safe
            }
        }
    if (unusedImages.isNotEmpty()) {
        logError(
            "checkForUnusedImagesInsideMediaLibrary unused images",
            "ERROR: Unused images in media library: ${unusedImages.size}"
        )
    }
    unusedImages.forEach {
        println("LINK: https://foryoufashion.gr/wp-admin/post.php?post=${it.id}&action=edit")
        if (shouldAutomaticallyDeleteUnusedImages) {
            deleteUnusedImage(it, wordPressWriteCredentials)
        }
    }
}

fun deleteUnusedImage(unusedImage: Media, wordPressWriteCredentials: String) {
    val client = OkHttpClient()
    val deleteUrl = "https://foryoufashion.gr/wp-json/wp/v2/media/${unusedImage.id}?force=true"
    val request = Request.Builder()
        .url(deleteUrl)
        .delete(RequestBody.create(null, ByteArray(0))) // Empty body for DELETE request
        .header("Authorization", "Basic $wordPressWriteCredentials")
        .build()

    client.newCall(request).execute().use { response ->
        if (response.isSuccessful) {
            println("ACTION: Successfully deleted unusedImage ID: ${unusedImage.id}")
        } else {
            logError(
                "deleteUnusedImage",
                "ERROR: Failed to delete unusedImage ID: ${unusedImage.id}. Response code: ${response.code}"
            )
            println("ERROR: Response message: ${response.message}")
        }
    }
}

fun getAllMedia(wordPressWriteCredentials: String, nonRecent: Boolean): List<Media> {
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

        val filteredMedia = if (nonRecent) {
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
    val cache = loadImageCache()
    for (image in product.images) {
        val dimensions = cache[image.src] ?: getImageDimensions(image.src).also {
            cache[image.src] = it
        }

        val ratio = dimensions.second.toDouble() / dimensions.first.toDouble()
        if (ratio > 1.51 || ratio < 1.49) {
            println("WARNING: Product ${product.sku} image ${image.src} has the wrong resolution ratio ($ratio). Image width: ${dimensions.first}px, image height: ${dimensions.second}px ")
            println("LINK: ${product.permalink}")
        }
    }
    saveImageCache(cache)
}

fun checkForImagesWithTooLowResolution(product: Product) {
    val cache = loadImageCache()
    for (image in product.images) {
        val dimensions = cache[image.src] ?: getImageDimensions(image.src).also {
            cache[image.src] = it
        }

        if (dimensions.first < 1200) {
            println("WARNING: Product ${product.sku} has an image with too low resolution (width < 1200px).")
            println("DEBUG: Image width: ${dimensions.first}px")
            println("DEBUG: Image URL: ${image.src}")
            println("LINK: ${product.permalink}")
        }
    }
    saveImageCache(cache)
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
                    "checkForImagesWithTooHighResolution",
                    "ERROR: Product ${product.sku} has an image with too high resolution (width > 1500px)."
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
            logError(
                "checkForProductsInParentCategoryOnly",
                "ERROR: Product SKU ${product.sku} is only in parent categories but should be in a more specific sub-category."
            )
            println("LINK: ${product.permalink}")
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
                "checkForIncorrectPlusSizeCategorizationAndTagging 1",
                "ERROR: Product SKU ${product.sku} is missing a plus size tag."
            )
            println("LINK: ${product.permalink}")
        }

        if (inPlusSizeCategory && !inOlosomesFormesCategory && !inPlusSizeForemataCategory) {
            logError(
                "checkForIncorrectPlusSizeCategorizationAndTagging 2",
                "ERROR: Product SKU ${product.sku} is in the plus category but it's not in olosomes formes or plus size foremata category"
            )
            println("LINK: ${product.permalink}")
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
            "checkForSpecialOccasionOrPlusSizeCategoriesWithoutOtherCategories",
            "ERROR: Product SKU ${product.sku} is only in the 'Special Occasion' or 'Plus size' category and not in any other category."
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
                "checkForDraftProducts",
                "ERROR: Product SKU ${product.sku} has been in draft status for more than 1 month."
            )
        }
    }
}

fun checkForInvalidSKUNumbers(product: Product, variation: Variation) {
    if (product.status=="draft") {
        return
    }
    if (product.sku in listOf( // Some wedding dresses - ignore
            "7489", "2345", "9101", "5678", "1234", "3821", "8Ε0053", "3858", "3885", "5832", "QC368Bt50"
        )
    ) {
        return
    }
    val finalProductRegex = Regex("^\\d{5}-\\d{3,4}$")
    if (!product.sku.matches(finalProductRegex)) {
        logError("checkForInvalidSKUNumbers 1", "ERROR: Product SKU ${product.sku} does not match Product SKU regex ")
        println("LINK: ${product.permalink}")
    }
    val finalProductVariationRegex = Regex("^\\d{5}-\\d{3,4}-\\d{1,3}$")
    if (!variation.sku.matches(finalProductVariationRegex)) {
        logError(
            "checkForInvalidSKUNumbers 2",
            "ERROR: Variation SKU ${variation.sku} does not match Variation SKU regex "
        )
        println("LINK: ${product.permalink}")
    }
    if (!variation.sku.startsWith(product.sku)) {
        logError(
            "checkForInvalidSKUNumbers 3",
            "ERROR: Variation SKU ${variation.sku} does not start with the product SKU ${product.sku}"
        )
        println(product.permalink)
    }
}

fun checkForMissingToMonteloForaeiTextInDescription(product: Product) {
    if (product.status!="publish") {
        return
    }
    // Define the target date
    val startingCheckDate = LocalDate.of(2024, Month.AUGUST, 9)
    val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    val productCreationDate = LocalDate.parse(product.date_created, dateFormatter)

    // Check if the product was created on or after the target date
    if (productCreationDate.isAfter(startingCheckDate)) {
        if (product.description.contains("μοντελο", ignoreCase = true) ||
            product.description.contains("μοντέλο", ignoreCase = true)
        ) {
            logError(
                "checkForMissingToMonteloForaeiTextInDescription",
                "ERROR: Product SKU ${product.sku} has info about the size the model is wearing in the long description."
            )
        }
        if (!product.short_description.contains("το μοντέλο φοράει", ignoreCase = true)) {
            println("WARNING: Product SKU ${product.sku} does not have info about the size the model is wearing in the short description.")
        }
    }
}

fun checkForMissingSizeGuide(product: Product) {
//    println("DEBUG: product meta data ${product.meta_data}")
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
        println("WARNING: No size guide for product with SKU ${product.sku}")
    }
}

private fun checkForEmptyOrShortTitlesOrLongTitles(product: Product) {
    val title = product.name.replace("&amp", "&")
    if (title.isEmpty() || title.length < 20) {
        logError(
            "checkForEmptyOrShortTitlesOrLongTitles 1",
            "ERROR: Product ${product.sku} has an empty or too short title: '$title'."
        )
        println("LINK: ${product.permalink}")
    } else if (title.length > 70) { // 65 is the meta ads recommendation
        logError(
            "checkForEmptyOrShortTitlesOrLongTitles 2",
            "ERROR: Product ${product.sku} has a too long title: '$title'."
        )
        println("LINK: ${product.permalink}")
    }
}

fun checkNameModelTag(product: Product) {
    if (product.status!="draft"
        && product.sku!="57140-003"
        && product.sku!="59468-281"
        && product.sku!="59469-281"
        && product.sku!="57630-556"
    ) {
        val formatter = DateTimeFormatter.ISO_DATE_TIME
        val targetDate = LocalDate.of(2024, 9, 20) // When we started implementing this
        val productCreationDate = LocalDate.parse(product.date_created, formatter)
        if (productCreationDate.isAfter(targetDate)) {
            val hasModelTag = product.tags.any { tag -> tag.name.startsWith("ΜΟΝΤΕΛΟ", ignoreCase = true) }
            if (!hasModelTag) {
                logError(
                    "checkNameModelTag",
                    "ERROR: Product SKU ${product.sku} does not have a model tag starting with 'ΜΟΝΤΕΛΟ'."
                )
                println("LINK: ${product.permalink}")
            }
        }
    }
}

fun checkFwtografisiStudioEkswterikiTag(product: Product) {
    if (product.status!="draft") {
        val hasFwtografisiTag = product.tags.any { tag -> tag.name.startsWith("ΦΩΤΟΓΡΑΦΙΣΗ", ignoreCase = true) }
        if (!hasFwtografisiTag) {
            logError(
                "checkFwtografisiStudioEkswterikiTag",
                "ERROR: Product SKU ${product.sku} does not have a fwtografisi tag starting with 'ΦΩΤΟΓΡΑΦΙΣΗ'."
            )
            println("LINK: ${product.permalink}")
        }
    }
}

private fun checkForInvalidDescriptions(product: Product, credentials: String) {
    if (product.status!="draft") {
//    println("DEBUG: long description ${product.description}")
//    println("DEBUG: short description ${product.short_description}")
        isValidHtml(product.short_description)
        isValidHtml(product.description)
        if (product.short_description.equals(product.description, ignoreCase = true)) {
            logError(
                "checkForInvalidDescriptions 1",
                "ERROR: same short and long descriptions found for product: ${product.sku}"
            )
            println("LINK: ${product.permalink}")
        }
        if (product.short_description.length > product.description.length) {
            logError(
                "checkForInvalidDescriptions 2",
                "ERROR: short description longer than long description for product: ${product.sku}"
            )
            println("LINK: ${product.permalink}")
//        println("DEBUG: long description ${product.description}")
//        println("DEBUG: short description ${product.short_description}")
            if (shouldSwapDescriptions) {
                swapProductDescriptions(product, credentials)
            }
        }
        if (product.short_description.length < 120 || product.short_description.length > 250) {
//            println("WARNING: Product ${product.sku} has a short description of invalid length, with length ${product.short_description.length}")
//        println("DEBUG: ${product.permalink} συντομη περιγραφη μηκος: ${product.short_description.length}")
        }
        if (product.description.length < 250 || product.description.length > 550) {
//            println("WARNING: Product ${product.sku} has a long description of invalid length, with length ${product.description.length}")
//        println("DEBUG: ${product.permalink} μεγαλη περιγραφη μηκος: ${product.description.length}")
        }
        if (product.description.contains("&nbsp;")) {
            logError(
                "checkForInvalidDescriptions 3",
                "ERROR: Product ${product.sku} has unnecessary line breaks in description"
            )
            println(product.permalink)
            if (shouldRemoveEmptyLinesFromDescriptions) {
                updateProductDescriptions(
                    product, product.short_description, product.description.replace("&nbsp;", ""), credentials
                )
            }
        }
        if (product.short_description.contains("&nbsp;")) {
            logError(
                "checkForInvalidDescriptions 4",
                "ERROR: Product ${product.sku} has unnecessary line breaks in short description"
            )
            println(product.permalink)
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
            "checkForStockManagementAtProductLevelOutOfVariations",
            "ERROR: Product ${product.sku} is a variable product with stock management at the product level. It should be only at the variation level"
        )
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
                "checkForWrongPricesAndUpdateEndTo99 1",
                "ERROR: Regular price for variation ${variation.id} is invalid or empty"
            )
            return
        }
        if (salePrice!=null && salePrice <= 0) {
            logError(
                "checkForWrongPricesAndUpdateEndTo99 2",
                "ERROR: Sale price for variation ${variation.id} is invalid "
            )
            return
        }
        if (salePrice!=null && salePrice >= regularPrice) {
            logError(
                "checkForWrongPricesAndUpdateEndTo99 3",
                "ERROR: Sale price for variation ${variation.id} $salePrice cannot be bigger or equal to regular price $regularPrice"
            )
            return
        }

        if (regularPriceString.isNotEmpty() && !priceHasCorrectPennies(regularPriceString)) {
            logError(
                "checkForWrongPricesAndUpdateEndTo99 4",
                "ERROR: product SKU ${product.sku} regular price $regularPriceString has incorrect pennies"
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
                            "checkForWrongPricesAndUpdateEndTo99 5",
                            "ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process."
                        )
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
        if (salePriceString.isNotEmpty() && !priceHasCorrectPennies(salePriceString)) {
            logError(
                "checkForWrongPricesAndUpdateEndTo99 6",
                "ERROR: product SKU ${product.sku} salePrice price $salePriceString has incorrect pennies"
            )
            if (shouldUpdatePricesToEndIn99) {
                val updatedSalePrice = adjustPrice(salePriceString.toDouble())
                println("ACTION: Updating product SKU ${product.sku} variation SKU ${variation.sku} sale price from $salePriceString to $updatedSalePrice")
                if (isSignificantPriceDifference(salePriceString.toDouble(), updatedSalePrice.toDouble())) {
                    logError(
                        "checkForWrongPricesAndUpdateEndTo99 7",
                        "ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process."
                    )
                    exitProcess(1)
                }
                updateProductPrice(product.id, variation.id, updatedSalePrice, PriceType.SALE_PRICE, credentials)
                println(product.permalink)
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
            logError("checkForNonPortraitImagesWithCache", "ERROR: Product ${product.sku} has a non-portrait image.")
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
                "checkForProductsWithImagesNotInMediaLibrary 1",
                "ERROR: Product SKU ${product.sku} is using an image that is not in the media library: ${image.src}"
            )
            println("LINK: ${product.permalink}")
        }
    }
}

private fun checkForMissingImages(product: Product) {
    val images = product.images
    val mainImageMissing = images.isEmpty() || images[0].src.isEmpty()
    val galleryImagesMissing = images.size < 3

    if (mainImageMissing) {
        logError("checkForMissingImages 1", "ERROR: Product ${product.sku} is missing a main image.")
        println(product.permalink)
    }

    if (galleryImagesMissing) {
        println("WARNING: Product ${product.sku} only has ${images.size} images.")
        println("LINK: ${product.permalink}")
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
                "checkForMissingImages 2",
                "ERROR: Product ${product.sku} has an image with URL ${image.src} that does not exist"
            )
        }
    }
    saveMediaCache(cache)
}

private fun checkForDuplicateImagesAcrossProducts(products: List<Product>) {
    val imageToProductsMap = mutableMapOf<String, MutableList<Product>>()
    for (product in products) {
        if (product.sku !in listOf("59183-514", "59182-514", "59341-003")) { // known to contain duplicate images
            val images = product.images
            for (image in images) {
                val imageUrl = image.src
                imageToProductsMap.computeIfAbsent(imageUrl) { mutableListOf() }.add(product)
            }
        }
    }

    imageToProductsMap.forEach { (imageUrl, associatedProducts) ->
        val distinctAssociatedProducts = associatedProducts.distinct()
        if (distinctAssociatedProducts.size > 1) {
            logError("checkForDuplicateImagesAcrossProducts 1", "ERROR: Duplicate image found across products.")
            println("ERROR: Image URL: $imageUrl")
            println("ERROR: Products with this image:")
            distinctAssociatedProducts.forEach { product ->
                println("ERROR: SKU: ${product.sku}, LINK: ${product.permalink}")
            }
        }
    }
}

private fun checkForDuplicateImagesInAProduct(product: Product) {
    val images = product.images
    if (images.isNotEmpty()) {
        val imageUrls = images.map { it.src }
        val uniqueUrls = imageUrls.distinct()

        if (imageUrls.size!=uniqueUrls.size) {
            logError("checkForDuplicateImagesInAProduct 1", "ERROR: Product ${product.sku} has duplicate images.")
            println("LINK: ${product.permalink}")
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


private fun swapProductDescriptions(
    product: Product, credentials: String
) {
    println("reversing descriptions for product ${product.sku}")
    updateProductDescriptions(product, product.description, product.short_description, credentials)
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
                "addProsforesTagForDiscountedProductsAndRemoveItForRest 1",
                "ERROR: Could not determine discount because regular price empty or negative"
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
            println("WARNING: Category '${category.name}' contains only $productCount products.")
        }
    }
}


private fun checkProductAttributes(allAttributes: List<Attribute>, credentials: String) {
    for (attribute in allAttributes) {
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
                println("WARNING: Attribute '${attribute.name}' with term '${term.name}' contains only $productCount products.")
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
            println("WARNING: Tag '${tag.name}' contains $productCount products.")
        }
    }
}


private fun checkForNonSizeAttributesUsedForVariationsEgColour(product: Product) {
    product.attributes.let { attributes ->
        if (attributes.none { it.variation }) {
            logError(
                "checkForNonSizeAttributesUsedForVariationsEgColour 1",
                "ERROR: Product ${product.sku} has no attributes used for variations."
            )
            println("LINK: ${product.permalink}")
        }
        attributes.forEach { attribute ->
            if (!attribute.name.equals("Μέγεθος", ignoreCase = true) && attribute.variation) {
                logError(
                    "checkForNonSizeAttributesUsedForVariationsEgColour 2",
                    "ERROR: Product ${product.sku} has the '${attribute.name}' attribute marked as used for variations."
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
                "checkForMissingSizeVariations 1",
                "ERROR: Product SKU ${product.sku} is missing a variation for size $size."
            )
            println("LINK: ${product.permalink}")
        }
    }
}

fun checkForOldProductsThatAreOutOfStockAndMoveToPrivate(
    product: Product, productVariations: List<Variation>, credentials: String
) {
    if (product.status!="draft") {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoYearsAgo = LocalDate.now().minus(2, ChronoUnit.YEARS)
        if (productDate.isBefore(twoYearsAgo)) {
            val allOutOfStock = productVariations.all { it.stock_status=="outofstock" }
            if (allOutOfStock) {
                if (product.status=="private") {
                    logError(
                        "checkForOldProductsThatAreOutOfStockAndMoveToPrivate 1",
                        "ERROR: Product ${product.sku} is out of stock on all sizes and private and was added more than 2 years ago."
                    )
                    println("LINK: ${product.permalink}")
                } else {
                    logError(
                        "checkForOldProductsThatAreOutOfStockAndMoveToPrivate 1",
                        "ERROR: Product ${product.sku} is out of stock on all sizes and was added more than 2 years ago."
                    )
                    println("LINK: ${product.permalink}")
                    if (shouldMoveOldOutOfStockProductsToPrivate) {
                        updateProductStatusToPrivate(product, credentials)
                    }
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
        currentPlugins.any { plugin -> plugin.name==stored.name }
    }

    val updatedPlugins = currentPlugins.filter { current ->
        storedPlugins.any { stored -> stored.name==current.name && stored.version!=current.version }
    }

    val disabledPlugins = currentPlugins.filter { current ->
        storedPlugins.any { stored -> stored.name==current.name && stored.status!=current.status }
    }

    if (newPlugins.isNotEmpty()) {
        logError("checkForPluginChanges 1", "ERROR: The following plugins have been installed:")
        newPlugins.forEach { println("- ${it.name} v${it.version}, ${it.description.rendered}") }
    }

    if (deletedPlugins.isNotEmpty()) {
        logError("checkForPluginChanges 2", "ERROR: The following plugins have been deleted:")
        deletedPlugins.forEach { println("- ${it.name} v${it.version}") }
    }

    if (updatedPlugins.isNotEmpty()) {
        logError("checkForPluginChanges 3", "ERROR: The following plugins have been updated:")
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
        logError("checkForPluginChanges 4", "ERROR: The following plugins have been enabled/disabled:")
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

fun checkPaymentMethodsInLastTwoMonths(orders: List<Order>) {
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
    val twoMonthsAgo = LocalDate.now().minusMonths(2)

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

        if (orderDate.isAfter(twoMonthsAgo)) {
            usedPaymentMethods.add(paymentMethod)
        }
    }

    allPaymentMethods.forEach { method ->
        if (!usedPaymentMethods.contains(method)) {
            logError(
                "checkPaymentMethodsInLastTwoMonths 1",
                "ERROR: The payment method '$method' has not been used in the last two months."
            )
        }
    }
}
