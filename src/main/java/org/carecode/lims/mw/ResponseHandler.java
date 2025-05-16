package org.carecode.lims.mw;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.*;

public class ResponseHandler implements HttpHandler {
    private final Logger logger;
    private final LISCommunicator limsUtils;

    public ResponseHandler(Logger logger, LISCommunicator limsUtils) {
        this.logger = logger;
        this.limsUtils = limsUtils;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        logger.info("Received " + method + " request from " + exchange.getRemoteAddress());

        if ("POST".equalsIgnoreCase(method)) {
            StringBuilder requestBody = new StringBuilder();
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                int data;
                while ((data = isr.read()) != -1) {
                    requestBody.append((char) data);
                }
            }

            Map<String, String> params = limsUtils.parseQueryParams(requestBody.toString());
            DataBundle dataBundle = limsUtils.createDataBundleFromParams(params);

            limsUtils.pushResults(dataBundle);
            logger.info("Results sent to LIMS successfully.");

            String responseMessage = "Response received and processed";
            exchange.sendResponseHeaders(200, responseMessage.getBytes(StandardCharsets.UTF_8).length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseMessage.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            exchange.sendResponseHeaders(405, -1); // 405 Method Not Allowed
        }
    }
}
