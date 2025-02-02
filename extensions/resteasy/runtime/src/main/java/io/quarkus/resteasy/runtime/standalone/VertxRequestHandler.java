package io.quarkus.resteasy.runtime.standalone;

import java.io.IOException;
import java.io.InputStream;

import javax.ws.rs.core.HttpHeaders;

import org.jboss.logging.Logger;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.specimpl.ResteasyHttpHeaders;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.Failure;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.spi.UnhandledException;

import io.quarkus.arc.ManagedContext;
import io.quarkus.arc.runtime.BeanContainer;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
public class VertxRequestHandler implements Handler<HttpServerRequest> {
    private static final Logger log = Logger.getLogger("io.quarkus.resteasy");

    protected final Vertx vertx;
    protected final RequestDispatcher dispatcher;
    protected final String servletMappingPrefix;
    protected final BufferAllocator allocator;
    protected final BeanContainer beanContainer;

    public VertxRequestHandler(Vertx vertx,
            BeanContainer beanContainer,
            ResteasyDeployment deployment,
            String servletMappingPrefix,
            BufferAllocator allocator) {
        this.vertx = vertx;
        this.beanContainer = beanContainer;
        this.dispatcher = new RequestDispatcher((SynchronousDispatcher) deployment.getDispatcher(),
                deployment.getProviderFactory(), null);
        this.servletMappingPrefix = servletMappingPrefix;
        this.allocator = allocator;
    }

    @Override
    public void handle(HttpServerRequest request) {
        // have to create input stream here.  Cannot execute in another thread
        // otherwise request handlers may not get set up before request ends
        VertxInputStream is = new VertxInputStream(request);
        vertx.executeBlocking(event -> {
            dispatchRequestContext(request, is, new VertxBlockingOutput(request));
        }, false, event -> {
        });
    }

    private void dispatchRequestContext(HttpServerRequest request, InputStream is, VertxOutput output) {
        ManagedContext requestContext = beanContainer.requestContext();
        requestContext.activate();
        try {
            dispatch(request, is, output);
        } finally {
            requestContext.terminate();
        }
    }

    private void dispatch(HttpServerRequest request, InputStream is, VertxOutput output) {
        Context ctx = vertx.getOrCreateContext();
        ResteasyUriInfo uriInfo = VertxUtil.extractUriInfo(request, servletMappingPrefix);
        ResteasyHttpHeaders headers = VertxUtil.extractHttpHeaders(request);
        HttpServerResponse response = request.response();
        VertxHttpResponse vertxResponse = new VertxHttpResponse(request, dispatcher.getProviderFactory(),
                request.method(), allocator, output);
        VertxHttpRequest vertxRequest = new VertxHttpRequest(ctx, headers, uriInfo, request.rawMethod(),
                dispatcher.getDispatcher(), vertxResponse, false);
        vertxRequest.setInputStream(is);
        try {
            dispatcher.service(ctx, request, response, vertxRequest, vertxResponse, true);
        } catch (Failure e1) {
            vertxResponse.setStatus(e1.getErrorCode());
            if (e1.isLoggable()) {
                log.error(e1);
            }
        } catch (UnhandledException ex) {
            vertxResponse.setStatus(500);
            if (ex.getCause() != null) {
                if (ex.getCause().getMessage() != null) {
                    try {
                        vertxResponse.getOutputHeaders().putSingle(HttpHeaders.CONTENT_TYPE, "text/plain");
                        vertxResponse.getOutputStream().write(ex.getCause().getMessage().getBytes());
                    } catch (Exception ignore) {
                    }
                }
                log.error("Unhandled Exception", ex.getCause());
            } else {
                log.error("Unexpected failure", ex);
            }
        } catch (Exception ex) {
            vertxResponse.setStatus(500);
            //vertxResponse.getOutputHeaders().putSingle(HttpHeaders.CONTENT_TYPE, "text/plain");
            log.error("Unexpected failure", ex);
        }

        if (!vertxRequest.getAsyncContext().isSuspended()) {
            try {
                vertxResponse.finish();
            } catch (IOException e) {
                log.error("Unexpected failure", e);
            }
        }
    }
}
