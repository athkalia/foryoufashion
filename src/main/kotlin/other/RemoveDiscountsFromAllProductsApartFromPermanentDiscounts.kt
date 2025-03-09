package other

import allAutomatedChecks.PriceType
import allAutomatedChecks.fetchAllProducts
import allAutomatedChecks.updateProductPrice
import okhttp3.Credentials
import writeConsumerKey
import writeConsumerSecret

fun main() {
    val credentials = Credentials.basic(writeConsumerKey, writeConsumerSecret)
    val allProducts = fetchAllProducts(credentials)

    val selectedCategories = setOf(
        "nyfika-foremata",
        "special-occasion",
        "plus-size",
        "vradina-foremata",
        "lefka-foremata",
        "cocktail-foremata",
        "plus-size-foremata",
    )

    val alwaysDiscountTagSlug = "ekptwseis-panta"

    for (product in allProducts) {
        if (product.status!="draft" && product.status!="private") {
            val isInSelectedCategory = product.categories.any { it.slug in selectedCategories }
            val hasAlwaysDiscountTag = product.tags.any { it.slug==alwaysDiscountTagSlug }
            if (isInSelectedCategory) {
                if (!hasAlwaysDiscountTag) {
                    val productVariations = allAutomatedChecks.getVariations(product.id, credentials)
                    for (variation in productVariations) {
                        val salePrice = variation.sale_price.toDoubleOrNull()
                        if (salePrice!=null && salePrice > 0) {
                            println("ACTION: Removing discount for variation ${variation.sku} of product ${product.sku}")
                            updateProductPrice(
                                product.id,
                                variationId = variation.id,
                                updatedPrice = "",
                                priceType = PriceType.SALE_PRICE,
                                credentials
                            )
                        }
                    }
                } else {
                    println("Product ${product.sku} has permanent discount tag - skipping..")
                }
            }
        }
    }
}
