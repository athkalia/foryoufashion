package other

import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import Order
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Credentials
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Properties
import readOnlyConsumerKey
import readOnlyConsumerSecret
import sakisForYouFashionEmailPassword

const val MANUAL_INPUT_totalMarketingExpensesForMonth: Double = 0.0

fun main(args: Array<String>) {
    val toEmail = "ads@conversion.gr"
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val previousMonthDate = getPreviousMonthDate()
    val currentMonthOrders = fetchOrders(previousMonthDate, credentials)
    val totalRevenue = calculateTotalRevenue(currentMonthOrders)
    val nonCompletedOrdersPercentage = calculateNonCompletedOrdersPercentage(currentMonthOrders)
    val completedRevenue = calculateCompletedRevenue(currentMonthOrders)

    val monthAndYear = previousMonthDate.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    val emailContent = generateEmailContent(
        totalRevenue,
        nonCompletedOrdersPercentage,
        completedRevenue,
        monthAndYear,
    )
    sendEmail(toEmail, emailContent, monthAndYear)
    println("ACTION: Email sent to $toEmail")
}

private fun getPreviousMonthDate(): LocalDate = LocalDate.now().minusMonths(1)

fun fetchOrders(previousMonthDate: LocalDate, credentials: String): List<Order> {
    val client = OkHttpClient()
    val orders = mutableListOf<Order>()
    val currentMonth = previousMonthDate.monthValue
    val currentYear = previousMonthDate.year
    var page = 1
    var hasMoreOrders: Boolean

    do {
        val url = "https://foryoufashion.gr/wp-json/wc/v3/orders?page=$page&per_page=100"
        val request = Request.Builder().url(url).header("Authorization", credentials).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body?.string()!!
            val mapper = jacksonObjectMapper()
            val fetchedOrders: List<Order> = mapper.readValue(body)
            orders.addAll(fetchedOrders.filter {
                val orderDate = LocalDate.parse(it.date_created.substring(0, 10))
                orderDate.monthValue==currentMonth && orderDate.year==currentYear
            })
            hasMoreOrders = fetchedOrders.isNotEmpty()
        }
        page++
    } while (hasMoreOrders)

    return orders
}

fun calculateTotalRevenue(orders: List<Order>): Double {
    return orders.sumOf { it.total.toDouble() }
}

fun calculateNonCompletedOrdersPercentage(orders: List<Order>): Double {
    val totalOrders = orders.size
    val nonCompletedOrders = orders.count { it.status!="completed" }
    return if (totalOrders > 0) (nonCompletedOrders.toDouble() / totalOrders.toDouble()) * 100 else 0.0
}

fun calculateRefundedOrdersPercentage(orders: List<Order>): Double {
    val totalOrders = orders.size
    val returnedOrders = orders.count { it.status=="refunded" }
    return if (totalOrders > 0) (returnedOrders.toDouble() / totalOrders.toDouble()) * 100 else 0.0
}

fun calculateCompletedRevenue(orders: List<Order>): Double {
    return orders.filter { it.status=="completed" }.sumOf { it.total.toDouble() }
}

fun generateEmailContent(
    allOrdersRevenue: Double,
    nonCompletedOrdersPercentage: Double,
    completedOrdersRevenue: Double,
    monthAndYear: String,
): String {
    return """
        Καλησπέρα,

        Σας επισυνάπτω τις πωλήσεις απο το site για το μήνα $monthAndYear
        - Συνολικές Πωλήσεις: €${"%.2f".format(allOrdersRevenue)}
        - Έσοδα από ολοκληρωμένες παραγγελίες: €${"%.2f".format(completedOrdersRevenue)}
        - Μη ολοκληρωμένες παραγγελίες %: ${"%.2f".format(nonCompletedOrdersPercentage)}%
        - Συνολικά έξοδα marketing για το μήνα: €${"%.2f".format(MANUAL_INPUT_totalMarketingExpensesForMonth)} 
        - Συνολικό ROAS για όλο το site: ${"%.2f".format(completedOrdersRevenue / MANUAL_INPUT_totalMarketingExpensesForMonth)} 

        Φιλικά,
        Σάκης
    """.trimIndent()
}

fun sendEmail(recipientEmail: String, content: String, monthAndYear: String) {
    val username = "sakis@foryoufashion.gr"
    val props = Properties()
    props["mail.smtp.auth"] = "true"
    props["mail.smtp.starttls.enable"] = "true"
    props["mail.smtp.host"] = "mail.foryoufashion.gr"
    props["mail.smtp.port"] = "587"

    val session = Session.getInstance(props,
        object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(username, sakisForYouFashionEmailPassword)
            }
        })

    try {
        val message = MimeMessage(session)
        message.setFrom(InternetAddress(username))
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail))
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse("k.kaliakouda@foryoufashion.gr"))
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse("sakis@foryoufashion.gr"))
        message.subject = "For You Fashion - Έσοδα μήνα $monthAndYear"
        message.setText(content)
        Transport.send(message)
    } catch (e: MessagingException) {
        throw RuntimeException(e)
    }
}
