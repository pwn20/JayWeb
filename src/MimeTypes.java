import java.util.HashMap;
import java.util.Map;

/**
 * A utility class to determine the MIME type of a file based on its extension.
 */
public class MimeTypes
{
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static
    {
        // [2025-08-07] Initialize the map with a variety of common MIME types.
        // This ensures the server can correctly handle different file types.
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "application/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("zip", "application/zip");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("xml", "application/xml");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("ogg", "audio/ogg");
        MIME_TYPES.put("wav", "audio/wav");
        MIME_TYPES.put("webm", "video/webm");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("eot", "application/vnd.ms-fontobject");
        MIME_TYPES.put("otf", "font/otf");
        // Add more types as needed
    }

    /**
     * Returns the MIME type for a given file extension.
     * Defaults to "application/octet-stream" if the type is unknown.
     *
     * @param filename The name of the file, including its extension.
     * @return The corresponding MIME type string.
     */
    public static String getMimeType(String filename)
    {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1)
        {
            String extension = filename.substring(dotIndex + 1).toLowerCase();
            return MIME_TYPES.getOrDefault(extension, "application/octet-stream"); // Default for unknown types
        }
        return "application/octet-stream"; // Default if no extension
    }
}
