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

//    val foremataCategorySlug = "foremata"
    val olosomesFormesCategorySlug = "olosomes-formes"
    val alwaysDiscountTagSlug = "ekptwseis-panta"

    for (product in allProducts) {
        if (product.status!="draft") {
            val isInForemataCategory = product.categories.any { it.slug==olosomesFormesCategorySlug }
            val hasAlwaysDiscountTag = product.tags.any { it.slug==alwaysDiscountTagSlug }
            if (isInForemataCategory && !hasAlwaysDiscountTag) {
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
            }
        }
    }
}
