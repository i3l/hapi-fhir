package ca.uhn.fhir.rest.client;

import static org.junit.Assert.*;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.collections.EnumerationUtils;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.BeforeClass;
import org.junit.Test;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.dstu.resource.Patient;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.util.PortUtil;

public class HttpProxyTest {
	private static FhirContext ourCtx;
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(HttpProxyTest.class);
	private static HttpServletRequest ourRequest;
	private boolean myFirstRequest;
	private String myAuthHeader;

	@SuppressWarnings("serial")
	@Test
	public void testProxiedRequest() throws Exception {
		int port = PortUtil.findFreePort();
		Server server = new Server(port);
		myFirstRequest = true;
		myAuthHeader = null;
		
		RestfulServer restServer = new RestfulServer(ourCtx) {

			@Override
			protected void doGet(HttpServletRequest theRequest, HttpServletResponse theResponse) throws ServletException, IOException {
				if (myFirstRequest) {
					theResponse.addHeader("Proxy-Authenticate", "Basic realm=\"some_realm\"");
					theResponse.setStatus(407);
					theResponse.getWriter().close();
					myFirstRequest = false;
					return;
				}
				String auth = theRequest.getHeader("Proxy-Authorization");
				if (auth != null) {
					myAuthHeader = auth;
				}
				
				super.doGet(theRequest, theResponse);
			}

		};
		restServer.setResourceProviders(new PatientResourceProvider());

		// ServletHandler proxyHandler = new ServletHandler();
		ServletHolder servletHolder = new ServletHolder(restServer);

		ServletContextHandler ch = new ServletContextHandler();
		ch.setContextPath("/rootctx/rcp2");
		ch.addServlet(servletHolder, "/fhirctx/fcp2/*");

		ContextHandlerCollection contexts = new ContextHandlerCollection();
		server.setHandler(contexts);

		server.setHandler(ch);
		server.start();
		try {

//			final String authUser = "username";
//			final String authPassword = "password";
//			CredentialsProvider credsProvider = new BasicCredentialsProvider();
//			credsProvider.setCredentials(new AuthScope("127.0.0.1", port), new UsernamePasswordCredentials(authUser, authPassword));
//
//			HttpHost myProxy = new HttpHost("127.0.0.1", port);
//
//			//@formatter:off
//			HttpClientBuilder clientBuilder = HttpClientBuilder.create();
//			clientBuilder
//				.setProxy(myProxy)
//				.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
//				.setDefaultCredentialsProvider(credsProvider)
//				.disableCookieManagement();
//			CloseableHttpClient httpClient = clientBuilder.build();
//			//@formatter:on
//			ourCtx.getRestfulClientFactory().setHttpClient(httpClient);

			ourCtx.getRestfulClientFactory().setProxy("127.0.0.1", port);
			ourCtx.getRestfulClientFactory().setProxyCredentials("username", "password");
			
			String baseUri = "http://99.99.99.99:" + port + "/rootctx/rcp2/fhirctx/fcp2";
			IGenericClient client = ourCtx.newRestfulGenericClient(baseUri);

			IdDt id = new IdDt("Patient", "123");
			client.read(Patient.class, id);

			assertEquals("Basic dXNlcm5hbWU6cGFzc3dvcmQ=", myAuthHeader);
			
		} finally {
			server.stop();
		}

	}

	@BeforeClass
	public static void beforeClass() throws Exception {
		ourCtx = FhirContext.forDstu1();
	}

	public static class PatientResourceProvider implements IResourceProvider {

		@Override
		public Class<? extends IResource> getResourceType() {
			return Patient.class;
		}

		@Read
		public Patient read(@IdParam IdDt theId, HttpServletRequest theRequest) {
			Patient retVal = new Patient();
			retVal.setId(theId);
			ourRequest = theRequest;

			ourLog.info(EnumerationUtils.toList(ourRequest.getHeaderNames()).toString());
			ourLog.info("Proxy-Connection: " + EnumerationUtils.toList(ourRequest.getHeaders("Proxy-Connection")));
			ourLog.info("Host: " + EnumerationUtils.toList(ourRequest.getHeaders("Host")));
			ourLog.info("User-Agent: " + EnumerationUtils.toList(ourRequest.getHeaders("User-Agent")));

			return retVal;
		}

	}

}
