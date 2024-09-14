package reports

import forYouFashionFtpPassword
import forYouFashionFtpUrl
import forYouFashionFtpUsername
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

fun main(args: Array<String>) {
    listLargeFiles(forYouFashionFtpUsername, forYouFashionFtpPassword, "/")
}

fun listLargeFiles(ftpUsername: String, ftpPassword: String, startDirectory: String) {
    val ftpClient = FTPClient()

    try {
        // Connect and login to the FTP server
        ftpClient.connect(forYouFashionFtpUrl)
        ftpClient.login(ftpUsername, ftpPassword)

        val result = mutableListOf<Pair<String, Long>>()
        listFilesRecursively(ftpClient, startDirectory, result)

        println("Total number of files: ${result.size}")
        // Print the full file paths and their sizes
        result.forEach { (filePath, size) ->
            println("File: $filePath, Size: ${size / 1_048_576.0} MB")
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

// Recursive function to list files from all directories
fun listFilesRecursively(ftpClient: FTPClient, directory: String, result: MutableList<Pair<String, Long>>) {
    // Change to the directory
    ftpClient.changeWorkingDirectory(directory)

    // List all files and directories in the current directory
    val files: Array<FTPFile> = ftpClient.listFiles()

    for (file in files) {
        val filePath = "$directory/${file.name}" // Construct the full file path
        if (file.isFile) {
            // Check if it's larger than 300KB and not a ".js" file
            if (file.size > 300_000
                && !file.name.endsWith(".js")
                && !file.name.endsWith(".ttf")
                && !file.name.endsWith(".css")
                && !file.name.contains("-scaled")
            ) {
                result.add(filePath to file.size)
            }
        } else if (file.isDirectory && file.name!="." && file.name!="..") {
            // Recursive call to process subdirectories
            println("Recursing into $filePath, so far size: ${result.size}")
            listFilesRecursively(ftpClient, filePath, result)
        }
    }
}
