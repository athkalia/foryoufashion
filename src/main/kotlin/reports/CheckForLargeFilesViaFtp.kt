package reports

import forYouFashionFtpPassword
import forYouFashionFtpUrl
import forYouFashionFtpUsername
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

fun main(args: Array<String>) {
    listLargeFiles(forYouFashionFtpUsername, forYouFashionFtpPassword, "/")
}

fun listLargeFiles(
    ftpUsername: String,
    ftpPassword: String,
    startDirectory: String,
) {
    val ftpClient = FTPClient()

    try {
        ftpClient.controlEncoding = "UTF-8"
        ftpClient.autodetectUTF8 = true

        // Connect and login to the FTP server
        ftpClient.connect(forYouFashionFtpUrl)
        ftpClient.login(ftpUsername, ftpPassword)

        val filteredResults = mutableListOf<Pair<String, Long>>()
        val allResults = mutableListOf<Pair<String, Long>>()
        listFilesRecursively(ftpClient, startDirectory, filteredResults, allResults)

        println("Total number of all results: ${allResults.size}")
        println("Total number of filtered results: ${filteredResults.size}")
        filteredResults.forEach { (filePath, size) ->
//            println("File: $filePath, Size: ${size / 1_048_576.0} MB")
            println(filePath)
        }
        val allResultsTotalSize = allResults.sumOf { it.second }
        val filteredResultsTotalSize = filteredResults.sumOf { it.second }
        println("All results size: ${allResultsTotalSize / 1_048_576.0} MB")
        println("Filtered results size: ${filteredResultsTotalSize / 1_048_576.0} MB")
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

fun listFilesRecursively(
    ftpClient: FTPClient,
    directory: String,
    filteredResults: MutableList<Pair<String, Long>>,
    allResults: MutableList<Pair<String, Long>>,
) {
    ftpClient.changeWorkingDirectory(directory)
    val files: Array<FTPFile> = ftpClient.listFiles()
    val threeMonthsAgo = LocalDate.now().minus(3, ChronoUnit.MONTHS)
    for (file in files) {
        val filePath = "$directory/${file.name}" // Construct the full file path
        if (file.isFile) {
            allResults.add(filePath to file.size)
            val fileDate = file.timestamp.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            if (
            // file.size > 100_000
                fileDate.isBefore(threeMonthsAgo) &&
                !file.name.endsWith(".js") &&
                !file.name.endsWith(".ttf") &&
                !file.name.endsWith(".css") &&
                !file.name.endsWith(".php") &&
                !file.name.contains("-scaled") &&
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
