import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

fun main() {
    val url = "https://foryoufashion.gr/wp-content/uploads/webexpert-google-merchant-product-data-feed/google_merchant.xml"
    val storeCodes = listOf(
        "4026847598477194766",
        "6017085857398635205",
        "11148567353721964103",
        "17956680230070379877"
    )
    val fileName = "updated_local_inventory.xml"

    // Step 1: Fetch the XML
    val client = OkHttpClient()
    val request = Request.Builder().url(url).build()
    val response = client.newCall(request).execute()

    if (!response.isSuccessful) throw Exception("Failed to fetch XML")

    val xmlContent = response.body?.string() ?: throw Exception("Empty response")

    // Step 2: Parse the XML
    val dbFactory = DocumentBuilderFactory.newInstance()
    dbFactory.isNamespaceAware = true
    val dBuilder = dbFactory.newDocumentBuilder()
    val doc = dBuilder.parse(xmlContent.byteInputStream())
    doc.documentElement.normalize()

    // Print original item count
    val originalItems = doc.getElementsByTagName("item")
    println("Original item count: ${originalItems.length}")

    // Step 3: Add store codes
    val root = doc.documentElement
    val items = root.getElementsByTagName("item")

    // Create a list to hold the new entries
    val newEntries = mutableListOf<Element>()

    // Add the first store code to the existing items
    for (i in 0 until items.length) {
        val item = items.item(i) as Element
        val storeCodeElement = doc.createElement("g:store_code")
        storeCodeElement.appendChild(doc.createTextNode(storeCodes[0]))
        item.appendChild(storeCodeElement)
    }

    // Create new entries for the other store codes
    for (i in 0 until originalItems.length) {
        val item = originalItems.item(i) as Element
        storeCodes.drop(1).forEach { storeCode ->
            val newEntry = item.cloneNode(true) as Element
            val storeCodeElement = newEntry.getElementsByTagName("g:store_code").item(0) as Element
            storeCodeElement.textContent = storeCode
            newEntries.add(newEntry)
        }
    }

    // Append new entries to the document
    newEntries.forEach { root.appendChild(it) }

    // Re-count the items in the updated document
    val updatedItems = doc.getElementsByTagName("item")
    println("Updated item count: ${updatedItems.length}")

    // Step 4: Write the updated XML to a file
    val transformerFactory = TransformerFactory.newInstance()
    val transformer = transformerFactory.newTransformer()
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    val source = DOMSource(doc)
    val result = StreamResult(File(fileName))
    transformer.transform(source, result)

    println("Updated XML written to $fileName")
}
