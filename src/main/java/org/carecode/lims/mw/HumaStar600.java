// ChatGPT contribution: Production middleware for HumaStar600
package org.carecode.lims.mw;

import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.Gson;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fazecast.jSerialComm.SerialPort;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.*;

public class HumaStar600 {

    // Serial Port object
    private static SerialPort analyzerPort;

    // Analyzer settings
    private static final int BAUD_RATE = 38400;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.ODD_PARITY;

    // Timing settings (in milliseconds)
    private static final int QUERY_TIMEOUT = 3000;
    private static final int RETRY_WAIT_TIME = 3000;
    private static final int TIME_BETWEEN_MESSAGES = 100;

    // Buffer and encoding
    private static final int READ_BUFFER_SIZE = 1024;
    private static final Charset ENCODING = StandardCharsets.ISO_8859_1; // or US_ASCII if safer

    // Control characters (ASTM framing)
    public static final char ENQ = 0x05;
    public static final char ACK = 0x06;
    public static final char NAK = 0x15;
    public static final char STX = 0x02;
    public static final char ETX = 0x03;
    public static final char EOT = 0x04;
    public static final char CR = 0x0D;
    public static final char LF = 0x0A;

    // Message assembly buffer
    private static final StringBuilder partialMessageBuffer = new StringBuilder();

    // Add any shared flags or state trackers here
    private static volatile boolean receivingMessage = false;

    public static final Logger logger = LogManager.getLogger("HumaStar600Logger");
    public static MiddlewareSettings middlewareSettings;
    public static LISCommunicator limsUtils;
    public static boolean testingLis = false;

    private static PatientRecord currentPatient;
    private static String currentSampleId;
    private static List<ResultsRecord> currentResults = new ArrayList<>();

    public static void main(String[] args) {
        logger.info("HumaStar600 Middleware started at: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        loadSettings();
        if (middlewareSettings != null) {
            limsUtils = new LISCommunicator(logger, middlewareSettings);
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

    public static void listenToAnalyzer() {
        analyzerPort = SerialPort.getCommPort(middlewareSettings.getAnalyzerDetails().getAnalyzerIP());

        analyzerPort.setBaudRate(BAUD_RATE);
        analyzerPort.setNumDataBits(DATA_BITS);
        analyzerPort.setNumStopBits(STOP_BITS);
        analyzerPort.setParity(PARITY);

        if (!analyzerPort.openPort()) {
            logger.error("Failed to open serial port: " + middlewareSettings.getAnalyzerDetails().getAnalyzerIP());
            return;
        }

        logger.info("Listening to analyzer on port: " + analyzerPort.getSystemPortName());

        new Thread(() -> {
            byte[] buffer = new byte[READ_BUFFER_SIZE];

            while (true) {
                try {
                    if (analyzerPort.bytesAvailable() > 0) {
                        int numRead = analyzerPort.readBytes(buffer, buffer.length);
                        String received = new String(buffer, 0, numRead, ENCODING);
                        partialMessageBuffer.append(received);

                        // Handle complete ASTM message if EOT is found
                        if (received.indexOf(EOT) != -1) {
                            String fullMessage = partialMessageBuffer.toString();
                            partialMessageBuffer.setLength(0); // clear buffer

                            handleAstmMessage(fullMessage);
                        }
                    }

                    Thread.sleep(TIME_BETWEEN_MESSAGES);
                } catch (Exception e) {
                    logger.error("Error while reading from serial port", e);
                }
            }
        }).start();
    }

    private static void handleAstmMessage(String message) {
        try {
            logger.info("Full ASTM Message Received:\n" + message.replaceAll("[\\x02\\x03\\x04\\x05\\x06\\x15]", ""));

            String[] frames = message.split(String.valueOf(STX));
            for (String frame : frames) {
                if (frame == null || frame.trim().isEmpty()) {
                    continue;
                }

                // Remove trailing ETX, CR, LF and checksum block if present
                int etxIndex = frame.indexOf(ETX);
                if (etxIndex != -1) {
                    frame = frame.substring(0, etxIndex);
                }

                frame = frame.replaceAll("[\\r\\n]", "").trim();

                if (frame.isEmpty()) {
                    continue;
                }

                // Get ASTM record type (e.g., H, P, O, R, Q, L)
                String[] fields = frame.split("\\|");
                if (fields.length < 1) {
                    logger.warn("ASTM frame without valid delimiter: " + frame);
                    continue;
                }

                String recordType = fields[0].trim();
                switch (recordType) {
                    case "H":
                        handleHeader(fields);
                        break;
                    case "P":
                        handlePatient(fields);
                        break;
                    case "O":
                        handleOrder(fields);
                        break;
                    case "R":
                        handleResult(fields);
                        break;
                    case "Q":
                        handleQuery(fields);
                        break;
                    case "L":
                        handleTerminator(fields);
                        break;
                    default:
                        logger.warn("Unhandled ASTM record type: " + recordType);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling ASTM message", e);
        }
    }

    private static void handleHeader(String[] fields) {
        try {
            String sender = fields.length > 4 ? fields[4] : "";
            String version = fields.length > 12 ? fields[12] : "";
            String timestamp = fields.length > 13 ? fields[13] : "";

            logger.info("ASTM Header received - Sender: " + sender + ", Version: " + version + ", Time: " + timestamp);

            // Optionally: reset session data if needed
            currentPatient = null;
            currentSampleId = null;
            currentResults.clear();

        } catch (Exception e) {
            logger.error("Error processing ASTM H (Header) record", e);
        }
    }

    private static String extractTestCode(String rawId) {
        if (rawId == null || !rawId.contains("^^^")) {
            return null;
        }
        String[] parts = rawId.split("\\^\\^\\^");
        return parts.length > 1 ? parts[1].trim() : null;
    }

    private static void handlePatient(String[] fields) {
        String patientId = fields.length > 2 ? fields[2] : "Unknown";
        String patientNameRaw = fields.length > 5 ? fields[5] : "";
        String[] nameParts = patientNameRaw.split("\\^");
        String fullName = nameParts.length >= 2 ? nameParts[1] + " " + nameParts[0] : patientNameRaw;

        currentPatient = new PatientRecord(
                0,
                patientId,
                null,
                fullName,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static void handleOrder(String[] fields) {
        currentSampleId = fields.length > 2 ? fields[2].split("\\^")[0] : "UnknownSample";
        // You may also extract specimen type from fields[15] if needed
    }

    private static void handleResult(String[] fields) {
        try {
            if (fields.length < 5) {
                logger.warn("Incomplete R record received: " + String.join("|", fields));
                return;
            }

            String testIdRaw = fields[2];        // Universal Test ID, format ^^^TESTCODE
            String resultValue = fields[3];      // Actual result (as String, might be ">10", "<2.3", etc.)
            String units = fields[4];            // Units, e.g., mmol/L

            String resultFlag = fields.length > 6 ? fields[6] : ""; // e.g., N = normal, A = abnormal
            String status = fields.length > 8 ? fields[8] : "";     // e.g., F = final
            String resultTime = fields.length > 12 ? fields[12] : null; // Format: YYYYMMDDHHMMSS

            String testCode = extractTestCode(testIdRaw);
            if (testCode == null) {
                logger.warn("Unable to extract test code from: " + testIdRaw);
                return;
            }

            ResultsRecord resultsRecord = new ResultsRecord(
                    0, // frameNumber
                    testCode,
                    resultValue,
                    units,
                    resultTime,
                    "HumaLyte", // instrument name
                    currentSampleId != null ? currentSampleId : "UnknownSample"
            );

            logger.info("Parsed result: " + testCode + " = " + resultValue + " " + units + " (" + status + ")");
            currentResults.add(resultsRecord);

        } catch (Exception e) {
            logger.error("Error parsing R record", e);
        }
    }

    private static void handleTerminator(String[] fields) {
        if (currentPatient == null || currentSampleId == null || currentResults.isEmpty()) {
            logger.warn("Skipping incomplete message (missing patient/sample/results)");
            return;
        }

        try {
            DataBundle dataBundle = new DataBundle();
            dataBundle.setMiddlewareSettings(middlewareSettings);
            dataBundle.setPatientRecord(currentPatient);

            for (ResultsRecord rr : currentResults) {
                rr.setSampleId(currentSampleId);
                dataBundle.addResultsRecord(rr);
            }

            limsUtils.pushResults(dataBundle);
            logger.info("Result bundle pushed for sample: " + currentSampleId + " / patient: " + currentPatient.getPatientId());

        } catch (Exception e) {
            logger.error("Failed to push DataBundle to LIS", e);
        } finally {
            // Reset session
            currentPatient = null;
            currentSampleId = null;
            currentResults.clear();
        }
    }

    private static void handleQuery(String[] fields) {
        try {
            // ASTM Q Record - Field 3 contains the sample ID or rack ID
            // Format: PatientID^SampleID or ^SampleID or ALL
            String sampleId = "UnknownSample";

            if (fields.length > 2 && fields[2] != null && !fields[2].isEmpty()) {
                String[] idParts = fields[2].split("\\^");
                if (idParts.length == 2 && !idParts[1].isEmpty()) {
                    sampleId = idParts[1];
                } else if (!fields[2].startsWith("^")) {
                    sampleId = idParts[0];
                }
            }

            logger.info("ASTM Query received for sample ID: " + sampleId);

            // Delegate to LISCommunicator to send patient + order records
            limsUtils.sendWorkingListToAnalyzer(sampleId);

        } catch (Exception e) {
            logger.error("Error handling ASTM Q record", e);
        }
    }

    public static void sendAck() {
        try {
            if (analyzerPort != null && analyzerPort.isOpen()) {
                analyzerPort.writeBytes(new byte[]{ACK}, 1);
                logger.info("ACK sent to analyzer");
            } else {
                logger.warn("Cannot send ACK — port not open");
            }
        } catch (Exception e) {
            logger.error("Failed to send ACK", e);
        }
    }

    public static void sendNak() {
        try {
            if (analyzerPort != null && analyzerPort.isOpen()) {
                analyzerPort.writeBytes(new byte[]{NAK}, 1);
                logger.info("NAK sent to analyzer");
            } else {
                logger.warn("Cannot send NAK — port not open");
            }
        } catch (Exception e) {
            logger.error("Failed to send NAK", e);
        }
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
