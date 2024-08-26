import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.LocalDate

@JsonIgnoreProperties(ignoreUnknown = true)
data class Order(
    val id: Int,
    val date_created: String,
    val status: String,
    val payment_method_title: String,
    val payment_method: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Product(
    val id: Int,
    val name: String,
    val variations: List<Int>,
    val sku: String,
    val status: String,
    val permalink: String,
    val short_description: String,
    val description: String,
    val regular_price: String,
    val sale_price: String,
    val price: String,
    val images: List<ProductImage>,
    val manage_stock: Boolean,
    val stock_quantity: Int?,
    val tags: List<Tag>,
    val attributes: List<Attribute>,
    val date_created: String? = null,
    val meta_data: List<MetaData>,
    val categories: List<Category>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaCacheEntry(
    val url: String,
    val isFileMissing: Boolean,
    val lastChecked: LocalDate
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProductImage(
    val id: Int,
    val src: String,
    val name: String,
    val alt: String,
    val width: Int,
    val height: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Media(
    val id: Int,
    val post: Int?, // This indicates if the media is attached to a post or not
    val media_type: String, // Should be "image" for images
    val mime_type: String, // MIME type to ensure you're working with images
    val source_url: String, // The URL of the image file
    val date: String // Date when the media was uploaded (ISO 8601 format)
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tag(
    val id: Int? = null,
    val name: String,
    val count: Int? = null,
    val slug: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Variation(
    val id: Int,
    val sku: String,
    val attributes: List<Attribute>,
    val regular_price: String,
    val sale_price: String,
    val stock_status: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Attribute(
    val id: Int? = null,
    val name: String,
    val variation: Boolean,
    val option: String?,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JsonEntry(
    val code: String,
    val colorname: String,
    val colorcode: String,
    val sizename: String?,
    val sizecode: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Category(
    val id: Int,
    val name: String,
    val slug: String,
    val count: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class AttributeTerm(
    val id: Int,
    val name: String,
    val count: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaData(
    val id: Int,
    val key: String,
    val value: Any
)
