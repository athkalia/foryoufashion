package allAutomatedChecks

import java.io.File
import java.io.IOException
import okhttp3.Request
import Attribute
import AttributeTerm
import Category
import Media
import MediaCacheEntry
import Plugin
import Product
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
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.spi.IIORegistry
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
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import readOnlyConsumerKey
import readOnlyConsumerSecret
import wordPressApplicationPassword
import wordPressUsername
import writeConsumerKey
import writeConsumerSecret

const val readOnly = true

// Slow
const val shouldSkipVariationChecks = false
const val checkMediaLibraryChecks = true

// Require manual input
const val shouldCheckForLargeImagesOutsideMediaLibrary = false

// Recurring updates
const val shouldMoveOldOutOfStockProductsToPrivate = true
const val shouldUpdatePricesToEndIn99 = true
const val shouldUpdateProsforesProductTag = true
const val shouldUpdateNeesAfikseisProductTag = true

// One-off updates
const val shouldSwapDescriptions = false
const val shouldRemoveEmptyLinesFromDescriptions = false
const val shouldAutomaticallyDeleteUnusedImages = false
const val shouldDeleteLargeImagesOutsideMediaLibrary = false

val allWooCommerceApiUpdateVariables = listOf(
    shouldMoveOldOutOfStockProductsToPrivate,
    shouldUpdatePricesToEndIn99,
    shouldSwapDescriptions,
    shouldRemoveEmptyLinesFromDescriptions,
    shouldUpdateProsforesProductTag,
    shouldUpdateNeesAfikseisProductTag,
)

private const val CACHE_FILE_PATH = "product_images_dimensions_cache.csv"
private const val MEDIA_LIBRARY_CACHE_FILE_PATH = "media_library_missing_files_cache.csv"
private val formatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private const val PLUGINS_FILE_PATH = "installed_plugins.json"

private fun registerWebPReader() {
    val registry = IIORegistry.getDefaultInstance()
    registry.registerServiceProvider(WebPImageReaderSpi())
}

fun main() {
    val wordPressWriteCredentials =
        Base64.getEncoder().encodeToString("$wordPressUsername:$wordPressApplicationPassword".toByteArray())

    if (readOnly && (allWooCommerceApiUpdateVariables.any { it })) {
        throw Exception("Something set for update but readOnly is set to 'false'")
    }
    if (shouldUpdateProsforesProductTag && shouldSkipVariationChecks) {
        throw Exception("Cannot update prosfores if variation checks is disabled")
    }

    val credentials = if (allWooCommerceApiUpdateVariables.any { it }) {
        Credentials.basic(writeConsumerKey, writeConsumerSecret)
    } else {
        Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    }

    if (shouldCheckForLargeImagesOutsideMediaLibrary) {
        checkForLargeImagesOutsideMediaLibrary(wordPressApplicationPassword, credentials)
    } else {
        val allProducts = fetchAllProducts(credentials)

        println("DEBUG: Total products fetched: ${allProducts.size}")

        checkPluginsList(wordPressWriteCredentials)
        if (checkMediaLibraryChecks) {
            val allMedia = checkForUnusedImagesInsideMediaLibrary(allProducts, wordPressWriteCredentials)
            checkForMissingFilesInsideMediaLibraryEntries(allMedia)
        }
        if (shouldUpdateNeesAfikseisProductTag) {
            updateNeesAfikseisProducts(allProducts, credentials)
        }
        for (product in allProducts) {
            // offer and discount empty products, not sure what these are.
            if (product.id==27948 || product.id==27947) {
                continue
            }
//            println("DEBUG: product SKU: ${product.sku}")
            checkForInvalidDescriptions(product, credentials)
            checkForGreekCharactersInSlug(product)
            checkForMissingImages(product)
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

            if (!shouldSkipVariationChecks) {
                val productVariations = getVariations(productId = product.id, credentials)
                if (shouldUpdateProsforesProductTag) {
                    val firstVariation = productVariations.firstOrNull()
                    firstVariation?.let {
//                        println("DEBUG: variation SKU: ${it.sku}")
                        addProsforesTagForDiscountedProductsAndRemoveItForRest(product, it, 30, credentials)
                    }
                }
                checkForOldProductsThatAreOutOfStockAndMoveToPrivate(product, productVariations, credentials)
                checkAllVariationsHaveTheSamePrices(product, productVariations)
                for (variation in productVariations) {
                    checkForNegativeOrMissingPrices(variation)
                    //                    println("DEBUG: variation SKU: ${variation.sku}")
                    checkForMissingOrWrongPricesAndUpdateEndTo99(product, variation, credentials)
                    checkForInvalidSKUNumbers(product, variation)
                }
            }
        }
        checkProductCategories(credentials)
        checkProductAttributes(credentials)
        checkProductTags(credentials)
    }
}

private fun checkForNegativeOrMissingPrices(variation: Variation) {
    val regularPrice = variation.regular_price.toDoubleOrNull()
    val salePrice = variation.sale_price.toDoubleOrNull()
    if (regularPrice==null || regularPrice <= 0) {
        println("ERROR: Regular price for variation ${variation.id} is invalid or empty")
    }
    if (salePrice!=null && salePrice <= 0) {
        println("ERROR: Sale price for variation ${variation.id} is invalid ")
    }
}

private fun checkAllVariationsHaveTheSamePrices(product: Product, productVariations: List<Variation>) {
    val allDistinctRegularPrices = productVariations.map { it.regular_price }.distinct()
    val allDistinctSalePrices = productVariations.map { it.sale_price }.distinct()

    if (allDistinctRegularPrices.size!=1 || allDistinctSalePrices.size!=1) {
        println("ERROR: Multiple variation prices for product SKU ${product.sku}.")
    }
}

fun checkForGreekCharactersInSlug(product: Product) {
    val greekCharRegex = Regex("[\u0370-\u03FF\u1F00-\u1FFF]")
    if (greekCharRegex.containsMatchIn(product.slug)) {
        println("ERROR: Product SKU ${product.sku} has a slug containing Greek characters.")
//        println("LINK: ${product.permalink}")
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

fun checkForLargeImagesOutsideMediaLibrary(wordPressWriteCredentials: String, credentials: String) {
    // Manually copy the list of large image paths from https://foryoufashion.gr/wp-admin/admin.php?page=big-files
    val largeImages = listOf<String>()

    val allMedia = getAllNonRecentMedia(wordPressWriteCredentials)
    val allMediaUrls = allMedia.map { it.source_url }.toSet()
    val allProducts = fetchAllProducts(credentials)
    val allProductImages = allProducts.flatMap { it.images }.map { it.src }.toSet()
    val imagePathsToDelete = mutableListOf<String>()
    largeImages.forEach { imagePath ->
        val imageUrl = imagePath.replace("/home/www/webex29/foryoufashion.gr/www", "https://foryoufashion.gr")
        if (imageUrl !in allMediaUrls && imageUrl !in allProductImages) {
            imagePathsToDelete.add(imagePath)
        }
    }

    if (imagePathsToDelete.isNotEmpty()) {
        println("WARNING: ${imagePathsToDelete.size} Large images outside media library detected")
    }
    imagePathsToDelete.forEach {
        println("DEBUG: link $it")
    }

    if (shouldDeleteLargeImagesOutsideMediaLibrary) {
        val ftpClient = FTPClient()
        try {
            ftpClient.controlEncoding = "UTF-8"
            ftpClient.connect(forYouFashionFtpUrl)
            ftpClient.login(forYouFashionFtpUsername, forYouFashionFtpPassword)
            imagePathsToDelete.forEach {
                // Removing the root folder to get the relative FTP path
                val remotePath = it.replace("/home/www/webex29/foryoufashion.gr/www", "")
                if (ftpClient.deleteFile(remotePath)) {
                    println("ACTION: Deleted unused image: $remotePath")
                } else {
                    println("ERROR: Could not delete image: $remotePath")
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

fun checkForMissingFilesInsideMediaLibraryEntries(allMedia: List<Media>) {
    val cache = loadMediaCache()
    val today = LocalDate.now()
    allMedia.forEach { media ->
        val cachedEntry = cache[media.source_url]
        val isFileMissing = if (cachedEntry!=null && !isCacheExpired(cachedEntry.lastChecked, today)) {
            // Cache hit and still valid
            cachedEntry.isFileMissing
        } else {
            // Cache miss or expired, check file existence
            val fileExists = checkIfFileExists(media.source_url)
            cache[media.source_url] = MediaCacheEntry(media.source_url, !fileExists, today)
            !fileExists
        }

        if (isFileMissing) {
            println("ERROR: File missing for Media ID: ${media.id}, URL: ${media.source_url}")
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

private fun checkIfFileExists(url: String): Boolean {
    println("DEBUG: downloading image: $url")
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    return client.newCall(request).execute().use { response ->
        response.isSuccessful // Returns true if file exists (HTTP 200), false otherwise
    }
}

fun checkForUnusedImagesInsideMediaLibrary(allProducts: List<Product>, wordPressWriteCredentials: String): List<Media> {
    val allMedia = getAllNonRecentMedia(wordPressWriteCredentials)
    println("DEBUG: Total media files: ${allMedia.size}")

    val allProductImages: Set<String> = allProducts.flatMap { it.images }.map { it.src }.toSet()
    println("DEBUG: Total product images: ${allProductImages.size}")

    val unusedImages = findUnusedImages(allMedia, allProductImages)
    if (unusedImages.isNotEmpty()) {
        println("ERROR: Unused images in media library: ${unusedImages.size}")
    }
    unusedImages.forEach {
        println("LINK: https://foryoufashion.gr/wp-admin/post.php?post=${it.id}&action=edit")
        if (shouldAutomaticallyDeleteUnusedImages) {
            deleteUnusedImage(it, wordPressWriteCredentials)
        }
    }
    return allMedia
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
            println("ERROR: Failed to delete unusedImage ID: ${unusedImage.id}. Response code: ${response.code}")
            println("ERROR: Response message: ${response.message}")
        }
    }
}


fun getAllNonRecentMedia(wordPressWriteCredentials: String): List<Media> {
    val formatter = DateTimeFormatter.ISO_DATE_TIME // Adjust if needed
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

        val filteredMedia = media.filter {
            val mediaDate = LocalDate.parse(it.date, formatter)
            mediaDate.isBefore(threeMonthsAgo)
        }

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
            println("WARNING: Product ${product.sku} has the wrong resolution ratio")
            println("DEBUG: Image width: ${dimensions.first}px")
            println("DEBUG: Image height: ${dimensions.second}px")
            println("DEBUG: Image URL: ${image.src}")
            println("DEBUG: Image ratio: $ratio")
            println(product.permalink)
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
                println("ERROR: Product ${product.sku} has an image with too high resolution (width > 1500px).")
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
            println("ERROR: Product SKU ${product.sku} is only in parent categories but should be in a more specific sub-category.")
            println("LINK: ${product.permalink}")
        }
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

    if ((inPlusSizeCategory || inPlusSizeForemataCategory) && !hasPlusSizeTag) {
        println("ERROR: Product SKU ${product.sku} is missing a plus size tag.")
        println("LINK: ${product.permalink}")
    }

    if (inPlusSizeCategory && !inOlosomesFormesCategory && !inPlusSizeForemataCategory) {
        println("ERROR: Product SKU ${product.sku} is in the plus category but it's not in olosomes formes or plus size foremata category")
        println("LINK: ${product.permalink}")
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
        println("ERROR: Product SKU ${product.sku} is only in the 'Special Occasion' or 'Plus size' category and not in any other category.")
    }
}

fun checkForDraftProducts(product: Product) {
    if (product.status.equals("draft", ignoreCase = true)) {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productLastModificationDate = LocalDate.parse(product.date_modified, dateFormatter)
        val oneMonthAgo = LocalDate.now().minus(1, ChronoUnit.MONTHS)
        if (productLastModificationDate.isBefore(oneMonthAgo)) {
            println("ERROR: Product SKU ${product.sku} has been in draft status for more than 1 month.")
        }
    }
}

fun checkForInvalidSKUNumbers(product: Product, variation: Variation) {
    // Some wedding dresses - ignore
    if (product.sku !in listOf(
            "7489", "2345", "9101", "5678", "1234", "3821", "8Ε0053", "3858", "3885", "5832", "QC368Bt50"
        )
    ) {
        val finalProductRegex = Regex("^\\d{5}-\\d{3}\$")
        if (!product.sku.matches(finalProductRegex)) {
            println("ERROR: Product SKU ${product.sku} does not match Product SKU regex ")
        }
        val finalProductVariationRegex = Regex("^\\d{5}-\\d{3}-\\d{1,3}$")
        if (!variation.sku.matches(finalProductVariationRegex)) {
            println("ERROR: Variation SKU ${variation.sku} does not match Variation SKU regex ")
        }
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
            println("WARNING: Product SKU ${product.sku} has info about the size the model is wearing in the long description.")
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
        println("ERROR: Product ${product.sku} has an empty or too short title: '$title'.")
        println("LINK: ${product.permalink}")
    } else if (title.length > 65) { // Matches meta ads recommendations
        println("ERROR: Product ${product.sku} has a too long title: '$title'.")
        println("LINK: ${product.permalink}")
    }
}

private fun checkForInvalidDescriptions(product: Product, credentials: String) {
    if (product.status!="draft") {
//    println("DEBUG: long description ${product.description}")
//    println("DEBUG: short description ${product.short_description}")
        isValidHtml(product.short_description)
        isValidHtml(product.description)
        if (product.short_description.equals(product.description, ignoreCase = true)) {
            println("WARNING: same short and long descriptions found for product: ${product.sku}")
            println("LINK: ${product.permalink}")
        }
        if (product.short_description.length > product.description.length) {
            println("WARNING: short description longer than long description for product: ${product.sku}")
            println("LINK: ${product.permalink}")
//        println("DEBUG: long description ${product.description}")
//        println("DEBUG: short description ${product.short_description}")
            if (shouldSwapDescriptions) {
                swapProductDescriptions(product, credentials)
            }
        }
        if (product.short_description.length < 120 || product.short_description.length > 250) {
            println("WARNING: Product ${product.sku} has a short description of invalid length, with length ${product.short_description.length}")
//        println("DEBUG: ${product.permalink} συντομη περιγραφη μηκος: ${product.short_description.length}")
        }
        if (product.description.length < 250 || product.description.length > 550) {
            println("WARNING: Product ${product.sku} has a long description of invalid length, with length ${product.description.length}")
//        println("DEBUG: ${product.permalink} μεγαλη περιγραφη μηκος: ${product.description.length}")
        }
        if (product.description.contains("&nbsp;")) {
            println("ERROR: Product ${product.sku} has unnecessary line breaks in description")
            if (shouldRemoveEmptyLinesFromDescriptions) {
                updateProductDescriptions(
                    product, product.short_description, product.description.replace("&nbsp;", ""), credentials
                )
            }
        }
        if (product.short_description.contains("&nbsp;")) {
            println("ERROR: Product ${product.sku} has unnecessary line breaks in short description")
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
        println("ERROR: Product ${product.sku} is a variable product with stock management at the product level. It should be only at the variation level")
    }
}

private fun checkForMissingOrWrongPricesAndUpdateEndTo99(
    product: Product,
    variation: Variation,
    credentials: String
) {
    if (product.status!="draft") {

        val regularPrice = variation.regular_price
        val salePrice = variation.sale_price

        if (regularPrice.isEmpty()) {
            println("ERROR: product SKU ${product.sku} regular price empty")
        }
        if (regularPrice.isNotEmpty() && !priceHasCorrectPennies(regularPrice)) {
            println("ERROR: product SKU ${product.sku} regular price $regularPrice has incorrect pennies")
            if (shouldUpdatePricesToEndIn99) {
                val updatedRegularPrice = adjustPrice(regularPrice.toDouble())
                if (updatedRegularPrice!=regularPrice) {
                    println("ACTION: Updating product SKU ${product.sku} variation SKU ${variation.sku} regular price from $regularPrice to $updatedRegularPrice")
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
            println("ERROR: product SKU ${product.sku} salePrice price $salePrice has incorrect pennies")
            if (shouldUpdatePricesToEndIn99) {
                val updatedSalePrice = adjustPrice(salePrice.toDouble())
                println("ACTION: Updating product SKU ${product.sku} variation SKU ${variation.sku} sale price from $salePrice to $updatedSalePrice")
                if (isSignificantPriceDifference(salePrice.toDouble(), updatedSalePrice.toDouble())) {
                    println("ERROR: Significant price difference detected for product SKU ${product.sku}. Exiting process.")
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
            println("ERROR: Product ${product.sku} has a non-portrait image.")
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
        println("ERROR: Product ${product.sku} is missing a main image.")
        println(product.permalink)
    }

    if (galleryImagesMissing) {
        println("WARNING: Product ${product.sku} only has ${images.size} images.")
        println("LINK: ${product.permalink}")
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
    println("Updating descriptions for product ${product.sku}")
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
                addNeesAfikseisTag(product, neesAfikseisTag, credentials)
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
        println("DEBUG: variation regular price $regularPrice")
        println("DEBUG: variation sale price $salePrice")
        if (regularPrice!=null && regularPrice > 0) {
            val url = "https://foryoufashion.gr/wp-json/wc/v3/products/${product.id}"
            if (salePrice!=null) {
                val discountPercentage = ((regularPrice - salePrice) / regularPrice * 100).roundToInt()
                println("discount found $discountPercentage")
                if (discountPercentage >= addTagAboveThisDiscount) {
                    addTagProsfores(product, prosforesTag, url, credentials)
                } else {
                    removeTagProsfores(product, prosforesTag, url, credentials)
                }
            } else {
                removeTagProsfores(product, prosforesTag, url, credentials)
            }
        } else {
            println("ERROR: Could not determine discount because regular price empty or negative")
        }
    }
}

private fun addTagProsfores(product: Product, prosforesTag: Tag, url: String, credentials: String) {
    println("Adding tag 'prosfores'")
    if (!product.tags.any { it.slug==prosforesTag.slug }) {
        val updatedTags = product.tags.toMutableList()
        updatedTags.add(prosforesTag)
        val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
        println("ACTION: Updating product ${product.id} with tags: $data")
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
        println("DEBUG: Prosfores tag doesn't exist anyway")
    }
}

private fun removeTagProsfores(product: Product, prosforesTag: Tag, url: String, credentials: String) {
    if (product.tags.any { it.slug==prosforesTag.slug }) {
        val updatedTags = product.tags.filter { it.slug!=prosforesTag.slug }
        val data = mapper.writeValueAsString(mapOf("tags" to updatedTags))
        println("ACTION: Updating product ${product.id} with tags: $data")
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
        val productCount = tag.count
        if (productCount!! < 10) {
            println("WARNING: Tag '${tag.name}' contains $productCount products.")
        }
    }
}

private fun checkForNonSizeAttributesUsedForVariationsEgColour(product: Product) {
    product.attributes.let { attributes ->
        attributes.forEach { attribute ->
            if (!attribute.name.equals("Μέγεθος", ignoreCase = true) && attribute.variation) {
                println("ERROR: Product ${product.sku} has the '${attribute.name}' attribute marked as used for variations.")
                println(product.permalink)
            }
        }
    }
}

fun checkForOldProductsThatAreOutOfStockAndMoveToPrivate(
    product: Product, productVariations: List<Variation>, credentials: String
) {
    if (product.status!="private" && product.status!="draft") {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val productDate = LocalDate.parse(product.date_created, dateFormatter)
        val twoYearsAgo = LocalDate.now().minus(2, ChronoUnit.YEARS)
        if (productDate.isBefore(twoYearsAgo)) {
            val allOutOfStock = productVariations.all { it.stock_status=="outofstock" }
            if (allOutOfStock) {
                println("ERROR: Product ${product.sku} is out of stock on all sizes and was added more than 2 years ago.")
                println("LINK: ${product.permalink}")
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
        println("WARNING: The following plugins have been installed:")
        newPlugins.forEach { println("- ${it.name} v${it.version}") }
    }

    if (deletedPlugins.isNotEmpty()) {
        println("WARNING: The following plugins have been deleted:")
        deletedPlugins.forEach { println("- ${it.name} v${it.version}") }
    }

    if (updatedPlugins.isNotEmpty()) {
        println("WARNING: The following plugins have been updated:")
        updatedPlugins.forEach { println("- ${it.name} updated to v${it.version}") }
    }

    if (disabledPlugins.isNotEmpty()) {
        println("WARNING: The following plugins have been enabled/disabled:")
        disabledPlugins.forEach { println("- ${it.name} updated to ${it.status}") }
    }
}
