package archive

import JsonEntry
import Product
import Variation
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val client = OkHttpClient()
val mapper = jacksonObjectMapper().registerModule(KotlinModule())

fun main1() {
    val readOnly = true
    val readOnlyConsumerKey = ""
    val readOnlyConsumerSecret = ""
    val writeConsumerKey = ""
    val writeConsumerSecret = ""

    val credentials =
        if (readOnly) {
            Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
        } else {
            Credentials.basic(
                writeConsumerKey,
                writeConsumerSecret
            )
        }
    val productSkuWithColourNameRegex = Regex("^\\d{5}\\s\\p{L}+(?:[\\s-]\\p{L}+)?\$")
    val finalProductRegex = Regex("^\\d{5}-\\d{3}\$")
    val finalProductVariationRegex = Regex("^\\d{5}-\\d{3}-\\d{1,3}$")

    val jsonData = fetchJsonData("https://foryoufashion.gr/test.php")

    var page = 1
    var products: List<Product>
    do {
        products = getProducts(page, credentials)
//        println("Products size: ${products.size}")
        products.forEach outerloop@{ product ->
            // skpping some old products that we don't want to touch
            if (product.sku in listOf("3821", "", "8Ε0053", "3858", "3885", "5832", "QC368Bt50")) {
                return@outerloop
            }

            if (product.sku.matches(finalProductRegex)) {
                product.variations.forEach { variationId ->
                    val variation =
                        retry(7, 1000) { getVariation(variationId, credentials) }
                    if (variation.sku.matches(finalProductVariationRegex)) {
//                        println("skipping product variation with SKU ${variation.sku}")
                        return@forEach
                    }
                    // Sioustis will do those
                    if (product.sku in listOf<String>(
//                            "58632-022",
//                            "58684-006",
//                            "55077-007",
//                            "56016-55",
//                            "52437-022",
//                            "55616-097",
//                            "58946-514",
//                            "57118-011",
//                            "56064-281",
//                            "58950-488",
//                            "58955-530",
//                            "58945-247",
//                            "58940-082",
//                            "56062-443",
//                            "58199-561",
//                            "58513-019",
//                            "58944-488",
//                            "56091-097",
//                            "56067-003",
//                            "55757-530",
//                            "55116-014",
//                            "56016-550",
//                            "56076-550",
//                            "53741-007",
//                            "56066-561",
//                            "57359-561",
//                            "57394-157",
//                            "55677-015",
//                            "56818-488",
                        )
                    ) {
                        return@outerloop
                    }

//                    println("Variation ID: ${variation.id}, SKU: ${variation.sku}")
                    if (variation.sku!=product.sku) {
                        println("WARNING: Variation SKU ${variation.sku} does not match product SKU ${product.sku}")
                    }
                    variation.attributes.forEach { attribute ->
                        if (!attribute.name.equals("Μέγεθος")) {
                            println("WARNING: DIFFERENT VARIATION detected:  ${attribute.name}")
                        }
                    }
                    val productSkuCode = product.sku.substringBefore("-")
//                    println("deduced product code from SKU: '$productSkuCode'")
                    val productSkuColorCode = product.sku.substringAfter("-")
//                    println("deduced product color code from SKU: '$productSkuColorCode'")
                    val sizeName =
                        variation.attributes.find { it.name.equals("Μέγεθος", ignoreCase = true) }?.option
                    val matchingProductEntriesJustSKU = jsonData.filter { it.code==productSkuCode }

                    val matchingProductEntries =
                        matchingProductEntriesJustSKU.filter { it.colorcode.equals(productSkuColorCode, true) }
                    val matchingVariationEntries =
                        matchingProductEntries.filter {
                            it.sizename==sizeName || (sizeName=="One size" && it.sizename=="ONE") ||
                                    (sizeName=="2XL" && it.sizename=="XXL")
                        }
                    if (matchingVariationEntries.size!=1 &&
                        matchingVariationEntries.map { it.colorcode }.distinct().count()!=1
                        && matchingVariationEntries.map { it.sizecode }.distinct().count()!=1
                    ) {
                        println(product.permalink)
                        println("SKU: ${variation.sku}, problematic size: $sizeName")
                        println()
//            println("WARNING: Variation SKU ${variation.sku} with productcolorcode '$productSkuColorCode' and size '$sizeName' matches ${matchingVariationEntries.size} entries in JSON: $matchingVariationEntries")
                    } else {
                        println("Variation SKU ${variation.sku} with productcolorcode '$productSkuColorCode' and size '$sizeName' correctly associated in JSON")
                        setNewSkuForVariation(
                            product,
                            variation,
                            "$productSkuCode-${matchingVariationEntries.first().colorcode}-${matchingVariationEntries.first().sizecode}",
                            credentials, readOnly
                        )
                    }
                }
                return@outerloop
            }

//            println("Processing product: ${product.name} (ID: ${product.id}), SKU: ${product.sku}")
            updateSkuToMatchRegex(product, productSkuWithColourNameRegex, credentials, readOnly)

            val productSkuCode = product.sku.substringBefore(" ")
//            println("deduced product code from SKU: '$productSkuCode'")
            val productSkuColor = product.sku.substringAfter(" ")
//            println("deduced product color from SKU: '$productSkuColor'")

            val matchingProductEntriesJustSKU = jsonData.filter { it.code==productSkuCode }
            if (matchingProductEntriesJustSKU.isEmpty()) {
//                val desktop = Desktop.getDesktop()
//                desktop.browse(URI(product.permalink));
                println(product.permalink)
                println()
//                println("WARNING: Product SKU CODE ${product.sku} does not match entries in JSON")
            } else {
                val matchingProductEntries =
                    matchingProductEntriesJustSKU.filter { it.colorname.equals(productSkuColor, true) }
                if (matchingProductEntries.isEmpty()) {
                    println(product.permalink)
                    println()
//                    println("WARNING: Product SKU CODE ${product.sku} does not match colour entries in JSON")
                    val similarColours = mapOf(
                        "ΡΟΖ" to "LIGHT PINK",
                    )
                    similarColours.entries.forEach {
                        similarcolourreplace(
                            productSkuColor,
                            it.key,
                            matchingProductEntriesJustSKU,
                            it.value,
                            product,
                            productSkuCode,
                            credentials,
                            readOnly
                        )
                    }
                } else {
                    setNewSkuForProduct(
                        product,
                        "$productSkuCode-${matchingProductEntries.first().colorcode}",
                        credentials, readOnly
                    )
                }
            }
        }
        page++
    } while (products.isNotEmpty())
}

private fun similarcolourreplace(
    productSkuColor: String,
    startingColour: String,
    matchingProductEntriesJustSKU: List<JsonEntry>,
    endColour: String,
    product: Product,
    productSkuCode: String,
    credentials: String,
    readOnly: Boolean
) {
    if (productSkuColor==startingColour) {
        val goldMatchingEntries =
            matchingProductEntriesJustSKU.filter { it.colorname.equals(endColour, true) }
        if (!goldMatchingEntries.isEmpty()) {
            setNewSkuForProduct(product, "$productSkuCode $endColour", credentials, readOnly)
        }
    }
}

private fun updateSkuToMatchRegex(
    product: Product, productSkuWithColourNameRegex: Regex, credentials: String, readOnly: Boolean
): Boolean {
    if (!product.sku.matches(productSkuWithColourNameRegex)) {
        //println("WARNING: Product SKU '${product.sku}' does not match the regex")
        //println(product.permalink)
        if (product.sku.matches(Regex("^\\d{5}$"))) {
            val colorsMap = mapOf(
                "NAVY BLUE" to "NAVY BLUE",
                "Πράσινο" to "ΠΡΑΣΙΝΟ",
                "IVORY" to "IVORY",
                "ΡΟΖ" to "ΡΟΖ",
                "Ηλεκτρίκ" to "ΗΛΕΚΤΡΙΚ",
                "Οινοπνευματί" to "ΟΙΝΟΠΝΕΥΜΑΤΙ",
                "Τιρκουάζ" to "ΤΥΡΚΟΥΑΖ",
                "Τυρκουάζ" to "ΤΥΡΚΟΥΑΖ",
                "OLD LILA" to "OLD LILA",
                "Λιλά" to "ΛΙΛΑ",
                "ΜΠΛΕ" to "ΜΠΛΕ",
                "Ασημί" to "ΑΣΗΜΙ",
                "ΜΠΛΕ-ΜΠΕΖ" to "ΜΠΛΕ-ΜΠΕΖ",
                "LIGHT GOLD" to "LIGHT GOLD",
                "GOLD" to "GOLD",
                "OLD PINK" to "OLD PINK",
                "Μαύρο" to "ΜΑΥΡΟ",
                "Πετρόλ" to "ΠΕΤΡΟΛ",
                "ΜΠΕΖ" to "ΜΠΕΖ",
                "Ματζέντα" to "MAJENTA",
                "Majenta" to "MAJENTA",
                "Lime" to "LIME",
                "Ταμπά" to "ΤΑΜΠΑ",
                "Φούξια" to "ΦΟΥΞΙΑ",
                "LIGHT BLUE" to "LIGHT BLUE",
                "Μωβ" to "ΜΩΒ",
                "Σιέλ" to "ΣΙΕΛ",
                "Πούρο" to "ΠΟΥΡΟ",
                "Κόκκινο" to "ΚΟΚΚΙΝΟ",
                "Κερασί" to "ΚΕΡΑΣΙ",
                "Μπρονζέ" to "ΜΠΡΟΝΖΕ",
                "Μπορντώ" to "ΜΠΟΡΝΤΩ",
                "Μπορντό" to "ΜΠΟΡΝΤΩ",
                "Βιολετί" to "ΒΙΟΛΕΤΙ",
                "Βυσσινί" to "ΒΥΣΙΝΙ",
                "Ανθρακί" to "ΑΝΘΡΑΚΙ",
                "Terracotta" to "ΤΕΡΑΚΟΤΑ",
                "Πορτοκαλί" to "ΠΟΡΤΟΚΑΛΙ",
                "Καφέ" to "ΚΑΦΕ",
                "Εμπριμέ" to "ΕΜΠΡΙΜΕ",
                "Χακί" to "ΧΑΚΙ",
                "Λευκό" to "ΛΕΥΚΟ",
                "Pink" to "PINK",
                "NUDE" to "NUDE",
                "Χρυσό" to "ΧΡΥΣΟ",
                "Εκρού" to "ΕΚΡΟΥ",
                "Μέντα" to "ΜΕΝΤΑ",
                "Σάπιο Μήλο" to "ΣΑΠΙΟ ΜΗΛΟ",
                "Κοραλλί" to "ΚΟΡΑΛΙ",
                "Κοραλί" to "ΚΟΡΑΛΙ",
                "INDIGO" to "INDIGO",
                "Σμαραγδί" to "ΣΜΑΡΑΓΔΙ",
                "OLIVE" to "OLIVE"
            )

            val titleColourMapKey = colorsMap.keys.find { product.name.contains(it, ignoreCase = true) }
            if (titleColourMapKey!=null) {
                println("Using colour from title to update product ")
//                        println("Title is: ${product.name}")
//                        println("Colour from title is: ${titleColourMapKey}")
                setNewSkuForProduct(
                    product,
                    "${product.sku} ${colorsMap[titleColourMapKey]}",
                    credentials,
                    readOnly
                )
            } else {
//                        val desktop = Desktop.getDesktop()
//                        desktop.browse(URI(product.permalink));
                println(product.permalink)
                println()
            }
        } else {
            // This prints these bridal dresses
            println("WARNING: products with weird SKUs ${product.sku}")
        }
        return false
    }
    return true
}

fun setNewSkuForProduct(product: Product, newSku: String, credentials: String, readOnly: Boolean) {
    println("updating old SKU '${product.sku}' with newSku '$newSku'")
    if (!readOnly) {
        updateProductSKU(product.id, newSku, credentials)
    }
}

fun setNewSkuForVariation(
    product: Product,
    variation: Variation,
    newSku: String,
    credentials: String,
    readOnly: Boolean
) {
    println("updating old variation SKU '${variation.sku}' with newSku '$newSku'")
    if (!readOnly) {
        updateProductVariationSku(
            productId = product.id,
            variationId = variation.id,
            newSku,
            credentials
        )
    }
}

fun updateProductVariationSku(productId: Int, variationId: Int, newSKU: String, credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId/variations/$variationId"
    val requestBody = """
        {
            "sku": "$newSKU"
        }
    """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .put(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        } else {
            println("Product SKU updated successfully: $newSKU")
        }
    }
}


fun updateProductSKU(productId: Int, newSKU: String, credentials: String) {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$productId"
    val requestBody = """
        {
            "sku": "$newSKU"
        }
    """.trimIndent().toRequestBody("application/json".toMediaTypeOrNull())

    val request = Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .put(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Unexpected code $response")
        } else {
            println("Product SKU updated successfully: $newSKU")
        }
    }
}


private fun getProducts(page: Int, credentials: String): List<Product> {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products?page=$page&per_page=100"
    val request = Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        return mapper.readValue(response.body?.string() ?: "")
    }
}

fun getVariation(variationId: Int, credentials: String): Variation {
    val url = "https://foryoufashion.gr/wp-json/wc/v3/products/$variationId"
    val request = Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        return mapper.readValue(response.body?.string() ?: "")
    }
}

fun fetchJsonData(url: String): List<JsonEntry> {
    val request = Request.Builder()
        .url(url)
        .build()

    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        return mapper.readValue(response.body?.string() ?: "")
    }
}


fun <T> retry(
    times: Int,
    initialDelay: Long,
    factor: Double = 2.0,
    block: () -> T
): T {
    var currentDelay = initialDelay
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Thread.sleep(currentDelay)
        currentDelay = (currentDelay * factor).toLong()
    }
    return block() // last attempt
}
