// ChatGPT contribution: Production middleware for HumaLyte Plus 5 (one result per transmission)
package org.carecode.lims.mw;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.Gson;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.*;

public class HumaLytePlus5 {

    public static final Logger logger = LogManager.getLogger("HumaLytePlus5Logger");
    public static MiddlewareSettings middlewareSettings;
    public static LISCommunicator limsUtils;
    public static boolean testingLis = false;

    public static void main(String[] args) {
        logger.info("HumaLytePlus5 Middleware started at: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        loadSettings();

        if (middlewareSettings != null) {
            limsUtils = new LISCommunicator(logger, middlewareSettings);

            if (testingLis) {
                logger.info("Testing LIS started");
                testLis();
                logger.info("Testing LIS Ended. System will now shutdown.");
                System.exit(0);
            }

            listenToAnalyzer();
        } else {
            logger.error("Failed to load settings.");
        }
    }

    public static void loadSettings() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader("config.json")) {
            middlewareSettings = gson.fromJson(reader, MiddlewareSettings.class);
            logger.info("Settings loaded from config.json");
        } catch (IOException e) {
            logger.error("Failed to load settings from config.json", e);
        }
    }

    public static void testLis() {
        logger.info("Starting LIMS test process...");
        String filePath = "response.txt";

        try {
            String responseContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            Map<String, String> params = limsUtils.parseQueryParams(responseContent);
            DataBundle dataBundle = limsUtils.createDataBundleFromParams(params);
            limsUtils.pushResults(dataBundle);
            logger.info("Test results sent to LIMS successfully.");
        } catch (IOException e) {
            logger.error("Failed to read test data from file: " + filePath, e);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during the LIMS test process.", e);
        }
    }

    public static void listenToAnalyzer() {
        SerialPort analyzerPort = SerialPort.getCommPort(middlewareSettings.getAnalyzerDetails().getAnalyzerIP());

        analyzerPort.setBaudRate(19200);
        analyzerPort.setNumDataBits(8);
        analyzerPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
        analyzerPort.setParity(SerialPort.NO_PARITY);

        if (!analyzerPort.openPort()) {
            logger.error("Failed to open serial port: " + middlewareSettings.getAnalyzerDetails().getAnalyzerIP());
            return;
        }

        logger.info("Listening to analyzer on port: " + analyzerPort.getSystemPortName());

        new Thread(() -> {
            byte[] buffer = new byte[1024];
            StringBuilder partialData = new StringBuilder();

            while (true) {
                try {
                    if (analyzerPort.bytesAvailable() > 0) {
                        int numRead = analyzerPort.readBytes(buffer, buffer.length);
                        String received = new String(buffer, 0, numRead, StandardCharsets.UTF_8);
                        partialData.append(received);

                        String[] lines = partialData.toString().split("\r?\n");

                        for (int i = 0; i < lines.length - 1; i++) {
                            processLine(lines[i]);
                        }

                        partialData = new StringBuilder(lines[lines.length - 1]);
                    }

                    Thread.sleep(100);
                } catch (Exception e) {
                    logger.error("Error while reading from serial port", e);
                }
            }
        }).start();
    }

    private static void processLine(String line) {
        try {
            line = line.replaceAll("[^\\u0020-\\u007E]", "").trim();
            if (line.isEmpty()) {
                return;
            }

            logger.info("Received Line: " + line);

            String[] parts = line.split("\\s+");
            if (parts.length >= 8) {
                String sampleNo = parts[0];
                String patientId = parts[1];
                String k = parts[3];
                String na = parts[4];
                String cl = parts[5];
                String ca = parts[6];
                String ph = parts[7];

                sendSingleResult(patientId, sampleNo, "K", k, 3.5, 5.5);
                sendSingleResult(patientId, sampleNo, "Na", na, 135.0, 145.0);
                sendSingleResult(patientId, sampleNo, "Cl", cl, 98.0, 108.0);
                sendSingleResult(patientId, sampleNo, "Ca", ca, 2.2, 2.7);

            } else {
                logger.warn("Received line format not matching expected pattern: " + line);
            }
        } catch (Exception e) {
            logger.error("Error processing line: " + line, e);
        }
    }

    private static void sendSingleResult(String patientId, String sampleNo, String testCode, String resultValue, double minValue, double maxValue) {
        try {
            DataBundle dataBundle = new DataBundle();
            dataBundle.setMiddlewareSettings(middlewareSettings);

            PatientRecord patientRecord = new PatientRecord(
                    0,
                    patientId,
                    null,
                    "Unknown Patient",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            dataBundle.setPatientRecord(patientRecord);

            ResultsRecord resultsRecord = new ResultsRecord(
                    0,
                    testCode,
                    resultValue,
                    minValue,
                    maxValue,
                    "",
                    "Serum",
                    "mmol/L",
                    null,
                    null,
                    patientId
            );

            dataBundle.addResultsRecord(resultsRecord);

            limsUtils.pushResults(dataBundle);
            logger.info("Result pushed: " + testCode + " for sample: " + sampleNo);
        } catch (Exception e) {
            logger.error("Error sending single result for: " + testCode, e);
        }
    }
}
