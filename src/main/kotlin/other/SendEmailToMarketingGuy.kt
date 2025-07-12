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

const val MANUAL_INPUT_totalMarketingExpensesForMonth: Double = 4000.0
const val monthlyMarketingPayment: Double = 350.0
const val totalMarketingSpendIncludingMonthlyPayment =
    MANUAL_INPUT_totalMarketingExpensesForMonth + monthlyMarketingPayment
const val test = true
const val thisMonth = true

fun main(args: Array<String>) {
    val toEmail = if (test || thisMonth) "sakis@foryoufashion.gr" else "ads@conversion.gr"
    val credentials = Credentials.basic(readOnlyConsumerKey, readOnlyConsumerSecret)
    val month = if (thisMonth) getCurrentMonth() else getPreviousMonthDate()
    val orders = fetchOrders(month, credentials)
    val refundedOrderCountPercentage = calculateRefundedOrderCountPercentage(orders)
    val refundedMoneyPercentage = calculateRefundedOrderMoneyPercentage(orders)
    val completedRevenue = calculateCompletedRevenue(orders)
    val refundedRevenue = calculateRefundedRevenue(orders)

    val monthAndYear = month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    val emailContent = generateEmailContent(
        refundedOrderCountPercentage,
        refundedMoneyPercentage,
        completedRevenue,
        refundedRevenue,
        monthAndYear,
    )
    println(emailContent)
    sendEmail(toEmail, emailContent, monthAndYear)
    println("ACTION: Email sent to $toEmail")
}

fun calculateRefundedOrderMoneyPercentage(orders: List<Order>): Double {
    val refundedOrdersMoney = orders.filter { it.status=="refunded" }.sumOf { it.total.toDouble() }
    val completedOrdersMoney =
        orders.filter { it.status=="completed" || it.status=="processing" }.sumOf { it.total.toDouble() }
    return (refundedOrdersMoney / (completedOrdersMoney + refundedOrdersMoney)) * 100
}

private fun getPreviousMonthDate(): LocalDate = LocalDate.now().minusMonths(1)
private fun getCurrentMonth(): LocalDate = LocalDate.now().minusMonths(0)

fun fetchOrders(previousMonthDate: LocalDate, credentials: String): List<Order> {
    println("Fetching orders..")
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

fun calculateRefundedOrderCountPercentage(orders: List<Order>): Double {
    val refundedOrders = orders.count { it.status=="refunded" }
    val completedOrders = orders.count { it.status=="completed" || it.status=="processing" }
    return (refundedOrders.toDouble() / (completedOrders + refundedOrders).toDouble()) * 100
}

fun calculateCompletedRevenue(orders: List<Order>): Double {
    val completedOrders = orders.filter { it.status=="completed" || it.status=="processing" }
    return completedOrders.sumOf { it.total.toDouble() }
}

fun calculateRefundedRevenue(orders: List<Order>): Double {
    return orders.filter { it.status=="refunded" }.sumOf { it.total.toDouble() }
}

fun generateEmailContent(
    refundedOrderCountPercentage: Double,
    refundedMoneyPercentage: Double,
    completedOrdersRevenue: Double,
    refundedRevenue: Double,
    monthAndYear: String,
): String {
    return """
        Καλησπέρα,

        Σας επισυνάπτω τις πωλήσεις από το site για το μήνα $monthAndYear
        - Έσοδα από ολοκληρωμένες παραγγελίες: €${"%.2f".format(completedOrdersRevenue + refundedRevenue)}
        - Επιστροφές χρημάτων: €${"%.2f".format(refundedRevenue)}
        - Έσοδα μετά τις επιστροφές: €${"%.2f".format(completedOrdersRevenue)}
        - Ποσοστό επιστροφών σε αριθμο παραγγελιων: ${"%.2f".format(refundedOrderCountPercentage)}%
        - Ποσοστό επιστροφών σε λεφτα: ${"%.2f".format(refundedMoneyPercentage)}%
        - Συνολικά έξοδα marketing για το μήνα: €${"%.2f".format(totalMarketingSpendIncludingMonthlyPayment)} 
        - Συνολικό πραγματικό ROAS για όλο το site: ${"%.2f".format(completedOrdersRevenue / totalMarketingSpendIncludingMonthlyPayment)} 

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
        if (test || thisMonth) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse("sakis@foryoufashion.gr"))
        } else {
            message.setRecipients(
                Message.RecipientType.CC,
                InternetAddress.parse("k.kaliakouda@foryoufashion.gr, sakis@foryoufashion.gr")
            )
        }
        message.subject = "For You Fashion - Έσοδα μήνα $monthAndYear"
        message.setText(content)
        Transport.send(message)
    } catch (e: MessagingException) {
        throw RuntimeException(e)
    }
}
