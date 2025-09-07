import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * [2025-09-07] A new class to handle fetching and processing of M3U playlist files.
 */
public class M3UProcessor
{
    /**
     * Fetches an M3U playlist from a given URL, filters it to include only "MLB" and "NFL"
     * group titles, and returns the processed playlist as a string.
     *
     * @param urlString The URL of the M3U playlist to process.
     * @return A string containing the filtered M3U playlist, or null if an error occurs.
     */
    public String process(String urlString)
    {
        StringBuilder filteredContent = new StringBuilder();
        HttpURLConnection connection = null;

        try
        {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000); // 10 seconds
            connection.setReadTimeout(10000);    // 10 seconds

            int status = connection.getResponseCode();

            if (status != HttpURLConnection.HTTP_OK)
            {
                System.err.println("M3UProcessor: Failed to fetch M3U. Server responded with status: " + status);
                return null;
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)))
            {
                String line;
                boolean entryFound = false;

                // The first line of an extended M3U file must be #EXTM3U
                line = in.readLine();
                if (line != null && line.trim().startsWith("#EXTM3U"))
                {
                    filteredContent.append(line).append("\n");
                }
                else
                {
                    // If the file doesn't start with the mandatory header, it's not a valid extended M3U.
                    // We could be lenient, but for now we'll treat it as an error.
                    System.err.println("M3UProcessor: The remote file is not a valid extended M3U file.");
                    return null;
                }


                while ((line = in.readLine()) != null)
                {
                    if (line.startsWith("#EXTINF"))
                    {
                        if (line.contains("group-title=\"NFL\"") || line.contains("group-title=\"MLB\""))
                        {
                            filteredContent.append(line).append("\n");
                            entryFound = true; // Mark that we found a valid entry and need the next line (the URL)
                        }
                        else
                        {
                            entryFound = false; // Reset if the group-title doesn't match
                        }
                    }
                    else if (entryFound)
                    {
                        // This line is the URL for the previously found EXTINF entry
                        filteredContent.append(line).append("\n");
                        entryFound = false; // Reset after capturing the URL
                    }
                }
            }
        }
        catch (IOException e)
        {
            System.err.println("M3UProcessor: I/O error while processing the M3U file: " + e.getMessage());
            return null; // Return null to indicate failure
        }
        finally
        {
            if (connection != null)
            {
                connection.disconnect();
            }
        }

        return filteredContent.toString();
    }
}
