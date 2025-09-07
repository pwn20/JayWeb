import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.RandomAccessFile;
import java.util.Collections;

/**
 * [2025-07-29] New record to represent a parsed byte range
 *
 * @param start         The start byte of the range.
 * @param end           The end byte of the range. A value of -1 indicates an open-ended range.
 * @param totalLength   The total length of the file, used to calculate the length of open-ended ranges.
 */
record Range(long start, long end, long totalLength)
{
    // A method to indicate if the range is open-ended (e.g., bytes=500-)
    public boolean isOpenEnded()
    {
        return end == -1;
    }

    // Returns the length of this specific range, useful for Content-Length header
    public long getLength()
    {
        if (isOpenEnded())
        {
            return totalLength - start;
        }
        return end - start + 1;
    }

    // Check if the range is valid
    public boolean isValid()
    {
        // Start must be non-negative and within the file bounds
        if (start < 0 || start >= totalLength)
        {
            return false;
        }

        // If not open-ended, end must be after start and within file bounds
        if (!isOpenEnded() && (end < start || end >= totalLength))
        {
            return false;
        }

        return true;
    }
}

public class ClientHandler implements Runnable
{
    // Record to represent the parsed HTTP request
    private record HttpRequest(String method, String uri, Map<String, String> headers)
    {
        public String getHeader(String name)
        {
            return headers.getOrDefault(name, null);
        }
    }

    // [2025-08-07] Functional interface for handling specific commands
    @FunctionalInterface
    private interface Command
    {
        void execute(OutputStream out) throws IOException;
    }

    // [2025-08-07] A map to hold special URI commands and their handlers
    private final Map<String, Command> specialCommands = new HashMap<>();

    private static final String DEFAULT_FILE = "index.html";
    private final Socket clientSocket;
    private final JayWeb.Config appConfig;
    private final boolean debug;

    public ClientHandler(Socket socket, JayWeb.Config config, boolean debug)
    {
        this.clientSocket = socket;
        this.appConfig = config;
        this.debug = debug;
        // [2025-08-07] Initialize the map here, referencing the dedicated method.
        specialCommands.put("/suspend", this::handleSuspendCommand);
        specialCommands.put("/channels.m3u", this::handleM3uRequest);
        // [2025-08-07] Add more special commands here later, e.g., for reboot or hibernate.
    }

    private static String getTimestamp()
    {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss a");
        return now.format(formatter);
    }

    @Override
    public void run()
    {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = clientSocket.getOutputStream();
        )
        {
            // Set a timeout for reading the request to prevent threads from hanging indefinitely
            clientSocket.setSoTimeout(5000); // 5 seconds

            HttpRequest request = parseHttpRequest(in);

            // [2025-08-07] Check for special URIs before attempting to serve a file.
            if (this.specialCommands.containsKey(request.uri()))
            {
                this.specialCommands.get(request.uri()).execute(out);
            }
            else
            {
                // [2025-08-07] Existing logic for file serving
                Path resolvedFilePath = getResolvedFilePath(request.uri());
                handleFileRequest(out, resolvedFilePath, request);
            }

        }
        catch (SocketTimeoutException ex)
        {
            System.err.println("[" + getTimestamp() + "]: Socket timeout during request from " + clientSocket.getInetAddress().getHostAddress());
        }
        catch (IOException ex)
        {
            System.err.println("[" + getTimestamp() + "]: An I/O error occurred: " + ex.getMessage());
        }
        finally
        {
            try
            {
                clientSocket.close();
                if (debug)
                {
                    System.out.println("[" + getTimestamp() + " - " + clientSocket.getInetAddress().getHostAddress() + "]: Connection closed.");
                }
            }
            catch (IOException ex)
            {
                System.err.println("[" + getTimestamp() + "]: Error closing client socket: " + ex.getMessage());
            }
        }
    }

    /**
     * [2025-08-07] Dedicated method to handle the "/suspend" URI.
     * This separates the command's logic from the map's initialization.
     *
     * @param out The output stream to write the response to.
     * @throws IOException If an I/O error occurs.
     */
    private void handleSuspendCommand(OutputStream out) throws IOException
    {
        if (this.debug)
        {
            System.out.println("[" + getTimestamp() + "]: Received special command: /suspend");
        }

        try
        {
            String successBody = "<!DOCTYPE html><html><head><title>Success</title></head><body><h1>PC is going to sleep...</h1></body></html>";
            byte[] bodyBytes = successBody.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();

            String[] command = {"rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"};

            // For testing purposes, we'll just log the command instead of executing it.
            // Uncomment the line below to enable.
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.start();
        }
        catch (IOException e)
        {
            System.err.println("[" + getTimestamp() + "]: Error handling /suspend command: " + e.getMessage());
            sendErrorResponse(out, "500 Internal Server Error", "<h1>500 Internal Server Error</h1><p>Error processing command.</p>");
        }
    }

    /**
     * [2025-09-07] Handles the special request for a filtered M3U playlist.
     *
     * @param out The output stream to write the response to.
     * @throws IOException If an I/O error occurs.
     */
    private void handleM3uRequest(OutputStream out) throws IOException
    {
        if (this.debug)
        {
            System.out.println("[" + getTimestamp() + "]: Received special command: /channels.m3u");
        }

        M3UProcessor processor = new M3UProcessor();
        String filteredM3u = processor.process(appConfig.m3uRemoteUrl());

        if (filteredM3u != null && !filteredM3u.isEmpty())
        {
            byte[] bodyBytes = filteredM3u.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/x-mpegurl; charset=UTF-8\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        }
        else
        {
            sendErrorResponse(out, "500 Internal Server Error", "<h1>500 Internal Server Error</h1><p>Failed to process M3U playlist.</p>");
        }
    }


    /**
     * Parses the HTTP request from the input stream.
     *
     * @param in The BufferedReader connected to the client.
     * @return A HttpRequest object representing the parsed request, or null if parsing fails.
     * @throws IOException If an I/O error occurs while reading the stream.
     */
    private HttpRequest parseHttpRequest(BufferedReader in) throws IOException
    {
        String firstLine;
        try
        {
            firstLine = in.readLine();
            if (firstLine == null || firstLine.isEmpty())
            {
                return null; // Empty request or connection closed
            }
        }
        catch (SocketTimeoutException ste)
        {
            throw ste;
        }

        if (debug)
        {
            System.out.println("[" + getTimestamp() + " - " + clientSocket.getInetAddress().getHostAddress() + "]: Received: " + firstLine);
        }

        String[] parts = firstLine.split(" ");
        if (parts.length < 3)
        {
            return null;
        }
        String method = parts[0];
        String uri = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty())
        {
            String[] headerParts = line.split(": ", 2);
            if (headerParts.length == 2)
            {
                headers.put(headerParts[0].trim(), headerParts[1].trim());
            }
        }

        return new HttpRequest(method, uri, Collections.unmodifiableMap(headers));
    }

    /**
     * Resolves the requested URI to a physical file path on the server.
     *
     * @param uri The URI from the HTTP request.
     * @return The Path to the requested file, or to the default file if the URI is a directory.
     */
    private Path getResolvedFilePath(String uri)
    {
        Path basePath = Paths.get(appConfig.baseDir()).toAbsolutePath().normalize();

        // [2025-08-07] Fix: Strip the query string from the URI before resolving the path.
        // The Java Path class does not support the '?' character in a file path.
        int queryIndex = uri.indexOf('?');
        String cleanUri = (queryIndex != -1) ? uri.substring(0, queryIndex) : uri;
        Path requestedPath = Paths.get(basePath.toString(), cleanUri).normalize();

        // Check against directory traversal
        if (!requestedPath.startsWith(basePath))
        {
            return null; // For security, disallow paths outside the base directory
        }

        File file = requestedPath.toFile();
        if (file.isDirectory())
        {
            return requestedPath.resolve(DEFAULT_FILE);
        }

        return requestedPath;
    }

    /**
     * Handles the file request, including single-file and range-based streaming.
     *
     * @param out The output stream to write to.
     * @param resolvedFilePath The path to the file to serve.
     * @param request The HttpRequest object.
     * @throws IOException If an I/O error occurs.
     */
    private void handleFileRequest(OutputStream out, Path resolvedFilePath, HttpRequest request) throws IOException
    {
        if (resolvedFilePath == null)
        {
            sendErrorResponse(out, "403 Forbidden", "<h1>403 Forbidden</h1><p>Access to this resource is forbidden.</p>");
            return;
        }

        File file = resolvedFilePath.toFile();
        if (!file.exists() || !file.isFile() || !file.canRead())
        {
            sendErrorResponse(out, "404 Not Found", "<h1>404 Not Found</h1><p>The requested file was not found.</p>");
            return;
        }

        String rangeHeader = request.getHeader("Range");
        if (rangeHeader != null)
        {
            handleRangeRequest(out, file, rangeHeader);
        }
        else
        {
            // [2025-08-07] Call a new method to handle a regular (non-range) request
            handleRegularRequest(out, file);
        }
    }

    /**
     * [2025-08-07] A new method to handle standard, non-range file requests.
     * It correctly sends the HTTP headers before streaming the file content.
     *
     * @param out The output stream to write the response to.
     * @param file The file to stream.
     * @throws IOException If an I/O error occurs.
     */
    private void handleRegularRequest(OutputStream out, File file) throws IOException
    {
        String mimeType = MimeTypes.getMimeType(file.getName());
        long contentLength = file.length();

        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + mimeType + "\r\n" +
                "Content-Length: " + contentLength + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        streamFile(out, file, 0, contentLength);
    }

    /**
     * Handles a request with a "Range" header, streaming a specific part of the file.
     *
     * @param out The output stream to write the response to.
     * @param file The file to stream.
     * @param rangeHeader The value of the "Range" header (e.g., "bytes=0-1023").
     * @throws IOException If an I/O error occurs.
     */
    private void handleRangeRequest(OutputStream out, File file, String rangeHeader) throws IOException
    {
        String rangeValue = rangeHeader.replace("bytes=", "");
        String[] rangeParts = rangeValue.split("-");

        long totalLength = file.length();
        long start, end;

        try
        {
            if (rangeParts.length == 2)
            {
                if (rangeParts[0].isEmpty() && !rangeParts[1].isEmpty())
                {
                    // Case: "bytes=-500" -> last 500 bytes
                    start = totalLength - Long.parseLong(rangeParts[1]);
                    end = totalLength - 1;
                }
                else if (!rangeParts[0].isEmpty() && rangeParts[1].isEmpty())
                {
                    // Case: "bytes=500-" -> from byte 500 to the end
                    start = Long.parseLong(rangeParts[0]);
                    end = totalLength - 1;
                }
                else
                {
                    // Case: "bytes=500-999" -> standard range
                    start = Long.parseLong(rangeParts[0]);
                    end = Long.parseLong(rangeParts[1]);
                }
            }
            else
            {
                sendErrorResponse(out, "400 Bad Request", "<h1>400 Bad Request</h1><p>Invalid Range header format.</p>");
                return;
            }
        }
        catch (NumberFormatException e)
        {
            sendErrorResponse(out, "400 Bad Request", "<h1>400 Bad Request</h1><p>Invalid number format in Range header.</p>");
            return;
        }

        // Validate the parsed range
        Range range = new Range(start, end, totalLength);
        if (!range.isValid())
        {
            sendErrorResponse(out, "416 Range Not Satisfiable", "<h1>416 Range Not Satisfiable</h1><p>The requested range is not valid.</p>");
            return;
        }

        // Send a 206 Partial Content response
        String headers = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: " + MimeTypes.getMimeType(file.getName()) + "\r\n" +
                "Content-Range: bytes " + range.start() + "-" + range.end() + "/" + totalLength + "\r\n" +
                "Content-Length: " + range.getLength() + "\r\n" +
                "Connection: keep-alive\r\n" +
                "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        streamFile(out, file, range.start(), range.getLength());
    }

    /**
     * Streams a file or a portion of a file to the client.
     *
     * @param out The output stream to write to.
     * @param file The file to stream.
     * @param start The starting position in the file to read from.
     * @param contentLengthToSend The number of bytes to send.
     * @throws IOException If an I/O error occurs.
     */
    private void streamFile(OutputStream out, File file, long start, long contentLengthToSend) throws IOException
    {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r"))
        {
            byte[] buffer = new byte[8192]; // Buffer size for streaming
            raf.seek(start); // Set the file pointer to the start position

            long totalBytesSent = 0;
            int bytesRead;

            while (totalBytesSent < contentLengthToSend && (bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, contentLengthToSend - totalBytesSent))) != -1)
            {
                out.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
            }
            out.flush(); // Flush any remaining data in the output stream
        }
        catch (IOException e)
        {
            if(debug)
                System.err.println("[" + getTimestamp() + "]: Error streaming file " + file.getAbsolutePath() + ": " + e.getMessage());
            // In case of streaming error after headers sent, we can't send a 500 error page.
            // The connection might need to be closed or an error logged.
            throw e; // Re-throw to be caught by outer handler's exception
        }
    }

    // Helper method to send error responses
    private static void sendErrorResponse(OutputStream out, String status, String body) throws IOException
    {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" + // Always close connection on error
                "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}
