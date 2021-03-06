package fi.bitrite.android.ws.api;

import fi.bitrite.android.ws.auth.http.HttpSessionContainer;
import fi.bitrite.android.ws.util.http.HttpException;
import fi.bitrite.android.ws.util.http.HttpUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.List;

/**
 * Base class for classes that interface with the REST API via POST.
 * see https://github.com/rfay/Warmshowers.org/wiki/Warmshowers-RESTful-Services-for-Mobile-Apps
 */
public class RestClient extends HttpReader {

    protected HttpResponse post(String url, List<NameValuePair> params) {
        HttpClient client = HttpUtils.getDefaultClient();
        HttpResponse response;

        try {
            HttpPost post = new HttpPost(url);
            post.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
            HttpContext httpContext = HttpSessionContainer.INSTANCE.getSessionContext();
            response = client.execute(post, httpContext);

            int responseCode = response.getStatusLine().getStatusCode();
            if (responseCode == HttpStatus.SC_FORBIDDEN ||
                responseCode == HttpStatus.SC_UNAUTHORIZED) {
                if (!isAuthenticationPerformed()) {
                    authenticate();
                    return post(url, params);
                } else {
                    throw new HttpException("Couldn't authenticate user");
                }
            }
        }

        catch (Exception e) {
			throw new HttpException(e);
		}

		finally {
			client.getConnectionManager().shutdown();
		}
        if (response.getStatusLine().getStatusCode() == 200) {
            return response;
        }
        else {
            throw new HttpException("HTTP Error on service request = " + response.getStatusLine().getStatusCode() + " " + response.getStatusLine().getReasonPhrase());
        }
    }

    protected String getJson(String url, List<NameValuePair> params) {
        HttpClient client = HttpUtils.getDefaultClient();
        String json;

		try {
            HttpPost post = new HttpPost(url);
            post.setEntity(new UrlEncodedFormEntity(params));
            HttpContext httpContext = HttpSessionContainer.INSTANCE.getSessionContext();
            HttpResponse response = client.execute(post, httpContext);
			HttpEntity entity = response.getEntity();

            int responseCode = response.getStatusLine().getStatusCode();
            if (responseCode == HttpStatus.SC_FORBIDDEN ||
                responseCode == HttpStatus.SC_UNAUTHORIZED) {
                if (!isAuthenticationPerformed()) {
                    authenticate();
                    return getJson(url, params);
                } else {
                    throw new HttpException("Couldn't authenticate user");
                }
            }

            json = EntityUtils.toString(entity, "UTF-8");

        }

        catch (IOException e) {
            throw new HttpException(e.getMessage());
        }

        finally {
            client.getConnectionManager().shutdown();
        }

        return json;
    }
}
