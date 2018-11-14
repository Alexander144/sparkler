package edu.usc.irds.sparkler.util;

import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlInput;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import edu.usc.irds.sparkler.AbstractExtensionPoint;
import edu.usc.irds.sparkler.Constants;
import edu.usc.irds.sparkler.Fetcher;
import edu.usc.irds.sparkler.JobContext;
import edu.usc.irds.sparkler.SparklerConfiguration;
import edu.usc.irds.sparkler.SparklerException;
import edu.usc.irds.sparkler.model.FetchedData;
import edu.usc.irds.sparkler.model.Resource;
import edu.usc.irds.sparkler.model.ResourceStatus;
import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.text.html.HTML;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.net.CookiePolicy.ACCEPT_ALL;

/**
 * This class is a default implementation of {@link Fetcher} contract.
 * This fetcher doesn't depends on external libraries,
 * instead it uses URLConnection provided by JDK to fetch the resources.
 *
 */
public class FetcherDefault extends AbstractExtensionPoint implements Fetcher, Function<Resource, FetchedData> {
    //TODO: move this to a plugin named fetcher-default
    public static final Logger LOG = LoggerFactory.getLogger(FetcherDefault.class);
    public static final Integer CONNECT_TIMEOUT = 5000; // Milliseconds. FIXME: Get from Configuration
    public static final Integer READ_TIMEOUT = 10000; // Milliseconds. FIXME: Get from Configuration
    public static final Integer CONTENT_LIMIT = 100 * 1024 * 1024; // Bytes. FIXME: Get from Configuration
    public static final Integer DEFAULT_ERROR_CODE = 400;
    public static final String USER_AGENT = "User-Agent";
    public static final String TRUNCATED = "X-Content-Truncated";

    protected List<String> userAgents;
    protected int userAgentIndex = 0; // index for rotating the agents
    protected Map<String, String> httpHeaders;

    private List<HttpCookie> httpCookies;
    private HttpsURLConnection urlConn;
    private CookieManager cookieManager;

    public FetcherDefault(){}

    @Override
    public void init(JobContext context, String pluginId) throws SparklerException {
        super.init(context, pluginId);
        SparklerConfiguration conf = context.getConfiguration();
        if (conf.containsKey(Constants.key.FETCHER_USER_AGENTS)) {
            Object agents = conf.get(Constants.key.FETCHER_USER_AGENTS);
            if (agents instanceof String) { // it is a file name
                try (InputStream stream = getClass().getClassLoader()
                        .getResourceAsStream(agents.toString())) {
                    if (stream == null) {
                        this.userAgents = Collections.EMPTY_LIST;
                        LOG.warn("Could not find Rotating user agents file in class path");
                    } else {
                        this.userAgents = IOUtils.readLines(stream, StandardCharsets.UTF_8).stream()
                                .map(String::trim)                               // white spaces are trimmed
                                .filter(s -> !s.isEmpty() && s.charAt(0) != '#') //remove empty and comment lines
                                .collect(Collectors.toList());
                    }
                }  catch (IOException e){
                    throw new SparklerException("Cant read user agent file", e);
                }
            } else { //it is a list
                this.userAgents = (List<String>) conf.get(Constants.key.FETCHER_USER_AGENTS);
            }
            //remove duplicates while preserving the order
            this.userAgents = new ArrayList<>(new LinkedHashSet<>(this.userAgents));
        }

        if (conf.containsKey(Constants.key.FETCHER_HEADERS)) {
            this.httpHeaders = (Map<String, String>) conf.get(Constants.key.FETCHER_HEADERS);
        }
    }

    public void init(JobContext context, String pluginId, String authUrl, String username, String password) throws SparklerException {

        init(context, pluginId);
        cookieManager = new CookieManager();

        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        CookieHandler.setDefault(cookieManager);
        String postParams = null;
        String page = null;
        try {
            page = GetPageContent(authUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            postParams = getFormParams(page, username, password);//"agnes@vio.no", "HastingsAgnes");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        try {
            sendPost(authUrl, postParams);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Gets a user agent from a list of configured values, rotates the list for each call
     * @return get a user agent string from the list of configured values
     */
    public String getUserAgent(){
        if (userAgents == null || userAgents.isEmpty()){
            return null;
        }
        String agent = userAgents.get(userAgentIndex);
        synchronized (this) { // rotate index
            userAgentIndex = (userAgentIndex + 1) % userAgents.size();
        }
        return agent;
    }

    @Override
    public Iterator<FetchedData> fetch(Iterator<Resource> resources) throws Exception {
        return new StreamTransformer<>(resources, this);
    }

    public FetchedData fetch(Resource resource) throws Exception {
        LOG.info("DEFAULT FETCHER {}", resource.getUrl());

        urlConn = (HttpsURLConnection) new URL(resource.getUrl()).openConnection();
        if (httpHeaders != null){
            httpHeaders.forEach(urlConn::setRequestProperty);
            LOG.debug("Adding headers:{}", httpHeaders.keySet());
        } else {
            LOG.debug("No headers are available");
        }
        String userAgentValue = getUserAgent();
        if (userAgentValue != null) {
            LOG.debug(USER_AGENT + ": " + userAgentValue);
            urlConn.setRequestProperty(USER_AGENT, userAgentValue);
        } else {
            LOG.debug("No rotating agents are available");
        }

        urlConn.setConnectTimeout(CONNECT_TIMEOUT);
        urlConn.setReadTimeout(READ_TIMEOUT);
        //Set the cookies
        if(httpCookies != null) {
            for (HttpCookie cookie : httpCookies) {
                urlConn.addRequestProperty("Cookie", cookie.getName() + "=" + cookie.getValue());
                System.out.println("Cookies added: " + cookie);
            }
        }

        int responseCode = ((HttpsURLConnection)urlConn).getResponseCode();
        LOG.debug("STATUS CODE : " + responseCode + " " + resource.getUrl());
        boolean truncated = false;
        try (InputStream inStream = urlConn.getInputStream()) {
            ByteArrayOutputStream bufferOutStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; // 4kb buffer
            int read;
            while((read = inStream.read(buffer, 0, buffer.length)) != -1) {
                bufferOutStream.write(buffer, 0, read);
                if (bufferOutStream.size() >= CONTENT_LIMIT) {
                    truncated = true;
                    LOG.info("Content Truncated: {}, TotalSize={}, TruncatedSize={}", resource.getUrl(),
                            urlConn.getContentLength(), bufferOutStream.size());
                    break;
                }
            }
            bufferOutStream.flush();
            byte[] rawData = bufferOutStream.toByteArray();
            IOUtils.closeQuietly(bufferOutStream);
            FetchedData fetchedData = new FetchedData(rawData, urlConn.getContentType(), responseCode);
            resource.setStatus(ResourceStatus.FETCHED.toString());
            fetchedData.setResource(resource);
            fetchedData.setHeaders(urlConn.getHeaderFields());
            if (truncated) {
                fetchedData.getHeaders().put(TRUNCATED, Collections.singletonList(Boolean.TRUE.toString()));
            }
            return fetchedData;
        }
    }

    @Override
    public FetchedData apply(Resource resource) {
        try {
            return this.fetch(resource);
        } catch (Exception e) {
            int statusCode =  DEFAULT_ERROR_CODE;
            if (e instanceof FileNotFoundException){
                statusCode = 404;
            }
            LOG.warn("FETCH-ERROR {}", resource.getUrl());
            LOG.debug(e.getMessage(), e);
            FetchedData fetchedData = new FetchedData(new byte[0], "", statusCode);
            resource.setStatus(ResourceStatus.ERROR.toString());
            fetchedData.setResource(resource);
            return fetchedData;
        }
    }

    public void logOut(String url) throws IOException {
        URL obj = new URL(url);
        urlConn = (HttpsURLConnection) obj.openConnection();
        urlConn.setRequestMethod("GET");
        urlConn.setRequestProperty("User-Agent", USER_AGENT);
        urlConn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        urlConn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        urlConn.setInstanceFollowRedirects(false);
        int responseCode = urlConn.getResponseCode();
        System.out.println("\nSending 'GET' request to URL : " + url);
        System.out.println("Response Code : " + responseCode);
        if(responseCode == 200)
        {
            System.out.println("Logged out");
        }
        else{
            System.out.println("Failed to log out");
        }
    }

    public void sendPost(String url, String postParams) throws Exception {

        URL obj = new URL(url);
        urlConn = (HttpsURLConnection) obj.openConnection();
        String userInfo = postParams;
        System.out.println("Username and password : " + userInfo);
        byte[] postData = userInfo.getBytes( StandardCharsets.UTF_8 );
        // Acts like a browser
        urlConn.setUseCaches(false);
        urlConn.setRequestMethod("POST");
        urlConn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        urlConn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
        urlConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        urlConn.setDoOutput(true);
        urlConn.setDoInput(true);

        // Send post request
        OutputStream wr = urlConn.getOutputStream();

        wr.write(postData);
        wr.flush();
        wr.close();

        //urlConn.connect();
        int responseCode = urlConn.getResponseCode();
        System.out.println("\nTrying to login on : " + url);
        httpCookies = cookieManager.getCookieStore().getCookies();

        BufferedReader in =
                new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        if(responseCode == 200)
        {
            System.out.println("Login sucess: " + responseCode);
        }
        else {
            System.out.print("Login failed: ");
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
                System.out.println(inputLine);
            }
        }
        in.close();
    }

    public String getFormParams(String page, String username, String password)
            throws UnsupportedEncodingException {

        Document doc = Jsoup.parse(page);

        Elements usernameInputs = doc.getElementsByAttributeValue("type", "text");
        String usernameInputField = usernameInputs.first().attr("name");
        Elements passwordInputs = doc.getElementsByAttributeValue("type", "password");
        String passwordInputField = passwordInputs.first().attr("name");

        List<String> paramList = new ArrayList<String>();
        paramList.add(usernameInputField + "=" + URLEncoder.encode(username, "UTF-8"));
        paramList.add(passwordInputField + "=" + URLEncoder.encode(password, "UTF-8"));

        System.out.println("UsernameField: " + usernameInputField);
        System.out.println("PasswordField: " + passwordInputField);

        // build parameters list
        StringBuilder result = new StringBuilder();
        for (String param : paramList) {
            if (result.length() == 0) {
                result.append(param);
            } else {
                result.append("&" + param);
            }
        }
        return result.toString();
    }

    private String GetPageContent(String url) throws Exception {

        URL obj = new URL(url);
        urlConn = (HttpsURLConnection) obj.openConnection();

        // default is GET
        urlConn.setRequestMethod("GET");

        urlConn.setUseCaches(false);

        // act like a browser
        urlConn.setRequestProperty("User-Agent", USER_AGENT);
        urlConn.setRequestProperty("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        urlConn.setRequestProperty("Accept-Language", "en-US,en;q=0.5");

        int responseCode = urlConn.getResponseCode();
        System.out.println("\nSending 'GET' request to URL : " + url);
        System.out.println("Response Code : " + responseCode);

        BufferedReader in =
                new BufferedReader(new InputStreamReader(urlConn.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        return response.toString();

    }
}
