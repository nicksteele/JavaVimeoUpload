import org.scribe.builder.ServiceBuilder;
import org.scribe.builder.api.VimeoApi;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Token;
import org.scribe.model.Verb;
import org.scribe.oauth.OAuthService;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

public class UploadVimeoService {

    private static String methodBase = "http://vimeo.com/api/rest/v2?method=%s";
    private static OAuthService service;
    private static int bufferSize = 1048576; // 1 MB = 1048576 bytes
    private static Token accessToken;
    private static Token secretToken;

    /**
     * Initializes a service req/response for uploading, then sends
     *
     * @param endpoint
     * @param video
     * @param consumerToken
     * @param grandStToken
     * @throws FileNotFoundException
     * @throws IOException
     */
    public static boolean uploadVideo(String endpoint, MultipartFile video, Token consumerToken,
                                      Token userToken)throws IOException {
        secretToken = new Token(userToken.getToken(),userToken.getSecret());
        accessToken = new Token(consumerToken.getToken(),consumerToken.getSecret());
        service = new ServiceBuilder().provider(VimeoApi.class).apiKey(accessToken.getToken()).apiSecret(accessToken.getSecret()).build();

        long contentLength = video.getSize();
        String clString = Long.toString(contentLength);

        FileInputStream is = (FileInputStream) video.getInputStream();
        byte[] bytesChunk = new byte[bufferSize];

        int maxAttempts = 3;
        long lastByteOnServer = 0;
        String contentType = video.getContentType();
        boolean firstChunk = false;
        while(is.read(bytesChunk,0,bufferSize) != -1){
            lastByteOnServer = prepAndSendChunk(endpoint,clString,contentType,lastByteOnServer,bytesChunk,0,maxAttempts,firstChunk);
            if(lastByteOnServer == -1){
                return false;
            }
            firstChunk = true;
            //printProgress(video.getSize(),lastByteOnServer); //DEBUG
        }
        return true;
    }

    /**
     * Makes sure the chunk is formatted properly and calls for sending
     *
     * @param endpoint
     * @param contentLength
     * @param lastByteOnServer
     * @param chunk
     * @param attempt
     * @param maxAttempts
     * @param firstChunk
     * @return
     */
    private static long prepAndSendChunk(String endpoint, String contentLength, String contentType, long lastByteOnServer,
                                         byte[] chunk, int attempt, int maxAttempts, boolean firstChunk){
        if (attempt > maxAttempts)
            return -1;
        else if (attempt > 0)
            System.out.println("Attempt number " + attempt + " of " + maxAttempts + " to upload to endpoint " + endpoint);
        long totalBytesShouldBeOnServer = lastByteOnServer + chunk.length;
        String contentRange = lastByteOnServer + "-" + totalBytesShouldBeOnServer;
        long bytesOnServer = sendVideoChunk(endpoint, contentLength, contentType, contentRange, chunk, firstChunk); //TODO change content type to be passed in
        if (bytesOnServer != totalBytesShouldBeOnServer) {
            System.err.println(bytesOnServer + " (bytesOnServer)" + " != " + totalBytesShouldBeOnServer + " (totalBytesShouldBeOnServer)");
            long remainingBytes = totalBytesShouldBeOnServer - bytesOnServer;
            int beginning = (int) (chunk.length - remainingBytes);
            int ending = chunk.length;
            byte[] newByteChunk = Arrays.copyOfRange(chunk, beginning, ending);
            return prepAndSendChunk(endpoint, contentLength,contentType, bytesOnServer, newByteChunk, attempt + 1, maxAttempts, firstChunk);
        } else {
            return bytesOnServer;
        }
    }

    /**
     * Send a chunk of the upload to the vimeo endpoint
     *
     * @param endpoint
     * @param contentLength
     * @param contentType
     * @param contentRange
     * @param chunk
     * @param addContentRange
     * @return
     */
    private static long sendVideoChunk(String endpoint, String contentLength, String contentType, String contentRange,
                                       byte[] chunk, boolean addContentRange){
        OAuthRequest req = new OAuthRequest(Verb.PUT, endpoint);
        req.addHeader("Content-Length", contentLength);
        req.addHeader("Content-Type",contentType);
        if (addContentRange){
            req.addHeader("Content-Range","bytes " + contentRange);
        }
        req.addPayload(chunk);
        service.signRequest(secretToken, req);
        //printRequest(req,"Send Video To" + endpoint);   //DEBUG
        Response resp = req.send();
        if(resp.getCode() != 200 && !resp.isSuccessful()){
            return -1;
        }
        return verifyUpload(endpoint);
    }

    /**
     * Verifies the upload and returns whether it's successful
     *
     * @param endpoint to verify upload to
     * @return the last byte on the server
     */
    private static long verifyUpload(String endpoint) {
        // Verify the upload
        OAuthRequest request = new OAuthRequest(Verb.PUT, endpoint);
        request.addHeader("Content-Length", "0");
        request.addHeader("Content-Range", "bytes */*");
        Response response = request.send();
        //printResponse(response, "Verify Upload to " + endpoint);      //DEBUG
        if (response.getCode() != 308 || !response.isSuccessful()) {
            return -1;
        }
        String range = response.getHeader("Range");
        return Long.parseLong(range.substring(range.lastIndexOf("-") + 1)) + 1;
        //The + 1 at the end is because Vimeo gives you 0-whatever byte where 0 = the first byte
    }

    /**
     * Verify that all the chunks have reached the socket without error, this is handled fairly well by verifyUpload()
     * This method is useful if they need to resume an upload. I wouldn't mess with it though
     *
     * @param ticketId
     * @return
     */
    public static String verifyChunks(String ticketId){
        String methodURL = String.format(methodBase, "vimeo.videos.upload.verifyChunks");
        OAuthRequest oRequest = new OAuthRequest(Verb.GET,methodURL);
        oRequest.addOAuthParameter("oauth_token", secretToken.getToken());
        oRequest.addQuerystringParameter("ticket_id", ticketId);
        oRequest.addQuerystringParameter("format", "json");

        service.signRequest(secretToken, oRequest);
        Response oresp = oRequest.send();

        String chunks = new String(oresp.getBody() + oresp.getCode());
        return chunks;
    }

    /**
     * Tell Vimeo to begin Transcription on their end
     *
     * @param ticketId
     * @param fileName
     * @return
     */
    public static String completeUpload(String ticketId, String fileName){
        String methodURL = String.format(methodBase, "vimeo.videos.upload.complete");
        OAuthRequest oRequest = new OAuthRequest(Verb.GET,methodURL);
        oRequest.addOAuthParameter("oauth_token", secretToken.getToken());
        oRequest.addQuerystringParameter("ticket_id", ticketId);
        oRequest.addQuerystringParameter("file_name", fileName);
        oRequest.addQuerystringParameter("format", "json");

        service.signRequest(secretToken,oRequest);
        Response oresp = oRequest.send();

        String ticket = new String(oresp.getBody() + oresp.getCode());
        return ticket;
    }

    /**
     * DEBUG see what's good in the request hood
     * @param request
     * @param description
     */
    private static void printRequest(OAuthRequest request, String description) {
        System.out.println();
        System.out.println(description + " >>> Request");
        if(!request.getBodyContents().isEmpty())
            System.out.println("GOOBLE GOBBLE");
        System.out.println("Headers: " + request.getHeaders());
        System.out.println("Verb: " + request.getVerb());
        System.out.println("Complete URL: " + request.getCompleteUrl());
    }

    /**
     * DEBUG see what's good in the request hood
     *
     * @param response
     * @param description
     */
    private static void printResponse(Response response, String description) {
        System.out.println();
        System.out.println(description + " >>> Response");
        System.out.println("Code: " + response.getCode());
        System.out.println("Headers: " + response.getHeaders());
        System.out.println("Body: " + response.getBody());
    }

    /**
     * DEBUG Print the progress of the upload.
     *
     * @param videoSize
     * @param bytesLoaded
     */
    private static void printProgress(long videoSize, long bytesLoaded) {
        int percentage = (int)(bytesLoaded * 100.0 / videoSize + 0.5);
        System.out.println(bytesLoaded + " of " + videoSize + " loaded (" + percentage + "%)");
    }

}
